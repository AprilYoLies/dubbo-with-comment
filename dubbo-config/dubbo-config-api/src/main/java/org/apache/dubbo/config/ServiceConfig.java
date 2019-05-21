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
package org.apache.dubbo.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.metadata.integration.MetadataReportService;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.ConfiguratorFactory;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ProviderModel;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.DUBBO_IP_TO_BIND;
import static org.apache.dubbo.common.constants.ConfigConstants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.common.constants.ConfigConstants.DUBBO_PORT_TO_BIND;
import static org.apache.dubbo.common.constants.ConfigConstants.DUBBO_PORT_TO_REGISTRY;
import static org.apache.dubbo.common.constants.ConfigConstants.EXPORT_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.MULTICAST;
import static org.apache.dubbo.common.constants.ConfigConstants.PROTOCOLS_SUFFIX;
import static org.apache.dubbo.common.constants.ConfigConstants.SCOPE_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.SCOPE_LOCAL;
import static org.apache.dubbo.common.constants.ConfigConstants.SCOPE_NONE;
import static org.apache.dubbo.common.constants.ConfigConstants.SCOPE_REMOTE;
import static org.apache.dubbo.common.constants.MonitorConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.GENERIC_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.LOCAL_PROTOCOL;
import static org.apache.dubbo.common.constants.RpcConstants.PROXY_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.TOKEN_KEY;
import static org.apache.dubbo.common.utils.NetUtils.getAvailablePort;
import static org.apache.dubbo.common.utils.NetUtils.getLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidPort;

/**
 * ServiceConfig
 *
 * @export
 */
public class ServiceConfig<T> extends AbstractServiceConfig {

    private static final long serialVersionUID = 3033787999037024738L;

    /**
     * The {@link Protocol} implementation with adaptive functionality,it will be different in different scenarios.
     * A particular {@link Protocol} implementation is determined by the protocol attribute in the {@link URL}.
     * For example:
     *
     * <li>when the url is registry://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=dubbo-sample,
     * then the protocol is <b>RegistryProtocol</b></li>
     *
     * <li>when the url is dubbo://224.5.6.7:1234/org.apache.dubbo.config.api.DemoService?application=dubbo-sample, then
     * the protocol is <b>DubboProtocol</b></li>
     * <p>
     * Actually，when the {@link ExtensionLoader} init the {@link Protocol} instants,it will automatically wraps two
     * layers, and eventually will get a <b>ProtocolFilterWrapper</b> or <b>ProtocolListenerWrapper</b>
     */

    // 这是静态字段，在加载 ServiceBean 的 class 对象时，就会对静态字段进行初始化工作
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
    private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a exported service proxy,the JavassistProxyFactory is its
     * default implementation
     */

    // ProxyFactory 实际为 ProxyFactory.Adaptive 实例
    // package org.apache.dubbo.rpc;
    // import org.apache.dubbo.common.extension.ExtensionLoader;
    // public class ProxyFactory$Adaptive implements org.apache.dubbo.rpc.ProxyFactory {
    //     public org.apache.dubbo.rpc.Invoker getInvoker(java.lang.Object arg0, java.lang.Class arg1, org.apache.dubbo.common.URL arg2) throws org.apache.dubbo.rpc.RpcException {
    //         if (arg2 == null) throw new IllegalArgumentException("url == null");
    //         org.apache.dubbo.common.URL url = arg2;
    //         String extName = url.getParameter("proxy", "javassist");
    //         if (extName == null)
    //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
    //         org.apache.dubbo.rpc.ProxyFactory extension = (org.apache.dubbo.rpc.ProxyFactory) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.ProxyFactory.class).getExtension(extName);
    //         return extension.getInvoker(arg0, arg1, arg2);
    //     }
    //     public java.lang.Object getProxy(org.apache.dubbo.rpc.Invoker arg0, boolean arg1) throws org.apache.dubbo.rpc.RpcException {
    //         if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
    //         if (arg0.getUrl() == null)
    //             throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
    //         org.apache.dubbo.common.URL url = arg0.getUrl();
    //         String extName = url.getParameter("proxy", "javassist");
    //         if (extName == null)
    //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
    //         org.apache.dubbo.rpc.ProxyFactory extension = (org.apache.dubbo.rpc.ProxyFactory) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.ProxyFactory.class).getExtension(extName);
    //         return extension.getProxy(arg0, arg1);
    //     }
    //     public java.lang.Object getProxy(org.apache.dubbo.rpc.Invoker arg0) throws org.apache.dubbo.rpc.RpcException {
    //         if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
    //         if (arg0.getUrl() == null)
    //             throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
    //         org.apache.dubbo.common.URL url = arg0.getUrl();
    //         String extName = url.getParameter("proxy", "javassist");
    //         if (extName == null)
    //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
    //         org.apache.dubbo.rpc.ProxyFactory extension = (org.apache.dubbo.rpc.ProxyFactory) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.ProxyFactory.class).getExtension(extName);
    //         return extension.getProxy(arg0);
    //     }
    // }
    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * A random port cache, the different protocols who has no port specified have different random port
     */
    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    /**
     * A delayed exposure service timer
     */
    private static final ScheduledExecutorService delayExportExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));

    /**
     * The urls of the services exported
     */
    private final List<URL> urls = new ArrayList<URL>();

    /**
     * The exported services
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();

    /**
     * The interface name of the exported service
     */
    private String interfaceName;

    /**
     * The interface class of the exported service
     */
    private Class<?> interfaceClass;

    /**
     * The reference of the interface implementation
     */
    private T ref;

    /**
     * The service name
     */
    private String path;

    /**
     * The method configuration
     */
    private List<MethodConfig> methods;

    /**
     * The provider configuration
     */
    private ProviderConfig provider;

    /**
     * The providerIds
     */
    private String providerIds;

    /**
     * Whether the provider has been exported
     */
    private transient volatile boolean exported;

    /**
     * The flag whether a service has unexported ,if the method unexported is invoked, the value is true
     */
    private transient volatile boolean unexported;

    /**
     * whether it is a GenericService
     */
    private volatile String generic;

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        appendAnnotation(Service.class, service);
        setMethods(MethodConfig.constructMethodConfig(service.methods()));
    }

    // 根据 ProviderConfig 集合获取 ProtocolConfig 集合
    @Deprecated
    private static List<ProtocolConfig> convertProviderToProtocol(List<ProviderConfig> providers) {
        if (CollectionUtils.isEmpty(providers)) {
            return null;
        }
        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>(providers.size());
        for (ProviderConfig provider : providers) {
            // 进行转换并添加
            protocols.add(convertProviderToProtocol(provider));
        }
        return protocols;
    }

    @Deprecated
    private static List<ProviderConfig> convertProtocolToProvider(List<ProtocolConfig> protocols) {
        if (CollectionUtils.isEmpty(protocols)) {
            return null;
        }
        List<ProviderConfig> providers = new ArrayList<ProviderConfig>(protocols.size());
        for (ProtocolConfig provider : protocols) {
            providers.add(convertProtocolToProvider(provider));
        }
        return providers;
    }

    // 将 ProviderConfig 转换为 ProtocolConfig，过程就是单纯的属性传递
    @Deprecated
    private static ProtocolConfig convertProviderToProtocol(ProviderConfig provider) {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(provider.getProtocol().getName());
        protocol.setServer(provider.getServer());
        protocol.setClient(provider.getClient());
        protocol.setCodec(provider.getCodec());
        protocol.setHost(provider.getHost());
        protocol.setPort(provider.getPort());
        protocol.setPath(provider.getPath());
        protocol.setPayload(provider.getPayload());
        protocol.setThreads(provider.getThreads());
        protocol.setParameters(provider.getParameters());
        return protocol;
    }

    @Deprecated
    private static ProviderConfig convertProtocolToProvider(ProtocolConfig protocol) {
        ProviderConfig provider = new ProviderConfig();
        provider.setProtocol(protocol);
        provider.setServer(protocol.getServer());
        provider.setClient(protocol.getClient());
        provider.setCodec(protocol.getCodec());
        provider.setHost(protocol.getHost());
        provider.setPort(protocol.getPort());
        provider.setPath(protocol.getPath());
        provider.setPayload(protocol.getPayload());
        provider.setThreads(protocol.getThreads());
        provider.setParameters(protocol.getParameters());
        return provider;
    }

    private static Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        return RANDOM_PORT_MAP.getOrDefault(protocol, Integer.MIN_VALUE);
    }

    private static void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
            logger.warn("Use random available port(" + port + ") for protocol " + protocol);
        }
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    @Parameter(excluded = true)
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    public boolean isUnexported() {
        return unexported;
    }

    public void checkAndUpdateSubConfigs() {
        // Use default configs defined explicitly on global configs
        // 完成复合属性的填充，即根据 provider、module 和 application 填充 ServiceBean 跟 ConfigManager 的属性
        completeCompoundConfigs();
        // Config Center should always being started first.
        // 这个方法初次启动时，只是对各种 config 的属性进行了更新
        startConfigCenter();
        // 检查取默认的 provider，没有的话，则新建一个
        checkDefault();
        // 检查 ServiceBean 是否存在 protocols 属性，没有的话要尝试进行填充
        checkProtocol();
        // 检查 application 属性是否存在，并对 shutdown.wait 相关的属性进行处理
        checkApplication();
        // if protocol is not injvm checkRegistry
        // 如果仅仅包含一种 protocol，并且其名字为 injvm
        if (!isOnlyInJvm()) {
            // 检查 ServiceBean 中的 registries 属性，并根据实际情况将其应用到 ConfigCenterConfig
            checkRegistry();
        }
        // 刷新 ServiceBean 自身的属性
        this.refresh();
        // 对 metadataReportConfig 相关内容进行检查
        checkMetadataReport();

        // ServiceBean 一定要有 interface 属性
        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }

        // 这个 if else 代码块，主要是用来对 interfaceClass 进行相关的设置，同时检查 interfaceClass 和 methods 属性的关系
        // 如果 ref 是 GenericService 的实例
        if (ref instanceof GenericService) {
            // 修改 interfaceClass 属性的值
            interfaceClass = GenericService.class;
            // 如果 generic 为空，就将其置为 true
            if (StringUtils.isEmpty(generic)) {
                generic = Boolean.TRUE.toString();
            }
        } else {
            try {
                // 否则初始化 interfaceClass 为 interfaceName 所指的 class 对象
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 通过 interfaceClass 对 methods 的相关属性进行设置，同时检查 methods 是否在 interfaceClass 存在相应的方法
            checkInterfaceAndMethods(interfaceClass, methods);
            // 检查 ref 是否是引用的 interfaceClass 类型
            checkRef();
            // 修改 generic 属性为 false
            generic = Boolean.FALSE.toString();
        }
        // 存在 local 相关的属性
        if (local != null) {
            if ("true".equals(local)) {
                // 指定 local 的属性值
                local = interfaceName + "Local";
            }
            Class<?> localClass;
            try {
                // 是不是要使用本地发布服务，就必须要自己新建 Local 结尾的服务类呢？
                localClass = ClassUtils.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 这个以 Local 结尾的类也必须是 interfaceClass 所代表的类，即是它的子类或者一致
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        // 存在 stub 相关的属性
        if (stub != null) {
            if ("true".equals(stub)) {
                stub = interfaceName + "Stub";
            }
            Class<?> stubClass;
            try {
                // 是不是要自己新建 Stub 结尾的相关类呢？
                stubClass = ClassUtils.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 这个以 Stub 结尾的类也必须是 interfaceClass 所代表的类，即是它的子类或者一致
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }
        // 主要是检查 Local 结尾的类是否有 interfaceClass 类型的参数
        checkStubAndLocal(interfaceClass);
        //TODO 对 mock 相关的一些属性进行解析,暂时不是很清楚 mock 是干嘛用的
        checkMock(interfaceClass);
    }

    /**
     * Determine if it is injvm
     *
     * @return
     */
    private boolean isOnlyInJvm() {
        return getProtocols().size() == 1 && LOCAL_PROTOCOL.equalsIgnoreCase(getProtocols().get(0).getName());
    }

    public synchronized void export() {
        // 对一系列的配置进行检查或者更新
        checkAndUpdateSubConfigs();

        // 需要相关的配置允许暴露服务才行
        if (!shouldExport()) {
            return;
        }

        // 延迟暴露服务
        if (shouldDelay()) {
            delayExportExecutor.schedule(this::doExport, getDelay(), TimeUnit.MILLISECONDS);
        } else {
            // 直接暴露服务
            doExport();
        }
    }

    private boolean shouldExport() {
        Boolean export = getExport();
        // default value is true
        return export == null ? true : export;
    }

    @Override
    public Boolean getExport() {
        // export 不为空就由 export 决定，否则 provider 不为空，就由 provider 决定，provider 为空，那还是 export 决定
        return (export == null && provider != null) ? provider.getExport() : export;
    }

    private boolean shouldDelay() {
        Integer delay = getDelay();
        return delay != null && delay > 0;
    }

    @Override
    public Integer getDelay() {
        return (delay == null && provider != null) ? provider.getDelay() : delay;
    }

    protected synchronized void doExport() {
        // 检查 unexported 和 exported 的状态
        if (unexported) {
            throw new IllegalStateException("The service " + interfaceClass.getName() + " has already unexported!");
        }
        if (exported) {
            return;
        }
        // 修改 exported 的状态，检查 path 属性
        exported = true;

        if (StringUtils.isEmpty(path)) {
            path = interfaceName;
        }
        // 发布服务 url 地址
        doExportUrls();
    }

    // ref 必须是引用的 interfaceClass 类型
    private void checkRef() {
        // reference should not be null, and is the implementation of the given interface
        if (ref == null) {
            throw new IllegalStateException("ref not allow null!");
        }
        if (!interfaceClass.isInstance(ref)) {
            throw new IllegalStateException("The class "
                    + ref.getClass().getName() + " unimplemented interface "
                    + interfaceClass + "!");
        }
    }

    public synchronized void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("Unexpected error occured when unexport " + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrls() {
        // 获取满足条件的 url 地址，根据 registries 属性得到
        List<URL> registryURLs = loadRegistries(true);
        for (ProtocolConfig protocolConfig : protocols) {
            // 构建的模式 group/path:version
            // getContextPath(protocolConfig) 只是为了在 path 前加一个上下文路径，没获取到的话就直接使用 path
            // org.apache.dubbo.demo.DemoService
            String pathKey = URL.buildKey(getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), group, version);
            // 新建一个 ProviderModel 对象，完成对它的 methods 属性的初始化
            ProviderModel providerModel = new ProviderModel(pathKey, ref, interfaceClass);
            // 将 providerModel 添加到 PROVIDED_SERVICES 中去
            ApplicationModel.initProviderModel(pathKey, providerModel);
            // 根据不同的 protocol 发布 URL
            doExportUrlsFor1Protocol(protocolConfig, registryURLs);
        }
    }

    // 根据不同的 protocol 发布 URL
    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        String name = protocolConfig.getName();
        // 缺省 protocol 为 dubbo
        if (StringUtils.isEmpty(name)) {
            name = DUBBO;
        }


        Map<String, String> map = new HashMap<String, String>();
        // side -> provider 区分身份
        map.put(SIDE_KEY, PROVIDER_SIDE);

        // 添加一些运行时的参数信息，比如：
        // dubbo -> dubbo 协议版本
        // release -> dubbo 版本
        // timestamp -> 运行的时间戳
        // 进程 id 号
        appendRuntimeParameters(map);
        // 将属性值添加到 map 中
        appendParameters(map, metrics);
        appendParameters(map, application);
        appendParameters(map, module);
        // remove 'default.' prefix for configs from ProviderConfig
        // appendParameters(map, provider, Constants.DEFAULT_KEY);
        appendParameters(map, provider);
        appendParameters(map, protocolConfig);
        appendParameters(map, this);
        if (CollectionUtils.isNotEmpty(methods)) {
            for (MethodConfig method : methods) {
                // 以 method.getName() 为前缀进行 parameter 的追加
                appendParameters(map, method, method.getName());
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    // 如果 map 中有 method.getName() + ".retry" 的属性且值为 false，将其替换为 0
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(method.getName() + ".retries", "0");
                    }
                }
                // MethodConfig 的 ArgumentConfig
                List<ArgumentConfig> arguments = method.getArguments();
                if (CollectionUtils.isNotEmpty(arguments)) {
                    for (ArgumentConfig argument : arguments) {
                        // convert argument type
                        // 如果 MethodConfig 的 ArgumentConfig 的 type 属性不为空或者空串
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            Method[] methods = interfaceClass.getMethods();
                            // visit all methods
                            if (methods != null && methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // target the method, and get its signature
                                    // 从 interfaceClass 接口中找到和当前 MethodConfig 的名字相同的方法
                                    if (methodName.equals(method.getName())) {
                                        // 这是接口的参数类型
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        if (argument.getIndex() != -1) {
                                            // 如果 interfaceClass 对应方法的参数类型和 MethodConfig 的 ArgumentConfig 的参数类型一致
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                // 在 map 中添加一堆属性，前缀为 method.getName() + "." + argument.getIndex()，也就是说记录了 argument 在方法的参数的位置
                                                appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException("Argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            // 这里说明 argument 并没有指定在方法参数中的位置
                                            // multiple callbacks in the method
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    // 那就自行从 interfaceClass 对应方法的参数中找到 argument应该在的位置，添加到 map 中
                                                    appendParameters(map, argument, method.getName() + "." + j);
                                                    // 没有找到那就报错
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException("Argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (argument.getIndex() != -1) {
                            // 如果 argument 没有记录类型，但是记录了在方法参数中的位置，也能添加
                            appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            // 这里说明 argument 在的位置和类型两种信息必须存在一个
                            throw new IllegalArgumentException("Argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }

        // true 或 nativejava 或 bean 或 probobuf-json
        if (ProtocolUtils.isGeneric(generic)) {
            map.put(GENERIC_KEY, generic);
            map.put(METHODS_KEY, ANY_VALUE);
        } else {
            // 从 MANIFEST.MF 文件获取版本信息，没有的话就根据 jar 推测版本信息，否则使用 version 参数值
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                // 追加 version 信息
                map.put(REVISION_KEY, revision);
            }

            // 通过 interfaceClass 构建 Wrapper 实例，然后获取其中的方法名
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                // 没有方法的情况下，就向 map 中存入 methods -> *
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                // 否则就是存入 methods -> method1,method2,method3...
                map.put(METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }
        // 对应 service 标签的 token 属性
        if (!ConfigUtils.isEmpty(token)) {
            // 为 true 或者 default 为真
            if (ConfigUtils.isDefault(token)) {
                // token -> uuid
                map.put(TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                // 使用设置的值
                map.put(TOKEN_KEY, token);
            }
        }
        // export service
        // 根据 protocolConfig, registryURLs 向 map 中添加 bind.ip 和 anyhost 属性，同时返回 hostToRegistry（bind.ip 属性的值）
        String host = this.findConfigedHosts(protocolConfig, registryURLs, map);
        // 针对 default、bind、registry 三种类型的端口进行处理，返回 registryPort
        Integer port = this.findConfigedPorts(protocolConfig, name, map);
        // 重构 url
        URL url = new URL(name, host, port, getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), map);

        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            // 如果有 url 协议名称的 ConfiguratorFactory SPI 接口的 Extension 拓展类
            // 那么就拿到对应的 ConfiguratorFactory Extension 拓展类，对 url 进行配置
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        String scope = url.getParameter(SCOPE_KEY);
        // don't export when none is configured
        if (!SCOPE_NONE.equalsIgnoreCase(scope)) {
            // 如果 url 有 scope 参数，且不为 none
            // export to local if the config is not remote (export to remote only when config is remote)
            // 如果 url 的 scope 参数为 remote，那么就只会进行进行远程的 url 暴露，而不会进行本地模式的 url 暴露
            if (!SCOPE_REMOTE.equalsIgnoreCase(scope)) {
                // 所做的就是将 url 封装成 Exporter，然后保存到 ServiceBean 的 exporters 属性中
                // exportLocal 看 url 的 scope 参数
                exportLocal(url);
            }
            // export to remote if the config is not local (export to local only when config is local)
            if (!SCOPE_LOCAL.equalsIgnoreCase(scope)) {
                // url 的 scope 参数不为 local，protocols 也不是唯一的 injvm
                if (!isOnlyInJvm() && logger.isInfoEnabled()) {
                    logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                }
                if (CollectionUtils.isNotEmpty(registryURLs)) {
                    // 遍历待注册的 urls
                    for (URL registryURL : registryURLs) {
                        //if protocol is only injvm ,not register
                        if (LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
                            // 跳过 injvm 协议的 url
                            continue;
                        }
                        // 指定 dynamic
                        url = url.addParameterIfAbsent(DYNAMIC_KEY, registryURL.getParameter(DYNAMIC_KEY));
                        // 根据 monitor 配置构建对应的 url 地址
                        URL monitorUrl = loadMonitor(registryURL);
                        if (monitorUrl != null) {
                            // 将 monitorUrl 作为了 url 的一个参数，monitor -> monitorUrl
                            url = url.addParameterAndEncoded(MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
                        }

                        // For providers, this is used to enable custom proxy to generate invoker
                        String proxy = url.getParameter(PROXY_KEY);
                        if (StringUtils.isNotEmpty(proxy)) {
                            // url 中的 proxy 属性添加到 registryURL 中
                            registryURL = registryURL.addParameter(PROXY_KEY, proxy);
                        }

                        // proxyFactory 实例的代码内容
                        // package org.apache.dubbo.rpc;
                        // import org.apache.dubbo.common.extension.ExtensionLoader;
                        // public class ProxyFactory$Adaptive implements org.apache.dubbo.rpc.ProxyFactory {
                        //     public org.apache.dubbo.rpc.Invoker getInvoker(java.lang.Object arg0, java.lang.Class arg1, org.apache.dubbo.common.URL arg2) throws org.apache.dubbo.rpc.RpcException {
                        //         if (arg2 == null) throw new IllegalArgumentException("url == null");
                        //         org.apache.dubbo.common.URL url = arg2;
                        // extName 值为 javassist
                        //         String extName = url.getParameter("proxy", "javassist");
                        //         if (extName == null)
                        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
                        // 这里获取的 extension 实际类型为 org.apache.dubbo.rpc.proxy.wrapper.StubProxyFactoryWrapper
                        //         org.apache.dubbo.rpc.ProxyFactory extension = (org.apache.dubbo.rpc.ProxyFactory) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.ProxyFactory.class).getExtension(extName);
                        //         return extension.getInvoker(arg0, arg1, arg2);
                        //     }
                        //     public java.lang.Object getProxy(org.apache.dubbo.rpc.Invoker arg0, boolean arg1) throws org.apache.dubbo.rpc.RpcException {
                        //         if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
                        //         if (arg0.getUrl() == null)
                        //             throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
                        //         org.apache.dubbo.common.URL url = arg0.getUrl();
                        //         String extName = url.getParameter("proxy", "javassist");
                        //         if (extName == null)
                        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
                        //         org.apache.dubbo.rpc.ProxyFactory extension = (org.apache.dubbo.rpc.ProxyFactory) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.ProxyFactory.class).getExtension(extName);
                        //         return extension.getProxy(arg0, arg1);
                        //     }
                        //     public java.lang.Object getProxy(org.apache.dubbo.rpc.Invoker arg0) throws org.apache.dubbo.rpc.RpcException {
                        //         if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
                        //         if (arg0.getUrl() == null)
                        //             throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
                        //         org.apache.dubbo.common.URL url = arg0.getUrl();
                        //         String extName = url.getParameter("proxy", "javassist");
                        //         if (extName == null)
                        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
                        //         org.apache.dubbo.rpc.ProxyFactory extension = (org.apache.dubbo.rpc.ProxyFactory) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.ProxyFactory.class).getExtension(extName);
                        //         return extension.getProxy(arg0);
                        //     }
                        // }

                        // registryURL 将 url 当做了它的一个属性添加到其中，键为 export
                        // ref 为 DemoServiceImpl，interfaceClass 为 org.apache.dubbo.demo.DemoService
                        // 第三个参数内容
                        // registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?
                        // application=demo-provider&
                        // dubbo=2.0.2&
                        // export=dubbo%3A%2F%2F192.168.1.104%3A20880%2Forg.apache.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26application%3Ddemo-provider%26bean.name%3Dorg.apache.dubbo.demo.DemoService%26bind.ip%3D192.168.1.104%26bind.port%3D20880%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26interface%3Dorg.apache.dubbo.demo.DemoService%26methods%3DsayHello%26pid%3D4280%26register%3Dtrue%26release%3D%26side%3Dprovider%26timestamp%3D1558423288116&
                        // pid=4280&
                        // registry=zookeeper&
                        // timestamp=1558423288108
                        // getInvoker 得到的实际是一个 JavassistProxyFactory 类中的一个匿名类 AbstractProxyInvoker
                        Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(EXPORT_KEY, url.toFullString()));
                        // 对得到的 invoker 进行封装
                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                        // protocol 的实例代码，protocol 实际是 Protocol$Adaptive 实例
                        // package org.apache.dubbo.rpc;
                        // import org.apache.dubbo.common.extension.ExtensionLoader;
                        // public class Protocol$Adaptive implements org.apache.dubbo.rpc.Protocol {
                        //     public void destroy() {
                        //         throw new UnsupportedOperationException("The method public abstract void org.apache.dubbo.rpc.Protocol.destroy() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
                        //
                        //     public int getDefaultPort() {
                        //         throw new UnsupportedOperationException("The method public abstract int org.apache.dubbo.rpc.Protocol.getDefaultPort() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
                        //
                        // 这里的 arg0 实际是一个 AbstractProxyInvoker，是通过 new 的方式获得，它引用了 wrapper（动态构造）实例，保存了 DemoServiceImpl、DemoService、url 地址
                        //     public org.apache.dubbo.rpc.Exporter export(org.apache.dubbo.rpc.Invoker arg0) throws org.apache.dubbo.rpc.RpcException {
                        //         if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
                        //         if (arg0.getUrl() == null)
                        //             throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
                        //         org.apache.dubbo.common.URL url = arg0.getUrl();
                        //         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
                        //         if (extName == null)
                        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
                        // 返回的 extension 被包装为 ProtocolListenerWrapper
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
                        Exporter<?> exporter = protocol.export(wrapperInvoker);
                        // 保存 exporter 信息
                        exporters.add(exporter);
                    }
                } else {
                    // 这里处理 registryURLs 为空的情况
                    // getInvoker 得到的实际是一个 JavassistProxyFactory 类中的一个匿名类 AbstractProxyInvoker
                    Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                    Exporter<?> exporter = protocol.export(wrapperInvoker);
                    exporters.add(exporter);
                }
                /**
                 * @since 2.7.0
                 * ServiceData Store
                 */
                MetadataReportService metadataReportService = null;
                if ((metadataReportService = getMetadataReportService()) != null) {
                    metadataReportService.publishProvider(url);
                }
            }
        }
        this.urls.add(url);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /**
     * always export injvm
     */
    private void exportLocal(URL url) {
        URL local = URLBuilder.from(url)
                // injvm
                .setProtocol(LOCAL_PROTOCOL)
                // 127.0.0.1
                .setHost(LOCALHOST_VALUE)
                // 0
                .setPort(0)
                .build();
        // protocol 的实例代码，protocol 实际是 Protocol$Adaptive 实例
        // package org.apache.dubbo.rpc;
        // import org.apache.dubbo.common.extension.ExtensionLoader;
        // public class Protocol$Adaptive implements org.apache.dubbo.rpc.Protocol {
        //     public void destroy() {
        //         throw new UnsupportedOperationException("The method public abstract void org.apache.dubbo.rpc.Protocol.destroy() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
        //
        //     public int getDefaultPort() {
        //         throw new UnsupportedOperationException("The method public abstract int org.apache.dubbo.rpc.Protocol.getDefaultPort() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
        //
        // 这里的 arg0 实际是一个 AbstractProxyInvoker，是通过 new 的方式获得，它引用了 wrapper（动态构造）实例，保存了 DemoServiceImpl、DemoService、url 地址
        //     public org.apache.dubbo.rpc.Exporter export(org.apache.dubbo.rpc.Invoker arg0) throws org.apache.dubbo.rpc.RpcException {
        //         if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
        //         if (arg0.getUrl() == null)
        //             throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
        //         org.apache.dubbo.common.URL url = arg0.getUrl();
        //         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
        //         if (extName == null)
        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
        // 返回的 extension 被包装为 ProtocolListenerWrapper
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
        Exporter<?> exporter = protocol.export(
                // proxyFactory 的实例源码
                // package org.apache.dubbo.rpc;
                // import org.apache.dubbo.common.extension.ExtensionLoader;
                // public class ProxyFactory$Adaptive implements org.apache.dubbo.rpc.ProxyFactory {
                //     public org.apache.dubbo.rpc.Invoker getInvoker(java.lang.Object arg0, java.lang.Class arg1, org.apache.dubbo.common.URL arg2) throws org.apache.dubbo.rpc.RpcException {
                //         if (arg2 == null) throw new IllegalArgumentException("url == null");
                //         org.apache.dubbo.common.URL url = arg2;
                //         String extName = url.getParameter("proxy", "javassist");
                //         if (extName == null)
                //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
                //         org.apache.dubbo.rpc.ProxyFactory extension = (org.apache.dubbo.rpc.ProxyFactory) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.ProxyFactory.class).getExtension(extName);
                // 这里的 extension 实际为 StubProxyFactoryWrapper
                //         return extension.getInvoker(arg0, arg1, arg2);
                //     }
                //     public java.lang.Object getProxy(org.apache.dubbo.rpc.Invoker arg0, boolean arg1) throws org.apache.dubbo.rpc.RpcException {
                //         if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
                //         if (arg0.getUrl() == null)
                //             throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
                //         org.apache.dubbo.common.URL url = arg0.getUrl();
                //         String extName = url.getParameter("proxy", "javassist");
                //         if (extName == null)
                //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
                // 这里得到的 extension 为 ProtocolListenerWrapper 包含了 ProtocolFilterWrapper 属性，而 ProtocolFilterWrapper 才是包含了真正的 InjvmProtocol 属性
                //         org.apache.dubbo.rpc.ProxyFactory extension = (org.apache.dubbo.rpc.ProxyFactory) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.ProxyFactory.class).getExtension(extName);
                // 这里便是调用的 InjvmProtocol.getProxy 方法
                //         return extension.getProxy(arg0, arg1);
                //     }
                //     public java.lang.Object getProxy(org.apache.dubbo.rpc.Invoker arg0) throws org.apache.dubbo.rpc.RpcException {
                //         if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
                //         if (arg0.getUrl() == null)
                //             throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
                //         org.apache.dubbo.common.URL url = arg0.getUrl();
                //         String extName = url.getParameter("proxy", "javassist");
                //         if (extName == null)
                //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
                //         org.apache.dubbo.rpc.ProxyFactory extension = (org.apache.dubbo.rpc.ProxyFactory) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.ProxyFactory.class).getExtension(extName);
                //         return extension.getProxy(arg0);
                //     }
                // }
                // ref 为 interfaceClass 的实例类型，interfaceClass 为要发布的服务接口，local 为本地 url
                // getInvoker 得到的实际是一个 JavassistProxyFactory 类中的一个匿名类 AbstractProxyInvoker
                proxyFactory.getInvoker(ref, (Class) interfaceClass, local));
        // 保存 exporter 信息
        exporters.add(exporter);
        logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry url : " + local);
    }

    private Optional<String> getContextPath(ProtocolConfig protocolConfig) {
        String contextPath = protocolConfig.getContextpath();
        if (StringUtils.isEmpty(contextPath) && provider != null) {
            contextPath = provider.getContextpath();
        }
        return Optional.ofNullable(contextPath);
    }

    protected Class getServiceClass(T ref) {
        return ref.getClass();
    }

    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    private String findConfigedHosts(ProtocolConfig protocolConfig, List<URL> registryURLs, Map<String, String> map) {
        boolean anyhost = false;

        // 尝试从环境变量和系统属性中获取 DUBBO_IP_TO_BIND 信息
        String hostToBind = getValueFromConfig(protocolConfig, DUBBO_IP_TO_BIND);
        // 验证获取的 port 信息
        if (hostToBind != null && hostToBind.length() > 0 && isInvalidLocalHost(hostToBind)) {
            throw new IllegalArgumentException("Specified invalid bind ip from property:" + DUBBO_IP_TO_BIND + ", value:" + hostToBind);
        }

        // if bind ip is not found in environment, keep looking up
        if (StringUtils.isEmpty(hostToBind)) {
            hostToBind = protocolConfig.getHost();
            // protocol 中没有找到，试着从 provider 中获取
            if (provider != null && StringUtils.isEmpty(hostToBind)) {
                hostToBind = provider.getHost();
            }
            if (isInvalidLocalHost(hostToBind)) {
                anyhost = true;
                try {
                    // 获取到本机 IP 地址
                    hostToBind = InetAddress.getLocalHost().getHostAddress();
                } catch (UnknownHostException e) {
                    logger.warn(e.getMessage(), e);
                }
                if (isInvalidLocalHost(hostToBind)) {
                    if (CollectionUtils.isNotEmpty(registryURLs)) {
                        // 还是没有获取到有效的 hostToBind，那就从 registryURLs 中尝试获取
                        for (URL registryURL : registryURLs) {
                            // url 中，参数 registry 为 multicast 的，应该被忽略
                            if (MULTICAST.equalsIgnoreCase(registryURL.getParameter("registry"))) {
                                // skip multicast registry since we cannot connect to it via Socket
                                continue;
                            }
                            // 带资源的 try 代码块
                            try (Socket socket = new Socket()) {
                                // 通过连接 url 指定的 ip + port，从 socket 中得到本地 ip
                                SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                                socket.connect(addr, 1000);
                                hostToBind = socket.getLocalAddress().getHostAddress();
                                break;
                            } catch (Exception e) {
                                logger.warn(e.getMessage(), e);
                            }
                        }
                    }
                    if (isInvalidLocalHost(hostToBind)) {
                        // 通过 url 获取 hostToBind 也失败的话，那就直接使用 127.0.0.1 或者 当前 ip
                        hostToBind = getLocalHost();
                    }
                }
            }
        }

        // bind.ip -> hostToBind
        map.put(RemotingConstants.BIND_IP_KEY, hostToBind);

        // registry ip is not used for bind ip by default
        // 尝试从环境变量和系统属性中获取 DUBBO_IP_TO_REGISTRY 信息
        String hostToRegistry = getValueFromConfig(protocolConfig, DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry != null && hostToRegistry.length() > 0 && isInvalidLocalHost(hostToRegistry)) {
            // 验证 DUBBO_IP_TO_REGISTRY 的有效性高
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        } else if (StringUtils.isEmpty(hostToRegistry)) {
            // bind ip is used as registry ip by default
            // 默认使用 hostToBind 作为 hostToRegistry
            hostToRegistry = hostToBind;
        }

        // anyhost -> true/false
        // 只有在没能从环境变量和系统属性中获取 DUBBO_IP_TO_BIND 信息，才会使得 anyhost 变为 true
        map.put(ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }

    /**
     * 针对 default、bind、registry 三种类型的端口进行处理，返回 registryPort
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *
     * @param protocolConfig
     * @param name
     * @return
     */
    private Integer findConfigedPorts(ProtocolConfig protocolConfig, String name, Map<String, String> map) {
        Integer portToBind = null;

        // parse bind port from environment
        // 尝试从环境变量和系统属性中获取 DUBBO_PORT_TO_BIND 信息
        String port = getValueFromConfig(protocolConfig, DUBBO_PORT_TO_BIND);
        // String port to Integer port
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        if (portToBind == null) {
            // 直接从 protocolConfig 获取端口信息
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                // protocolConfig 中没有获取到 port，尝试从 provider 中获取
                portToBind = provider.getPort();
            }
            // 这里获取的 Extension 其实是一个 ProtocolListenerWrapper，getDefaultPort 方法实际是获取的 wrapper 类属性 protocol 的默认端口
            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                // 如果绑定端口为空或者 0，将获取的默认端口赋值给绑定端口
                portToBind = defaultPort;
            }
            if (portToBind == null || portToBind <= 0) {
                // 根据 name 从 RANDOM_PORT_MAP 获取随机端口，没有获取到便是 Integer.MIN_VALUE
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    // 根据 defaultPort 获取绑定端口
                    portToBind = getAvailablePort(defaultPort);
                    // 将 name -> portToBind 保存到 RANDOM_PORT_MAP
                    putRandomPort(name, portToBind);
                }
            }
        }

        // save bind port, used as url's key later
        map.put(RemotingConstants.BIND_PORT_KEY, String.valueOf(portToBind));

        // registry port, not used as bind port by default
        // // 分别从系统环境 evn 和系统属性 property 中获取 key 相关的系统属性
        String portToRegistryStr = getValueFromConfig(protocolConfig, DUBBO_PORT_TO_REGISTRY);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            // 获取的 registry 端口为空，就使用绑定端口作为 registry 端口
            portToRegistry = portToBind;
        }

        return portToRegistry;
    }

    private Integer parsePort(String configPort) {
        Integer port = null;
        if (configPort != null && configPort.length() > 0) {
            try {
                Integer intPort = Integer.parseInt(configPort);
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    // 分别从系统环境 evn 和系统属性 property 中获取 key 相关的系统属性
    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        // protocolPrefix : DUBBO_
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        // DUBBO_DUBBO_IP_TO_BIND
        String port = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (StringUtils.isEmpty(port)) {
            // DUBBO_IP_TO_BIND
            port = ConfigUtils.getSystemProperty(key);
        }
        return port;
    }

    // 完成复合属性的填充，即根据 provider、module 和 application 填充 ServiceBean 跟 ConfigManager 的属性
    private void completeCompoundConfigs() {
        // 根据 provider 的属性填充 ServiceBean 跟 ConfigManager 的属性
        if (provider != null) {
            if (application == null) {
                // 将 application 填充到 ConfigManager 和 ServiceBean 中各一份
                setApplication(provider.getApplication());
            }
            if (module == null) {
                setModule(provider.getModule());
            }
            if (registries == null) {
                setRegistries(provider.getRegistries());
            }
            if (monitor == null) {
                setMonitor(provider.getMonitor());
            }
            if (protocols == null) {
                setProtocols(provider.getProtocols());
            }
            if (configCenter == null) {
                setConfigCenter(provider.getConfigCenter());
            }
        }
        // 根据 module 的属性填充 ServiceBean 跟 ConfigManager 的属性
        if (module != null) {
            if (registries == null) {
                setRegistries(module.getRegistries());
            }
            if (monitor == null) {
                setMonitor(module.getMonitor());
            }
        }
        // 根据 application 的属性填充 ServiceBean 跟 ConfigManager 的属性
        if (application != null) {
            if (registries == null) {
                setRegistries(application.getRegistries());
            }
            if (monitor == null) {
                setMonitor(application.getMonitor());
            }
        }
    }

    // 检查取默认的 provider，没有的话，则新建一个
    private void checkDefault() {
        createProviderIfAbsent();
    }

    private void createProviderIfAbsent() {
        // 如果 ServiceBean 没有 provider 属性，也就是说没有配置 provider 标签
        if (provider != null) {
            return;
        }
        // 分别保存 ProviderConfig 到 ConfigManager 和 ServiceConfig 中去
        // 先从 ServiceBean 的 providers 属性中获取 default Provider，如果没有则新建一个，同时刷新属性值
        setProvider(
                ConfigManager.getInstance()
                        .getDefaultProvider()
                        .orElseGet(() -> {
                            // 没能获取到默认的 Provider，就直接新建一个，同时刷新
                            ProviderConfig providerConfig = new ProviderConfig();
                            providerConfig.refresh();
                            return providerConfig;
                        })
        );
    }

    // 检查 ServiceBean 是否存在 protocols 属性，没有的话要尝试进行填充
    private void checkProtocol() {
        // 如果 protocols 属性为空，但是 provider 属性不为空
        if (CollectionUtils.isEmpty(protocols) && provider != null) {
            // 则从 provider 中获取 protocols 填充到 ServiceBean 当中
            setProtocols(provider.getProtocols());
        }
        convertProtocolIdsToProtocols();
    }

    /**
     * 将 protocolIds 转换为 protocols 并添加到 ServiceBean 中去
     * 这里的 protocolIds 可能是通过标签进行了设置，也可能是通过环境配置进行设置
     */
    private void convertProtocolIdsToProtocols() {
        if (StringUtils.isEmpty(protocolIds) && CollectionUtils.isEmpty(protocols)) {
            // protocolIds 和 protocols 都为空，那么就分别从 ExternalConfiguration 和 AppExternalConfiguration 配置中获取 dubbo.protocols. 属性
            // 然后添加到 configedProtocols 中去
            List<String> configedProtocols = new ArrayList<>();
            configedProtocols.addAll(getSubProperties(Environment.getInstance()
                    .getExternalConfigurationMap(), PROTOCOLS_SUFFIX));
            configedProtocols.addAll(getSubProperties(Environment.getInstance()
                    .getAppExternalConfigurationMap(), PROTOCOLS_SUFFIX));

            // 构建 protocolIds 属性
            protocolIds = String.join(",", configedProtocols);
        }

        if (StringUtils.isEmpty(protocolIds)) {
            // 执行到这里，说明 protocols 不为空，或者没有从环境配置中获取到 dubbo.protocols. 属性，但是通常都是由于没能从环境配置中获取到 dubbo.protocols 属性
            if (CollectionUtils.isEmpty(protocols)) {
                // 这里设置属性，先是从 ConfigManager 中进行获取，如果没有获取到，就新建一个，同时进行属性的刷新
                setProtocols(
                        ConfigManager.getInstance().getDefaultProtocols()
                                .filter(CollectionUtils::isNotEmpty)
                                .orElseGet(() -> {
                                    // 执行到这里，就说明没有能够从 ConfigManager 获取到，直接新建一个
                                    ProtocolConfig protocolConfig = new ProtocolConfig();
                                    protocolConfig.refresh();
                                    return new ArrayList<>(Arrays.asList(protocolConfig));
                                })
                );
            }
        } else {
            // 执行到这里说明 protocolIds 不为空，可能是本来就不为空，也可能是从环境配置中获取到 dubbo.protocols 属性，然后生成 protocolsIds 属性
            String[] arr = COMMA_SPLIT_PATTERN.split(protocolIds);
            List<ProtocolConfig> tmpProtocols = CollectionUtils.isNotEmpty(protocols) ? protocols : new ArrayList<>();
            // 留意这个流式处理
            Arrays.stream(arr).forEach(id -> {
                // 对每一个切割出来的 protocolId 进行处理
                // 找出 tmpProtocols 中不存在的 protocol
                if (tmpProtocols.stream().noneMatch(prot -> prot.getId().equals(id))) {
                    // 如果不存在当前，就添加进去
                    tmpProtocols.add(ConfigManager.getInstance().getProtocol(id).orElseGet(() -> {
                        // 尝试的是从 ConfigManager 进行获取，如果没有获取到，那么就直接新建，同时进行刷新属性
                        ProtocolConfig protocolConfig = new ProtocolConfig();
                        protocolConfig.setId(id);
                        protocolConfig.refresh();
                        return protocolConfig;
                    }));
                }
            });
            if (tmpProtocols.size() > arr.length) {
                throw new IllegalStateException("Too much protocols found, the protocols comply to this service are :" + protocolIds + " but got " + protocols
                        .size() + " registries!");
            }
            // 将得到的 protocols 添加到 ServiceBean 中去
            setProtocols(tmpProtocols);
        }
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (ref instanceof GenericService) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (StringUtils.isEmpty(id)) {
            id = interfaceName;
        }
    }

    public T getRef() {
        return ref;
    }

    public void setRef(T ref) {
        this.ref = ref;
    }

    @Parameter(excluded = true)
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        checkPathName(PATH_KEY, path);
        this.path = path;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    // ======== Deprecated ========

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ProviderConfig getProvider() {
        return provider;
    }

    // 分别保存 ProviderConfig 到 ConfigManager 和 ServiceConfig 中去
    public void setProvider(ProviderConfig provider) {
        // 单例模式获取 ConfigManager 实例，并且保存 ProviderConfig
        ConfigManager.getInstance().addProvider(provider);
        // ServiceConfig 中也保存一份
        this.provider = provider;
    }

    @Parameter(excluded = true)
    public String getProviderIds() {
        return providerIds;
    }

    public void setProviderIds(String providerIds) {
        this.providerIds = providerIds;
    }

    public String getGeneric() {
        return generic;
    }

    public void setGeneric(String generic) {
        if (StringUtils.isEmpty(generic)) {
            return;
        }
        if (ProtocolUtils.isGeneric(generic)) {
            this.generic = generic;
        } else {
            throw new IllegalArgumentException("Unsupported generic type " + generic);
        }
    }

    @Override
    public void setMock(Boolean mock) {
        throw new IllegalArgumentException("mock doesn't support on provider side");
    }

    @Override
    public void setMock(String mock) {
        throw new IllegalArgumentException("mock doesn't support on provider side");
    }

    public List<URL> getExportedUrls() {
        return urls;
    }

    /**
     * @deprecated Replace to getProtocols()
     */
    @Deprecated
    public List<ProviderConfig> getProviders() {
        return convertProtocolToProvider(protocols);
    }

    /**
     * @deprecated Replace to setProtocols()
     */
    @Deprecated
    public void setProviders(List<ProviderConfig> providers) {
        this.protocols = convertProviderToProtocol(providers);
    }

    @Override
    @Parameter(excluded = true)
    public String getPrefix() {
        return DUBBO + ".service." + interfaceName;
    }
}
