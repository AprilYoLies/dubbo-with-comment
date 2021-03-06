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
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.configcenter.DynamicConfigurationFactory;
import org.apache.dubbo.metadata.integration.MetadataReportService;
import org.apache.dubbo.monitor.MonitorFactory;
import org.apache.dubbo.monitor.MonitorService;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.InvokerListener;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.support.MockInvoker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.config.ConfigurationUtils.parseProperties;
import static org.apache.dubbo.common.constants.ClusterConstants.TAG_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.FILE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PID_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.RELEASE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMESTAMP_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.CLUSTER_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.common.constants.ConfigConstants.DUBBO_PROTOCOL;
import static org.apache.dubbo.common.constants.ConfigConstants.LAYER_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.LISTENER_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.REFER_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.REGISTER_IP_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.REGISTRIES_SUFFIX;
import static org.apache.dubbo.common.constants.ConfigConstants.SHUTDOWN_WAIT_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.SHUTDOWN_WAIT_SECONDS_KEY;
import static org.apache.dubbo.common.constants.MonitorConstants.LOGSTAT_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTER_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.SUBSCRIBE_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.INVOKER_LISTENER_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.LOCAL_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.PROXY_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.REFERENCE_FILTER_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.RETURN_PREFIX;
import static org.apache.dubbo.common.constants.RpcConstants.THROW_PREFIX;
import static org.apache.dubbo.common.extension.ExtensionLoader.getExtensionLoader;

/**
 * AbstractDefaultConfig
 *
 * @export
 */
public abstract class AbstractInterfaceConfig extends AbstractMethodConfig {

    private static final long serialVersionUID = -1559314110797223229L;

    /**
     * Local impl class name for the service interface
     */
    protected String local;

    /**
     * Local stub class name for the service interface
     */
    protected String stub;

    /**
     * Service monitor
     */
    protected MonitorConfig monitor;

    /**
     * Strategies for generating dynamic agents，there are two strategies can be choosed: jdk and javassist
     */
    protected String proxy;

    /**
     * Cluster type
     */
    protected String cluster;

    /**
     * The {@link Filter} when the provider side exposed a service or the customer side references a remote service used,
     * if there are more than one, you can use commas to separate them
     */
    protected String filter;

    /**
     * The Listener when the provider side exposes a service or the customer side references a remote service used
     * if there are more than one, you can use commas to separate them
     */
    protected String listener;

    /**
     * The owner of the service providers
     */
    protected String owner;

    /**
     * Connection limits, 0 means shared connection, otherwise it defines the connections delegated to the current service
     */
    protected Integer connections;

    /**
     * The layer of service providers
     */
    protected String layer;

    /**
     * The application info
     */
    protected ApplicationConfig application;

    /**
     * The module info
     */
    protected ModuleConfig module;

    /**
     * Registry centers
     */
    protected List<RegistryConfig> registries;

    protected String registryIds;

    // connection events
    protected String onconnect;

    /**
     * Disconnection events
     */
    protected String ondisconnect;

    /**
     * The metrics configuration
     */
    protected MetricsConfig metrics;
    protected MetadataReportConfig metadataReportConfig;

    protected ConfigCenterConfig configCenter;

    // callback limits
    private Integer callbacks;
    // the scope for referring/exporting a service, if it's local, it means searching in current JVM only.
    private String scope;

    protected String tag;

    /**
     * 检查当前 ConfigBean 中的 registries 属性，并根据实际情况将其应用到 ConfigCenterConfig，然后 startConfigCenter
     * <p>
     * Check whether the registry config is exists, and then conversion it to {@link RegistryConfig}
     */
    protected void checkRegistry() {
        // 检查 registry 属性是否存在，否则从环境中获取 registries 相关的配置，转换为 registry 填充到属性中
        loadRegistriesFromBackwardConfig();
        // 没有获取到的前提下，就根据 registryIds 进行 registries 的注册
        // 整体优先级是先使用已存在的 registries，没有的话就根据 registriesIds，还是没有就使用 ConfigManager 中的 registries，最后才是新建一个
        convertRegistryIdsToRegistries();

        // 对 ServiceBean 中的 registries 进行验证
        for (RegistryConfig registryConfig : registries) {
            if (!registryConfig.isValid()) {
                throw new IllegalStateException("No registry config found or it's not a valid config! " +
                        "The registry config is: " + registryConfig);
            }
        }
        // 检查并填充 registries 属性，找到 registries 中第一个使用 zookeeper 协议的，将此 registry 的协议和地址填充到 ConfigCenterConfig 中，然后将这个配置中心填充到当前 reference 实例中，启动配置中心
        // 启动配置中心核心就是获取 ZookeeperDynamicConfiguration，然后还会根据 configCenter 和 application 的相关属性从 zookeeper 的对应路径下获取配置信息，并更新到四种配置项中，最后刷新各种 ConfigBean
        useRegistryForConfigIfNecessary();
    }

    /**
     * 检查 application 属性是否存在，将 application 的 name 设置到 ApplicationModel 中，并对 shutdown.wait 相关的属性进行处理
     */
    @SuppressWarnings("deprecation")
    protected void checkApplication() {
        // for backward compatibility
        // 没有 application 属性，就尝试从 ConfigManager 中获取，再没有，就新建并刷新属性
        createApplicationIfAbsent();

        if (!application.isValid()) {
            throw new IllegalStateException("No application config found or it's not a valid config! " +
                    "Please add <dubbo:application name=\"...\" /> to your spring config.");
        }

        // 将 application 的名字保存到 ApplicationModel 中去
        ApplicationModel.setApplication(application.getName());

        // backward compatibility
        // 处理 shutdown.wati 相关的一些属性
        String wait = ConfigUtils.getProperty(SHUTDOWN_WAIT_KEY);
        if (wait != null && wait.trim().length() > 0) {
            System.setProperty(SHUTDOWN_WAIT_KEY, wait.trim());
        } else {
            wait = ConfigUtils.getProperty(SHUTDOWN_WAIT_SECONDS_KEY);
            if (wait != null && wait.trim().length() > 0) {
                System.setProperty(SHUTDOWN_WAIT_SECONDS_KEY, wait.trim());
            }
        }
    }

    // 检查 monitor 属性是否存在且有效
    protected void checkMonitor() {
        // 检查 monitor 属性，没有的话就新建一个
        createMonitorIfAbsent();
        if (!monitor.isValid()) {
            logger.info("There's no valid monitor config found, if you want to open monitor statistics for Dubbo, " +
                    "please make sure your monitor is configured properly.");
        }
    }

    // 检查 monitor 属性，没有的话就新建一个
    private void createMonitorIfAbsent() {
        if (this.monitor != null) {
            return;
        }
        ConfigManager configManager = ConfigManager.getInstance();
        setMonitor(
                configManager
                        .getMonitor()
                        // 尝试获取 MonitorConfig，没有的话就直接创建
                        .orElseGet(() -> {
                            MonitorConfig monitorConfig = new MonitorConfig();
                            monitorConfig.refresh();
                            return monitorConfig;
                        })
        );
    }

    // 对 metadataReportConfig 相关内容进行检查
    protected void checkMetadataReport() {
        // TODO get from ConfigManager first, only create if absent.
        if (metadataReportConfig == null) {
            setMetadataReportConfig(new MetadataReportConfig());
        }
        metadataReportConfig.refresh();
        if (!metadataReportConfig.isValid()) {
            logger.warn("There's no valid metadata config found, if you are using the simplified mode of registry url, " +
                    "please make sure you have a metadata address configured properly.");
        }
    }

    // 检查 ConfigCenter 是否存在，刷新其属性
    void startConfigCenter() {
        // 这里对应 config-center 标签，存储在 ServiceBean 中
        if (configCenter == null) {
            // java.util.Optional.ifPresent 如果值存在，就调用函数，否则什么都不干
            ConfigManager.getInstance().getConfigCenter().ifPresent(cc -> this.configCenter = cc);
        }

        // 初次调用时，如果没有配置 config-center 标签，则这里是为 null 的
        if (this.configCenter != null) {
            // TODO there may have duplicate refresh
            this.configCenter.refresh();    // 刷新 configCenter 的属性信息
            // 核心就是获取 ZookeeperDynamicConfiguration，然后还会根据 configCenter 和 application 的相关属性从 zookeeper 的对应路径下获取配置信息，并更新到四种配置项中
            prepareEnvironment();
        }
        // 单例获取 ConfigManager，依据具体的配置信息对相应的 config 进行属性的更新（这里 ConfigManager 持有了全部的配置信息）
        ConfigManager.getInstance().refreshAll(); // 因为可能从 zookeeper 指定路径下获取了新的配置信息，需要将这部分信息更新到相关的 ConfigBean 中
    }

    // 核心就是获取 ZookeeperDynamicConfiguration，然后还会根据 configCenter 和 application 的相关属性从 zookeeper 的对应路径下获取配置信息，并更新到四种配置项中
    private void prepareEnvironment() {
        if (configCenter.isValid()) {
            // 通过 cas 操作，将 init 状态从 false 修改为 true
            if (!configCenter.checkOrUpdateInited()) {
                return;
            }
            // configCenter.toUrl() 将当 configCenter 的 MetaData 转换为 map，向其中添加一个 path -> ConfigCenterConfig 属性，然后将得到的 map 和属性 address 组装成为 URL
            // getDynamicConfiguration 获取到的是 DynamicConfiguration
            // 以 ZookeeperDynamicConfiguration 为例，ZookeeperDynamicConfiguration 此时已经是持有了 zkClient 实例了
            DynamicConfiguration dynamicConfiguration = getDynamicConfiguration(configCenter.toUrl());
            // 尝试从 zookeeper 中获取配置文件，参数 key 和 group 均是通过 config-center 标签进行设置，dubbo.properties,dubbo
            String configContent = dynamicConfiguration.getConfig(configCenter.getConfigFile(), configCenter.getGroup());   // /dubbo/config/dubbo/dubbo.properties

            // 使用 application name 作为 appGroup
            String appGroup = application != null ? application.getName() : null;
            String appConfigContent = null;
            if (StringUtils.isNotEmpty(appGroup)) {
                // 从 zookeeper 的 appGroup/configCenter.getAppConfigFile 或者 appGroup/configCenter.getConfigFile 路径下获取 appConfigContent
                appConfigContent = dynamicConfiguration.getConfig   // 优先 configCenter 的 appConfigFile，然后再是 configFile 作为 key，group 为 demo-consumer
                        (StringUtils.isNotEmpty(configCenter.getAppConfigFile()) ? configCenter.getAppConfigFile() : configCenter.getConfigFile(),
                                appGroup
                        );  // /dubbo/config/group/config-file
            }
            try {
                // 设置 configCenter 的优先级
                Environment.getInstance().setConfigCenterFirst(configCenter.isHighestPriority());
                // 将从 zookeeper 中获取的配置更新到 ExternalConfigurationMap 中    位于 zookeeper 的 /dubbo/config/dubbo/dubbo.properties 路径之下
                Environment.getInstance().updateExternalConfigurationMap(parseProperties(configContent));   // 更新四种配置中的 ExternalConfiguration
                // 将从 zookeeper 中获取的配置更新到 AppExternalConfiguration 中    位于 zookeeper 的 /dubbo/config/group/config-file 路径之下
                Environment.getInstance().updateAppExternalConfigurationMap(parseProperties(appConfigContent)); // 更新四种配置中的 AppExternalConfiguration
            } catch (IOException e) {
                throw new IllegalStateException("Failed to parse configurations from Config Center.", e);
            }
        }
    }

    // 根据 url 的协议获取对应的 DynamicConfigurationFactory，此 DynamicConfigurationFactory 工厂类在获取时通过 inject 方法填充了 zookeeperTransporter 属性，
    // 作为工厂类，它会根据注入的 zookeeperTransporter 属性来构建 ZookeeperDynamicConfiguration 实例，也就是说 ZookeeperDynamicConfiguration 会持有 zookeeperTransporter
    // 在构建 ZookeeperDynamicConfiguration 时，会对 zkClient 进行初始化，需要使用到 zookeeperTransporter，它其实是一个 Adaptive 实例，实际调用的是 CuratorZookeeperTransporter
    // 的 connect 方法，此 connect 方法实际是返回了一个新构建的 CuratorZookeeperClient，注意这个 connect 方法的内容较多，主要是还是做了一些关于主机地址和备用地址到 zkClient（CuratorZookeeperClient）
    // 的映射缓存操作，还有 registry url 到 client url 的转换
    private DynamicConfiguration getDynamicConfiguration(URL url) {
        // url -> zookeeper://127.0.0.1:2181/ConfigCenterConfig?
        //          address=zookeeper://127.0.0.1:2181&
        //          check=true&configFile=dubbo.properties&
        //          group=dubbo&
        //          highestPriority=false&
        //          namespace=dubbo&
        //          prefix=dubbo.config-center&
        //          timeout=3000&
        //          valid=true
        // 通过 DynamicConfigurationFactory SPI 接口的 ExtensionLoader 获取 别名为 url.getProtocol() 的 DynamicConfigurationFactory
        DynamicConfigurationFactory factories = ExtensionLoader
                .getExtensionLoader(DynamicConfigurationFactory.class)
                .getExtension(url.getProtocol());   // 根据 url 的协议获取 DynamicConfigurationFactory 的实现类，可能存在 setter 方法，所以还会通过 inject 方法对相应的属性进行填充
        // 通过 factories 工厂类获取 DynamicConfiguration，实际获取到的是 ZookeeperDynamicConfiguration
        DynamicConfiguration configuration = factories.getDynamicConfiguration(url);
        // 将 DynamicConfiguration 保存到 Environment 中，实际保存的是 ZookeeperDynamicConfiguration
        Environment.getInstance().setDynamicConfiguration(configuration);
        return configuration;
    }

    /**
     * Load the registry and conversion it to {@link URL}, the priority order is: system property > dubbo registry config
     *
     * @param provider whether it is the provider side
     * @return
     */ // 通过 registries 的 address 属性获取到地址链，通过 application、registry 等信息得到参数 map，将地址链和 map 构建成新的地址，修改协议为 registry，将原协议保存到 registry 参数中
    protected List<URL> loadRegistries(boolean provider) {
        // check && override if necessary
        List<URL> registryList = new ArrayList<URL>();
        if (CollectionUtils.isNotEmpty(registries)) {
            for (RegistryConfig config : registries) {
                // 遍历 registries，获取 address 信息
                String address = config.getAddress();
                if (StringUtils.isEmpty(address)) {
                    // 如果地址信息为空，那么就使用统配地址
                    address = ANYHOST_VALUE;
                }
                // registry 标签不能将地址配置为 N/A
                if (!RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(address)) {
                    Map<String, String> map = new HashMap<String, String>();
                    // 将 application 中的一些属性追加到 map 中
                    appendParameters(map, application);
                    // 将 RegistryConfig 中的一些属性追加到 map 中
                    appendParameters(map, config);
                    // path -> org.apache.dubbo.registry.RegistryService
                    // URL 的地址就是这里指定的
                    map.put(PATH_KEY, RegistryService.class.getName());
                    // 添加一些运行时的参数信息，比如：
                    // dubbo -> dubbo 协议版本
                    // release -> dubbo 版本
                    // timestamp -> 运行的时间戳
                    // 进程 id 号
                    appendRuntimeParameters(map);
                    if (!map.containsKey(PROTOCOL_KEY)) {
                        // 如果没有指定 protocol 属性，默认使用 dubbo 协议
                        map.put(PROTOCOL_KEY, DUBBO_PROTOCOL);
                    }
                    // 根据 address 信息和 map 集合，拼接出对应的 url 地址
                    // zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&pid=56159&timestamp=1558062932218
                    List<URL> urls = UrlUtils.parseURLs(address, map);
                    for (URL url : urls) {
                        // 修改 url 的 protocol，并添加一个 registry 参数
                        url = URLBuilder.from(url)
                                .addParameter(REGISTRY_KEY, url.getProtocol())  // registry -> zookeeper
                                .setProtocol(REGISTRY_PROTOCOL)
                                .build();

                        // registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&pid=56922&registry=zookeeper&timestamp=1558065143107
                        if ((provider && url.getParameter(REGISTER_KEY, true))
                                || (!provider && url.getParameter(SUBSCRIBE_KEY, true))) {
                            // provider 为 true，URL 的 register 参数为 true 或者为空
                            // provider 为 false，URL 的 subscribe 参数为 true 或者为空
                            // 这两种情况的 url 都是满足条件的
                            registryList.add(url);
                        }
                    }
                }
            }
        }
        return registryList;
    }

    /**
     * Load the monitor config from the system properties and conversation it to {@link URL}
     *
     * @param registryURL
     * @return
     */
    // 如果 monitor 的 address 属性存在，那么就用这个 address 加 map 构建 monitor url，否则如果 monitor 的 protocol 属性为 registry，
    // 那么用 registryURL 构建 monitor url，需要改变它的协议为 dubbo，同时 map 转换为字符串作为 refer 属性添加到 url 中
    protected URL loadMonitor(URL registryURL) {
        // 检查 monitor 属性是否存在且有效
        checkMonitor();
        Map<String, String> map = new HashMap<String, String>();
        // interface -> org.apache.dubbo.monitor.MonitorService
        map.put(INTERFACE_KEY, MonitorService.class.getName()); // interface -> org.apache.dubbo.monitor.MonitorService
        // 添加一些运行时的参数信息，比如：
        // dubbo -> dubbo 协议版本
        // release -> dubbo 版本
        // timestamp -> 运行的时间戳
        // 进程 id 号
        appendRuntimeParameters(map);
        //set ip
        String hostToRegistry = ConfigUtils.getSystemProperty(DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {
            // 系统属性中没有获取到 DUBBO_IP_TO_REGISTRY，使用 127.0.0.1 或者本机 ip
            hostToRegistry = NetUtils.getLocalHost();
        } else if (NetUtils.isInvalidLocalHost(hostToRegistry)) {
            // 系统属性一定要设置正确，否则报错
            throw new IllegalArgumentException("Specified invalid registry ip from property:" +
                    DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        // ip 键值对
        map.put(REGISTER_IP_KEY, hostToRegistry);   // register.ip -> 本机 ip
        // 从 config 中获取相关参数，添加到 parameters 中
        appendParameters(map, monitor);
        appendParameters(map, application);
        String address = monitor.getAddress();  // 如果 monitor 没有指定 address，使用 dubbo.monitor.address 系统属性的值
        String sysaddress = System.getProperty("dubbo.monitor.address");
        if (sysaddress != null && sysaddress.length() > 0) {
            // dubbo.monitor.address 系统属性优先
            address = sysaddress;
        }
        if (ConfigUtils.isNotEmpty(address)) {  // 如果有系统属性指定 address，那就通过这个 address 和 map 构建 url
            // map 中没有 protocol 属性
            if (!map.containsKey(PROTOCOL_KEY)) {
                // 有别名为 logstat 的 MonitorFactory SPI 接口的
                if (getExtensionLoader(MonitorFactory.class).hasExtension(LOGSTAT_PROTOCOL)) {
                    // protocol -> logstat
                    map.put(PROTOCOL_KEY, LOGSTAT_PROTOCOL);
                } else {
                    // protocol -> dubbo
                    map.put(PROTOCOL_KEY, DUBBO_PROTOCOL);
                }
            }
            // 为 address 拼接相关属性
            return UrlUtils.parseURL(address, map);
        } else if (REGISTRY_PROTOCOL.equals(monitor.getProtocol()) && registryURL != null) {    // registry
            // 如果 monitor 的协议属性为 registry，registryURL 不为空
            // 根据 registryURL 重构 URL
            return URLBuilder.from(registryURL)
                    .setProtocol(DUBBO_PROTOCOL)
                    .addParameter(PROTOCOL_KEY, REGISTRY_PROTOCOL)
                    .addParameterAndEncoded(REFER_KEY, StringUtils.toQueryString(map))
                    .build();
        }
        return null;
    }

    // 添加一些运行时的参数信息，比如：
    // dubbo -> dubbo 协议版本
    // release -> dubbo 版本
    // timestamp -> 运行的时间戳
    // 进程 id 号
    static void appendRuntimeParameters(Map<String, String> map) {
        map.put(DUBBO_VERSION_KEY, Version.getProtocolVersion());
        map.put(RELEASE_KEY, Version.getVersion());
        map.put(TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            map.put(PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
    }

    private URL loadMetadataReporterURL() {
        String address = metadataReportConfig.getAddress();
        if (StringUtils.isEmpty(address)) {
            return null;
        }
        Map<String, String> map = new HashMap<String, String>();
        appendParameters(map, metadataReportConfig);
        return UrlUtils.parseURL(address, map);
    }

    protected MetadataReportService getMetadataReportService() {

        if (metadataReportConfig == null || !metadataReportConfig.isValid()) {
            return null;
        }
        return MetadataReportService.instance(this::loadMetadataReporterURL);
    }

    /**
     * 通过 interfaceClass 对 methods 的相关属性进行设置，同时检查 methods 是否在 interfaceClass 存在相应的方法
     * Check whether the remote service interface and the methods meet with Dubbo's requirements.it mainly check, if the
     * methods configured in the configuration file are included in the interface of remote service
     *
     * @param interfaceClass the interface of remote service
     * @param methods        the methods configured
     */
    protected void checkInterfaceAndMethods(Class<?> interfaceClass, List<MethodConfig> methods) {
        // interface cannot be null
        Assert.notNull(interfaceClass, new IllegalStateException("interface not allow null!"));

        // to verify interfaceClass is an interface
        if (!interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        // check if methods exist in the remote service interface
        if (CollectionUtils.isNotEmpty(methods)) {
            for (MethodConfig methodBean : methods) {
                // 依据 interfaceClass 对 methods 相关的属性进行设置
                methodBean.setService(interfaceClass.getName());
                methodBean.setServiceId(this.getId());
                methodBean.refresh();
                String methodName = methodBean.getName();
                // MethodConfig 必须要有名字
                if (StringUtils.isEmpty(methodName)) {
                    throw new IllegalStateException("<dubbo:method> name attribute is required! Please check: " +
                            "<dubbo:service interface=\"" + interfaceClass.getName() + "\" ... >" +
                            "<dubbo:method name=\"\" ... /></<dubbo:reference>");
                }

                // 定义的 method 标签生成的 MethodConfig 类的方法必须在 interfaceClass 中存在
                boolean hasMethod = Arrays.stream(interfaceClass.getMethods()).anyMatch(method -> method.getName().equals(methodName));
                if (!hasMethod) {
                    throw new IllegalStateException("The interface " + interfaceClass.getName()
                            + " not found method " + methodName);
                }
            }
        }
    }

    /**
     * Legitimacy check and setup of local simulated operations. The operations can be a string with Simple operation or
     * a classname whose {@link Class} implements a particular function
     *
     * @param interfaceClass for provider side, it is the {@link Class} of the service that will be exported; for consumer
     *                       side, it is the {@link Class} of the remote service interface that will be referenced
     */
    void checkMock(Class<?> interfaceClass) {
        if (ConfigUtils.isEmpty(mock)) {
            return;
        }

        // 规范化 mock
        String normalizedMock = MockInvoker.normalizeMock(mock);
        if (normalizedMock.startsWith(RETURN_PREFIX)) {
            // returnxxxx -> xxxx
            normalizedMock = normalizedMock.substring(RETURN_PREFIX.length()).trim();
            try {
                //Check whether the mock value is legal, if it is illegal, throw exception
                MockInvoker.parseMockValue(normalizedMock);
            } catch (Exception e) {
                throw new IllegalStateException("Illegal mock return in <dubbo:service/reference ... " +
                        "mock=\"" + mock + "\" />");
            }
        } else if (normalizedMock.startsWith(THROW_PREFIX)) {
            // throwxxxx -> xxxx
            normalizedMock = normalizedMock.substring(THROW_PREFIX.length()).trim();
            if (ConfigUtils.isNotEmpty(normalizedMock)) {
                try {
                    //Check whether the mock value is legal
                    MockInvoker.getThrowable(normalizedMock);
                } catch (Exception e) {
                    throw new IllegalStateException("Illegal mock throw in <dubbo:service/reference ... " +
                            "mock=\"" + mock + "\" />");
                }
            }
        } else {
            //Check whether the mock class is a implementation of the interfaceClass, and if it has a default constructor
            MockInvoker.getMockObject(normalizedMock, interfaceClass);
        }
    }

    /**
     * Legitimacy check of stub, note that: the local will deprecated, and replace with <code>stub</code>
     * 检查是否有 local 和 stub 属性，如果有且为 true 或者 default，那么需要保证 interfaceClass 对应的 local 和 stub 类存在且为子类或者一致
     *
     * @param interfaceClass for provider side, it is the {@link Class} of the service that will be exported; for consumer
     *                       side, it is the {@link Class} of the remote service interface
     */
    void checkStubAndLocal(Class<?> interfaceClass) {
        if (ConfigUtils.isNotEmpty(local)) {
            // 如果 ServiceBean 的 local 属性为 default 或者为 true，不论那种情况，都是获取的 xxxLocal 实例
            Class<?> localClass = ConfigUtils.isDefault(local) ?
                    ReflectUtils.forName(interfaceClass.getName() + "Local") : ReflectUtils.forName(local);
            verify(interfaceClass, localClass);
        }
        // 同上
        if (ConfigUtils.isNotEmpty(stub)) {
            Class<?> localClass = ConfigUtils.isDefault(stub) ?
                    ReflectUtils.forName(interfaceClass.getName() + "Stub") : ReflectUtils.forName(stub);
            verify(interfaceClass, localClass);
        }
    }

    private void verify(Class<?> interfaceClass, Class<?> localClass) {
        if (!interfaceClass.isAssignableFrom(localClass)) {
            throw new IllegalStateException("The local implementation class " + localClass.getName() +
                    " not implement interface " + interfaceClass.getName());
        }

        try {
            //Check if the localClass a constructor with parameter who's type is interfaceClass
            // localClass 必须要有 interfaceClass 类型的构造函数
            ReflectUtils.findConstructor(localClass, interfaceClass);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("No such constructor \"public " + localClass.getSimpleName() +
                    "(" + interfaceClass.getName() + ")\" in local implementation class " + localClass.getName());
        }
    }

    // 整体优先级是先使用已存在的 registries，没有的话就根据 registriesIds，还是没有就使用 ConfigManager 中的 registries，最后才是新建一个
    private void convertRegistryIdsToRegistries() {
        // 从环境中获取 registryIds 相关的属性值
        if (StringUtils.isEmpty(registryIds) && CollectionUtils.isEmpty(registries)) {
            Set<String> configedRegistries = new HashSet<>();
            configedRegistries.addAll(getSubProperties(Environment.getInstance().getExternalConfigurationMap(),
                    REGISTRIES_SUFFIX));
            configedRegistries.addAll(getSubProperties(Environment.getInstance().getAppExternalConfigurationMap(),
                    REGISTRIES_SUFFIX));
            // 将获取的属性值拼接成 registryIds
            registryIds = String.join(COMMA_SEPARATOR, configedRegistries);
        }

        if (StringUtils.isEmpty(registryIds)) {
            if (CollectionUtils.isEmpty(registries)) {
                // 尝试从 ConfigManager 获取 registries 或者是直接新建 registries
                setRegistries(
                        ConfigManager.getInstance().getDefaultRegistries()
                                .filter(CollectionUtils::isNotEmpty)
                                .orElseGet(() -> {
                                    RegistryConfig registryConfig = new RegistryConfig();
                                    registryConfig.refresh();
                                    return Arrays.asList(registryConfig);
                                })
                );
            }
        } else {
            // 根据 registryIds 获取相应的 registries 并进行注册
            String[] ids = COMMA_SPLIT_PATTERN.split(registryIds);
            List<RegistryConfig> tmpRegistries = CollectionUtils.isNotEmpty(registries) ? registries : new ArrayList<>();
            Arrays.stream(ids).forEach(id -> {
                if (tmpRegistries.stream().noneMatch(reg -> reg.getId().equals(id))) {
                    tmpRegistries.add(ConfigManager.getInstance().getRegistry(id).orElseGet(() -> {
                        RegistryConfig registryConfig = new RegistryConfig();
                        registryConfig.setId(id);
                        registryConfig.refresh();
                        return registryConfig;
                    }));
                }
            });

            if (tmpRegistries.size() > ids.length) {
                throw new IllegalStateException("Too much registries found, the registries assigned to this service " +
                        "are :" + registryIds + ", but got " + tmpRegistries.size() + " registries!");
            }

            setRegistries(tmpRegistries);
        }

    }

    // 用于将环境属性 dubbo.registry.address 转换为 registries 并添加到 ServiceBean 的属性中
    private void loadRegistriesFromBackwardConfig() {
        // for backward compatibility
        // -Ddubbo.registry.address is now deprecated.
        if (registries == null || registries.isEmpty()) {
            String address = ConfigUtils.getProperty("dubbo.registry.address");
            if (address != null && address.length() > 0) {
                List<RegistryConfig> tmpRegistries = new ArrayList<RegistryConfig>();
                // 对 dubbo.registry.address 进行切割
                String[] as = address.split("\\s*[|]+\\s*");
                for (String a : as) {
                    // 每个地址对应一个 RegistryConfig
                    RegistryConfig registryConfig = new RegistryConfig();
                    registryConfig.setAddress(a);
                    registryConfig.refresh();
                    tmpRegistries.add(registryConfig);
                }
                // 添加到 ServiceBean 的属性中
                setRegistries(tmpRegistries);
            }
        }
    }

    /**
     * For compatibility purpose, use registry as the default config center if the registry protocol is zookeeper and
     * there's no config center specified explicitly.
     */
    // 找到 registries 中第一个使用 zookeeper 协议的，将此 registry 的协议和地址填充到 ConfigCenterConfig 中，然后将这个配置中心填充到当前 reference 实例中，启动配置中心
    // 启动配置中心核心就是获取 ZookeeperDynamicConfiguration，然后还会根据 configCenter 和 application 的相关属性从 zookeeper 的对应路径下获取配置信息，并更新到四种配置项中，最后刷新各种 ConfigBean
    private void useRegistryForConfigIfNecessary() {
        // 从所有的 registries 中查找第一个使用 zookeeper 协议的，如果找到了，就获取 ConfigCenterConfig，并将该 registry 的相关信息添加到 ConfigCenterConfig 中
        registries.stream().filter(RegistryConfig::isZookeeperProtocol).findFirst().ifPresent(rc -> {
            // we use the loading status of DynamicConfiguration to decide whether ConfigCenter has been initiated.
            // 这里应该是说，如果从 Environment 中获取到了 DynamicConfiguration，便不会再执行 orElseGet 方法中的函数了
            Environment.getInstance().getDynamicConfiguration().orElseGet(() -> {
                ConfigManager configManager = ConfigManager.getInstance();
                ConfigCenterConfig cc = configManager.getConfigCenter().orElse(new ConfigCenterConfig());
                // 将该 registry 的相关信息添加到 ConfigCenterConfig 中
                cc.setProtocol(rc.getProtocol());
                cc.setAddress(rc.getAddress());
                cc.setHighestPriority(false);
                // 将配置中心分别保存到 ConfigManager 和 ServiceBean 中各一份
                setConfigCenter(cc);
                // 核心就是获取 ZookeeperDynamicConfiguration，然后还会根据 configCenter 和 application 的相关属性从 zookeeper 的对应路径下获取配置信息，并更新到四种配置项中，最后刷新各种 ConfigBean
                startConfigCenter();
                return null;
            });
        });
    }

    /**
     * @return local
     * @deprecated Replace to <code>getStub()</code>
     */
    @Deprecated
    public String getLocal() {
        return local;
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(Boolean)</code>
     */
    @Deprecated
    public void setLocal(Boolean local) {
        if (local == null) {
            setLocal((String) null);
        } else {
            setLocal(local.toString());
        }
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(String)</code>
     */
    @Deprecated
    public void setLocal(String local) {
        checkName(LOCAL_KEY, local);
        this.local = local;
    }

    public String getStub() {
        return stub;
    }

    public void setStub(Boolean stub) {
        if (stub == null) {
            setStub((String) null);
        } else {
            setStub(stub.toString());
        }
    }

    public void setStub(String stub) {
        checkName("stub", stub);
        this.stub = stub;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        checkExtension(Cluster.class, CLUSTER_KEY, cluster);
        this.cluster = cluster;
    }

    public String getProxy() {
        return proxy;
    }

    public void setProxy(String proxy) {
        checkExtension(ProxyFactory.class, PROXY_KEY, proxy);
        this.proxy = proxy;
    }

    public Integer getConnections() {
        return connections;
    }

    public void setConnections(Integer connections) {
        this.connections = connections;
    }

    @Parameter(key = REFERENCE_FILTER_KEY, append = true)
    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        checkMultiExtension(Filter.class, FILE_KEY, filter);
        this.filter = filter;
    }

    @Parameter(key = INVOKER_LISTENER_KEY, append = true)
    public String getListener() {
        return listener;
    }

    public void setListener(String listener) {
        checkMultiExtension(InvokerListener.class, LISTENER_KEY, listener);
        this.listener = listener;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        checkNameHasSymbol(LAYER_KEY, layer);
        this.layer = layer;
    }

    public ApplicationConfig getApplication() {
        return application;
    }

    // 将 application 填充到 ConfigManager 和 ServiceBean 中各一份
    public void setApplication(ApplicationConfig application) {
        ConfigManager.getInstance().setApplication(application);
        this.application = application;
    }

    private void createApplicationIfAbsent() {
        if (this.application != null) {
            return;
        }
        // 没有 application 属性，就尝试从 ConfigManager 中获取，再没有，就新建并刷新属性
        ConfigManager configManager = ConfigManager.getInstance();
        setApplication(
                configManager
                        .getApplication()
                        .orElseGet(() -> {
                            ApplicationConfig applicationConfig = new ApplicationConfig();
                            applicationConfig.refresh();
                            return applicationConfig;
                        })
        );
    }

    public ModuleConfig getModule() {
        return module;
    }

    public void setModule(ModuleConfig module) {
        ConfigManager.getInstance().setModule(module);
        this.module = module;
    }

    public RegistryConfig getRegistry() {
        return CollectionUtils.isEmpty(registries) ? null : registries.get(0);
    }

    public void setRegistry(RegistryConfig registry) {
        List<RegistryConfig> registries = new ArrayList<RegistryConfig>(1);
        registries.add(registry);
        setRegistries(registries);
    }

    public List<RegistryConfig> getRegistries() {
        return registries;
    }

    /**
     * 在 ConfigManager 和 ServiceBean 中各保留一份 registries
     *
     * @param registries
     */
    @SuppressWarnings({"unchecked"})
    public void setRegistries(List<? extends RegistryConfig> registries) {
        // 单例模式获取 ConfigManager 实例，将 registries 添加到其中
        ConfigManager.getInstance().addRegistries((List<RegistryConfig>) registries);
        // 在 ServiceBean（当前 bean） 中也保留一份
        this.registries = (List<RegistryConfig>) registries;
    }

    @Parameter(excluded = true)
    public String getRegistryIds() {
        return registryIds;
    }

    public void setRegistryIds(String registryIds) {
        this.registryIds = registryIds;
    }

    public MonitorConfig getMonitor() {
        return monitor;
    }

    public void setMonitor(String monitor) {
        setMonitor(new MonitorConfig(monitor));
    }

    /**
     * 在 ConfigManager 和 ServiceBean 中各保留一份 monitorConfig
     *
     * @param monitor
     */
    public void setMonitor(MonitorConfig monitor) {
        // ConfigManager 中保存一份 monitor
        ConfigManager.getInstance().setMonitor(monitor);
        // ServiceBean 中也保留一份 monitor
        this.monitor = monitor;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        checkMultiName("owner", owner);
        this.owner = owner;
    }

    public ConfigCenterConfig getConfigCenter() {
        return configCenter;
    }

    // 将配置中心分别保存到 ConfigManager 和 ServiceBean 中各一份
    public void setConfigCenter(ConfigCenterConfig configCenter) {
        ConfigManager.getInstance().setConfigCenter(configCenter);
        this.configCenter = configCenter;
    }

    public Integer getCallbacks() {
        return callbacks;
    }

    public void setCallbacks(Integer callbacks) {
        this.callbacks = callbacks;
    }

    public String getOnconnect() {
        return onconnect;
    }

    public void setOnconnect(String onconnect) {
        this.onconnect = onconnect;
    }

    public String getOndisconnect() {
        return ondisconnect;
    }

    public void setOndisconnect(String ondisconnect) {
        this.ondisconnect = ondisconnect;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public MetadataReportConfig getMetadataReportConfig() {
        return metadataReportConfig;
    }

    public void setMetadataReportConfig(MetadataReportConfig metadataReportConfig) {
        this.metadataReportConfig = metadataReportConfig;
    }

    public MetricsConfig getMetrics() {
        return metrics;
    }

    public void setMetrics(MetricsConfig metrics) {
        this.metrics = metrics;
    }

    @Parameter(key = TAG_KEY, useKeyAsProperty = false)
    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }
}
