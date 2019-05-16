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
package org.apache.dubbo.config.context;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConfigCenterConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.RegistryConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;

/**
 * TODO
 * Experimental API, should only being used internally at present.
 * <p>
 * Maybe we can consider open to end user in the following version by providing a fluent style builder.
 *
 * <pre>{@code
 *  public void class DubboBuilder() {
 *
 *      public static DubboBuilder create() {
 *          return new DubboBuilder();
 *      }
 *
 *      public DubboBuilder application(ApplicationConfig application) {
 *          ConfigManager.getInstance().addApplication(application);
 *          return this;
 *      }
 *
 *      ...
 *
 *      public void build() {
 *          // export all ServiceConfigs
 *          // refer all ReferenceConfigs
 *      }
 *  }
 *  }
 * </pre>
 * </p>
 * TODO
 * The properties defined here are duplicate with that in ReferenceConfig/ServiceConfig,
 * the properties here are currently only used for duplication check but are still not being used in the export/refer process yet.
 * Maybe we can remove the property definition in ReferenceConfig/ServiceConfig and only keep the setXxxConfig() as an entrance.
 * All workflow internally can rely on ConfigManager.
 */
public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    private static final ConfigManager CONFIG_MANAGER = new ConfigManager();

    private ApplicationConfig application;
    private MonitorConfig monitor;
    private ModuleConfig module;
    private ConfigCenterConfig configCenter;

    private Map<String, ProtocolConfig> protocols = new ConcurrentHashMap<>();
    private Map<String, RegistryConfig> registries = new ConcurrentHashMap<>();
    private Map<String, ProviderConfig> providers = new ConcurrentHashMap<>();
    private Map<String, ConsumerConfig> consumers = new ConcurrentHashMap<>();

    public static ConfigManager getInstance() {
        return CONFIG_MANAGER;
    }

    private ConfigManager() {

    }

    public Optional<ApplicationConfig> getApplication() {
        return Optional.ofNullable(application);
    }

    public void setApplication(ApplicationConfig application) {
        if (application != null) {
            checkDuplicate(this.application, application);
            this.application = application;
        }
    }

    public Optional<MonitorConfig> getMonitor() {
        return Optional.ofNullable(monitor);
    }

    // 将 monitor 保存到 ConfigManager 中去
    public void setMonitor(MonitorConfig monitor) {
        if (monitor != null) {
            checkDuplicate(this.monitor, monitor);
            this.monitor = monitor;
        }
    }

    public Optional<ModuleConfig> getModule() {
        return Optional.ofNullable(module);
    }

    public void setModule(ModuleConfig module) {
        if (module != null) {
            checkDuplicate(this.module, module);
            this.module = module;
        }
    }

    public Optional<ConfigCenterConfig> getConfigCenter() {
        return Optional.ofNullable(configCenter);
    }

    public void setConfigCenter(ConfigCenterConfig configCenter) {
        if (configCenter != null) {
            checkDuplicate(this.configCenter, configCenter);
            this.configCenter = configCenter;
        }
    }

    public Optional<ProviderConfig> getProvider(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    public Optional<ProviderConfig> getDefaultProvider() {
        return Optional.ofNullable(providers.get(DEFAULT_KEY));
    }

    /**
     * 向 ConfigManager 添加被管理的 config
     *
     * @param providerConfig
     */
    public void addProvider(ProviderConfig providerConfig) {
        if (providerConfig == null) {
            return;
        }

        // 确定被管理的 config 实例的 key，如果 providerConfig 的 id 不为空，就直接使用，
        // 否则就根据其是否是 default config 来决定是使用 default 还是 null
        String key = StringUtils.isNotEmpty(providerConfig.getId())
                ? providerConfig.getId()
                : (providerConfig.isDefault() == null || providerConfig.isDefault()) ? DEFAULT_KEY : null;

        // 没有 key 或者是空串，那就抛异常
        if (StringUtils.isEmpty(key)) {
            throw new IllegalStateException("A ProviderConfig should either has an id or it's the default one, " + providerConfig);
        }

        // 如果 key 相等，但是 config 不相等，就是日志警告
        if (providers.containsKey(key) && !providerConfig.equals(providers.get(key))) {
            logger.warn("Duplicate ProviderConfig found, there already has one default ProviderConfig or more than two ProviderConfigs have the same id, " +
                    "you can try to give each ProviderConfig a different id. " + providerConfig);
        } else {
            // 向 ConfigManager 添加 key 和 config 对
            providers.put(key, providerConfig);
        }
    }

    public Optional<ConsumerConfig> getConsumer(String id) {
        return Optional.ofNullable(consumers.get(id));
    }

    public Optional<ConsumerConfig> getDefaultConsumer() {
        return Optional.ofNullable(consumers.get(DEFAULT_KEY));
    }

    public void addConsumer(ConsumerConfig consumerConfig) {
        if (consumerConfig == null) {
            return;
        }

        String key = StringUtils.isNotEmpty(consumerConfig.getId())
                ? consumerConfig.getId()
                : (consumerConfig.isDefault() == null || consumerConfig.isDefault()) ? DEFAULT_KEY : null;

        if (StringUtils.isEmpty(key)) {
            throw new IllegalStateException("A ConsumerConfig should either has an id or it's the default one, " + consumerConfig);
        }

        if (consumers.containsKey(key) && !consumerConfig.equals(consumers.get(key))) {
            logger.warn("Duplicate ConsumerConfig found, there already has one default ConsumerConfig or more than two ConsumerConfigs have the same id, " +
                    "you can try to give each ConsumerConfig a different id. " + consumerConfig);
        } else {
            consumers.put(key, consumerConfig);
        }
    }

    public Optional<ProtocolConfig> getProtocol(String id) {
        return Optional.ofNullable(protocols.get(id));
    }

    public Optional<List<ProtocolConfig>> getDefaultProtocols() {
        List<ProtocolConfig> defaults = new ArrayList<>();
        protocols.forEach((k, v) -> {
            if (DEFAULT_KEY.equalsIgnoreCase(k)) {
                defaults.add(v);
            } else if (v.isDefault() == null || v.isDefault()) {
                defaults.add(v);
            }
        });
        return Optional.of(defaults);
    }

    public void addProtocols(List<ProtocolConfig> protocolConfigs) {
        if (protocolConfigs != null) {
            // 逐个添加，这里的 this::addProtocol 是方法引用，java 的新特性
            protocolConfigs.forEach(this::addProtocol);
        }
    }

    public void addProtocol(ProtocolConfig protocolConfig) {
        if (protocolConfig == null) {
            return;
        }

        // 确定被管理的 config 实例的 key，如果 protocolConfig 的 id 不为空，就直接使用，
        // 否则就根据其是否是 default config 来决定是使用 default 还是 null
        String key = StringUtils.isNotEmpty(protocolConfig.getId())
                ? protocolConfig.getId()
                : (protocolConfig.isDefault() == null || protocolConfig.isDefault()) ? DEFAULT_KEY : null;

        // 没有 key 或者是空串，那就抛异常
        if (StringUtils.isEmpty(key)) {
            throw new IllegalStateException("A ProtocolConfig should either has an id or it's the default one, " + protocolConfig);
        }

        // 如果 key 相等，但是 config 不相等，就是日志警告
        if (protocols.containsKey(key) && !protocolConfig.equals(protocols.get(key))) {
            logger.warn("Duplicate ProtocolConfig found, there already has one default ProtocolConfig or more than two ProtocolConfigs have the same id, " +
                    "you can try to give each ProtocolConfig a different id. " + protocolConfig);
        } else {
            // 添加 key 和 protocolConfig 对
            protocols.put(key, protocolConfig);
        }
    }

    public Optional<RegistryConfig> getRegistry(String id) {
        return Optional.ofNullable(registries.get(id));
    }

    public Optional<List<RegistryConfig>> getDefaultRegistries() {
        List<RegistryConfig> defaults = new ArrayList<>();
        registries.forEach((k, v) -> {
            if (DEFAULT_KEY.equalsIgnoreCase(k)) {
                defaults.add(v);
            } else if (v.isDefault() == null || v.isDefault()) {
                defaults.add(v);
            }
        });
        return Optional.of(defaults);
    }

    public void addRegistries(List<RegistryConfig> registryConfigs) {
        if (registryConfigs != null) {
            // 逐个添加，这里的 this::addRegistry 是方法引用，java 的新特性
            registryConfigs.forEach(this::addRegistry);
        }
    }

    public void addRegistry(RegistryConfig registryConfig) {
        if (registryConfig == null) {
            return;
        }

        // 确定被管理的 config 实例的 key，如果 providerConfig 的 id 不为空，就直接使用，
        // 否则就根据其是否是 default config 来决定是使用 default 还是 null
        String key = StringUtils.isNotEmpty(registryConfig.getId())
                ? registryConfig.getId()
                : (registryConfig.isDefault() == null || registryConfig.isDefault()) ? DEFAULT_KEY : null;

        // 没有 key 或者是空串，那就抛异常
        if (StringUtils.isEmpty(key)) {
            throw new IllegalStateException("A RegistryConfig should either has an id or it's the default one, " + registryConfig);
        }

        // 如果 key 相等，但是 config 不相等，就是日志警告
        if (registries.containsKey(key) && !registryConfig.equals(registries.get(key))) {
            logger.warn("Duplicate RegistryConfig found, there already has one default RegistryConfig or more than two RegistryConfigs have the same id, " +
                    "you can try to give each RegistryConfig a different id. " + registryConfig);
        } else {
            // 添加 key 和 registryConfig 对
            registries.put(key, registryConfig);
        }
    }

    public Map<String, ProtocolConfig> getProtocols() {
        return protocols;
    }

    public Map<String, RegistryConfig> getRegistries() {
        return registries;
    }

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public Map<String, ConsumerConfig> getConsumers() {
        return consumers;
    }

    public void refreshAll() {
        // refresh all configs here,
        // 即从 ConfigManager 中获取保存的配置信息，然后调用相应的刷新方法
        getApplication().ifPresent(ApplicationConfig::refresh);
        getMonitor().ifPresent(MonitorConfig::refresh);
        getModule().ifPresent(ModuleConfig::refresh);

        getProtocols().values().forEach(ProtocolConfig::refresh);
        getRegistries().values().forEach(RegistryConfig::refresh);
        getProviders().values().forEach(ProviderConfig::refresh);
        getConsumers().values().forEach(ConsumerConfig::refresh);
    }

    private void checkDuplicate(AbstractConfig oldOne, AbstractConfig newOne) {
        if (oldOne != null && !oldOne.equals(newOne)) {
            String configName = oldOne.getClass().getSimpleName();
            throw new IllegalStateException("Duplicate Config found for " + configName + ", you should use only one unique " + configName + " for one application.");
        }
    }

    // For test purpose
    public void clear() {
        this.application = null;
        this.configCenter = null;
        this.monitor = null;
        this.module = null;
        this.registries.clear();
        this.protocols.clear();
        this.providers.clear();
        this.consumers.clear();
    }

}
