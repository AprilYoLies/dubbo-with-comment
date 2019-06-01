/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.registry.support.ProviderConsumerRegTable;
import org.apache.dubbo.registry.support.ProviderInvokerWrapper;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.apache.dubbo.common.constants.ClusterConstants.LOADBALANCE_KEY;
import static org.apache.dubbo.common.constants.ClusterConstants.WARMUP_KEY;
import static org.apache.dubbo.common.constants.ClusterConstants.WEIGHT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.HIDE_KEY_PREFIX;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.RELEASE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMESTAMP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.ACCEPT_FOREIGN_IP;
import static org.apache.dubbo.common.constants.ConfigConstants.CLUSTER_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.EXPORT_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.QOS_ENABLE;
import static org.apache.dubbo.common.constants.ConfigConstants.QOS_PORT;
import static org.apache.dubbo.common.constants.ConfigConstants.REFER_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.REGISTER_IP_KEY;
import static org.apache.dubbo.common.constants.FilterConstants.VALIDATION_KEY;
import static org.apache.dubbo.common.constants.MonitorConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_SUFFIX;
import static org.apache.dubbo.common.constants.RegistryConstants.CONSUMERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_REGISTRY;
import static org.apache.dubbo.common.constants.RegistryConstants.EXTRA_KEYS_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.OVERRIDE_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDER_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTER_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.SIMPLIFIED_KEY;
import static org.apache.dubbo.common.constants.RemotingConstants.BIND_IP_KEY;
import static org.apache.dubbo.common.constants.RemotingConstants.BIND_PORT_KEY;
import static org.apache.dubbo.common.constants.RemotingConstants.CHECK_KEY;
import static org.apache.dubbo.common.constants.RemotingConstants.CODEC_KEY;
import static org.apache.dubbo.common.constants.RemotingConstants.EXCHANGER_KEY;
import static org.apache.dubbo.common.constants.RemotingConstants.SERIALIZATION_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.CONNECTIONS_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.DEPRECATED_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.INTERFACES;
import static org.apache.dubbo.common.constants.RpcConstants.MOCK_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.TOKEN_KEY;
import static org.apache.dubbo.common.utils.UrlUtils.classifyUrls;

/**
 * RegistryProtocol
 */
public class RegistryProtocol implements Protocol {
    public static final String[] DEFAULT_REGISTER_PROVIDER_KEYS = {
            APPLICATION_KEY, CODEC_KEY, EXCHANGER_KEY, SERIALIZATION_KEY, CLUSTER_KEY, CONNECTIONS_KEY, DEPRECATED_KEY,
            GROUP_KEY, LOADBALANCE_KEY, MOCK_KEY, PATH_KEY, TIMEOUT_KEY, TOKEN_KEY, VERSION_KEY, WARMUP_KEY,
            WEIGHT_KEY, TIMESTAMP_KEY, DUBBO_VERSION_KEY, RELEASE_KEY
    };

    public static final String[] DEFAULT_REGISTER_CONSUMER_KEYS = {
            APPLICATION_KEY, VERSION_KEY, GROUP_KEY, DUBBO_VERSION_KEY, RELEASE_KEY
    };

    private final static Logger logger = LoggerFactory.getLogger(RegistryProtocol.class);
    private static RegistryProtocol INSTANCE;
    private final Map<URL, NotifyListener> overrideListeners = new ConcurrentHashMap<>();
    private final Map<String, ServiceConfigurationListener> serviceConfigurationListeners = new ConcurrentHashMap<>();
    private final ProviderConfigurationListener providerConfigurationListener = new ProviderConfigurationListener();
    //To solve the problem of RMI repeated exposure port conflicts, the services that have been exposed are no longer exposed.
    //providerurl <--> exporter
    private final ConcurrentMap<String, ExporterChangeableWrapper<?>> bounds = new ConcurrentHashMap<>();
    private Cluster cluster;
    // protocol 实例的源代码
    // package org.apache.dubbo.rpc;
    // import org.apache.dubbo.common.extension.ExtensionLoader;
    // public class Protocol$Adaptive implements org.apache.dubbo.rpc.Protocol {
    //     public void destroy() {
    //         throw new UnsupportedOperationException("The method public abstract void org.apache.dubbo.rpc.Protocol.destroy() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
    //
    //     public int getDefaultPort() {
    //         throw new UnsupportedOperationException("The method public abstract int org.apache.dubbo.rpc.Protocol.getDefaultPort() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
    //
    //     public org.apache.dubbo.rpc.Exporter export(org.apache.dubbo.rpc.Invoker arg0) throws org.apache.dubbo.rpc.RpcException {
    //         if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
    //         if (arg0.getUrl() == null)
    //             throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
    //         org.apache.dubbo.common.URL url = arg0.getUrl();
    //         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
    //         if (extName == null)
    //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
    //         org.apache.dubbo.rpc.Protocol extension = (org.apache.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.Protocol.class).getExtension(extName);
    //         return extension.export(arg0);
    //
    //     public org.apache.dubbo.rpc.Invoker refer(java.lang.Class arg0, org.apache.dubbo.common.URL arg1) throws org.apache.dubbo.rpc.RpcException {
    //         if (arg1 == null) throw new IllegalArgumentException("url == null");
    //         org.apache.dubbo.common.URL url = arg1;
    //         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
    //         if (extName == null)
    //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
    //         org.apache.dubbo.rpc.Protocol extension = (org.apache.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.Protocol.class).getExtension(extName);
    //         return extension.refer(arg0, arg1);
    //     }
    // }
    private Protocol protocol;
    // package org.apache.dubbo.registry;
    // import org.apache.dubbo.common.extension.ExtensionLoader;
    //
    // public class RegistryFactory$Adaptive implements org.apache.dubbo.registry.RegistryFactory {
    //     public org.apache.dubbo.registry.Registry getRegistry(org.apache.dubbo.common.URL arg0) {
    //         if (arg0 == null) throw new IllegalArgumentException("url == null");
    //         org.apache.dubbo.common.URL url = arg0;
    //         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
    //         if (extName == null)
    //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.registry.RegistryFactory) name from url (" + url.toString() + ") use keys([protocol])");
    //         org.apache.dubbo.registry.RegistryFactory extension = (org.apache.dubbo.registry.RegistryFactory) ExtensionLoader.getExtensionLoader(org.apache.dubbo.registry.RegistryFactory.class).getExtension(extName);
    //         return extension.getRegistry(arg0);
    //     }
    // }
    private RegistryFactory registryFactory;
    private ProxyFactory proxyFactory;

    public RegistryProtocol() {
        INSTANCE = this;
    }

    public static RegistryProtocol getRegistryProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(REGISTRY_PROTOCOL); // load
        }
        return INSTANCE;
    }

    //Filter the parameters that do not need to be output in url(Starting with .)
    private static String[] getFilteredKeys(URL url) {
        Map<String, String> params = url.getParameters();
        if (CollectionUtils.isNotEmptyMap(params)) {
            return params.keySet().stream()
                    .filter(k -> k.startsWith(HIDE_KEY_PREFIX))
                    .toArray(String[]::new);
        } else {
            return new String[0];
        }
    }

    public void setCluster(Cluster cluster) {
        this.cluster = cluster;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistryFactory(RegistryFactory registryFactory) {
        this.registryFactory = registryFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    @Override
    public int getDefaultPort() {
        return 9090;
    }

    public Map<URL, NotifyListener> getOverrideListeners() {
        return overrideListeners;
    }

    public void register(URL registryUrl, URL registeredProviderUrl) {
        Registry registry = registryFactory.getRegistry(registryUrl);
        registry.register(registeredProviderUrl);
    }

    public void unregister(URL registryUrl, URL registeredProviderUrl) {
        Registry registry = registryFactory.getRegistry(registryUrl);
        registry.unregister(registeredProviderUrl);
    }

    @Override
    // 这里的 originInvoker 是通过 DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this); 进行构建
    public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {
        // zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?
        // application=demo-provider&
        // dubbo=2.0.2&
        // export=dubbo%3A%2F%2F192.168.1.104%3A20880%2Forg.apache.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26application%3Ddemo-provider%26bean.name%3Dorg.apache.dubbo.demo.DemoService%26bind.ip%3D192.168.1.104%26bind.port%3D20880%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26interface%3Dorg.apache.dubbo.demo.DemoService%26methods%3DsayHello%26pid%3D5980%26register%3Dtrue%26release%3D%26side%3Dprovider%26timestamp%3D1558427892398&
        // pid=5980&
        // timestamp=1558427892390
        // 获取 originInvoker 的 url，根据 url 本身的 registry 参数修改 url 的协议，并移除 registry 参数
        URL registryUrl = getRegistryUrl(originInvoker);
        // url to export locally
        // dubbo://192.168.1.104:20880/org.apache.dubbo.demo.DemoService?
        // anyhost=true&
        // application=demo-provider&
        // bean.name=org.apache.dubbo.demo.DemoService&
        // bind.ip=192.168.1.104&
        // bind.port=20880&
        // deprecated=false&
        // dubbo=2.0.2&
        // dynamic=true&
        // generic=false&
        // interface=org.apache.dubbo.demo.DemoService&
        // methods=sayHello&
        // pid=5980&
        // register=true&
        // release=&
        // side=provider&
        // timestamp=1558427892398
        // 将 originInvoker 的 url 的 export 参数值反解为 URL 对象
        URL providerUrl = getProviderUrl(originInvoker);

        // Subscribe the override data
        // FIXME When the provider subscribes, it will affect the scene : a certain JVM exposes the service and call
        //  the same service. Because the subscribed is cached key with the name of the service, it causes the
        //  subscription information to cover.
        // 修改 registeredProviderUrl 协议为 provider，添加
        // category -> configurators
        // check -> false 参数
        // provider://192.168.1.104:20880/org.apache.dubbo.demo.DemoService?
        // anyhost=true&
        // application=demo-provider&
        // bean.name=org.apache.dubbo.demo.DemoService&
        // bind.ip=192.168.1.104&
        // bind.port=20880&
        // category=configurators&
        // check=false&
        // deprecated=false&
        // dubbo=2.0.2&
        // dynamic=true&
        // generic=false&
        // interface=org.apache.dubbo.demo.DemoService&
        // methods=sayHello&
        // pid=8518&
        // register=true&
        // release=&
        // side=provider&
        // timestamp=1558439540723
        final URL overrideSubscribeUrl = getSubscribedOverrideUrl(providerUrl);
        // 封装 overrideSubscribeUrl 和 originInvoker 为 OverrideListener
        final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl, originInvoker);
        // 缓存 OverrideListener 实例和 overrideSubscribeUrl
        overrideListeners.put(overrideSubscribeUrl, overrideSubscribeListener);

        // 通过 configurators 对 providerUrl 的部分属性进行重写，同时缓存了封装重写过的 providerUrl
        // 和 listener 的 ServiceConfigurationListener
        // dubbo://192.168.1.104:20880/org.apache.dubbo.demo.DemoService?
        // anyhost=true&
        // application=demo-provider&
        // bean.name=org.apache.dubbo.demo.DemoService&
        // bind.ip=192.168.1.104&
        // bind.port=20880&
        // deprecated=false&
        // dubbo=2.0.2&
        // dynamic=true&
        // generic=false&
        // interface=org.apache.dubbo.demo.DemoService&
        // methods=sayHello&
        // pid=8603&
        // register=true&
        // release=&
        // side=provider&
        // timestamp=1558439778014
        providerUrl = overrideUrlWithConfig(providerUrl, overrideSubscribeListener);
        //export invoker
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker, providerUrl);

        // url to registry
        final Registry registry = getRegistry(originInvoker);
        final URL registeredProviderUrl = getRegisteredProviderUrl(providerUrl, registryUrl);
        ProviderInvokerWrapper<T> providerInvokerWrapper = ProviderConsumerRegTable.registerProvider(originInvoker,
                registryUrl, registeredProviderUrl);
        //to judge if we need to delay publish
        boolean register = registeredProviderUrl.getParameter("register", true);
        if (register) {
            register(registryUrl, registeredProviderUrl);
            providerInvokerWrapper.setReg(true);
        }

        // Deprecated! Subscribe to override rules in 2.6.x or before.
        registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);

        exporter.setRegisterUrl(registeredProviderUrl);
        exporter.setSubscribeUrl(overrideSubscribeUrl);
        //Ensure that a new exporter instance is returned every time export
        return new DestroyableExporter<>(exporter);
    }

    // 通过 configurators 对 providerUrl 的部分属性进行重写，同时缓存了封装重写过的 providerUrl 和 listener 的 ServiceConfigurationListener
    private URL overrideUrlWithConfig(URL providerUrl, OverrideListener listener) {
        // 通过 configurators 重写部分 providerUrl 属性
        providerUrl = providerConfigurationListener.overrideUrl(providerUrl);
        // 封装重写过的 providerUrl 和 listener 为 ServiceConfigurationListener
        ServiceConfigurationListener serviceConfigurationListener = new ServiceConfigurationListener(providerUrl, listener);
        // 缓存此 ServiceConfigurationListener，键为 providerUrl 的 interface 属性
        serviceConfigurationListeners.put(providerUrl.getServiceKey(), serviceConfigurationListener);
        return serviceConfigurationListener.overrideUrl(providerUrl);
    }

    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper<T> doLocalExport(final Invoker<T> originInvoker, URL providerUrl) {
        // dubbo://192.168.1.104:20880/org.apache.dubbo.demo.DemoService?
        // anyhost=true&
        // application=demo-provider&
        // bean.name=org.apache.dubbo.demo.DemoService&
        // bind.ip=192.168.1.104&
        // bind.port=20880&
        // deprecated=false&
        // dubbo=2.0.2&
        // generic=false&
        // interface=org.apache.dubbo.demo.DemoService&
        // methods=sayHello&
        // pid=8684&
        // register=true&
        // release=&
        // side=provider&
        // timestamp=1558440008495
        String key = getCacheKey(originInvoker);

        return (ExporterChangeableWrapper<T>) bounds.computeIfAbsent(key, s -> {
            // 用 InvokerDelegate 封装 originInvoker，providerUrl
            Invoker<?> invokerDelegate = new InvokerDelegate<>(originInvoker, providerUrl);
            return new ExporterChangeableWrapper<>((Exporter<T>) protocol.export(invokerDelegate), originInvoker);
        });
    }

    public <T> void reExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        // update local exporter
        ExporterChangeableWrapper exporter = doChangeLocalExport(originInvoker, newInvokerUrl);
        // update registry
        URL registryUrl = getRegistryUrl(originInvoker);
        final URL registeredProviderUrl = getRegisteredProviderUrl(newInvokerUrl, registryUrl);

        //decide if we need to re-publish
        ProviderInvokerWrapper<T> providerInvokerWrapper = ProviderConsumerRegTable.getProviderWrapper(registeredProviderUrl, originInvoker);
        ProviderInvokerWrapper<T> newProviderInvokerWrapper = ProviderConsumerRegTable.registerProvider(originInvoker, registryUrl, registeredProviderUrl);
        /**
         * Only if the new url going to Registry is different with the previous one should we do unregister and register.
         */
        if (providerInvokerWrapper.isReg() && !registeredProviderUrl.equals(providerInvokerWrapper.getProviderUrl())) {
            unregister(registryUrl, providerInvokerWrapper.getProviderUrl());
            register(registryUrl, registeredProviderUrl);
            newProviderInvokerWrapper.setReg(true);
        }

        exporter.setRegisterUrl(registeredProviderUrl);
    }

    /**
     * Reexport the invoker of the modified url
     *
     * @param originInvoker
     * @param newInvokerUrl
     */
    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper doChangeLocalExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        String key = getCacheKey(originInvoker);
        final ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        if (exporter == null) {
            logger.warn(new IllegalStateException("error state, exporter should not be null"));
        } else {
            final Invoker<T> invokerDelegate = new InvokerDelegate<T>(originInvoker, newInvokerUrl);
            exporter.setExporter(protocol.export(invokerDelegate));
        }
        return exporter;
    }

    /**
     * Get an instance of registry based on the address of invoker
     *
     * @param originInvoker
     * @return
     */
    private Registry getRegistry(final Invoker<?> originInvoker) {
        URL registryUrl = getRegistryUrl(originInvoker);
        return registryFactory.getRegistry(registryUrl);
    }

    // originInvoker 是 DelegateProviderMetaDataInvoker 的实例
    private URL getRegistryUrl(Invoker<?> originInvoker) {
        // 从 DelegateProviderMetaDataInvoker 获取 registryUrl
        URL registryUrl = originInvoker.getUrl();
        if (REGISTRY_PROTOCOL.equals(registryUrl.getProtocol())) {
            // 是使用的 registry 协议（只是理解为一种协议）
            // 获取 url 的 registry 参数值
            String protocol = registryUrl.getParameter(REGISTRY_KEY, DEFAULT_REGISTRY);
            // 根据 url 本身的 registry 参数修改 url 的协议，并移除 registry 参数
            registryUrl = registryUrl.setProtocol(protocol).removeParameter(REGISTRY_KEY);
        }
        return registryUrl;
    }


    /**
     * Return the url that is registered to the registry and filter the url parameter once
     *
     * @param providerUrl
     * @return url to registry.
     */
    private URL getRegisteredProviderUrl(final URL providerUrl, final URL registryUrl) {
        //The address you see at the registry
        if (!registryUrl.getParameter(SIMPLIFIED_KEY, false)) {
            return providerUrl.removeParameters(getFilteredKeys(providerUrl)).removeParameters(
                    MONITOR_KEY, BIND_IP_KEY, BIND_PORT_KEY, QOS_ENABLE, QOS_PORT, ACCEPT_FOREIGN_IP, VALIDATION_KEY,
                    INTERFACES);
        } else {
            String extraKeys = registryUrl.getParameter(EXTRA_KEYS_KEY, "");
            // if path is not the same as interface name then we should keep INTERFACE_KEY,
            // otherwise, the registry structure of zookeeper would be '/dubbo/path/providers',
            // but what we expect is '/dubbo/interface/providers'
            if (!providerUrl.getPath().equals(providerUrl.getParameter(INTERFACE_KEY))) {
                if (StringUtils.isNotEmpty(extraKeys)) {
                    extraKeys += ",";
                }
                extraKeys += INTERFACE_KEY;
            }
            String[] paramsToRegistry = getParamsToRegistry(DEFAULT_REGISTER_PROVIDER_KEYS
                    , COMMA_SPLIT_PATTERN.split(extraKeys));
            return URL.valueOf(providerUrl, paramsToRegistry, providerUrl.getParameter(METHODS_KEY, (String[]) null));
        }

    }

    // 修改 registeredProviderUrl 协议为 provider，添加
    // category -> configurators
    // check -> false 参数
    private URL getSubscribedOverrideUrl(URL registeredProviderUrl) {
        return registeredProviderUrl.setProtocol(PROVIDER_PROTOCOL)
                // 添加可变参数类型的参数，返回通过新参数重构的 URL
                // category -> configurators
                // check -> false
                .addParameters(CATEGORY_KEY, CONFIGURATORS_CATEGORY, CHECK_KEY, String.valueOf(false));
    }

    /**
     * Get the address of the providerUrl through the url of the invoker
     *
     * @param originInvoker
     * @return
     */
    private URL getProviderUrl(final Invoker<?> originInvoker) {
        // 获取 originInvoker 的 url 的 export 参数
        String export = originInvoker.getUrl().getParameterAndDecoded(EXPORT_KEY);
        if (export == null || export.length() == 0) {
            throw new IllegalArgumentException("The registry export url is null! registry: " + originInvoker.getUrl());
        }
        // 将 export 参数值反解为 URL 对象
        return URL.valueOf(export);
    }

    /**
     * Get the key cached in bounds by invoker
     *
     * @param originInvoker
     * @return
     */
    private String getCacheKey(final Invoker<?> originInvoker) {
        // 获取 originInvoker 的 url 属性的 export 参数对应的 url
        URL providerUrl = getProviderUrl(originInvoker);
        // 去掉 dynamic 和 enable 两个参数
        String key = providerUrl.removeParameters("dynamic", "enabled").toFullString();
        return key;
    }

    @Override
    @SuppressWarnings("unchecked")  // 此方法主要就是干了两件事情，根据 url 获取对应的 registry，然后根据 url 和获得的 registry 进行真正的 refer 操作
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        // registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?
        // application=demo-consumer&
        // dubbo=2.0.2&
        // pid=49041&
        // refer=application=demo-consumer&
        // check=false&
        // dubbo=2.0.2&
        // interface=org.apache.dubbo.demo.DemoService&
        // lazy=false&
        // methods=sayHello&
        // pid=49041&
        // register.ip=192.168.1.104&
        // side=consumer&
        // sticky=false&
        // timestamp=1558921508381&
        // registry=zookeeper&
        // timestamp=1558921508775
        url = URLBuilder.from(url)  // 此处又将 url 中的 registry 属性拿出来作为 protocol 类型，这里的 registry 其实就是 registry 标签的那个协议，在构建 url 时进行了替换而已
                .setProtocol(url.getParameter(REGISTRY_KEY, DEFAULT_REGISTRY))
                .removeParameter(REGISTRY_KEY)
                .build();
        // registryFactory 实例源代码
        // package org.apache.dubbo.registry;
        // import org.apache.dubbo.common.extension.ExtensionLoader;
        //
        // public class RegistryFactory$Adaptive implements org.apache.dubbo.registry.RegistryFactory {
        //     public org.apache.dubbo.registry.Registry getRegistry(org.apache.dubbo.common.URL arg0) {
        //         if (arg0 == null) throw new IllegalArgumentException("url == null");
        //         org.apache.dubbo.common.URL url = arg0;
        // 这里的 extName 实际是 zookeeper
        //         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
        //         if (extName == null)
        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.registry.RegistryFactory) name from url (" + url.toString() + ") use keys([protocol])");
        // 获取到的 RegistryFactory 实际是 ZookeeperRegistryFactory
        //         org.apache.dubbo.registry.RegistryFactory extension = (org.apache.dubbo.registry.RegistryFactory) ExtensionLoader.getExtensionLoader(org.apache.dubbo.registry.RegistryFactory.class).getExtension(extName);
        //         return extension.getRegistry(arg0);  // ZookeeperRegistryFactory 的父类方法，重构了 url，缓存了 serviceKey 与 registry 的关系
        //     }
        // }
        Registry registry = registryFactory.getRegistry(url);   // 拿到的是 ZookeeperRegistry，持有了 zkClient
        if (RegistryService.class.equals(type)) {
            return proxyFactory.getInvoker((T) registry, type, url);
        }

        // group="a,b" or group="*"
        // application=demo-consumer&
        // check=false&
        // dubbo=2.0.2&
        // interface=org.apache.dubbo.demo.DemoService&
        // lazy=false&
        // methods=sayHello&
        // pid=49134&
        // register.ip=192.168.1.104&
        // side=consumer&
        // sticky=false&
        // timestamp=1558921722520
        Map<String, String> qs = StringUtils.parseQueryString(url.getParameterAndDecoded(REFER_KEY));   // 将 refer 参数的值转换为 map
        String group = qs.get(GROUP_KEY);
        if (group != null && group.length() > 0) {
            if ((COMMA_SPLIT_PATTERN.split(group)).length > 1 || "*".equals(group)) {
                return doRefer(getMergeableCluster(), registry, type, url); // 这里是多个 group 的处理方式
            }
        }
        // 得到的是 MockClusterInvoker
        return doRefer(cluster, registry, type, url);
    }

    private Cluster getMergeableCluster() {
        return ExtensionLoader.getExtensionLoader(Cluster.class).getExtension("mergeable");
    }

    private <T> Invoker<T> doRefer(Cluster cluster, Registry registry, Class<T> type, URL url) {
        RegistryDirectory<T> directory = new RegistryDirectory<T>(type, url);   // refer 操作需要依赖于 RegistryDirectory，新建 RegistryDirectory，并完成了相关属性的赋值
        directory.setRegistry(registry);    // ZookeeperRegistry 也保存到 directory 中
        // protocol 实例的源代码
        // package org.apache.dubbo.rpc;
        // import org.apache.dubbo.common.extension.ExtensionLoader;
        // public class Protocol$Adaptive implements org.apache.dubbo.rpc.Protocol {
        //     public void destroy() {
        //         throw new UnsupportedOperationException("The method public abstract void org.apache.dubbo.rpc.Protocol.destroy() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
        //
        //     public int getDefaultPort() {
        //         throw new UnsupportedOperationException("The method public abstract int org.apache.dubbo.rpc.Protocol.getDefaultPort() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
        //
        //     public org.apache.dubbo.rpc.Exporter export(org.apache.dubbo.rpc.Invoker arg0) throws org.apache.dubbo.rpc.RpcException {
        //         if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
        //         if (arg0.getUrl() == null)
        //             throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
        //         org.apache.dubbo.common.URL url = arg0.getUrl();
        //         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
        //         if (extName == null)
        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
        //         org.apache.dubbo.rpc.Protocol extension = (org.apache.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.Protocol.class).getExtension(extName);
        //         return extension.export(arg0);
        //
        //     public org.apache.dubbo.rpc.Invoker refer(java.lang.Class arg0, org.apache.dubbo.common.URL arg1) throws org.apache.dubbo.rpc.RpcException {
        //         if (arg1 == null) throw new IllegalArgumentException("url == null");
        //         org.apache.dubbo.common.URL url = arg1;
        //         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
        //         if (extName == null)
        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
        //         org.apache.dubbo.rpc.Protocol extension = (org.apache.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.Protocol.class).getExtension(extName);
        //         return extension.refer(arg0, arg1);
        //     }
        // }
        directory.setProtocol(protocol);
        // all attributes of REFER_KEY，存储的是 directory 的 overrideDirectoryUrl 的参数，在构造函数中进行初始化
        Map<String, String> parameters = new HashMap<String, String>(directory.getUrl().getParameters());
        // consumer://192.168.1.101/org.apache.dubbo.demo.DemoService?application=demo-consumer&check=false&dubbo=2.0.2&interface=org.apache.dubbo.demo.DemoService&lazy=false&methods=sayHello&pid=83276&side=consumer&sticky=false&timestamp=1559352722835
        // application=demo-consumer&
        // check=false&
        // dubbo=2.0.2&
        // interface=org.apache.dubbo.demo.DemoService&
        // lazy=false&
        // methods=sayHello&
        // pid=50763&
        // side=consumer&
        // sticky=false&
        // timestamp=1558925222997
        URL subscribeUrl = new URL(CONSUMER_PROTOCOL, parameters.remove(REGISTER_IP_KEY), 0, type.getName(), parameters);   // registry.ip
        if (!ANY_VALUE.equals(url.getServiceInterface()) && url.getParameter(REGISTER_KEY, true)) {
            directory.setRegisteredConsumerUrl(getRegisteredConsumerUrl(subscribeUrl, url));    // 为 subscribeUrl 添加了 category 和 check 属性，填充到 directory 中
            registry.register(directory.getRegisteredConsumerUrl());    // 缓存了 url 到 registered 结合中，同时根据 url 在 zookeeper 中创建了 /root/url的interface参数/url的category参数/url的全字符串编码路径
        }   // 构建 RouterChain 的过程中还会获取全部的 RouterFactory，然后得到对应的 Router，最后将这些 Router 保存到实例字段中
        directory.buildRouterChain(subscribeUrl);   // 构建了 routerChain 并填充了属性
        directory.subscribe(subscribeUrl.addParameter(CATEGORY_KEY, // category -> providers
                PROVIDERS_CATEGORY + "," + CONFIGURATORS_CATEGORY + "," + ROUTERS_CATEGORY));   // configurators -> routers
        // 此 cluster.adaptive 获得的 extension 为 MockClusterWrapper 包含了 FailoverCluster，然后调用它的 join 方法得到 MockClusterInvoker
        Invoker invoker = cluster.join(directory);  // 得到 MockClusterInvoker
        // 根本就是将参数包装成为 ConsumerInvokerWrapper，然后保存到 consumerInvokers 的 serviceUniqueName key 对应的 set 集合中
        ProviderConsumerRegTable.registerConsumer(invoker, url, subscribeUrl, directory);
        return invoker;
    }

    public URL getRegisteredConsumerUrl(final URL consumerUrl, URL registryUrl) {
        if (!registryUrl.getParameter(SIMPLIFIED_KEY, false)) { // registryUrl 的 simplified 属性为 false
            return consumerUrl.addParameters(CATEGORY_KEY, CONSUMERS_CATEGORY,  // category -> consumers
                    CHECK_KEY, String.valueOf(false));  // check -> false
        } else {
            return URL.valueOf(consumerUrl, DEFAULT_REGISTER_CONSUMER_KEYS, null).addParameters(
                    CATEGORY_KEY, CONSUMERS_CATEGORY, CHECK_KEY, String.valueOf(false));
        }
    }

    // available to test
    public String[] getParamsToRegistry(String[] defaultKeys, String[] additionalParameterKeys) {
        int additionalLen = additionalParameterKeys.length;
        String[] registryParams = new String[defaultKeys.length + additionalLen];
        System.arraycopy(defaultKeys, 0, registryParams, 0, defaultKeys.length);
        System.arraycopy(additionalParameterKeys, 0, registryParams, defaultKeys.length, additionalLen);
        return registryParams;
    }

    @Override
    public void destroy() {
        List<Exporter<?>> exporters = new ArrayList<Exporter<?>>(bounds.values());
        for (Exporter<?> exporter : exporters) {
            exporter.unexport();
        }
        bounds.clear();

        DynamicConfiguration.getDynamicConfiguration()
                .removeListener(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX, providerConfigurationListener);
    }

    //Merge the urls of configurators
    private static URL getConfigedInvokerUrl(List<Configurator> configurators, URL url) {
        if (configurators != null && configurators.size() > 0) {
            for (Configurator configurator : configurators) {
                // 遍历 configurator 实例，对 url 进行配置
                url = configurator.configure(url);
            }
        }
        return url;
    }

    public static class InvokerDelegate<T> extends InvokerWrapper<T> {
        private final Invoker<T> invoker;

        /**
         * @param invoker
         * @param url     invoker.getUrl return this value
         */
        public InvokerDelegate(Invoker<T> invoker, URL url) {
            super(invoker, url);
            this.invoker = invoker;
        }

        public Invoker<T> getInvoker() {
            if (invoker instanceof InvokerDelegate) {
                return ((InvokerDelegate<T>) invoker).getInvoker();
            } else {
                return invoker;
            }
        }
    }

    static private class DestroyableExporter<T> implements Exporter<T> {

        private Exporter<T> exporter;

        public DestroyableExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        @Override
        public void unexport() {
            exporter.unexport();
        }
    }

    /**
     * Reexport: the exporter destroy problem in protocol
     * 1.Ensure that the exporter returned by registryprotocol can be normal destroyed
     * 2.No need to re-register to the registry after notify
     * 3.The invoker passed by the export method , would better to be the invoker of exporter
     */
    private class OverrideListener implements NotifyListener {
        private final URL subscribeUrl;
        private final Invoker originInvoker;


        private List<Configurator> configurators;

        public OverrideListener(URL subscribeUrl, Invoker originalInvoker) {
            this.subscribeUrl = subscribeUrl;
            this.originInvoker = originalInvoker;
        }

        /**
         * @param urls The list of registered information, is always not empty, The meaning is the same as the
         *             return value of {@link org.apache.dubbo.registry.RegistryService#lookup(URL)}.
         */
        @Override
        public synchronized void notify(List<URL> urls) {
            logger.debug("original override urls: " + urls);

            List<URL> matchedUrls = getMatchedUrls(urls, subscribeUrl.addParameter(CATEGORY_KEY,
                    CONFIGURATORS_CATEGORY));
            logger.debug("subscribe url: " + subscribeUrl + ", override urls: " + matchedUrls);

            // No matching results
            if (matchedUrls.isEmpty()) {
                return;
            }

            this.configurators = Configurator.toConfigurators(classifyUrls(matchedUrls, UrlUtils::isConfigurator))
                    .orElse(configurators);

            doOverrideIfNecessary();
        }

        public synchronized void doOverrideIfNecessary() {
            final Invoker<?> invoker;
            if (originInvoker instanceof InvokerDelegate) {
                invoker = ((InvokerDelegate<?>) originInvoker).getInvoker();
            } else {
                invoker = originInvoker;
            }
            //The origin invoker
            URL originUrl = RegistryProtocol.this.getProviderUrl(invoker);
            String key = getCacheKey(originInvoker);
            ExporterChangeableWrapper<?> exporter = bounds.get(key);
            if (exporter == null) {
                logger.warn(new IllegalStateException("error state, exporter should not be null"));
                return;
            }
            //The current, may have been merged many times
            URL currentUrl = exporter.getInvoker().getUrl();
            //Merged with this configuration
            URL newUrl = getConfigedInvokerUrl(configurators, originUrl);
            newUrl = getConfigedInvokerUrl(serviceConfigurationListeners.get(originUrl.getServiceKey())
                    .getConfigurators(), newUrl);
            newUrl = getConfigedInvokerUrl(providerConfigurationListener.getConfigurators(), newUrl);
            if (!currentUrl.equals(newUrl)) {
                RegistryProtocol.this.reExport(originInvoker, newUrl);
                logger.info("exported provider url changed, origin url: " + originUrl +
                        ", old export url: " + currentUrl + ", new export url: " + newUrl);
            }
        }

        private List<URL> getMatchedUrls(List<URL> configuratorUrls, URL currentSubscribe) {
            List<URL> result = new ArrayList<URL>();
            for (URL url : configuratorUrls) {
                URL overrideUrl = url;
                // Compatible with the old version
                if (url.getParameter(CATEGORY_KEY) == null && OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
                    overrideUrl = url.addParameter(CATEGORY_KEY, CONFIGURATORS_CATEGORY);
                }

                // Check whether url is to be applied to the current service
                if (UrlUtils.isMatch(currentSubscribe, overrideUrl)) {
                    result.add(url);
                }
            }
            return result;
        }
    }

    private class ServiceConfigurationListener extends AbstractConfiguratorListener {
        private URL providerUrl;
        private OverrideListener notifyListener;

        public ServiceConfigurationListener(URL providerUrl, OverrideListener notifyListener) {
            this.providerUrl = providerUrl;
            this.notifyListener = notifyListener;
            this.initWith(providerUrl.getEncodedServiceKey() + CONFIGURATORS_SUFFIX);
        }

        private <T> URL overrideUrl(URL providerUrl) {
            // 通过 configurators 对 providerUrl 进行相应的配置
            return RegistryProtocol.getConfigedInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            notifyListener.doOverrideIfNecessary();
        }
    }

    private class ProviderConfigurationListener extends AbstractConfiguratorListener {

        public ProviderConfigurationListener() {
            this.initWith(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX);
        }

        /**
         * Get existing configuration rule and override provider url before exporting.
         *
         * @param providerUrl
         * @param <T>
         * @return
         */
        private <T> URL overrideUrl(URL providerUrl) {
            // 通过 configurators 对 providerUrl 进行相应的配置
            return RegistryProtocol.getConfigedInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            overrideListeners.values().forEach(listener -> ((OverrideListener) listener).doOverrideIfNecessary());
        }
    }

    /**
     * exporter proxy, establish the corresponding relationship between the returned exporter and the exporter
     * exported by the protocol, and can modify the relationship at the time of override.
     *
     * @param <T>
     */
    private class ExporterChangeableWrapper<T> implements Exporter<T> {

        private final ExecutorService executor = newSingleThreadExecutor(new NamedThreadFactory("Exporter-Unexport", true));

        private final Invoker<T> originInvoker;
        private Exporter<T> exporter;
        private URL subscribeUrl;
        private URL registerUrl;

        public ExporterChangeableWrapper(Exporter<T> exporter, Invoker<T> originInvoker) {
            this.exporter = exporter;
            this.originInvoker = originInvoker;
        }

        public Invoker<T> getOriginInvoker() {
            return originInvoker;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        public void setExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public void unexport() {
            String key = getCacheKey(this.originInvoker);
            bounds.remove(key);

            Registry registry = RegistryProtocol.INSTANCE.getRegistry(originInvoker);
            try {
                registry.unregister(registerUrl);
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
            try {
                NotifyListener listener = RegistryProtocol.INSTANCE.overrideListeners.remove(subscribeUrl);
                registry.unsubscribe(subscribeUrl, listener);
                DynamicConfiguration.getDynamicConfiguration()
                        .removeListener(subscribeUrl.getServiceKey() + CONFIGURATORS_SUFFIX,
                                serviceConfigurationListeners.get(subscribeUrl.getServiceKey()));
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }

            executor.submit(() -> {
                try {
                    int timeout = ConfigurationUtils.getServerShutdownTimeout();
                    if (timeout > 0) {
                        logger.info("Waiting " + timeout + "ms for registry to notify all consumers before unexport. " +
                                "Usually, this is called when you use dubbo API");
                        Thread.sleep(timeout);
                    }
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            });
        }

        public void setSubscribeUrl(URL subscribeUrl) {
            this.subscribeUrl = subscribeUrl;
        }

        public void setRegisterUrl(URL registerUrl) {
            this.registerUrl = registerUrl;
        }
    }
}
