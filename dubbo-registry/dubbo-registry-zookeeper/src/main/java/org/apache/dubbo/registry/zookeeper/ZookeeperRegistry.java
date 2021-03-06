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
package org.apache.dubbo.registry.zookeeper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.support.FailbackRegistry;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;
import org.apache.dubbo.rpc.RpcException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_SEPARATOR;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONSUMERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;

/**
 * ZookeeperRegistry
 */
public class ZookeeperRegistry extends FailbackRegistry {

    private final static Logger logger = LoggerFactory.getLogger(ZookeeperRegistry.class);

    private final static int DEFAULT_ZOOKEEPER_PORT = 2181;

    private final static String DEFAULT_ROOT = "dubbo";

    private final String root;

    private final Set<String> anyServices = new ConcurrentHashSet<>();

    private final ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners = new ConcurrentHashMap<>();

    private final ZookeeperClient zkClient;

    public ZookeeperRegistry(URL url, ZookeeperTransporter zookeeperTransporter) {
        super(url); // 记录了 retryPeriod 和 retryTimer 信息
        if (url.isAnyHost()) {  // host 为 0.0.0.0 或者 anyhost 参数为 true
            throw new IllegalStateException("registry address == null");
        }
        String group = url.getParameter(GROUP_KEY, DEFAULT_ROOT);   // 优先使用 url 的 group 属性，默认为 dubbo
        if (!group.startsWith(PATH_SEPARATOR)) {
            group = PATH_SEPARATOR + group;
        }
        this.root = group;  // 用 group 做 root
        // zookeeperTransporter 实例的源代码
        // package org.apache.dubbo.remoting.zookeeper;
        // import org.apache.dubbo.common.extension.ExtensionLoader;
        // public class ZookeeperTransporter$Adaptive implements org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter {
        //     public org.apache.dubbo.remoting.zookeeper.ZookeeperClient connect(org.apache.dubbo.common.URL arg0)  {
        //         if (arg0 == null) throw new IllegalArgumentException("url == null");
        //         org.apache.dubbo.common.URL url = arg0;
        // extName 实际为 curator
        //         String extName = url.getParameter("client", url.getParameter("transporter", "curator"));
        //         if(extName == null) throw new IllegalStateException("Failed to get extension (org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter) name from url (" + url.toString() + ") use keys([client, transporter])");
        // 此处获得 extension 实际为 CuratorZookeeperTransporter
        //         org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter extension = (org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter)ExtensionLoader.getExtensionLoader(org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter.class).getExtension(extName);
        //         return extension.connect(arg0);
        //     }
        // }
        zkClient = zookeeperTransporter.connect(url);
        zkClient.addStateListener(state -> {
            if (state == StateListener.RECONNECTED) {
                try {
                    recover();  // 如果 zkClient 监听到重连消息后，需要对失效的 url 进行重新注册
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        });
    }

    @Override
    public boolean isAvailable() {
        return zkClient.isConnected();
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            zkClient.close();
        } catch (Exception e) {
            logger.warn("Failed to close zookeeper client " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doRegister(URL url) {   // 模式为： /root/url的interface参数/url的category参数/url的全字符串编码
        try {   // /dubbo/org.apache.dubbo.demo.DemoService/consumers/consumer%3A%2F%2F192.168.1.101%2Forg.apache.dubbo.demo.DemoService%3Fapplication%3Ddemo-consumer%26category%3Dconsumers%26check%3Dfalse%26dubbo%3D2.0.2%26interface%3Dorg.apache.dubbo.demo.DemoService%26lazy%3Dfalse%26methods%3DsayHello%26pid%3D83276%26side%3Dconsumer%26sticky%3Dfalse%26timestamp%3D1559352722835
            zkClient.create(toUrlPath(url), url.getParameter(DYNAMIC_KEY, true));
        } catch (Throwable e) {
            throw new RpcException("Failed to register " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doUnregister(URL url) {
        try {
            zkClient.delete(toUrlPath(url));
        } catch (Throwable e) {
            throw new RpcException("Failed to unregister " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    // 然后将此 listener 注册到 path 对应的路径下，得到 provider 地址信息，最后将这个地址信息进行通知
    @Override    // 此方法只看 else 代码块，先是根据 url 获取不同分类的 path，然后根据 url 获取 zkListeners 中 listener 参数对应的 ChildListener
    // 所谓的通知就是将 urls 进行分类，获取 url 对应的分类 url 链，将这个链通知给 listener（就是将 url 转换为配置类，然后保存到字段中），同时保存一些 serviceKey 和 url 信息到文件中
    public void doSubscribe(final URL url, final NotifyListener listener) {
        try {
            if (ANY_VALUE.equals(url.getServiceInterface())) {  // url 的 interface 属性为 *，暂时不研究这个分支
                String root = toRootPath();
                ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url); // url -> (notifyListener, childListener)
                if (listeners == null) {
                    zkListeners.putIfAbsent(url, new ConcurrentHashMap<>());
                    listeners = zkListeners.get(url);
                }
                ChildListener zkListener = listeners.get(listener);
                if (zkListener == null) {
                    listeners.putIfAbsent(listener, (parentPath, currentChilds) -> {
                        for (String child : currentChilds) {
                            child = URL.decode(child);
                            if (!anyServices.contains(child)) {
                                anyServices.add(child); // 这里就是说 NotifyListener 和 url 一起存储到 subscribed 中，而 ChildListener 被添做了 url 参数
                                subscribe(url.setPath(child).addParameters(INTERFACE_KEY, child,    // interface -> child
                                        RemotingConstants.CHECK_KEY, String.valueOf(false)), listener); // check -> false
                            }
                        }
                    });
                    zkListener = listeners.get(listener);
                }
                zkClient.create(root, false);
                List<String> services = zkClient.addChildListener(root, zkListener);
                if (CollectionUtils.isNotEmpty(services)) {
                    for (String service : services) {
                        service = URL.decode(service);
                        anyServices.add(service);
                        subscribe(url.setPath(service).addParameters(INTERFACE_KEY, service,
                                RemotingConstants.CHECK_KEY, String.valueOf(false)), listener);
                    }   // else 的代码，就是在 zkListeners 的 categoriesPath 对应的 map 中添加 NotifyListener（RegistryDirectory）, ChildListener（lambda 表达式的内容），并在 zk 中创建 path，添加了监听器，然后处理了返回的 list 并通知
                }                                               // toCategoriesPath 函数的结果，模式为 /root/url的interface参数或者path/url的category
            } else {                                            // /dubbo/org.apache.dubbo.demo.DemoService/providers
                List<URL> urls = new ArrayList<>();             // /dubbo/org.apache.dubbo.demo.DemoService/configurators
                for (String path : toCategoriesPath(url)) {     // /dubbo/org.apache.dubbo.demo.DemoService/routers
                    ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                    if (listeners == null) {    // 在 zkListeners 中，url 对应的是 listener 链
                        zkListeners.putIfAbsent(url, new ConcurrentHashMap<>());    // 没有则新建
                        listeners = zkListeners.get(url);
                    }   // 在 listener 链中尝试获取 listener
                    ChildListener zkListener = listeners.get(listener); // 也就是说 NotifyListener（这里指 RegistryDirectory）对应多个 ChildListener
                    if (zkListener == null) {                   // 没有则新建
                        listeners.putIfAbsent(listener, (parentPath, currentChilds) -> ZookeeperRegistry.this.notify(url, listener, toUrlsWithEmpty(url, parentPath, currentChilds)));
                        zkListener = listeners.get(listener);
                    }
                    zkClient.create(path, false);   // /dubbo/org.apache.dubbo.demo.DemoService/providers
                    List<String> children = zkClient.addChildListener(path, zkListener);    // 为指定路径添加一个 ChildListener 监听器，此过程会返回一个地址信息
                    if (children != null) { // 返回的 children 类似于 dubbo%3A%2F%2F192.168.1.101%3A20880%2Forg.apache.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26application%3Ddemo-provider%26bean.name%3Dorg.apache.dubbo.demo.DemoService%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26interface%3Dorg.apache.dubbo.demo.DemoService%26methods%3DsayHello%26pid%3D95332%26register%3Dtrue%26release%3D%26side%3Dprovider%26timestamp%3D1559390395469
                        urls.addAll(toUrlsWithEmpty(url, path, children));  // toUrlsWithEmpty 就是获取和 consumer url 匹配的 provider url 集合，如果 children 为空，那么返回的就是一个由 consumer 构建的协议为空的 url
                    }   // 重要：这里关于 listener 的关系整理，zkListeners（ZookeeperRegistry 的字段） ->(url,ConcurrentMap<NotifyListener, ChildListener>)
                }       // childListeners（CuratorZookeeperClient 的父类 AbstractZookeeperClient 持有） -> (path，ConcurrentMap<ChildListener, TargetChildListener> )
                notify(url, listener, urls);    // 实际向 zookeeper 路径注册的监听器是 TargetChildListener,真正的 invoker 构建函数
            }   // 将 urls 进行分类，获取 url 对应的分类 url 链，将这个链通知给 listener（就是将 url 转换为配置类，然后保存到字段中），同时保存一些 serviceKey 和 url 信息到文件中
        } catch (Throwable e) {
            throw new RpcException("Failed to subscribe " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
        if (listeners != null) {
            ChildListener zkListener = listeners.get(listener);
            if (zkListener != null) {
                if (ANY_VALUE.equals(url.getServiceInterface())) {
                    String root = toRootPath();
                    zkClient.removeChildListener(root, zkListener);
                } else {
                    for (String path : toCategoriesPath(url)) {
                        zkClient.removeChildListener(path, zkListener);
                    }
                }
            }
        }
    }

    @Override
    public List<URL> lookup(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("lookup url == null");
        }
        try {
            List<String> providers = new ArrayList<>();
            for (String path : toCategoriesPath(url)) {
                List<String> children = zkClient.getChildren(path);
                if (children != null) {
                    providers.addAll(children);
                }
            }
            return toUrlsWithoutEmpty(url, providers);
        } catch (Throwable e) {
            throw new RpcException("Failed to lookup " + url + " from zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    private String toRootDir() {
        if (root.equals(PATH_SEPARATOR)) {
            return root;
        }
        return root + PATH_SEPARATOR;
    }

    private String toRootPath() {
        return root;
    }

    private String toServicePath(URL url) {
        String name = url.getServiceInterface();
        if (ANY_VALUE.equals(name)) {
            return toRootPath();
        }
        return toRootDir() + URL.encode(name);
    }

    // 模式为 /root/url的interface参数或者path/url的category
    private String[] toCategoriesPath(URL url) {
        String[] categories;
        if (ANY_VALUE.equals(url.getParameter(CATEGORY_KEY))) {
            categories = new String[]{PROVIDERS_CATEGORY, CONSUMERS_CATEGORY, ROUTERS_CATEGORY, CONFIGURATORS_CATEGORY};
        } else {    // 获取 category 属性的数组形式
            categories = url.getParameter(CATEGORY_KEY, new String[]{DEFAULT_CATEGORY});
        }
        String[] paths = new String[categories.length];
        for (int i = 0; i < categories.length; i++) {
            paths[i] = toServicePath(url) + PATH_SEPARATOR + categories[i]; // 将数组形式的 categories 用 / 拼接起来
        }                   // /dubbo/org.apache.dubbo.demo.DemoService/providers
        return paths;       // /dubbo/org.apache.dubbo.demo.DemoService/configurators
    }                       // /dubbo/org.apache.dubbo.demo.DemoService/routers

    private String toCategoryPath(URL url) {
        return toServicePath(url) + PATH_SEPARATOR + url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
    }

    private String toUrlPath(URL url) {
        return toCategoryPath(url) + PATH_SEPARATOR + URL.encode(url.toFullString());
    }   // consumer -> consumer://192.168.1.101/org.apache.dubbo.demo.DemoService?application=demo-consumer&category=providers,configurators,routers&check=false&dubbo=2.0.2&interface=org.apache.dubbo.demo.DemoService&lazy=false&methods=sayHello&pid=95339&side=consumer&sticky=false&timestamp=1559390405986

    // providers -> dubbo%3A%2F%2F192.168.1.101%3A20880%2Forg.apache.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26application%3Ddemo-provider%26bean.name%3Dorg.apache.dubbo.demo.DemoService%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26interface%3Dorg.apache.dubbo.demo.DemoService%26methods%3DsayHello%26pid%3D95332%26register%3Dtrue%26release%3D%26side%3Dprovider%26timestamp%3D1559390395469
    private List<URL> toUrlsWithoutEmpty(URL consumer, List<String> providers) {    // 将 providers 中和 consumer 匹配的 url 添加到 urls 集合中返回
        List<URL> urls = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(providers)) {
            for (String provider : providers) { // 服务发布者的集合
                provider = URL.decode(provider);
                if (provider.contains(PROTOCOL_SEPARATOR)) {
                    URL url = URL.valueOf(provider);
                    if (UrlUtils.isMatch(consumer, url)) {
                        urls.add(url);  // 就是将 providers 转换为 URL 后返回
                    }
                }
            }
        }
        return urls;
    }

    // consumer://192.168.1.104/org.apache.dubbo.demo.DemoService?application=demo-consumer&category=providers,configurators,routers&check=false&dubbo=2.0.2&interface=org.apache.dubbo.demo.DemoService&lazy=false&methods=sayHello&pid=51672&side=consumer&sticky=false&timestamp=1558927288895
    private List<URL> toUrlsWithEmpty(URL consumer, String path, List<String> providers) {  // 返回匹配的 provider url，如果为空，那就返回一个根据 consumer 构造的协议为空的 url（添加到 list 中返回）
        List<URL> urls = toUrlsWithoutEmpty(consumer, providers);   // 就是将 providers 转换为 URL 后返回，只要 consumer 消费的 provider 地址
        if (urls == null || urls.isEmpty()) {
            int i = path.lastIndexOf(PATH_SEPARATOR);
            String category = i < 0 ? path : path.substring(i + 1);
            URL empty = URLBuilder.from(consumer)   // 如果 providers 转换为 URL 为空，那么就直接通过 consumer 进行构造
                    .setProtocol(EMPTY_PROTOCOL)    // 如果返回了 empty 协议的 url，那么就说明没有对应的服务
                    .addParameter(CATEGORY_KEY, category)
                    .build();
            urls.add(empty);
        }
        return urls;
    }

}
