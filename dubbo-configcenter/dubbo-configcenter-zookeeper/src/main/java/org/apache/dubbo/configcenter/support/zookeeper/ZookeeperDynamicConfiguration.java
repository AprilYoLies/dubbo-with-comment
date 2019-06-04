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
package org.apache.dubbo.configcenter.support.zookeeper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.configcenter.ConfigurationListener;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.apache.dubbo.common.constants.ConfigConstants.CONFIG_NAMESPACE_KEY;

/**
 *
 */
public class ZookeeperDynamicConfiguration implements DynamicConfiguration {    // ZookeeperDynamicConfiguration 被称为配置类，是因为持有了众多的配置相关的信息
    private static final Logger logger = LoggerFactory.getLogger(ZookeeperDynamicConfiguration.class);

    private Executor executor;
    // The final root path would be: /configRootPath/"config"
    private String rootPath;
    private final ZookeeperClient zkClient;
    private CountDownLatch initializedLatch;

    private CacheListener cacheListener;
    private URL url;

    // 构造函数，完成了相关属性的设置，如 url、rootPath、initializedLatch、cacheListener、executor、zkClient
    ZookeeperDynamicConfiguration(URL url, ZookeeperTransporter zookeeperTransporter) {
        // 完成对相关属性的填充
        this.url = url;
        // path 在 listeners 缓存中做 key，用来映射 ConcurrentMap<DataListener, TargetDataListener>，在 treeCacheMap 中做 key，用来映射 TreeCache，TreeCache 也持有了 path
        rootPath = "/" + url.getParameter(CONFIG_NAMESPACE_KEY, DEFAULT_GROUP) + "/config"; // config.namespace 默认 dubbo，实际得到 /dubbo/config
        initializedLatch = new CountDownLatch(1);
        this.cacheListener = new CacheListener(rootPath, initializedLatch); // 该类会监听 EventType，如果是 EventType.INITIALIZED，会释放 CountDownLatch，否则会构建对应的 ConfigChangeEvent，交由它持有的 Listener 执行
        this.executor = Executors.newFixedThreadPool(1, new NamedThreadFactory(this.getClass().getSimpleName(), true));
        // 获取 ZookeeperClient，zookeeperTransporter 是 Adaptive 实例，实际是执行的 CuratorZookeeperTransporter 的 connect 方法，而此 connect 方法实际是返回了一个
        // 新构建的 CuratorZookeeperClient，注意这个 connect 方法的内容较多，主要是还是做了一些关于主机地址和备用地址到 zkClient（CuratorZookeeperClient）的映射缓存操作，还有 registry url 到 client url 的转换
        zkClient = zookeeperTransporter.connect(url);
        zkClient.addDataListener(rootPath, cacheListener, executor);
        try {
            // Wait for connection，因为添加了 cacheListener（持有 initializedLatch），在连接成功后会释放 CountDownLatch
            this.initializedLatch.await();
        } catch (InterruptedException e) {
            logger.warn("Failed to build local cache for config center (zookeeper)." + url);
        }
    }

    /**
     * @param key e.g., {service}.configurators, {service}.tagrouters, {group}.dubbo.properties
     * @return
     */
    @Override
    public Object getInternalProperty(String key) {
        // 获取位于 zookeeper 中的配置文件 /dubbo/config/demo-provider/dubbo.properties
        return zkClient.getContent(key);
    }

    /**
     * For service governance, multi group is not supported by this implementation. So group is not used at present.
     */
    @Override
    public void addListener(String key, String group, ConfigurationListener listener) {
        cacheListener.addListener(key, listener);
    }

    @Override
    public void removeListener(String key, String group, ConfigurationListener listener) {
        cacheListener.removeListener(key, listener);
    }

    /**
     * 尝试从 zookeeper 中获取配置文件
     *
     * @param key     the key to represent a configuration
     * @param group   the group where the key belongs to
     * @param timeout timeout value for fetching the target config
     * @return
     * @throws IllegalStateException
     */
    @Override   //  // key -> dubbo.properties group -> dubbo
    public String getConfig(String key, String group, long timeout) throws IllegalStateException {
        /**
         * when group is not null, we are getting startup configs from Config Center, for example:
         * group=dubbo, key=dubbo.properties
         * 存在 group 的情况下就是 group/key
         */
        if (StringUtils.isNotEmpty(group)) {
            key = group + "/" + key;
        }
        /**
         * when group is null, we are fetching governance rules, for example:
         * 1. key=org.apache.dubbo.DemoService.configurators
         * 2. key = org.apache.dubbo.DemoService.condition-router
         * 不存在 group 的情况下就是直接将 key 的 . 替换为 / 即可
         */
        else {
            int i = key.lastIndexOf(".");
            key = key.substring(0, i) + "/" + key.substring(i + 1);
        }

        // 尝试获取 zookeeper 中的配置文件    /dubbo/config/dubbo/dubbo.properties
        return (String) getInternalProperty(rootPath + "/" + key);
    }
}
