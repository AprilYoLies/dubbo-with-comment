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
package org.apache.dubbo.rpc.cluster.configurator;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.cluster.Configurator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.constants.ClusterConstants.CONFIG_VERSION_KEY;
import static org.apache.dubbo.common.constants.ClusterConstants.OVERRIDE_PROVIDERS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;

/**
 * AbstractOverrideConfigurator
 */
public abstract class AbstractConfigurator implements Configurator {

    private final URL configuratorUrl;

    public AbstractConfigurator(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("configurator url == null");
        }
        this.configuratorUrl = url;
    }

    @Override
    public URL getUrl() {
        return configuratorUrl;
    }

    @Override
    public URL configure(URL url) {
        // If override url is not enabled or is invalid, just return.
        // 要求 configuratorUrl 的 enable 参数为 null 或者 true
        // configuratorUrl 的 host 不为空
        // url 和 url 的 host 不为空
        if (!configuratorUrl.getParameter(ENABLED_KEY, true) || configuratorUrl.getHost() == null || url == null || url.getHost() == null) {
            return url;
        }
        /**
         * This if branch is created since 2.7.0.
         */
        // 获取 configuratorUrl 中的 configVersion 属性值
        String apiVersion = configuratorUrl.getParameter(CONFIG_VERSION_KEY);
        if (StringUtils.isNotEmpty(apiVersion)) {
            // 分别获取 url 和 configuratorUrl 的 side 信息
            String currentSide = url.getParameter(SIDE_KEY);
            String configuratorSide = configuratorUrl.getParameter(SIDE_KEY);
            if (currentSide.equals(configuratorSide) && CONSUMER.equals(configuratorSide) && 0 == configuratorUrl.getPort()) {
                // url 和 configuratorUrl 的 side 信息一致为 consumer，端口信息为 0
                // NetUtils.getLocalHost() 获取 127.0.0.1 或者本机 ip
                url = configureIfMatch(NetUtils.getLocalHost(), url);
            } else if (currentSide.equals(configuratorSide) && PROVIDER.equals(configuratorSide) && url.getPort() == configuratorUrl.getPort()) {
                // url 和 configuratorUrl 的 side 信息一致为 provider，且二者的端口号配置相同
                url = configureIfMatch(url.getHost(), url);
            }
        }
        /**
         * This else branch is deprecated and is left only to keep compatibility with versions before 2.7.0
         */
        else {
            url = configureDeprecated(url);
        }
        return url;
    }

    @Deprecated
    private URL configureDeprecated(URL url) {
        // If override url has port, means it is a provider address. We want to control a specific provider with this override url, it may take effect on the specific provider instance or on consumers holding this provider instance.
        if (configuratorUrl.getPort() != 0) {
            if (url.getPort() == configuratorUrl.getPort()) {
                return configureIfMatch(url.getHost(), url);
            }
        } else {// override url don't have a port, means the ip override url specify is a consumer address or 0.0.0.0
            // 1.If it is a consumer ip address, the intention is to control a specific consumer instance, it must takes effect at the consumer side, any provider received this override url should ignore;
            // 2.If the ip is 0.0.0.0, this override url can be used on consumer, and also can be used on provider
            if (url.getParameter(SIDE_KEY, PROVIDER).equals(CONSUMER)) {
                return configureIfMatch(NetUtils.getLocalHost(), url);// NetUtils.getLocalHost is the ip address consumer registered to registry.
            } else if (url.getParameter(SIDE_KEY, CONSUMER).equals(PROVIDER)) {
                return configureIfMatch(ANYHOST_VALUE, url);// take effect on all providers, so address must be 0.0.0.0, otherwise it won't flow to this if branch
            }
        }
        return url;
    }

    private URL configureIfMatch(String host, URL url) {
        // configuratorUrl 主机名为 0.0.0.0 或者为 127.0.0.1 或者本机 ip
        if (ANYHOST_VALUE.equals(configuratorUrl.getHost()) || host.equals(configuratorUrl.getHost())) {
            // TODO, to support wildcards
            // 获取 providerAddresses 参数
            String providers = configuratorUrl.getParameter(OVERRIDE_PROVIDERS_KEY);
            if (StringUtils.isEmpty(providers) || providers.contains(url.getAddress()) || providers.contains(ANYHOST_VALUE)) {
                // providers 为空，或者包含 url 的地址，或者 providers 包含 0.0.0.0
                // 从 configuratorUrl 中获取 application 参数，没有的话就获取 username
                String configApplication = configuratorUrl.getParameter(APPLICATION_KEY,
                        configuratorUrl.getUsername());
                // 从 url 中获取 application 参数，没有的话就获取 username
                String currentApplication = url.getParameter(APPLICATION_KEY, url.getUsername());
                if (configApplication == null || ANY_VALUE.equals(configApplication)
                        || configApplication.equals(currentApplication)) {
                    // 满足的情况下
                    Set<String> conditionKeys = new HashSet<String>();
                    conditionKeys.add(CATEGORY_KEY); // category
                    conditionKeys.add(RemotingConstants.CHECK_KEY); // check
                    conditionKeys.add(DYNAMIC_KEY); // dynamic
                    conditionKeys.add(ENABLED_KEY); // enable
                    conditionKeys.add(GROUP_KEY); // group
                    conditionKeys.add(VERSION_KEY); // version
                    conditionKeys.add(APPLICATION_KEY); // application
                    conditionKeys.add(SIDE_KEY); // side
                    conditionKeys.add(CONFIG_VERSION_KEY); // configVersion
                    for (Map.Entry<String, String> entry : configuratorUrl.getParameters().entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        if (key.startsWith("~") || APPLICATION_KEY.equals(key) || SIDE_KEY.equals(key)) {
                            // 从 configuratorUrl 参数中获取 ~ 开头、application、side 三种键，将其添加到 conditionKeys
                            conditionKeys.add(key);
                            // 如果键值非空，非 *，且不等于 url 相对应的键值，直接返回 url
                            if (value != null && !ANY_VALUE.equals(value)
                                    && !value.equals(url.getParameter(key.startsWith("~") ? key.substring(1) : key))) {
                                return url;
                            }
                        }
                    }
                    // 用 configuratorUrl 剩余的 parameters 对 url 进行配置，分为覆盖和非覆盖两种方式
                    return doConfigure(url, configuratorUrl.removeParameters(conditionKeys));
                }
            }
        }
        return url;
    }

    protected abstract URL doConfigure(URL currentUrl, URL configUrl);

}
