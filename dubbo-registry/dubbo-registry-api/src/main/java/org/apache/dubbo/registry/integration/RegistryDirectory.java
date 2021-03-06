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
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.RouterChain;
import org.apache.dubbo.rpc.cluster.RouterFactory;
import org.apache.dubbo.rpc.cluster.directory.AbstractDirectory;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.ClusterConstants.ROUTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DISABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.DUBBO_PROTOCOL;
import static org.apache.dubbo.common.constants.ConfigConstants.REFER_KEY;
import static org.apache.dubbo.common.constants.MonitorConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.APP_DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.COMPATIBLE_CONFIG_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_SUFFIX;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTE_PROTOCOL;


/**
 * RegistryDirectory
 */
public class RegistryDirectory<T> extends AbstractDirectory<T> implements NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(RegistryDirectory.class);
    // package org.apache.dubbo.rpc.cluster;
    // import org.apache.dubbo.common.extension.ExtensionLoader;
    //
    // public class Cluster$Adaptive implements org.apache.dubbo.rpc.cluster.Cluster {
    //     public org.apache.dubbo.rpc.Invoker join(org.apache.dubbo.rpc.cluster.Directory arg0) throws org.apache.dubbo.rpc.RpcException {
    //         if (arg0 == null)
    //             throw new IllegalArgumentException("org.apache.dubbo.rpc.cluster.Directory argument == null");
    //         if (arg0.getUrl() == null)
    //             throw new IllegalArgumentException("org.apache.dubbo.rpc.cluster.Directory argument getUrl() == null");
    //         org.apache.dubbo.common.URL url = arg0.getUrl();
    //         String extName = url.getParameter("cluster", "failover");
    //         if (extName == null)
    //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.cluster.Cluster) name from url (" + url.toString() + ") use keys([cluster])");
    //         org.apache.dubbo.rpc.cluster.Cluster extension = (org.apache.dubbo.rpc.cluster.Cluster) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.cluster.Cluster.class).getExtension(extName);
    //         return extension.join(arg0);
    //     }
    // }
    private static final Cluster CLUSTER = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    // package org.apache.dubbo.rpc.cluster;
    // import org.apache.dubbo.common.extension.ExtensionLoader;
    // public class RouterFactory$Adaptive implements org.apache.dubbo.rpc.cluster.RouterFactory {
    //     public org.apache.dubbo.rpc.cluster.Router getRouter(org.apache.dubbo.common.URL arg0)  {
    //         if (arg0 == null) throw new IllegalArgumentException("url == null");
    //         org.apache.dubbo.common.URL url = arg0;
    //         String extName = url.getProtocol();
    //         if(extName == null) throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.cluster.RouterFactory) name from url (" + url.toString() + ") use keys([protocol])");
    //         org.apache.dubbo.rpc.cluster.RouterFactory extension = (org.apache.dubbo.rpc.cluster.RouterFactory)ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.cluster.RouterFactory.class).getExtension(extName);
    //         return extension.getRouter(arg0);
    //     }
    // }
    private static final RouterFactory ROUTER_FACTORY = ExtensionLoader.getExtensionLoader(RouterFactory.class)
            .getAdaptiveExtension();

    private final String serviceKey; // Initialization at construction time, assertion not null
    private final Class<T> serviceType; // Initialization at construction time, assertion not null
    private final Map<String, String> queryMap; // Initialization at construction time, assertion not null
    private final URL directoryUrl; // Initialization at construction time, assertion not null, and always assign non null value
    private final boolean multiGroup;
    private Protocol protocol; // Initialization at the time of injection, the assertion is not null
    private Registry registry; // Initialization at the time of injection, the assertion is not null
    private volatile boolean forbidden = false;
    // zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?
    // anyhost=true&
    // application=demo-consumer&
    // bean.name=org.apache.dubbo.demo.DemoService&
    // check=false&
    // deprecated=false&
    // dubbo=2.0.2&
    // dynamic=true&
    // generic=false&
    // interface=org.apache.dubbo.demo.DemoService&
    // lazy=false&
    // methods=sayHello&
    // pid=2213&
    // register=true&
    // register.ip=192.168.1.104&
    // remote.application=demo-provider&
    // side=consumer&
    // sticky=false&
    // timestamp=1558957440824
    private volatile URL overrideDirectoryUrl; // Initialization at construction time, assertion not null, and always assign non null value

    private volatile URL registeredConsumerUrl;

    /**
     * override rules
     * Priority: override>-D>consumer>provider
     * Rule one: for a certain provider <ip:port,timeout=100>
     * Rule two: for all providers <* ,timeout=5000>
     */
    private volatile List<Configurator> configurators; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    // Map<url, Invoker> cache service url to invoker mapping.
    private volatile Map<String, Invoker<T>> urlInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference
    private volatile List<Invoker<T>> invokers;

    // Set<invokerUrls> cache invokeUrls to invokers mapping.
    private volatile Set<URL> cachedInvokerUrls; // The initial value is null and the midway may be assigned to null, please use the local variable reference
    // 构建 ConsumerConfigurationListener，同时为提前构建的 DynamicConfiguration 添加一个监听器（自身），获取指定路径下的配置信息进行处理
    private static final ConsumerConfigurationListener CONSUMER_CONFIGURATION_LISTENER = new ConsumerConfigurationListener();
    private ReferenceConfigurationListener serviceConfigurationListener;

    // url 带 refer 参数，zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-consumer&dubbo=2.0.2&pid=83276&refer=application%3Ddemo-consumer%26check%3Dfalse%26dubbo%3D2.0.2%26interface%3Dorg.apache.dubbo.demo.DemoService%26lazy%3Dfalse%26methods%3DsayHello%26pid%3D83276%26register.ip%3D192.168.1.101%26side%3Dconsumer%26sticky%3Dfalse%26timestamp%3D1559352722835&timestamp=1559352730532
    public RegistryDirectory(Class<T> serviceType, URL url) {
        super(url); // 保存了 url，consumerUrl 和 routerChain 信息，对于 url 如果是 registry 协议，有特殊处理方式
        if (serviceType == null) {
            throw new IllegalArgumentException("service type is null.");
        }
        if (url.getServiceKey() == null || url.getServiceKey().length() == 0) {
            throw new IllegalArgumentException("registry serviceKey is null.");
        }
        this.serviceType = serviceType; // org.apache.dubbo.demo.DemoService
        this.serviceKey = url.getServiceKey();  // org.apache.dubbo.registry.RegistryService
        this.queryMap = StringUtils.parseQueryString(url.getParameterAndDecoded(REFER_KEY));    // 将 refer 属性值映射为 map
        this.overrideDirectoryUrl = this.directoryUrl = turnRegistryUrlToConsumerUrl(url);  // 就是将 registryUrl 路径修改为 interface 参数，清除全部参数，只要 refer 对应的参数，移除 monitor 属性
        String group = directoryUrl.getParameter(GROUP_KEY, "");
        this.multiGroup = group != null && (ANY_VALUE.equals(group) || group.contains(","));    // group 为 * 或包含 ，
    }

    // 入参 url 为 registryUrl
    private URL turnRegistryUrlToConsumerUrl(URL url) {
        // save any parameter in registry that will be useful to the new url.
        String isDefault = url.getParameter(DEFAULT_KEY);
        if (StringUtils.isNotEmpty(isDefault)) {     // 如果 url 的 default 属性值不为空
            queryMap.put(REGISTRY_KEY + "." + DEFAULT_KEY, isDefault);  // registry.default -> default 属性值
        }
        return URLBuilder.from(url) // 就是将 registryUrl 路径修改为 interface 参数，清除全部参数，只要 refer 对应的参数，移除 monitor 属性
                .setPath(url.getServiceInterface())
                .clearParameters()
                .addParameters(queryMap)
                .removeParameter(MONITOR_KEY)
                .build();
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }
    // 保存 url，将自身添加到 CONSUMER_CONFIGURATION_LISTENER 中，核心是 registry.subscribe 方法，它会即根据 url 获取到对应的 category 路径，然后将 listener 添加到对
    // 应的路径下，得到不同路径的 provider url，再根据此 urls 构建相应的 invoker，保存到字段中
    public void subscribe(URL url) {
        setConsumerUrl(url);    // 此 url 添加了 category -> providers,configurators,routers 属性
        CONSUMER_CONFIGURATION_LISTENER.addNotifyListener(this);    // 以 listener 身份添加 RegistryDirectory 到 CONSUMER_CONFIGURATION_LISTENER
        serviceConfigurationListener = new ReferenceConfigurationListener(this, url);   // 将 RegistryDirectory 包装为 ReferenceConfigurationListener，填充到属性中
        registry.subscribe(url, this);   // 构建 ReferenceConfigurationListener，同时为提前构建的 DynamicConfiguration 添加一个监听器（自身），同时获取指定路径下的配置信息进行处理
    }


    @Override
    public void destroy() {
        if (isDestroyed()) {
            return;
        }

        // unregister.
        try {
            if (getRegisteredConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unregister(getRegisteredConsumerUrl());
            }
        } catch (Throwable t) {
            logger.warn("unexpected error when unregister service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        // unsubscribe.
        try {
            if (getConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unsubscribe(getConsumerUrl(), this);
            }
            DynamicConfiguration.getDynamicConfiguration()
                    .removeListener(ApplicationModel.getApplication(), CONSUMER_CONFIGURATION_LISTENER);
        } catch (Throwable t) {
            logger.warn("unexpected error when unsubscribe service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        super.destroy(); // must be executed after unsubscribing
        try {
            destroyAllInvokers();
        } catch (Throwable t) {
            logger.warn("Failed to destroy service " + serviceKey, t);
        }
    }

    @Override   // 此方法就是对 urls 进行分类，将不同类的 url 转换成为对应的配置类，添加到字段中
    public synchronized void notify(List<URL> urls) {
        Map<String, List<URL>> categoryUrls = urls.stream() // 这里是分组的结果，如 routers -> url 的 list
                .filter(Objects::nonNull)   // 如果 url 不为空
                .filter(this::isValidCategory)  // 验证 category 是否合法
                .filter(this::isNotCompatibleFor26x)
                .collect(Collectors.groupingBy(url -> { // 对 urls 按照 category 进行分组
                    if (UrlUtils.isConfigurator(url)) {
                        return CONFIGURATORS_CATEGORY;
                    } else if (UrlUtils.isRoute(url)) {
                        return ROUTERS_CATEGORY;
                    } else if (UrlUtils.isProvider(url)) {
                        return PROVIDERS_CATEGORY;
                    }
                    return "";
                }));
        // 尝试获取 configurators 分组的 urls，如果没有的话就是一个空集合。然后将 urls 逐个转换为 configuration 添加到 configurators 中
        List<URL> configuratorURLs = categoryUrls.getOrDefault(CONFIGURATORS_CATEGORY, Collections.emptyList());
        this.configurators = Configurator.toConfigurators(configuratorURLs).orElse(this.configurators);
        // 尝试获取 routers 分组的 urls，默认返回空集合，然后将 url 根据 router 参数修改协议，再将其转换为 router 添加到 routers 中，用 Optional 包装返回
        List<URL> routerURLs = categoryUrls.getOrDefault(ROUTERS_CATEGORY, Collections.emptyList());
        toRouters(routerURLs).ifPresent(this::addRouters);  // 将获得的 routers 添加到当前实例的 routerChain 属性的 routers 属性中

        // 尝试获取 providers 分组的 urls，默认返回空集合，然后将 url 根据 router 参数修改协议，再将其转换为 router 添加到 routers 中，用 Optional 包装返回
        List<URL> providerURLs = categoryUrls.getOrDefault(PROVIDERS_CATEGORY, Collections.emptyList());
        refreshOverrideAndInvoker(providerURLs);    // 就是在这个方法中，根据 provider url 获取了 invoker 信息
    }   // 通过各个配置项的 Configurator 来对 overrideDirectoryUrl 进行配置，根据 invokerUrls 参数和 cachedInvokerUrls 得到对应的 newUrlInvokerMap，
        // 根据 oldUrlInvokerMap, newUrlInvokerMap 确定相应的待移除项目，然后执行待移除项的 destroy 方法
    private void refreshOverrideAndInvoker(List<URL> urls) {
        // mock zookeeper://xxx?mock=return null
        overrideDirectoryUrl();  // 这个方法即通过各个配置项的 Configurator 来对 overrideDirectoryUrl 进行配置
        // 如果 invokerUrls 大小为 1 且协议为 empty，进行一些禁止访问的属性的设置
        // 如果 invokerUrls 为空，尝试获取 cachedInvokerUrls，还是为空直接返回
        // 其他情况，将通过 invokerUrls 获取 urls 和 invokers 关系的 map，完成分组，并进行一些允许访问相关的属性设置
        refreshInvoker(urls);   // 根据 invokerUrls 参数和 cachedInvokerUrls 得到对应的 newUrlInvokerMap，根据 oldUrlInvokerMap, newUrlInvokerMap
    }                           // 确定相应的待移除项目，然后执行待移除项的 destroy 方法

    /**
     * Convert the invokerURL list to the Invoker Map. The rules of the conversion are as follows:
     * <ol>
     * <li> If URL has been converted to invoker, it is no longer re-referenced and obtained directly from the cache,
     * and notice that any parameter changes in the URL will be re-referenced.</li>
     * <li>If the incoming invoker list is not empty, it means that it is the latest invoker list.</li>
     * <li>If the list of incoming invokerUrl is empty, It means that the rule is only a override rule or a route
     * rule, which needs to be re-contrasted to decide whether to re-reference.</li>
     * </ol>
     *
     * @param invokerUrls this parameter can't be null
     */
    // TODO: 2017/8/31 FIXME The thread pool should be used to refresh the address, otherwise the task may be accumulated.
    // 如果 invokerUrls 大小为 1 且协议为 empty，进行一些禁止访问的属性的设置
    // 如果 invokerUrls 为空，尝试获取 cachedInvokerUrls，还是为空直接返回
    // 其他情况，将通过 invokerUrls 获取 urls 和 invokers 关系的 map，完成分组，并进行一些允许访问相关的属性设置
    private void refreshInvoker(List<URL> invokerUrls) {    // 根据 invokerUrls 参数和 cachedInvokerUrls 得到对应的 newUrlInvokerMap，根据 oldUrlInvokerMap, newUrlInvokerMap
        Assert.notNull(invokerUrls, "invokerUrls should not be null");  // 确定相应的待移除项目，然后执行待移除项的 destroy 方法

        if (invokerUrls.size() == 1
                && invokerUrls.get(0) != null
                && EMPTY_PROTOCOL.equals(invokerUrls.get(0).getProtocol())) {   // 如果唯一的 url 的协议是 empty
            this.forbidden = true; // Forbid to access  修改状态
            this.invokers = Collections.emptyList();    // 置空 invokers
            routerChain.setInvokers(this.invokers); // 修改 routerChain 的 invokers 属性
            destroyAllInvokers(); // Close all invokers
        } else {
            this.forbidden = false; // Allow to access 修改状态
            Map<String, Invoker<T>> oldUrlInvokerMap = this.urlInvokerMap; // local reference，此 map 保存着 url 这 invoker 的关系
            if (invokerUrls == Collections.<URL>emptyList()) {
                invokerUrls = new ArrayList<>();
            }
            if (invokerUrls.isEmpty() && this.cachedInvokerUrls != null) {
                invokerUrls.addAll(this.cachedInvokerUrls); // 添加别人
            } else {
                this.cachedInvokerUrls = new HashSet<>();   // 或者被添加，cachedInvokerUrls 用于缓存 invokerUrls
                this.cachedInvokerUrls.addAll(invokerUrls);//Cached invoker urls, convenient for comparison
            }
            if (invokerUrls.isEmpty()) {
                return;
            }    // 此方法用于获取 url 和 invoker 对应关系的 map，在那之前需要先进行 url 协议的验证，也就是说 provider 的协议需要是使用的请求者指定的协议类型，另外 provider url 的使能参数要正确
            Map<String, Invoker<T>> newUrlInvokerMap = toInvokers(invokerUrls);// Translate url list to Invoker map

            /**
             * If the calculation is wrong, it is not processed.
             *
             * 1. The protocol configured by the client is inconsistent with the protocol of the server.
             *    eg: consumer protocol = dubbo, provider only has other protocol services(rest).
             * 2. The registration center is not robust and pushes illegal specification data.
             *
             */
            if (CollectionUtils.isEmptyMap(newUrlInvokerMap)) {
                logger.error(new IllegalStateException("urls to invokers error .invokerUrls.size :" + invokerUrls.size() + ", invoker.size :0. urls :" + invokerUrls
                        .toString()));
                return;
            }

            List<Invoker<T>> newInvokers = Collections.unmodifiableList(new ArrayList<>(newUrlInvokerMap.values()));
            // pre-route and build cache, notice that route cache should build on original Invoker list.
            // toMergeMethodInvokerMap() will wrap some invokers having different groups, those wrapped invokers not should be routed.
            routerChain.setInvokers(newInvokers);   // 将通过 urls 获取的 invoker 添加到 routerChain 中，也就是说 routerChain 还持有了根据 urls 获取的 invoker 链
            // 尝试对 invokers 进行分组，然后对每组的 invoker 包装成为一个 invoker 后，返回，如果只有一个分组，那么就原样返回
            this.invokers = multiGroup ? toMergeInvokerList(newInvokers) : newInvokers; // RegistryDirectory 直接持有 invokers 信息
            this.urlInvokerMap = newUrlInvokerMap;  // 将 url 和 invoker 的映射关系添加到 urlInvokerMap 属性中

            try {   // 根据 oldUrlInvokerMap, newUrlInvokerMap 确定待移除的项，执行它的 destroy 方法
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap); // Close the unused Invoker
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }
        }
    }

    // 尝试对 invokers 进行分组，然后对每组的 invoker 包装成为一个 invoker 后，返回，如果只有一个分组，那么就原样返回
    private List<Invoker<T>> toMergeInvokerList(List<Invoker<T>> invokers) {
        List<Invoker<T>> mergedInvokers = new ArrayList<>();
        Map<String, List<Invoker<T>>> groupMap = new HashMap<>();
        for (Invoker<T> invoker : invokers) {   // 将 invokers 进行分组
            String group = invoker.getUrl().getParameter(GROUP_KEY, "");
            groupMap.computeIfAbsent(group, k -> new ArrayList<>());
            groupMap.get(group).add(invoker);
        }

        if (groupMap.size() == 1) { // 只有一个组的话，那么就直接用链表的方式包装
            mergedInvokers.addAll(groupMap.values().iterator().next());
        } else if (groupMap.size() > 1) {   // 如果是多个分组
            for (List<Invoker<T>> groupList : groupMap.values()) {
                StaticDirectory<T> staticDirectory = new StaticDirectory<>(groupList);  // 将分组内的 invoker 包装为 StaticDirectory
                staticDirectory.buildRouterChain(); // 再将 StaticDirectory 通过 CLUSTER 处理后添加到 mergedInvokers 中，也就是一组作为一项
                mergedInvokers.add(CLUSTER.join(staticDirectory));
            }
        } else {
            mergedInvokers = invokers; // 没有分组的话，俺就原样返回
        }
        return mergedInvokers;
    }

    /**
     * @param urls
     * @return null : no routers ,do nothing
     * else :routers list
     */
    private Optional<List<Router>> toRouters(List<URL> urls) {
        if (urls == null || urls.isEmpty()) {   // 获取 urls 中协议不为空的 url，修改协议为 router 参数值，通过 url 获取对应的 router，添加到 routers 中返回
            return Optional.empty();
        }

        List<Router> routers = new ArrayList<>();
        for (URL url : urls) {
            if (EMPTY_PROTOCOL.equals(url.getProtocol())) { // 忽略那些协议为 empty 的 url
                continue;
            }
            String routerType = url.getParameter(ROUTER_KEY);   // 获取 url 的 router
            if (routerType != null && routerType.length() > 0) {
                url = url.setProtocol(routerType);  // 不为空的话，就修改 url 的协议为 router 参数 routerType 对应的值
            }
            try {   // 通过 ROUTER_FACTORY 将 url 转换为 Router
                Router router = ROUTER_FACTORY.getRouter(url);
                if (!routers.contains(router)) {    // 如果此 router 不存在，就添加到 routers 中
                    routers.add(router);
                }
            } catch (Throwable t) {
                logger.error("convert router url to router error, url: " + url, t);
            }
        }

        return Optional.of(routers);    // 将 routers 转换为 Optional 后返回
    }

    /**
     * Turn urls into invokers, and if url has been refer, will not re-reference.
     *
     * @param urls
     * @return invokers
     */ // 此方法用于获取 url 和 invoker 对应关系的 map，在那之前需要先进行 url 协议的验证，也就是说 provider 的协议需要是使用的请求者指定的协议类型，另外 provider url 的使能参数要正确
    private Map<String, Invoker<T>> toInvokers(List<URL> urls) {
        Map<String, Invoker<T>> newUrlInvokerMap = new HashMap<>();
        if (urls == null || urls.isEmpty()) {
            return newUrlInvokerMap;
        }
        Set<String> keys = new HashSet<>();
        String queryProtocols = this.queryMap.get(PROTOCOL_KEY);
        for (URL providerUrl : urls) {
            // If protocol is configured at the reference side, only the matching protocol is selected
            if (queryProtocols != null && queryProtocols.length() > 0) {    // 如果 consumer 指定的 protocol 不为空，那么从 provider url 中找到一个
                boolean accept = false;                                     // 使用 consumer 指定协议的 url
                String[] acceptProtocols = queryProtocols.split(",");   // 得到支持的协议数组
                for (String acceptProtocol : acceptProtocols) {
                    if (providerUrl.getProtocol().equals(acceptProtocol)) { // 如果当前 url 支持协议数组之一
                        accept = true;  // 修改 accept 状态，表示 url 支持当前协议集
                        break;
                    }
                }
                if (!accept) {
                    continue;
                }
            }
            if (EMPTY_PROTOCOL.equals(providerUrl.getProtocol())) {
                continue;   // empty 协议要跳过
            }   // 此协议对应的 Protocol SPI 接口实现类要存在
            if (!ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(providerUrl.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + providerUrl.getProtocol() +
                        " in notified url: " + providerUrl + " from registry " + getUrl().getAddress() +
                        " to consumer " + NetUtils.getLocalHost() + ", supported protocol: " +
                        ExtensionLoader.getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }   // 通过各种 configurators 对 providerUrl 进行配置，添加 queryMap 的参数，然后检查并制定 path 信息
            URL url = mergeUrl(providerUrl);

            String key = url.toFullString(); // The parameter urls are sorted
            if (keys.contains(key)) { // Repeated url
                continue;
            }
            keys.add(key);  // 重复记录，避免重复添加
            // Cache key is url that does not merge with consumer side parameters, regardless of how the consumer combines parameters, if the server url changes, then refer again
            Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
            Invoker<T> invoker = localUrlInvokerMap == null ? null : localUrlInvokerMap.get(key);
            if (invoker == null) { // Not in the cache, refer again
                try {
                    boolean enabled = true; // 根据 url 确定 enable 信息
                    if (url.hasParameter(DISABLED_KEY)) {   // url 有 disabled 参数
                        enabled = !url.getParameter(DISABLED_KEY, false);
                    } else {
                        enabled = url.getParameter(ENABLED_KEY, true);
                    }
                    if (enabled) {  // 如果 url 对应的 invoker 不存在，那么就直接根据 protocol 等信息新建一个，这是 invoker 构建的核心代码
                        invoker = new InvokerDelegate<>(protocol.refer(serviceType, url), url, providerUrl);
                    }   // RegistryDirectory$InvokerDelegate -> ListenerInvokerWrapper -> ProtocolFilterWrapper$1（过滤器 Invoker 链）-> （Invoker 链的最后一个）DubboInvoker
                } catch (Throwable t) { //
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + url + ")" + t.getMessage(), t);
                }
                if (invoker != null) { // Put new invoker in cache
                    newUrlInvokerMap.put(key, invoker); // 缓存 key 和 invoker 的关系
                }
            } else {
                newUrlInvokerMap.put(key, invoker);
            }
        }
        keys.clear();
        return newUrlInvokerMap;
    }

    /**
     * Merge url parameters. the order is: override > -D >Consumer > Provider
     *
     * @param providerUrl
     * @return
     */
    private URL mergeUrl(URL providerUrl) { // 通过各种 configurators 对 providerUrl 进行配置，添加 queryMap 的参数，然后检查并制定 path 信息
        providerUrl = ClusterUtils.mergeUrl(providerUrl, queryMap); // Merge the consumer side parameters

        providerUrl = overrideWithConfigurator(providerUrl);    // 此方法就是通过各种 configurators 对 providerUrl 进行相关的配置

        providerUrl = providerUrl.addParameter(RemotingConstants.CHECK_KEY, String.valueOf(false)); // Do not check whether the connection is successful or not, always create Invoker!

        // The combination of directoryUrl and override is at the end of notify, which can't be handled here
        this.overrideDirectoryUrl = this.overrideDirectoryUrl.addParametersIfAbsent(providerUrl.getParameters()); // Merge the provider side parameters

        if ((providerUrl.getPath() == null || providerUrl.getPath() // providerUrl 为 dubbo 协议，但是路径信息为空
                .length() == 0) && DUBBO_PROTOCOL.equals(providerUrl.getProtocol())) { // Compatible version 1.0
            //fix by tony.chenl DUBBO-44
            String path = directoryUrl.getParameter(INTERFACE_KEY); // 根据 directoryUrl 的 interface 参数对 providerUrl 的 path 进行设置
            if (path != null) {
                int i = path.indexOf('/');
                if (i >= 0) {
                    path = path.substring(i + 1);
                }
                i = path.lastIndexOf(':');
                if (i >= 0) {
                    path = path.substring(0, i);
                }
                providerUrl = providerUrl.setPath(path);
            }
        }
        return providerUrl;
    }

    // 此方法就是通过各种 configurators 对 providerUrl 进行相关的配置
    private URL overrideWithConfigurator(URL providerUrl) {
        // override url with configurator from "override://" URL for dubbo 2.6 and before
        providerUrl = overrideWithConfigurators(this.configurators, providerUrl);   // 通过 configurators 对 providerUrl 进行配置

        // override url with configurator from configurator from "app-name.configurators"   // 同理
        providerUrl = overrideWithConfigurators(CONSUMER_CONFIGURATION_LISTENER.getConfigurators(), providerUrl);

        // override url with configurator from configurators from "service-name.configurators"
        if (serviceConfigurationListener != null) { // 同理
            providerUrl = overrideWithConfigurators(serviceConfigurationListener.getConfigurators(), providerUrl);
        }

        return providerUrl;
    }

    private URL overrideWithConfigurators(List<Configurator> configurators, URL url) {
        if (CollectionUtils.isNotEmpty(configurators)) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
        }
        return url;
    }

    /**
     * Close all invokers，此方法就是调用 urlInvokerMap 的 value 集合的每个 invoker 的 destroy 方法
     */
    private void destroyAllInvokers() {
        Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
        if (localUrlInvokerMap != null) {
            for (Invoker<T> invoker : new ArrayList<>(localUrlInvokerMap.values())) {
                try {
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }
            localUrlInvokerMap.clear();
        }
        invokers = null;
    }

    /**
     * Check whether the invoker in the cache needs to be destroyed
     * If set attribute of url: refer.autodestroy=false, the invokers will only increase without decreasing,there may be a refer leak
     *
     * @param oldUrlInvokerMap
     * @param newUrlInvokerMap
     */
    private void destroyUnusedInvokers(Map<String, Invoker<T>> oldUrlInvokerMap, Map<String, Invoker<T>> newUrlInvokerMap) {
        if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
            destroyAllInvokers();   // 此方法就是调用 urlInvokerMap 的 value 集合的每个 invoker 的 destroy 方法
            return;
        }
        // check deleted invoker
        List<String> deleted = null;
        if (oldUrlInvokerMap != null) {
            Collection<Invoker<T>> newInvokers = newUrlInvokerMap.values();
            for (Map.Entry<String, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {
                if (!newInvokers.contains(entry.getValue())) {  // 这个 if 块代码就是将 oldUrlInvokerMap 中没有包含在 newUrlInvokerMap 中的 invoker 添加到 deleted 链表中
                    if (deleted == null) {
                        deleted = new ArrayList<>();
                    }
                    deleted.add(entry.getKey());
                }
            }
        }

        if (deleted != null) {
            for (String url : deleted) {
                if (url != null) {
                    Invoker<T> invoker = oldUrlInvokerMap.remove(url);  // 从 oldUrlInvokerMap 中移除那些将要删除的项
                    if (invoker != null) {
                        try {
                            invoker.destroy();  // 调用被移除项的 destroy 方法
                            if (logger.isDebugEnabled()) {
                                logger.debug("destroy invoker[" + invoker.getUrl() + "] success. ");
                            }
                        } catch (Exception e) {
                            logger.warn("destroy invoker[" + invoker.getUrl() + "] failed. " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    @Override
    public List<Invoker<T>> doList(Invocation invocation) { // 此方法主要是根据 routerChain 来筛选出 invokers 中的部分 invoker
        if (forbidden) {    // 检查 RegistryDirectory 的 forbidden 属性的状态
            // 1. No service provider 2. Service providers are disabled
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, "No provider available from registry " +
                    getUrl().getAddress() + " for service " + getConsumerUrl().getServiceKey() + " on consumer " +
                    NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() +
                    ", please check status of providers(disabled, not registered or in blacklist).");
        }

        if (multiGroup) {   // 如果是 multiGroup，直接返回 RegistryDirectory 的 invokers 属性
            return this.invokers == null ? Collections.emptyList() : this.invokers;
        }

        List<Invoker<T>> invokers = null;
        try {
            // Get invokers from cache, only runtime routers will be executed.
            invokers = routerChain.route(getConsumerUrl(), invocation); // 这个过程就是根据 invocation 的相关信息由不同的 router 对 invoker 进行筛选
        } catch (Throwable t) {
            logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
        }


        // FIXME Is there any need of failing back to Constants.ANY_VALUE or the first available method invokers when invokers is null?
        /*Map<String, List<Invoker<T>>> localMethodInvokerMap = this.methodInvokerMap; // local reference
        if (localMethodInvokerMap != null && localMethodInvokerMap.size() > 0) {
            String methodName = RpcUtils.getMethodName(invocation);
            invokers = localMethodInvokerMap.get(methodName);
            if (invokers == null) {
                invokers = localMethodInvokerMap.get(Constants.ANY_VALUE);
            }
            if (invokers == null) {
                Iterator<List<Invoker<T>>> iterator = localMethodInvokerMap.values().iterator();
                if (iterator.hasNext()) {
                    invokers = iterator.next();
                }
            }
        }*/
        return invokers == null ? Collections.emptyList() : invokers;
    }

    @Override
    public Class<T> getInterface() {
        return serviceType;
    }

    @Override
    public URL getUrl() {
        return this.overrideDirectoryUrl;
    }

    public URL getRegisteredConsumerUrl() {
        return registeredConsumerUrl;
    }

    // consumer://192.168.1.104/org.apache.dubbo.demo.DemoService?application=demo-consumer&category=consumers&check=false&dubbo=2.0.2&interface=org.apache.dubbo.demo.DemoService&lazy=false&methods=sayHello&pid=50859&side=consumer&sticky=false&timestamp=1558925435531
    public void setRegisteredConsumerUrl(URL registeredConsumerUrl) {
        this.registeredConsumerUrl = registeredConsumerUrl;
    }

    @Override
    public boolean isAvailable() {
        if (isDestroyed()) {
            return false;
        }
        Map<String, Invoker<T>> localUrlInvokerMap = urlInvokerMap;
        if (localUrlInvokerMap != null && localUrlInvokerMap.size() > 0) {
            for (Invoker<T> invoker : new ArrayList<>(localUrlInvokerMap.values())) {
                if (invoker.isAvailable()) {
                    return true;
                }
            }
        }
        return false;
    }

    // 构建 RouterChain 的过程中还会获取全部的 RouterFactory，然后得到对应的 Router，最后将这些 Router 保存到实例字段中
    public void buildRouterChain(URL url) {
        this.setRouterChain(RouterChain.buildChain(url));   // 构建 RouterChain 并填充属性
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, Invoker<T>> getUrlInvokerMap() {
        return urlInvokerMap;
    }

    public List<Invoker<T>> getInvokers() {
        return invokers;
    }

    private boolean isValidCategory(URL url) {
        String category = url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY); // category 默认为 providers
        if ((ROUTERS_CATEGORY.equals(category) || ROUTE_PROTOCOL.equals(url.getProtocol())) ||  // 如果 category 为 routers 或者 url 的协议为 route
                PROVIDERS_CATEGORY.equals(category) ||  // 或者 category 为 providers、configurators、dynamic-configurators、app-dynamic-configurators
                CONFIGURATORS_CATEGORY.equals(category) || DYNAMIC_CONFIGURATORS_CATEGORY.equals(category) ||
                APP_DYNAMIC_CONFIGURATORS_CATEGORY.equals(category)) {
            return true;    // 以上几种情况任意之一都是符合条件的
        }
        logger.warn("Unsupported category " + category + " in notified url: " + url + " from registry " +
                getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost());
        return false;
    }

    private boolean isNotCompatibleFor26x(URL url) {
        return StringUtils.isEmpty(url.getParameter(COMPATIBLE_CONFIG_KEY));    // compatible_config
    }

    private void overrideDirectoryUrl() {   // 这个方法即通过各个配置项的 Configurator 来对 overrideDirectoryUrl 进行配置
        // merge override parameters
        this.overrideDirectoryUrl = directoryUrl;   // 如方法名，用 directoryUrl 覆盖 overrideDirectoryUrl 参数
        List<Configurator> localConfigurators = this.configurators; // local reference
        doOverrideUrl(localConfigurators);  // 即通过 configurator 对 overrideDirectoryUrl 进行配置
        List<Configurator> localAppDynamicConfigurators = CONSUMER_CONFIGURATION_LISTENER.getConfigurators(); // local reference
        doOverrideUrl(localAppDynamicConfigurators);    // 通过 CONSUMER_CONFIGURATION_LISTENER 中的 configurator 对 overrideDirectoryUrl 进行配置
        if (serviceConfigurationListener != null) {
            List<Configurator> localDynamicConfigurators = serviceConfigurationListener.getConfigurators(); // local reference
            doOverrideUrl(localDynamicConfigurators);   // 通过 serviceConfigurationListener 中的 configurator 对 overrideDirectoryUrl 进行配置
        }
    }

    private void doOverrideUrl(List<Configurator> configurators) {  // 即通过 configurator 对 overrideDirectoryUrl 进行配置
        if (CollectionUtils.isNotEmpty(configurators)) {
            for (Configurator configurator : configurators) {   // 进行配置后，重新赋值
                this.overrideDirectoryUrl = configurator.configure(overrideDirectoryUrl);
            }
        }
    }

    /**
     * The delegate class, which is mainly used to store the URL address sent by the registry,and can be reassembled on the basis of providerURL queryMap overrideMap for re-refer.
     *
     * @param <T>
     */
    private static class InvokerDelegate<T> extends InvokerWrapper<T> {
        private URL providerUrl;

        public InvokerDelegate(Invoker<T> invoker, URL url, URL providerUrl) {
            super(invoker, url);
            this.providerUrl = providerUrl;
        }

        public URL getProviderUrl() {
            return providerUrl;
        }
    }

    private static class ReferenceConfigurationListener extends AbstractConfiguratorListener {
        private RegistryDirectory directory;
        private URL url;

        // 构建 ReferenceConfigurationListener，同时为提前构建的 DynamicConfiguration 添加一个监听器（自身），同时获取指定路径下的配置信息进行处理
        ReferenceConfigurationListener(RegistryDirectory directory, URL url) {
            this.directory = directory;
            this.url = url; // org.apache.dubbo.demo.DemoService.configurators
            this.initWith(url.getEncodedServiceKey() + CONFIGURATORS_SUFFIX);   // 为提前构建的 DynamicConfiguration 添加一个监听器（自身），同时获取指定路径下的配置信息进行处理
        }

        @Override
        protected void notifyOverrides() {
            // to notify configurator/router changes
            directory.refreshInvoker(Collections.emptyList());
        }
    }

    private static class ConsumerConfigurationListener extends AbstractConfiguratorListener {
        List<RegistryDirectory> listeners = new ArrayList<>();

        ConsumerConfigurationListener() {   // 为提前构建的 DynamicConfiguration 添加一个监听器（自身），同时获取指定路径下的配置信息进行处理
            this.initWith(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX);
        }

        void addNotifyListener(RegistryDirectory listener) {
            this.listeners.add(listener);
        }

        @Override
        protected void notifyOverrides() {  // 通过监听器告知主监者刷新 Invoker 信息
            listeners.forEach(listener -> listener.refreshInvoker(Collections.emptyList()));
        }
    }

}
