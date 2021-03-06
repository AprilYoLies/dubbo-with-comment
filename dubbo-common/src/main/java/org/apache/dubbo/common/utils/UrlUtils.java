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
package org.apache.dubbo.common.utils;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.RemotingConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.CLASSIFIER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REGISTRY_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.REMOVE_VALUE_PREFIX;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.DUBBO_PROTOCOL;
import static org.apache.dubbo.common.constants.ConfigConstants.HOST_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.PASSWORD_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.PORT_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.USERNAME_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.OVERRIDE_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTE_PROTOCOL;

public class UrlUtils {

    /**
     * in the url string,mark the param begin
     */
    private final static String URL_PARAM_STARTING_SYMBOL = "?";

    // 为 address 拼接相关属性
    public static URL parseURL(String address, Map<String, String> defaults) {
        if (address == null || address.length() == 0) {
            return null;
        }
        String url;
        // zookeeper://xxx.xxx.xxx.xxx:port?key=value 的形式直接使用
        if (address.contains("://") || address.contains(URL_PARAM_STARTING_SYMBOL)) {
            url = address;
        } else {
            // address1, address2, address3
            String[] addresses = COMMA_SPLIT_PATTERN.split(address);
            url = addresses[0];
            if (addresses.length > 1) {
                StringBuilder backup = new StringBuilder();
                // 拼接备用地址
                for (int i = 1; i < addresses.length; i++) {
                    if (i > 1) {
                        backup.append(",");
                    }
                    backup.append(addresses[i]);
                }
                // address1?backup=address2,address3 相当于作为备用地址
                url += URL_PARAM_STARTING_SYMBOL + RemotingConstants.BACKUP_KEY + "=" + backup.toString();
            }
        }
        // 获取协议类型
        String defaultProtocol = defaults == null ? null : defaults.get(PROTOCOL_KEY);
        if (defaultProtocol == null || defaultProtocol.length() == 0) {
            // 使用默认协议 dubbo
            defaultProtocol = DUBBO_PROTOCOL;
        }
        // 获取 username、password、port、path 相关属性
        String defaultUsername = defaults == null ? null : defaults.get(USERNAME_KEY);
        String defaultPassword = defaults == null ? null : defaults.get(PASSWORD_KEY);
        int defaultPort = StringUtils.parseInteger(defaults == null ? null : defaults.get(PORT_KEY));
        String defaultPath = defaults == null ? null : defaults.get(PATH_KEY);
        Map<String, String> defaultParameters = defaults == null ? null : new HashMap<String, String>(defaults);
        // 剔除部分信息
        if (defaultParameters != null) {
            defaultParameters.remove(PROTOCOL_KEY);
            defaultParameters.remove(USERNAME_KEY);
            defaultParameters.remove(PASSWORD_KEY);
            defaultParameters.remove(HOST_KEY);
            defaultParameters.remove(PORT_KEY);
            defaultParameters.remove(PATH_KEY);
        }
        // 将 String 类型的 url 转换为 URL 对象
        URL u = URL.valueOf(url);
        boolean changed = false;
        // URL 中的 username、password、port、path、host、path 等属性
        String protocol = u.getProtocol();
        String username = u.getUsername();
        String password = u.getPassword();
        String host = u.getHost();
        int port = u.getPort();
        String path = u.getPath();
        // URL 中的全部参数
        Map<String, String> parameters = new HashMap<String, String>(u.getParameters());
        // URL 未制定 protocol，就指定 protocol
        if ((protocol == null || protocol.length() == 0) && defaultProtocol != null && defaultProtocol.length() > 0) {
            changed = true;
            protocol = defaultProtocol;
        }
        // URL 未制定 username，就指定 username
        if ((username == null || username.length() == 0) && defaultUsername != null && defaultUsername.length() > 0) {
            changed = true;
            username = defaultUsername;
        }
        // URL 未制定 password，就指定 password
        if ((password == null || password.length() == 0) && defaultPassword != null && defaultPassword.length() > 0) {
            changed = true;
            password = defaultPassword;
        }
        /*if (u.isAnyHost() || u.isLocalHost()) {
            changed = true;
            host = NetUtils.getLocalHost();
        }*/
        // URL port 参数小于 0
        if (port <= 0) {
            // 要么使用默认端口（从 config 类中获取的端口号）
            if (defaultPort > 0) {
                changed = true;
                port = defaultPort;
            } else {
                // 要么使用 9090
                changed = true;
                port = 9090;
            }
        }
        // 修改 path 参数
        if (path == null || path.length() == 0) {
            if (defaultPath != null && defaultPath.length() > 0) {
                changed = true;
                path = defaultPath;
            }
        }
        if (defaultParameters != null && defaultParameters.size() > 0) {
            for (Map.Entry<String, String> entry : defaultParameters.entrySet()) {
                String key = entry.getKey();
                String defaultValue = entry.getValue();
                if (defaultValue != null && defaultValue.length() > 0) {
                    // 将 defaultParameters 中的参数添加到 URL 中，采用的是非覆盖的方式
                    String value = parameters.get(key);
                    if (StringUtils.isEmpty(value)) {
                        changed = true;
                        parameters.put(key, defaultValue);
                    }
                }
            }
        }
        if (changed) {
            // 根据各项参数重构 URL
            u = new URL(protocol, username, password, host, port, path, parameters);
        }
        return u;
    }

    // 根据 address 信息和 map 集合，拼接出对应的 url 地址
    public static List<URL> parseURLs(String address, Map<String, String> defaults) {
        if (address == null || address.length() == 0) {
            return null;
        }
        String[] addresses = REGISTRY_SPLIT_PATTERN.split(address);
        if (addresses == null || addresses.length == 0) {
            return null; //here won't be empty
        }
        List<URL> registries = new ArrayList<URL>();
        for (String addr : addresses) {
            // 为每一个 address 拼接相关属性，然后放入 registries
            registries.add(parseURL(addr, defaults));
        }
        return registries;
    }

    public static Map<String, Map<String, String>> convertRegister(Map<String, Map<String, String>> register) {
        Map<String, Map<String, String>> newRegister = new HashMap<String, Map<String, String>>();
        for (Map.Entry<String, Map<String, String>> entry : register.entrySet()) {
            String serviceName = entry.getKey();
            Map<String, String> serviceUrls = entry.getValue();
            if (!serviceName.contains(":") && !serviceName.contains("/")) {
                for (Map.Entry<String, String> entry2 : serviceUrls.entrySet()) {
                    String serviceUrl = entry2.getKey();
                    String serviceQuery = entry2.getValue();
                    Map<String, String> params = StringUtils.parseQueryString(serviceQuery);
                    String group = params.get("group");
                    String version = params.get("version");
                    //params.remove("group");
                    //params.remove("version");
                    String name = serviceName;
                    if (group != null && group.length() > 0) {
                        name = group + "/" + name;
                    }
                    if (version != null && version.length() > 0) {
                        name = name + ":" + version;
                    }
                    Map<String, String> newUrls = newRegister.get(name);
                    if (newUrls == null) {
                        newUrls = new HashMap<String, String>();
                        newRegister.put(name, newUrls);
                    }
                    newUrls.put(serviceUrl, StringUtils.toQueryString(params));
                }
            } else {
                newRegister.put(serviceName, serviceUrls);
            }
        }
        return newRegister;
    }

    public static Map<String, String> convertSubscribe(Map<String, String> subscribe) {
        Map<String, String> newSubscribe = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : subscribe.entrySet()) {
            String serviceName = entry.getKey();
            String serviceQuery = entry.getValue();
            if (!serviceName.contains(":") && !serviceName.contains("/")) {
                Map<String, String> params = StringUtils.parseQueryString(serviceQuery);
                String group = params.get("group");
                String version = params.get("version");
                //params.remove("group");
                //params.remove("version");
                String name = serviceName;
                if (group != null && group.length() > 0) {
                    name = group + "/" + name;
                }
                if (version != null && version.length() > 0) {
                    name = name + ":" + version;
                }
                newSubscribe.put(name, StringUtils.toQueryString(params));
            } else {
                newSubscribe.put(serviceName, serviceQuery);
            }
        }
        return newSubscribe;
    }

    public static Map<String, Map<String, String>> revertRegister(Map<String, Map<String, String>> register) {
        Map<String, Map<String, String>> newRegister = new HashMap<String, Map<String, String>>();
        for (Map.Entry<String, Map<String, String>> entry : register.entrySet()) {
            String serviceName = entry.getKey();
            Map<String, String> serviceUrls = entry.getValue();
            if (serviceName.contains(":") || serviceName.contains("/")) {
                for (Map.Entry<String, String> entry2 : serviceUrls.entrySet()) {
                    String serviceUrl = entry2.getKey();
                    String serviceQuery = entry2.getValue();
                    Map<String, String> params = StringUtils.parseQueryString(serviceQuery);
                    String name = serviceName;
                    int i = name.indexOf('/');
                    if (i >= 0) {
                        params.put("group", name.substring(0, i));
                        name = name.substring(i + 1);
                    }
                    i = name.lastIndexOf(':');
                    if (i >= 0) {
                        params.put("version", name.substring(i + 1));
                        name = name.substring(0, i);
                    }
                    Map<String, String> newUrls = newRegister.get(name);
                    if (newUrls == null) {
                        newUrls = new HashMap<String, String>();
                        newRegister.put(name, newUrls);
                    }
                    newUrls.put(serviceUrl, StringUtils.toQueryString(params));
                }
            } else {
                newRegister.put(serviceName, serviceUrls);
            }
        }
        return newRegister;
    }

    public static Map<String, String> revertSubscribe(Map<String, String> subscribe) {
        Map<String, String> newSubscribe = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : subscribe.entrySet()) {
            String serviceName = entry.getKey();
            String serviceQuery = entry.getValue();
            if (serviceName.contains(":") || serviceName.contains("/")) {
                Map<String, String> params = StringUtils.parseQueryString(serviceQuery);
                String name = serviceName;
                int i = name.indexOf('/');
                if (i >= 0) {
                    params.put("group", name.substring(0, i));
                    name = name.substring(i + 1);
                }
                i = name.lastIndexOf(':');
                if (i >= 0) {
                    params.put("version", name.substring(i + 1));
                    name = name.substring(0, i);
                }
                newSubscribe.put(name, StringUtils.toQueryString(params));
            } else {
                newSubscribe.put(serviceName, serviceQuery);
            }
        }
        return newSubscribe;
    }

    public static Map<String, Map<String, String>> revertNotify(Map<String, Map<String, String>> notify) {
        if (notify != null && notify.size() > 0) {
            Map<String, Map<String, String>> newNotify = new HashMap<String, Map<String, String>>();
            for (Map.Entry<String, Map<String, String>> entry : notify.entrySet()) {
                String serviceName = entry.getKey();
                Map<String, String> serviceUrls = entry.getValue();
                if (!serviceName.contains(":") && !serviceName.contains("/")) {
                    if (serviceUrls != null && serviceUrls.size() > 0) {
                        for (Map.Entry<String, String> entry2 : serviceUrls.entrySet()) {
                            String url = entry2.getKey();
                            String query = entry2.getValue();
                            Map<String, String> params = StringUtils.parseQueryString(query);
                            String group = params.get("group");
                            String version = params.get("version");
                            // params.remove("group");
                            // params.remove("version");
                            String name = serviceName;
                            if (group != null && group.length() > 0) {
                                name = group + "/" + name;
                            }
                            if (version != null && version.length() > 0) {
                                name = name + ":" + version;
                            }
                            Map<String, String> newUrls = newNotify.get(name);
                            if (newUrls == null) {
                                newUrls = new HashMap<String, String>();
                                newNotify.put(name, newUrls);
                            }
                            newUrls.put(url, StringUtils.toQueryString(params));
                        }
                    }
                } else {
                    newNotify.put(serviceName, serviceUrls);
                }
            }
            return newNotify;
        }
        return notify;
    }

    //compatible for dubbo-2.0.0
    public static List<String> revertForbid(List<String> forbid, Set<URL> subscribed) {
        if (CollectionUtils.isNotEmpty(forbid)) {
            List<String> newForbid = new ArrayList<String>();
            for (String serviceName : forbid) {
                if (!serviceName.contains(":") && !serviceName.contains("/")) {
                    for (URL url : subscribed) {
                        if (serviceName.equals(url.getServiceInterface())) {
                            newForbid.add(url.getServiceKey());
                            break;
                        }
                    }
                } else {
                    newForbid.add(serviceName);
                }
            }
            return newForbid;
        }
        return forbid;
    }

    public static URL getEmptyUrl(String service, String category) {
        String group = null;
        String version = null;
        int i = service.indexOf('/');
        if (i > 0) {
            group = service.substring(0, i);
            service = service.substring(i + 1);
        }
        i = service.lastIndexOf(':');
        if (i > 0) {
            version = service.substring(i + 1);
            service = service.substring(0, i);
        }
        return URL.valueOf(EMPTY_PROTOCOL + "://0.0.0.0/" + service + URL_PARAM_STARTING_SYMBOL
                + CATEGORY_KEY + "=" + category
                + (group == null ? "" : "&" + GROUP_KEY + "=" + group)
                + (version == null ? "" : "&" + VERSION_KEY + "=" + version));
    }
    // category 来自 providerUrl，categories 来自 consumerUrl
    public static boolean isMatchCategory(String category, String categories) {
        if (categories == null || categories.length() == 0) {   // consumerUrl 没有指定 category 情况下，就看 providerUrl 的 category 是否为 provider
            return DEFAULT_CATEGORY.equals(category);
        } else if (categories.contains(ANY_VALUE)) {
            return true;
        } else if (categories.contains(REMOVE_VALUE_PREFIX)) {  // 排除法
            return !categories.contains(REMOVE_VALUE_PREFIX + category);
        } else {
            return categories.contains(category);   // categories 包含 category 也行
        }
    }

    public static boolean isMatch(URL consumerUrl, URL providerUrl) {
        String consumerInterface = consumerUrl.getServiceInterface();
        String providerInterface = providerUrl.getServiceInterface();
        //FIXME accept providerUrl with '*' as interface name, after carefully thought about all possible scenarios I think it's ok to add this condition.
        if (!(ANY_VALUE.equals(consumerInterface)   // consumerUrl 和 providerUrl 的 interface 参数一致
                || ANY_VALUE.equals(providerInterface)
                || StringUtils.isEquals(consumerInterface, providerInterface))) {   // 两个 serviceInterface 非 * 下的不相等，直接返回 false
            return false;
        }

        if (!isMatchCategory(providerUrl.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY),  // consumerUrl 和 providerUrl 的 category 要匹配
                consumerUrl.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY))) {
            return false;
        }
        if (!providerUrl.getParameter(ENABLED_KEY, true)    // provider 的 url 包含 enable 参数为 false，consumer 的 url 不是通配 *，返回 false
                && !ANY_VALUE.equals(consumerUrl.getParameter(ENABLED_KEY))) {
            return false;
        }
        // 比较 group、version、classifier 餐厨是否匹配
        String consumerGroup = consumerUrl.getParameter(GROUP_KEY);
        String consumerVersion = consumerUrl.getParameter(VERSION_KEY);
        String consumerClassifier = consumerUrl.getParameter(CLASSIFIER_KEY, ANY_VALUE);

        String providerGroup = providerUrl.getParameter(GROUP_KEY);
        String providerVersion = providerUrl.getParameter(VERSION_KEY);
        String providerClassifier = providerUrl.getParameter(CLASSIFIER_KEY, ANY_VALUE);
        return (ANY_VALUE.equals(consumerGroup) || StringUtils.isEquals(consumerGroup, providerGroup) || StringUtils.isContains(consumerGroup, providerGroup))
                && (ANY_VALUE.equals(consumerVersion) || StringUtils.isEquals(consumerVersion, providerVersion))
                && (consumerClassifier == null || ANY_VALUE.equals(consumerClassifier) || StringUtils.isEquals(consumerClassifier, providerClassifier));
    }

    public static boolean isMatchGlobPattern(String pattern, String value, URL param) {
        if (param != null && pattern.startsWith("$")) {
            pattern = param.getRawParameter(pattern.substring(1));
        }
        return isMatchGlobPattern(pattern, value);
    }

    public static boolean isMatchGlobPattern(String pattern, String value) {
        if ("*".equals(pattern)) {
            return true;
        }
        if (StringUtils.isEmpty(pattern) && StringUtils.isEmpty(value)) {
            return true;
        }
        if (StringUtils.isEmpty(pattern) || StringUtils.isEmpty(value)) {
            return false;
        }

        int i = pattern.lastIndexOf('*');
        // doesn't find "*"
        if (i == -1) {
            return value.equals(pattern);
        }
        // "*" is at the end
        else if (i == pattern.length() - 1) {
            return value.startsWith(pattern.substring(0, i));
        }
        // "*" is at the beginning
        else if (i == 0) {
            return value.endsWith(pattern.substring(i + 1));
        }
        // "*" is in the middle
        else {
            String prefix = pattern.substring(0, i);
            String suffix = pattern.substring(i + 1);
            return value.startsWith(prefix) && value.endsWith(suffix);
        }
    }

    public static boolean isServiceKeyMatch(URL pattern, URL value) {
        return pattern.getParameter(INTERFACE_KEY).equals(
                value.getParameter(INTERFACE_KEY))
                && isItemMatch(pattern.getParameter(GROUP_KEY),
                value.getParameter(GROUP_KEY))
                && isItemMatch(pattern.getParameter(VERSION_KEY),
                value.getParameter(VERSION_KEY));
    }

    public static List<URL> classifyUrls(List<URL> urls, Predicate<URL> predicate) {
        return urls.stream().filter(predicate).collect(Collectors.toList());
    }

    public static boolean isConfigurator(URL url) {
        return OVERRIDE_PROTOCOL.equals(url.getProtocol()) ||
                CONFIGURATORS_CATEGORY.equals(url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY));
    }

    public static boolean isRoute(URL url) {
        return ROUTE_PROTOCOL.equals(url.getProtocol()) ||
                ROUTERS_CATEGORY.equals(url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY));
    }

    public static boolean isProvider(URL url) {
        return !OVERRIDE_PROTOCOL.equals(url.getProtocol()) &&
                !ROUTE_PROTOCOL.equals(url.getProtocol()) &&
                PROVIDERS_CATEGORY.equals(url.getParameter(CATEGORY_KEY, PROVIDERS_CATEGORY));
    }

    public static int getHeartbeat(URL url) {
        return url.getParameter(RemotingConstants.HEARTBEAT_KEY, RemotingConstants.DEFAULT_HEARTBEAT);
    }

    public static int getIdleTimeout(URL url) {
        // 从 url 中获取 heartbeat 参数
        int heartBeat = getHeartbeat(url);
        // 从 url 中获取 idleTimeout 参数，如果没有获取到，就直接使用 heartBeat 的三倍时长
        int idleTimeout = url.getParameter(RemotingConstants.HEARTBEAT_TIMEOUT_KEY, heartBeat * 3);
        // 也就是说 idleTimeout 必须是 2 倍的 heartBeat 及以上
        if (idleTimeout < heartBeat * 2) {
            throw new IllegalStateException("idleTimeout < heartbeatInterval * 2");
        }
        return idleTimeout;
    }

    /**
     * Check if the given value matches the given pattern. The pattern supports wildcard "*".
     *
     * @param pattern pattern
     * @param value   value
     * @return true if match otherwise false
     */
    static boolean isItemMatch(String pattern, String value) {
        if (pattern == null) {
            return value == null;
        } else {
            return "*".equals(pattern) || pattern.equals(value);
        }
    }
}
