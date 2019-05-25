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
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.MetadataReportConfig;
import org.apache.dubbo.config.MetricsConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.spring.extension.SpringExtensionFactory;
import org.apache.dubbo.config.support.Parameter;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;

/**
 * ReferenceFactoryBean
 */
public class ReferenceBean<T> extends ReferenceConfig<T> implements FactoryBean, ApplicationContextAware, InitializingBean, DisposableBean {

    private static final long serialVersionUID = 213195494150089726L;

    private transient ApplicationContext applicationContext;

    public ReferenceBean() {
        super();
    }

    public ReferenceBean(Reference reference) {
        super(reference);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {  // ApplicationContextAware 接口的实现方法
        this.applicationContext = applicationContext;   // 获取 ApplicationContext 保存到 SpringExtensionFactory 中
        SpringExtensionFactory.addApplicationContext(applicationContext);
    }

    @Override
    public Object getObject() {
        return get();
    }

    @Override
    public Class<?> getObjectType() {
        return getInterfaceClass();
    }

    @Override
    @Parameter(excluded = true)
    public boolean isSingleton() {
        return true;
    }

    @Override
    // 主要是对当前 bean 的部分属性进行补充，方式主要是从 spring 容器的对应属性 bean，当前 bean 的 protocol 或者 application 属性中进行获取
    public void afterPropertiesSet() throws Exception {
        if (applicationContext != null) {   // 已通过实现 ApplicationContextAware 接口获取到 applicationContext
            BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ConfigCenterBean.class, false, false);
        }

        if (getConsumer() == null) {
            Map<String, ConsumerConfig> consumerConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ConsumerConfig.class, false, false);
            if (consumerConfigMap != null && consumerConfigMap.size() > 0) {    // 如果有 ConsumerConfig 对应的类或者其父类
                ConsumerConfig consumerConfig = null;
                for (ConsumerConfig config : consumerConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault()) {
                        if (consumerConfig != null) {   // default consumer 有且只能有一个
                            throw new IllegalStateException("Duplicate consumer configs: " + consumerConfig + " and " + config);
                        }
                        consumerConfig = config;
                    }
                }
                if (consumerConfig != null) {
                    setConsumer(consumerConfig);    // 为 ReferenceBean 设置 consumer 属性
                }
            }
        }
        if (getApplication() == null
                && (getConsumer() == null || getConsumer().getApplication() == null)) { // 也就是说 consumer 中的 application 也能作为当前 bean 的 application 属性
            Map<String, ApplicationConfig> applicationConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ApplicationConfig.class, false, false);
            if (applicationConfigMap != null && applicationConfigMap.size() > 0) {
                ApplicationConfig applicationConfig = null;
                for (ApplicationConfig config : applicationConfigMap.values()) {
                    if (applicationConfig != null) {    // default ApplicationConfig 有且只能有一个
                        throw new IllegalStateException("Duplicate application configs: " + applicationConfig + " and " + config);
                    }
                    applicationConfig = config;
                }
                if (applicationConfig != null) {
                    setApplication(applicationConfig);  // 这里是从 spring 容器中获取 ApplicationConfig 实例，然后填充到当前 bean 中
                }
            }
        }
        if (getModule() == null
                && (getConsumer() == null || getConsumer().getModule() == null)) { // 也就是说 consumer 中的 module 也能作为当前 bean 的 module 属性
            Map<String, ModuleConfig> moduleConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ModuleConfig.class, false, false);
            if (moduleConfigMap != null && moduleConfigMap.size() > 0) {
                ModuleConfig moduleConfig = null;
                for (ModuleConfig config : moduleConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault()) {
                        if (moduleConfig != null) { // default ModuleConfig 有且只能有一个
                            throw new IllegalStateException("Duplicate module configs: " + moduleConfig + " and " + config);
                        }
                        moduleConfig = config;
                    }
                }
                if (moduleConfig != null) {
                    setModule(moduleConfig);    // 这里是从 spring 容器中获取 ModuleConfig 实例，然后填充到当前 bean 中
                }
            }
        }

        if (StringUtils.isEmpty(getRegistryIds())) {    // registryIds 属性为空
            if (getApplication() != null && StringUtils.isNotEmpty(getApplication().getRegistryIds())) {
                setRegistryIds(getApplication().getRegistryIds());  // 尝试用 application 中的 registryIds 属性进行填充
            }   // 这里说明优先 application，然后才是 consumer
            if (getConsumer() != null && StringUtils.isNotEmpty(getConsumer().getRegistryIds())) {
                setRegistryIds(getConsumer().getRegistryIds()); // 尝试用 consumer 中的 registryIds 属性进行填充
            }
        }

        if (CollectionUtils.isEmpty(getRegistries())    // registries 属性为空，consumer 和 application 的 registry 属性均为空
                && (getConsumer() == null || CollectionUtils.isEmpty(getConsumer().getRegistries()))
                && (getApplication() == null || CollectionUtils.isEmpty(getApplication().getRegistries()))) {
            Map<String, RegistryConfig> registryConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, RegistryConfig.class, false, false);
            if (registryConfigMap != null && registryConfigMap.size() > 0) {    // spring 容器中存在 RegistryConfig 实例
                List<RegistryConfig> registryConfigs = new ArrayList<>();
                if (StringUtils.isNotEmpty(registryIds)) {
                    Arrays.stream(COMMA_SPLIT_PATTERN.split(registryIds)).forEach(id -> {
                        if (registryConfigMap.containsKey(id)) {    // 取 spring 容器中实例为 RegistryConfig 且命名存在于 registryIds 中的 bean 添加到 registryConfigs
                            registryConfigs.add(registryConfigMap.get(id));
                        }
                    });
                }

                if (registryConfigs.isEmpty()) {    // 不存在 spring 容器中实例为 RegistryConfig 且命名存在于 registryIds 中的 bean
                    for (RegistryConfig config : registryConfigMap.values()) {
                        if (StringUtils.isEmpty(registryIds)) { // 如果 registryIds 为空，那么就直接添加 spring 容器中的全部 RegistryConfig 实例
                            registryConfigs.add(config);
                        }
                    }
                }
                if (!registryConfigs.isEmpty()) {
                    super.setRegistries(registryConfigs);   // 保存全部获取到的 registry 实例，一个是保存到当前 bean，另外一个是 ConfigManager
                }
            }
        }

        if (getMetadataReportConfig() == null) {    // MetadataReportConfig 属性为空
            Map<String, MetadataReportConfig> metadataReportConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, MetadataReportConfig.class, false, false);
            if (metadataReportConfigMap != null && metadataReportConfigMap.size() == 1) {   // 获取 spring 容器中存在的 MetadataReportConfig 实例，只应该存在一个
                // first elements
                super.setMetadataReportConfig(metadataReportConfigMap.values().iterator().next());  // 将唯一的 MetadataReportConfig 保存到当前 bean 和 ConfigManager 中
            } else if (metadataReportConfigMap != null && metadataReportConfigMap.size() > 1) {
                throw new IllegalStateException("Multiple MetadataReport configs: " + metadataReportConfigMap);
            }
        }

        if (getConfigCenter() == null) {    // ConfigCenter 属性为空
            Map<String, ConfigCenterConfig> configenterMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ConfigCenterConfig.class, false, false);
            if (configenterMap != null && configenterMap.size() == 1) {  // 获取 spring 容器中存在的 ConfigCenter 实例，只应该存在一个
                super.setConfigCenter(configenterMap.values().iterator().next());   // 将唯一的 ConfigCenter 保存到当前 bean 和 ConfigManager 中
            } else if (configenterMap != null && configenterMap.size() > 1) {
                throw new IllegalStateException("Multiple ConfigCenter found:" + configenterMap);
            }
        }

        if (getMonitor() == null    // monitor 属性为空，且 consumer 和 application 中的 monitor 属性也为空
                && (getConsumer() == null || getConsumer().getMonitor() == null)
                && (getApplication() == null || getApplication().getMonitor() == null)) {
            Map<String, MonitorConfig> monitorConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, MonitorConfig.class, false, false);
            if (monitorConfigMap != null && monitorConfigMap.size() > 0) {
                MonitorConfig monitorConfig = null;
                for (MonitorConfig config : monitorConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault()) {
                        if (monitorConfig != null) {    // 默认的 MonitorConfig 只能存在一个
                            throw new IllegalStateException("Duplicate monitor configs: " + monitorConfig + " and " + config);
                        }
                        monitorConfig = config; // 获取 spring 容器中的 MonitorConfig 实例，取唯一的默认项，保存到当前 bean 中
                    }
                }
                if (monitorConfig != null) {
                    setMonitor(monitorConfig);
                }
            }
        }

        if (getMetrics() == null) { // Metrics 属性为空
            Map<String, MetricsConfig> metricsConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, MetricsConfig.class, false, false);
            if (metricsConfigMap != null && metricsConfigMap.size() > 0) {
                MetricsConfig metricsConfig = null;
                for (MetricsConfig config : metricsConfigMap.values()) {
                    if (metricsConfig != null) {    // 默认的 Metrics 只能存在一个
                        throw new IllegalStateException("Duplicate metrics configs: " + metricsConfig + " and " + config);
                    }
                    metricsConfig = config;
                }
                if (metricsConfig != null) {
                    setMetrics(metricsConfig);  // 获取 spring 容器中的 Metrics 实例，取唯一的默认项，保存到当前 bean 中
                }
            }
        }

        if (shouldInit()) {
            getObject();
        }
    }

    @Override
    public void destroy() {
        // do nothing
    }
}
