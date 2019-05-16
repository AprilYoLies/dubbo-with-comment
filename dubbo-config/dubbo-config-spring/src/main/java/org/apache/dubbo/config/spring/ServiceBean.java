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
package org.apache.dubbo.config.spring;

import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConfigCenterConfig;
import org.apache.dubbo.config.MetadataReportConfig;
import org.apache.dubbo.config.MetricsConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.spring.context.event.ServiceBeanExportedEvent;
import org.apache.dubbo.config.spring.extension.SpringExtensionFactory;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.config.spring.util.BeanFactoryUtils.addApplicationListener;

/**
 * ServiceFactoryBean
 *
 * @export
 */
public class ServiceBean<T> extends ServiceConfig<T> implements InitializingBean, DisposableBean,
        ApplicationContextAware, ApplicationListener<ContextRefreshedEvent>, BeanNameAware,
        ApplicationEventPublisherAware {


    private static final long serialVersionUID = 213195494150089726L;

    private final transient Service service;

    private transient ApplicationContext applicationContext;

    private transient String beanName;

    private transient boolean supportedApplicationListener;

    private ApplicationEventPublisher applicationEventPublisher;

    public ServiceBean() {
        super();
        this.service = null;
    }

    public ServiceBean(Service service) {
        super(service);
        this.service = service;
    }

    // 实现 ApplicationContextAware 接口的方法，用于获取 spring application 上下文环境，同时注册了一个用于取消钩子函数的监听器和当前类所代表的监听器
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        // 将 applicationContext 保存到字段中
        this.applicationContext = applicationContext;
        // 保存 ApplicationContext，注册关闭钩子函数，取消在 dubbo 上的关闭钩子函数，同时向 spring 容器添加一个监听器(用于取消一些钩子函数)
        SpringExtensionFactory.addApplicationContext(applicationContext);
        // 通过反射的方式向 applicationContext 注册监听器
        supportedApplicationListener = addApplicationListener(applicationContext, this);
    }

    // 实现 BeanNameAware 接口的方法，用于获取当前 bean 的名字
    @Override
    public void setBeanName(String name) {
        this.beanName = name;
    }

    /**
     * Gets associated {@link Service}
     *
     * @return associated {@link Service}
     */
    public Service getService() {
        return service;
    }

    /**
     * 实现 ApplicationListener 接口的方法
     *
     * @param event spring 上下文环境刷新事件
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (!isExported() && !isUnexported()) {
            if (logger.isInfoEnabled()) {
                logger.info("The service ready on spring started. service: " + getInterface());
            }
            export();
        }
    }

    /**
     * 实现 InitializingBean 接口的方法,在 spring 管理的 bean 被初始化后，会调用此方法
     *
     * @throws Exception
     */
    @Override
    @SuppressWarnings({"unchecked", "deprecation"})
    public void afterPropertiesSet() throws Exception {
        // 获取 provider 属性，根据标签解析的规则，即便 service 标签有 provider 属性，也不会获取到 provider 的属性，因为向 beanDefinition 注册的是 providerIds 键
        if (getProvider() == null) {
            // applicationContext 不为空，通过实现 ApplicationContextAware 接口进行获取并保存
            // 通过 spring 提供的 BeanFactoryUtils 工具类，来获取已经解析过的 ProviderConfig 类实例，这里只有在存在 provider 标签的情况下，才会有 ProviderConfig 实例
            Map<String, ProviderConfig> providerConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProviderConfig.class, false, false);
            if (providerConfigMap != null && providerConfigMap.size() > 0) {
                // 通过 spring 提供的 BeanFactoryUtils 工具类，来获取已经解析过的 ProtocolConfig 类实例，这里只有在存在 protocol 标签的情况下，才会有 ProtocolConfig 实例
                Map<String, ProtocolConfig> protocolConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProtocolConfig.class, false, false);
                // 没有 ProtocolConfig 但是有 ProviderConfig
                if (CollectionUtils.isEmptyMap(protocolConfigMap)
                        && providerConfigMap.size() > 1) { // backward compatibility
                    List<ProviderConfig> providerConfigs = new ArrayList<ProviderConfig>();
                    // 获取默认的 ProviderConfig 保存到 providerConfigs
                    for (ProviderConfig config : providerConfigMap.values()) {
                        if (config.isDefault() != null && config.isDefault()) {
                            providerConfigs.add(config);
                        }
                    }
                    if (!providerConfigs.isEmpty()) {
                        // 存在默认 ProviderConfig，就将 ProviderConfig 转换为 ProtocolConfig 并保存
                        setProviders(providerConfigs);
                    }
                } else {
                    // 有 ProtocolConfig 同时也有 ProviderConfig，providerConfig 记录默认 ProviderConfig
                    ProviderConfig providerConfig = null;
                    for (ProviderConfig config : providerConfigMap.values()) {
                        // 为空或者为 true 都是说明是 default
                        if (config.isDefault() == null || config.isDefault()) {
                            // 默认 ProviderConfig 只能有一个
                            if (providerConfig != null) {
                                throw new IllegalStateException("Duplicate provider configs: " + providerConfig + " and " + config);
                            }
                            providerConfig = config;
                        }
                    }
                    if (providerConfig != null) {
                        // 保存默认 ProviderConfig
                        // 分别保存 ProviderConfig 到 ConfigManager 和 ServiceConfig 中去
                        setProvider(providerConfig);
                    }
                }
            }
        }
        // ServiceBean 没有 application 属性，并且没有 provider 属性，或者 provider 属性的 application 属性为空
        if (getApplication() == null
                && (getProvider() == null || getProvider().getApplication() == null)) {
            // 通过 spring 提供的 BeanFactoryUtils 工具类，来获取已经解析过的 ApplicationConfig 类实例，这里只有在存在 application 标签的情况下，才会有 ApplicationConfig 实例
            Map<String, ApplicationConfig> applicationConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ApplicationConfig.class, false, false);
            if (applicationConfigMap != null && applicationConfigMap.size() > 0) {
                ApplicationConfig applicationConfig = null;
                // 遍历获取的 ApplicationConfig 集合
                for (ApplicationConfig config : applicationConfigMap.values()) {
                    // application 标签只能有一个
                    if (applicationConfig != null) {
                        throw new IllegalStateException("Duplicate application configs: " + applicationConfig + " and " + config);
                    }
                    applicationConfig = config;
                }
                // 如果获取到了，就将其设置到 ServiceBean 中去
                if (applicationConfig != null) {
                    setApplication(applicationConfig);
                }
            }
        }
        // ServiceBean 没有 module 属性，并且没有 provider 属性，或者 provider 属性的 module 属性为空
        if (getModule() == null
                && (getProvider() == null || getProvider().getModule() == null)) {
            // 通过 spring 提供的 BeanFactoryUtils 工具类，来获取已经解析过的 ModuleConfig 类实例，这里只有在存在 module 标签的情况下，才会有 ModuleConfig 实例
            Map<String, ModuleConfig> moduleConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ModuleConfig.class, false, false);
            if (moduleConfigMap != null && moduleConfigMap.size() > 0) {
                ModuleConfig moduleConfig = null;
                for (ModuleConfig config : moduleConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault()) {
                        // module 标签只能有一个
                        if (moduleConfig != null) {
                            throw new IllegalStateException("Duplicate module configs: " + moduleConfig + " and " + config);
                        }
                        moduleConfig = config;
                    }
                }
                if (moduleConfig != null) {
                    // 如果获取到了，就将其设置到 ServiceBean 中去
                    setModule(moduleConfig);
                }
            }
        }

        // 如果 service 标签没有设置 registry 属性，这里就会为空，那么就将 application 或者 provider 标签中的 registry 属性拿过来
        if (StringUtils.isEmpty(getRegistryIds())) {
            // 如果这里成立，则表示定义了 application 标签，并且标签中含有 registry 属性
            if (getApplication() != null && StringUtils.isNotEmpty(getApplication().getRegistryIds())) {
                // 将 application 标签中的 registry 属性填充到 ServiceBean 中
                setRegistryIds(getApplication().getRegistryIds());
            }
            // 如果定义了 provider 标签，并且标签中有 registry 属性
            if (getProvider() != null && StringUtils.isNotEmpty(getProvider().getRegistryIds())) {
                // 将 registry 属性填充到 ServiceBean 中
                setRegistryIds(getProvider().getRegistryIds());
            }
        }

        // ServiceBean、provider 或者 application 中都没有 registry 属性
        if ((CollectionUtils.isEmpty(getRegistries()))
                && (getProvider() == null || CollectionUtils.isEmpty(getProvider().getRegistries()))
                && (getApplication() == null || CollectionUtils.isEmpty(getApplication().getRegistries()))) {
            // 通过 spring 提供的 BeanFactoryUtils 工具类，来获取已经解析过的 RegistryConfig 类实例，这里只有在存在 registry 标签的情况下，才会有 RegistryConfig 实例
            Map<String, RegistryConfig> registryConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, RegistryConfig.class, false, false);
            if (CollectionUtils.isNotEmptyMap(registryConfigMap)) {
                List<RegistryConfig> registryConfigs = new ArrayList<>();
                if (StringUtils.isNotEmpty(registryIds)) {
                    // 根据配置的 registryIds 获取相应的 registry，然后将其添加到 registryConfigs
                    Arrays.stream(COMMA_SPLIT_PATTERN.split(registryIds)).forEach(id -> {
                        if (registryConfigMap.containsKey(id)) {
                            registryConfigs.add(registryConfigMap.get(id));
                        }
                    });
                }

                // 上边是根据 registryIds 添加 registry，这里是在 registryIds 为空的情况下自行添加 registry
                if (registryConfigs.isEmpty()) {
                    for (RegistryConfig config : registryConfigMap.values()) {
                        if (StringUtils.isEmpty(registryIds)) {
                            registryConfigs.add(config);
                        }
                    }
                }
                if (!registryConfigs.isEmpty()) {
                    // 在 ConfigManager 和 ServiceBean 中各保留一份 registries
                    super.setRegistries(registryConfigs);
                }
            }
        }
        if (getMetadataReportConfig() == null) {
            // 通过 spring 提供的 BeanFactoryUtils 工具类，来获取已经解析过的 MetadataReportConfig 类实例，这里只有在存在 metadata-report 标签的情况下，才会有 MetadataReportConfig 实例
            Map<String, MetadataReportConfig> metadataReportConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, MetadataReportConfig.class, false, false);
            if (metadataReportConfigMap != null && metadataReportConfigMap.size() == 1) {
                // 将获取的这个唯一的 MetadataReportConfig 添加到 ServiceBean 中去
                super.setMetadataReportConfig(metadataReportConfigMap.values().iterator().next());
            } else if (metadataReportConfigMap != null && metadataReportConfigMap.size() > 1) {
                // 说明 metadata-report 标签只允许存在一个
                throw new IllegalStateException("Multiple MetadataReport configs: " + metadataReportConfigMap);
            }
        }

        if (getConfigCenter() == null) {
            // 通过 spring 提供的 ConfigCenterConfig 工具类，来获取已经解析过的 MetadataReportConfig 类实例，这里只有在存在 config-center 标签的情况下，才会有 ConfigCenterConfig 实例
            Map<String, ConfigCenterConfig> configenterMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ConfigCenterConfig.class, false, false);
            if (configenterMap != null && configenterMap.size() == 1) {
                // 将获取的这个唯一的 ConfigCenterConfig 添加到 ServiceBean 中去
                super.setConfigCenter(configenterMap.values().iterator().next());
            } else if (configenterMap != null && configenterMap.size() > 1) {
                // 说明 config-center 标签只允许存在一个
                throw new IllegalStateException("Multiple ConfigCenter found:" + configenterMap);
            }
        }

        // ServiceBean、provider 或者 application 中都没有 monitor 属性
        if (getMonitor() == null
                && (getProvider() == null || getProvider().getMonitor() == null)
                && (getApplication() == null || getApplication().getMonitor() == null)) {
            // 通过 spring 提供的 MonitorConfig 工具类，来获取已经解析过的 MetadataReportConfig 类实例，这里只有在存在 monitor 标签的情况下，才会有 MonitorConfig 实例
            Map<String, MonitorConfig> monitorConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, MonitorConfig.class, false, false);
            if (monitorConfigMap != null && monitorConfigMap.size() > 0) {
                MonitorConfig monitorConfig = null;
                for (MonitorConfig config : monitorConfigMap.values()) {
                    // 如果 MonitorConfig 的 isDefault 为空或者 true，都代表是默认
                    if (config.isDefault() == null || config.isDefault()) {
                        if (monitorConfig != null) {
                            // 默认的 MonitorConfig 只能有一个
                            throw new IllegalStateException("Duplicate monitor configs: " + monitorConfig + " and " + config);
                        }
                        monitorConfig = config;
                    }
                }
                if (monitorConfig != null) {
                    // 在 ConfigManager 和 ServiceBean 中各保留一份 monitorConfig
                    setMonitor(monitorConfig);
                }
            }
        }

        if (getMetrics() == null) {
            // 通过 spring 提供的 MonitorConfig 工具类，来获取已经解析过的 MetricsConfig 类实例，这里只有在存在 metrics 标签的情况下，才会有 MetricsConfig 实例
            Map<String, MetricsConfig> metricsConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, MetricsConfig.class, false, false);
            if (metricsConfigMap != null && metricsConfigMap.size() > 0) {
                MetricsConfig metricsConfig = null;
                for (MetricsConfig config : metricsConfigMap.values()) {
                    // 说明 metrics 标签只允许存在一个
                    if (metricsConfig != null) {
                        throw new IllegalStateException("Duplicate metrics configs: " + metricsConfig + " and " + config);
                    }
                    metricsConfig = config;
                }
                if (metricsConfig != null) {
                    // 将 MetricsConfig 保存到 ServiceBean
                    setMetrics(metricsConfig);
                }
            }
        }

        // 如果 service 标签没有 protocol 属性，但是存在 provider 标签，且其中有 protocol 属性，那么
        // ServiceBean 就直接使用 provider 的 protocol 属性
        if (StringUtils.isEmpty(getProtocolIds())) {
            if (getProvider() != null && StringUtils.isNotEmpty(getProvider().getProtocolIds())) {
                setProtocolIds(getProvider().getProtocolIds());
            }
        }

        if (CollectionUtils.isEmpty(getProtocols())
                && (getProvider() == null || CollectionUtils.isEmpty(getProvider().getProtocols()))) {
            // 通过 spring 提供的 BeanFactoryUtils 工具类，来获取已经解析过的 ProtocolConfig 类实例，这里只有在存在 protocol 标签的情况下，才会有 ProtocolConfig 实例
            Map<String, ProtocolConfig> protocolConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProtocolConfig.class, false, false);
            if (protocolConfigMap != null && protocolConfigMap.size() > 0) {
                List<ProtocolConfig> protocolConfigs = new ArrayList<ProtocolConfig>();
                if (StringUtils.isNotEmpty(getProtocolIds())) {
                    // 同样的套路，根据 protocolIds 来获取相应的 ProtocolConfig
                    Arrays.stream(COMMA_SPLIT_PATTERN.split(getProtocolIds()))
                            .forEach(id -> {
                                if (protocolConfigMap.containsKey(id)) {
                                    protocolConfigs.add(protocolConfigMap.get(id));
                                }
                            });
                }

                // 不同于上边的过程，这里是根据 protocolIds 属性是否为空来添加相应的 ProtocolConfig
                if (protocolConfigs.isEmpty()) {
                    for (ProtocolConfig config : protocolConfigMap.values()) {
                        if (StringUtils.isEmpty(protocolIds)) {
                            protocolConfigs.add(config);
                        }
                    }
                }

                if (!protocolConfigs.isEmpty()) {
                    // 将 ProtocolConfig 在 ConfigManager 和 ServiceBean 中各保存一份
                    super.setProtocols(protocolConfigs);
                }
            }
        }
        // 如果 service 标签中没有 path 属性
        if (StringUtils.isEmpty(getPath())) {
            // 如果通过 BeanNameAware 接口方法获取到了 beanName，service 标签有 interface 属性，且属性值是 beanName 的前半部分
            if (StringUtils.isNotEmpty(beanName)
                    && StringUtils.isNotEmpty(getInterface())
                    && beanName.startsWith(getInterface())) {
                // 那么就用 beanName（org.apache.dubbo.demo.DemoService） 作为 ServiceBean 的 path 属性值
                setPath(beanName);
            }
        }
        if (!supportedApplicationListener) {
            export();
        }
    }

    /**
     * Get the name of {@link ServiceBean}
     *
     * @return {@link ServiceBean}'s name
     * @since 2.6.5
     */
    public String getBeanName() {
        return this.beanName;
    }

    /**
     * @since 2.6.5
     */
    @Override
    public void export() {
        super.export();
        // Publish ServiceBeanExportedEvent
        publishExportEvent();
    }

    /**
     * @since 2.6.5
     */
    private void publishExportEvent() {
        ServiceBeanExportedEvent exportEvent = new ServiceBeanExportedEvent(this);
        applicationEventPublisher.publishEvent(exportEvent);
    }

    @Override
    public void destroy() throws Exception {
        // no need to call unexport() here, see
        // org.apache.dubbo.config.spring.extension.SpringExtensionFactory.ShutdownHookListener
    }

    // merged from dubbox
    @Override
    protected Class getServiceClass(T ref) {
        if (AopUtils.isAopProxy(ref)) {
            return AopUtils.getTargetClass(ref);
        }
        return super.getServiceClass(ref);
    }

    /**
     * 实现 ApplicationEventPublisherAware 接口的方法，用于获取 application 事件发布器
     *
     * @param applicationEventPublisher
     * @since 2.6.5
     */
    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }
}
