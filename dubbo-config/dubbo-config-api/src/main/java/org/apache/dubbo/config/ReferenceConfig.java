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
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.metadata.integration.MetadataReportService;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.cluster.support.RegistryAwareCluster;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ConsumerModel;
import org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SEMICOLON_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.CLUSTER_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.common.constants.ConfigConstants.REFER_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.REGISTER_IP_KEY;
import static org.apache.dubbo.common.constants.MonitorConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.constants.RpcConstants.LOCAL_PROTOCOL;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;

/**
 * ReferenceConfig
 *
 * @export
 */
public class ReferenceConfig<T> extends AbstractReferenceConfig {

    private static final long serialVersionUID = -5864351140409987595L;

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
    private static final Protocol REF_PROTOCOL = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * The {@link Cluster}'s implementation with adaptive functionality, and actually it will get a {@link Cluster}'s
     * specific implementation who is wrapped with <b>MockClusterInvoker</b>
     */
    private static final Cluster CLUSTER = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a reference service's proxy,the JavassistProxyFactory is
     * its default implementation
     */
    private static final ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * The url of the reference service
     */
    private final List<URL> urls = new ArrayList<URL>();

    /**
     * The interface name of the reference service
     */
    private String interfaceName;

    /**
     * The interface class of the reference service
     */
    private Class<?> interfaceClass;

    /**
     * client type
     */
    private String client;

    /**
     * The url for peer-to-peer invocation
     */
    private String url;

    /**
     * The method configs
     */
    private List<MethodConfig> methods;

    /**
     * The consumer config (default)
     */
    private ConsumerConfig consumer;

    /**
     * Only the service provider of the specified protocol is invoked, and other protocols are ignored.
     */
    private String protocol;

    /**
     * The interface proxy reference
     */
    private transient volatile T ref;

    /**
     * The invoker of the reference service
     */
    private transient volatile Invoker<?> invoker;

    /**
     * The flag whether the ReferenceConfig has been initialized
     */
    private transient volatile boolean initialized;

    /**
     * whether this ReferenceConfig has been destroyed
     */
    private transient volatile boolean destroyed;

    @SuppressWarnings("unused")
    private final Object finalizerGuardian = new Object() {
        @Override
        protected void finalize() throws Throwable {
            super.finalize();

            if (!ReferenceConfig.this.destroyed) {
                logger.warn("ReferenceConfig(" + url + ") is not DESTROYED when FINALIZE");

                /* don't destroy for now
                try {
                    ReferenceConfig.this.destroy();
                } catch (Throwable t) {
                        logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ") in finalize method!", t);
                }
                */
            }
        }
    };

    public ReferenceConfig() {
    }

    public ReferenceConfig(Reference reference) {
        appendAnnotation(Reference.class, reference);
        setMethods(MethodConfig.constructMethodConfig(reference.methods()));
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    /**
     * This method should be called right after the creation of this class's instance, before any property in other config modules is used.
     * Check each config modules are created properly and override their properties if necessary.
     */
    public void checkAndUpdateSubConfigs() {    // 检查和更新存根配置
        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
        }
        completeCompoundConfigs();  // 根据 consumer、module、application 属性来完成其它属性的填充，非覆盖
        startConfigCenter();    // 核心就是获取 ZookeeperDynamicConfiguration，然后还会根据 configCenter 和 application 的相关属性从 zookeeper 的对应路径下获取配置信息，并更新到四种配置项中，最后刷新各种 ConfigBean
        // get consumer's global configuration
        checkDefault(); // 检查 consumer 是否存在，没有的话就是从 ConfigManager 持有的 consumers 中获取默认的那一项，如果还是没有，那就新建一个
        this.refresh(); // 刷新当前实例
        if (getGeneric() == null && getConsumer() != null) {
            setGeneric(getConsumer().getGeneric());
        }
        if (ProtocolUtils.isGeneric(getGeneric())) {
            interfaceClass = GenericService.class;
        } else {
            try {
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }   // 通过 interfaceClass 对 methods 的相关属性进行设置，同时检查 methods 是否在 interfaceClass 存在相应的方法
            checkInterfaceAndMethods(interfaceClass, methods);
        }
        resolveFile();  // 尝试从系统属性中或者文件中获取 interfaceName 对应的属性值，并赋值给 url
        checkApplication(); // 检查 application 属性是否存在，将 application 的 name 设置到 ApplicationModel 中，并对 shutdown.wait 相关的属性进行处理
        checkMetadataReport();  // 对 metadataReportConfig 相关内容进行检查
    }

    public synchronized T get() {
        checkAndUpdateSubConfigs(); // 此方法主要是完成一些相关的属性的检查设置

        if (destroyed) {
            throw new IllegalStateException("The invoker of ReferenceConfig(" + url + ") has already destroyed!");
        }
        if (ref == null) {
            init();
        }
        return ref;
    }

    public synchronized void destroy() {
        if (ref == null) {
            return;
        }
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            invoker.destroy();
        } catch (Throwable t) {
            logger.warn("Unexpected error occured when destroy invoker of ReferenceConfig(" + url + ").", t);
        }
        invoker = null;
        ref = null;
    }

    private void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        checkStubAndLocal(interfaceClass);  // 检查是否有 local 和 stub 属性，如果有且为 true 或者 default，那么需要保证 interfaceClass 对应的 local 和 stub 类存在且为子类或者一致
        checkMock(interfaceClass);  // 测试相关的，这里先不深入了解
        Map<String, String> map = new HashMap<String, String>();

        map.put(SIDE_KEY, CONSUMER_SIDE);   // side -> consumer

        appendRuntimeParameters(map);   // 添加一些运行时的信息如 version、release、timestamp
        if (!isGeneric()) {
            String revision = Version.getVersion(interfaceClass, version);  // 尝试从 MANIFEST.MF、jar 文件名获取版本信息，没有的话就使用 version 默认参数
            if (revision != null && revision.length() > 0) {
                map.put(REVISION_KEY, revision);    // revision -> revision
            }
            // 获得的 Wrapper 实例的源代码
            // public class Wrapper0 extends org.apache.dubbo.common.bytecode.Wrapper {
            // pns 集合专门用来保存字段的名字
            //     public static String[] pns;
            // 字段的 name 和 type
            //     public static java.util.Map pts;
            // mns 集合专门用来保存方法名，全部的方法
            //     public static String[] mns;
            // 保存方法名，仅限服务接口声明的方法
            //     public static String[] dmns;
            // mts 字段用来保存方法的参数类型，这里的 mts0 表示的是服务接口的第一个方法，以此类推
            //     public static Class[] mts0;
            //
            //     public String[] getPropertyNames() { // 获取字段的名字
            //         return pns;
            //     }
            //
            //     public boolean hasProperty(String n) {   // 有 n 对应的字段
            //         return pts.containsKey($1);
            //     }
            //
            //     public Class getPropertyType(String n) { // 获取 n 对应字段的类型
            //         return (Class) pts.get($1);
            //     }
            //
            //     public String[] getMethodNames() {   // 获取全部的方法名，应该是包括 Object 类中的方法
            //         return mns;
            //     }
            //
            //     public String[] getDeclaredMethodNames() {   // 获取服务接口声明的方法，不包括 Object 类中的方法
            //         return dmns;
            //     }
            //
            //     public void setPropertyValue(Object o, String n, Object v) { // 因为是接口，没有具体的字段，所以这里没有额外有意义的操作
            //         org.apache.dubbo.demo.DemoService w;
            //         try {
            //             w = ((org.apache.dubbo.demo.DemoService) $1);
            //         } catch (Throwable e) {
            //             throw new IllegalArgumentException(e);
            //         }
            //         throw new org.apache.dubbo.common.bytecode.NoSuchPropertyException("Not found property \"" + $2 + "\" field or setter method in class org.apache.dubbo.demo.DemoService.");
            //     }
            //
            //     public Object getPropertyValue(Object o, String n) { // 因为是接口，没有具体的字段，所以这里没有额外有意义的操作
            //         org.apache.dubbo.demo.DemoService w;
            //         try {
            //             w = ((org.apache.dubbo.demo.DemoService) $1);
            //         } catch (Throwable e) {
            //             throw new IllegalArgumentException(e);
            //         }
            //         throw new org.apache.dubbo.common.bytecode.NoSuchPropertyException("Not found property \"" + $2 + "\" field or setter method in class org.apache.dubbo.demo.DemoService.");
            //     }
            //
            //     public Object invokeMethod(Object o, String n, Class[] p, Object[] v) throws java.lang.reflect.InvocationTargetException {
            //         org.apache.dubbo.demo.DemoService w;
            //         try {
            //             w = ((org.apache.dubbo.demo.DemoService) $1);    // 强转为服务接口类型
            //         } catch (Throwable e) {
            //             throw new IllegalArgumentException(e);
            //         }
            //         try {
            //             if ("sayHello".equals($2) && $3.length == 1) {   // 这里进行方法的判别，可能存在多个方法
            //                 return ($w) w.sayHello((java.lang.String) $4[0]);
            //             }
            //         } catch (Throwable e) {
            //             throw new java.lang.reflect.InvocationTargetException(e);
            //         }
            //         throw new org.apache.dubbo.common.bytecode.NoSuchMethodException("Not found method \"" + $2 + "\" in class org.apache.dubbo.demo.DemoService.");
            //     }
            // }
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();  // Wrapper 实例中的方法名
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                map.put(METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), COMMA_SEPARATOR));   // methods
            }
        }
        map.put(INTERFACE_KEY, interfaceName);  // interface -> org.apache.dubbo.demo.DemoService
        appendParameters(map, metrics); // 将 metrics、application、module、consumer、reference 中的配置信息添加到 map 中，属性获取的方式是 getter 方法反射调用
        appendParameters(map, application);
        appendParameters(map, module);
        // remove 'default.' prefix for configs from ConsumerConfig
        // appendParameters(map, consumer, Constants.DEFAULT_KEY);
        appendParameters(map, consumer);
        appendParameters(map, this);
        Map<String, Object> attributes = null;
        if (CollectionUtils.isNotEmpty(methods)) {
            attributes = new HashMap<String, Object>();
            for (MethodConfig methodConfig : methods) { // 针对 methods 属性，有三点要做
                appendParameters(map, methodConfig, methodConfig.getName());    // 1、追加 methods 的属性到 map 中
                String retryKey = methodConfig.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);   // 2、需要将 map 中 methodName.retry 对应的 false 属性值替换为 0
                    if ("false".equals(retryValue)) {
                        map.put(methodConfig.getName() + ".retries", "0");
                    }
                }   // 3、将 methodConfig 转换为 AyncInfo，然后添加到 attributes 中
                attributes.put(methodConfig.getName(), convertMethodConfig2AyncInfo(methodConfig));
            }
        }

        String hostToRegistry = ConfigUtils.getSystemProperty(DUBBO_IP_TO_REGISTRY);    // 优先获取系统的 DUBBO_IP_TO_REGISTRY 属性作为 hostToRegistry
        if (StringUtils.isEmpty(hostToRegistry)) {
            hostToRegistry = NetUtils.getLocalHost();   // 使用本机 ip 作为 hostToRegistry
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        map.put(REGISTER_IP_KEY, hostToRegistry);   // register.ip

        ref = createProxy(map);
        // org.apache.dubbo.demo.DemoService
        String serviceKey = URL.buildKey(interfaceName, group, version);
        ApplicationModel.initConsumerModel(serviceKey, buildConsumerModel(serviceKey, attributes));
    }

    private ConsumerModel buildConsumerModel(String serviceKey, Map<String, Object> attributes) {
        Method[] methods = interfaceClass.getMethods();
        Class serviceInterface = interfaceClass;
        if (interfaceClass == GenericService.class) {
            try {
                serviceInterface = Class.forName(interfaceName);
                methods = serviceInterface.getMethods();
            } catch (ClassNotFoundException e) {
                methods = interfaceClass.getMethods();
            }
        }   // 就是将服务的一些信息保存到 ConsumerModel 中
        return new ConsumerModel(serviceKey, serviceInterface, ref, methods, attributes);
    }

    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    private T createProxy(Map<String, String> map) {
        if (shouldJvmRefer(map)) {  // if 判断是否应该进行 JvmRefer，优先根据 injvm 属性判定，在 injvm 为空的情况下看 url 的 scope 参数为 local 或者 injvm 为 true，scope 为 remote 或者 generic 为 true 时返回 false
            URL url = new URL(LOCAL_PROTOCOL, LOCALHOST_VALUE, 0, interfaceClass.getName()).addParameters(map);
            invoker = REF_PROTOCOL.refer(interfaceClass, url);  // 此分支暂时不做深入了解
            if (logger.isInfoEnabled()) {
                logger.info("Using injvm service " + interfaceClass.getName());
            }
        } else {    // 这里是处理存在 url 的情况
            if (url != null && url.length() > 0) { // user specified URL, could be peer-to-peer address, or register center's address.
                String[] us = SEMICOLON_SPLIT_PATTERN.split(url);   // 按照 " " 或者 ";" 进行分割
                if (us != null && us.length > 0) {
                    for (String u : us) {
                        URL url = URL.valueOf(u);
                        if (StringUtils.isEmpty(url.getPath())) {
                            url = url.setPath(interfaceName);   // 对 url 的每一项修改 path 为 interfaceName
                        }
                        if (REGISTRY_PROTOCOL.equals(url.getProtocol())) {  // 如果 url 的协议是 registry，那么为 url 添加 refer 参数并编码后添加到 urls 中
                            urls.add(url.addParameterAndEncoded(REFER_KEY, StringUtils.toQueryString(map)));    // refer -> map 参数，也就是说如果是 registry 协议，那么 refer 引用 map 参数
                        } else {
                            urls.add(ClusterUtils.mergeUrl(url, map));  // 否则将 map 的参数添加到 url 中，再将 url 添加到 urls 中
                        }
                    }
                }
            } else { // assemble URL from register center's configuration
                // if protocols not injvm checkRegistry
                if (!LOCAL_PROTOCOL.equalsIgnoreCase(getProtocol())) {  // 如果 protocol 不是使用的 injvm
                    // 检查并填充 registries 属性，找到 registries 中第一个使用 zookeeper 协议的，将此 registry 的协议和地址填充到 ConfigCenterConfig 中，然后将这个配置中心填充到当前 reference 实例中，启动配置中心
                    // 启动配置中心核心就是获取 ZookeeperDynamicConfiguration，然后还会根据 configCenter 和 application 的相关属性从 zookeeper 的对应路径下获取配置信息，并更新到四种配置项中，最后刷新各种 ConfigBean
                    checkRegistry();    // 检查当前 ConfigBean 中的 registries 属性，并根据实际情况将其应用到 ConfigCenterConfig，然后 startConfigCenter
                    List<URL> us = loadRegistries(false);   // 根据 registries 获取 URL 链信息
                    if (CollectionUtils.isNotEmpty(us)) {   // 此一部分代码就是用来构建最终的 url 了，期间还考虑了 monitor 参数的问题
                        for (URL u : us) {
                            URL monitorUrl = loadMonitor(u);    // 根据当前 url 获取到 monitor url 信息
                            if (monitorUrl != null) {   // 将 monitor url 与 monitor 一起映射到 map 中
                                map.put(MONITOR_KEY, URL.encode(monitorUrl.toFullString()));    // 添加构建的 monitorUrl 到 map 中
                            }   // 所以添加的 url 最终就是 registry + map 的组合？？
                            urls.add(u.addParameterAndEncoded(REFER_KEY, StringUtils.toQueryString(map)));  // 将每一个 registries URL 地址添加 refer 属性并编码后添加到 urls 中
                        }
                    }
                    if (urls.isEmpty()) {
                        throw new IllegalStateException("No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", please config <dubbo:registry address=\"...\" /> to your spring config.");
                    }
                }
            }
            // registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-consumer&dubbo=2.0.2&pid=77389&refer=application%3Ddemo-consumer%26check%3Dfalse%26dubbo%3D2.0.2%26interface%3Dorg.apache.dubbo.demo.DemoService%26lazy%3Dfalse%26methods%3DsayHello%26pid%3D77389%26register.ip%3D192.168.1.102%26side%3Dconsumer%26sticky%3Dfalse%26timestamp%3D1559300508608&registry=zookeeper&timestamp=1559300517630
            if (urls.size() == 1) {
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
                // 这里实际得到的 extension 类是 ProtocolListenerWrapper
                //         org.apache.dubbo.rpc.Protocol extension = (org.apache.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.Protocol.class).getExtension(extName);
                //         return extension.refer(arg0, arg1);
                //     }
                // }
                // 得到的是 MockClusterInvoker
                // ProtocolListenerWrapper -> ProtocolFilterWrapper -> RegistryProtocol -> （RegistryProtocol 持有）RegistryFactory$Adaptive 获取 ZookeeperRegistryFactory ->
                //
                invoker = REF_PROTOCOL.refer(interfaceClass, urls.get(0));
            } else {
                List<Invoker<?>> invokers = new ArrayList<Invoker<?>>();
                URL registryURL = null;
                for (URL url : urls) {
                    invokers.add(REF_PROTOCOL.refer(interfaceClass, url));
                    if (REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                        registryURL = url; // use last registry url
                    }
                }
                if (registryURL != null) { // registry url is available
                    // use RegistryAwareCluster only when register's CLUSTER is available
                    URL u = registryURL.addParameter(CLUSTER_KEY, RegistryAwareCluster.NAME);
                    // The invoker wrap relation would be: RegistryAwareClusterInvoker(StaticDirectory) -> FailoverClusterInvoker(RegistryDirectory, will execute route) -> Invoker
                    invoker = CLUSTER.join(new StaticDirectory(u, invokers));
                } else { // not a registry url, must be direct invoke.
                    invoker = CLUSTER.join(new StaticDirectory(invokers));
                }
            }
        }

        if (shouldCheck() && !invoker.isAvailable()) {
            // make it possible for consumer to retry later if provider is temporarily unavailable
            initialized = false;
            throw new IllegalStateException("Failed to check the status of the service " + interfaceName + ". No provider available for the service " + (group == null ? "" : group + "/") + interfaceName + (version == null ? "" : ":" + version) + " from the url " + invoker.getUrl() + " to the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());
        }
        if (logger.isInfoEnabled()) {
            logger.info("Refer dubbo service " + interfaceClass.getName() + " from url " + invoker.getUrl());
        }
        /**
         * @since 2.7.0
         * ServiceData Store
         */
        MetadataReportService metadataReportService = null;
        if ((metadataReportService = getMetadataReportService()) != null) {
            URL consumerURL = new URL(CONSUMER_PROTOCOL, map.remove(REGISTER_IP_KEY), 0, map.get(INTERFACE_KEY), map);
            metadataReportService.publishConsumer(consumerURL); // 如果 metadataReportService 不为空，通过它发布 consumer 信息
        }
        // create service proxy
        // 此处获得的 extension 为 StubProxyFactoryWrapper
        return (T) PROXY_FACTORY.getProxy(invoker);
    }

    /**
     * Figure out should refer the service in the same JVM from configurations. The default behavior is true
     * 1. if injvm is specified, then use it
     * 2. then if a url is specified, then assume it's a remote call
     * 3. otherwise, check scope parameter
     * 4. if scope is not specified but the target service is provided in the same JVM, then prefer to make the local
     * call, which is the default behavior
     */ // 优先根据 injvm 属性判定，在 injvm 为空的情况下看 url 的 scope 参数为 local 或者 injvm 为 true，scope 为 remote 或者 generic 为 true 时返回 false
    protected boolean shouldJvmRefer(Map<String, String> map) {
        URL tmpUrl = new URL("temp", "localhost", 0, map);
        boolean isJvmRefer;
        if (isInjvm() == null) {
            // if a url is specified, don't do local reference
            if (url != null && url.length() > 0) {
                isJvmRefer = false; // 如果指定了 url 参数，那么就不是 JvmRefer
            } else {
                // by default, reference local service if there is，url 的 scope 参数为 local 或者 injvm 为 true，scope 为 remote 或者 generic 为 true 时返回 false
                isJvmRefer = InjvmProtocol.getInjvmProtocol().isInjvmRefer(tmpUrl); // InjvmProtocol
            }
        } else {
            isJvmRefer = isInjvm();
        }
        return isJvmRefer;
    }

    protected boolean shouldCheck() {
        Boolean shouldCheck = isCheck();
        if (shouldCheck == null && getConsumer() != null) {
            shouldCheck = getConsumer().isCheck();
        }
        if (shouldCheck == null) {
            // default true
            shouldCheck = true;
        }
        return shouldCheck;
    }

    protected boolean shouldInit() {
        Boolean shouldInit = isInit();  // 获取 init 标志位
        if (shouldInit == null && getConsumer() != null) {  // 没有的话，那么就从 consumer 属性中获取 init 信息
            shouldInit = getConsumer().isInit();
        }
        if (shouldInit == null) {
            // default is false
            return false;
        }
        return shouldInit;
    }

    private void checkDefault() {
        if (consumer != null) {
            return;
        }   // 从 ConfigManager 持有的 consumers 中获取默认的那一项
        setConsumer(ConfigManager.getInstance().getDefaultConsumer().orElseGet(() -> {
            ConsumerConfig consumerConfig = new ConsumerConfig();
            consumerConfig.refresh();
            return consumerConfig;
        }));
    }

    private void completeCompoundConfigs() {    // 根据 consumer、module、application 属性来完成其它属性的填充
        if (consumer != null) { // 根据 consumer 填充 application、module、registries、monitor，非覆盖，非覆盖
            if (application == null) {
                setApplication(consumer.getApplication());
            }
            if (module == null) {
                setModule(consumer.getModule());
            }
            if (registries == null) {
                setRegistries(consumer.getRegistries());
            }
            if (monitor == null) {
                setMonitor(consumer.getMonitor());
            }
        }
        if (module != null) {   // 根据 module 填充 registries、monitor，非覆盖
            if (registries == null) {
                setRegistries(module.getRegistries());
            }
            if (monitor == null) {
                setMonitor(module.getMonitor());
            }
        }
        if (application != null) {  // 根据 application 填充 registries、monitor，非覆盖
            if (registries == null) {
                setRegistries(application.getRegistries());
            }
            if (monitor == null) {
                setMonitor(application.getMonitor());
            }
        }
    }

    public Class<?> getInterfaceClass() {   // 优先由 interfaceClass 决定
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (isGeneric()
                || (getConsumer() != null && getConsumer().isGeneric())) {
            return GenericService.class;    // 如果 generic 属性是 true 或 nativejava 或 bean 或 probobuf-json，返回 GenericService.class
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {  // 将 interfaceClass 赋值为 interfaceName 对应的 class
                this.interfaceClass = Class.forName(interfaceName, true, ClassUtils.getClassLoader());
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
    @Deprecated
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (StringUtils.isEmpty(id)) {
            id = interfaceName;
        }
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        checkName(RemotingConstants.CLIENT_KEY, client);
        this.client = client;
    }

    @Parameter(excluded = true)
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ConsumerConfig getConsumer() {
        return consumer;
    }

    public void setConsumer(ConsumerConfig consumer) {
        ConfigManager.getInstance().addConsumer(consumer);
        this.consumer = consumer;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    // just for test
    Invoker<?> getInvoker() {
        return invoker;
    }

    @Override
    @Parameter(excluded = true)
    public String getPrefix() {
        return DUBBO + ".reference." + interfaceName;
    }

    private void resolveFile() {
        String resolve = System.getProperty(interfaceName); // 优先获取接口名对应的系统属性作为 resolve 结果
        String resolveFile = null;
        if (StringUtils.isEmpty(resolve)) {
            resolveFile = System.getProperty("dubbo.resolve.file"); // 尝试获取 dubbo.resolve.file 系统属性作为 resolveFile
            if (StringUtils.isEmpty(resolveFile)) { // resolveFile 为空的话，再 user.home 系统属性/dubbo-resolve.properties 的绝对路径作为 resolveFile
                File userResolveFile = new File(new File(System.getProperty("user.home")), "dubbo-resolve.properties");
                if (userResolveFile.exists()) {
                    resolveFile = userResolveFile.getAbsolutePath();    // 如果存在的话，就使用这个 AbsolutePath 作为 resolveFile
                }
            }
            if (resolveFile != null && resolveFile.length() > 0) {
                Properties properties = new Properties();
                try (FileInputStream fis = new FileInputStream(new File(resolveFile))) {
                    properties.load(fis);   // 用 Properties 加载 resolveFile 对应的文件属性
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to load " + resolveFile + ", cause: " + e.getMessage(), e);
                }

                resolve = properties.getProperty(interfaceName);    // 从加载的属性中获取 interfaceName 对应的属性值
            }
        }
        if (resolve != null && resolve.length() > 0) {
            url = resolve;  // 将 resolve 赋值给 url
            if (logger.isWarnEnabled()) {
                if (resolveFile != null) {
                    logger.warn("Using default dubbo resolve file " + resolveFile + " replace " + interfaceName + "" + resolve + " to p2p invoke remote service.");
                } else {
                    logger.warn("Using -D" + interfaceName + "=" + resolve + " to p2p invoke remote service.");
                }
            }
        }
    }
}
