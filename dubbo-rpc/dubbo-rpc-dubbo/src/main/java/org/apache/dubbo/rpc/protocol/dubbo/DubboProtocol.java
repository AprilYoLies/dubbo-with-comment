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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.support.SerializableClassRegistry;
import org.apache.dubbo.common.serialize.support.SerializationOptimizer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchangers;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.LAZY_CONNECT_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.ON_CONNECT_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.ON_DISCONNECT_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.CALLBACK_SERVICE_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.CONNECTIONS_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.DEFAULT_SHARE_CONNECTIONS;
import static org.apache.dubbo.common.constants.RpcConstants.DEFAULT_STUB_EVENT;
import static org.apache.dubbo.common.constants.RpcConstants.IS_CALLBACK_SERVICE;
import static org.apache.dubbo.common.constants.RpcConstants.IS_SERVER_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.OPTIMIZER_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.SHARE_CONNECTIONS_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.STUB_EVENT_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.STUB_EVENT_METHODS_KEY;

/**
 * dubbo protocol support.
 */
public class DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    public static final int DEFAULT_PORT = 20880;
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
    private static DubboProtocol INSTANCE;

    /**
     * <host:port,Exchanger>
     */
    private final Map<String, ExchangeServer> serverMap = new ConcurrentHashMap<>();
    /**
     * <host:port,Exchanger>
     */
    private final Map<String, List<ReferenceCountExchangeClient>> referenceClientMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    private final Set<String> optimizers = new ConcurrentHashSet<>();
    /**
     * consumer side export a stub service for dispatching event
     * servicekey-stubmethods
     */
    private final ConcurrentMap<String, String> stubServiceMethodsMap = new ConcurrentHashMap<>();

    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        @Override   // message 为已经完成解码的消息，用 DecodeableRpcInvocation 进行承载
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message) throws RemotingException {
            // 这里的 message 一定得是 Invocation 实例
            if (!(message instanceof Invocation)) {
                throw new RemotingException(channel, "Unsupported request: "
                        + (message == null ? null : (message.getClass().getName() + ": " + message))
                        + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
            }

            Invocation inv = (Invocation) message;
            // 根据参数拼接 serviceKey 再从 exporterMap 中获取对应的 Invoker
            Invoker<?> invoker = getInvoker(channel, inv);
            // need to consider backward-compatibility if it's a callback
            // 是 _isCallBackServiceInvoke 参数为 true
            if (Boolean.TRUE.toString().equals(inv.getAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) { // _isCallBackServiceInvoke
                String methodsStr = invoker.getUrl().getParameters().get("methods");
                boolean hasMethod = false;
                // 查找 inv 中是否有 methods 参数值，如果找到了，需要将 hasMethod 设置为 true
                if (methodsStr == null || !methodsStr.contains(",")) {
                    hasMethod = inv.getMethodName().equals(methodsStr);
                } else {
                    // methodsStr 有 ，分隔符需要进行切分查找
                    String[] methods = methodsStr.split(",");
                    for (String method : methods) {
                        if (inv.getMethodName().equals(method)) {
                            hasMethod = true;
                            break;
                        }
                    }
                }
                // 这里说明 url 中一定要有 inv 对应的 methodsStr
                if (!hasMethod) {
                    logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                            + " not found in callback service interface ,invoke will be ignored."
                            + " please update the api interface. url is:"
                            + invoker.getUrl()) + " ,invocation is :" + inv);
                    return null;
                }
            }
            RpcContext rpcContext = RpcContext.getContext();
            // 将 RemoteAddress 保存到 RpcContext 线程本地变量中，rpcContext 中实际保存的是 InetSocketAddress 实例
            rpcContext.setRemoteAddress(channel.getRemoteAddress());
            // 对 invoker 进行调用，调用链如下
            // Invoker（封装 EchoFilter）-> Invoker（封装 ClassLoaderFilter）-> Invoker（封装 GenericFilter）-> Invoker（封装 ContextFilter）->
            // Invoker（封装 TraceFilter）-> Invoker（封装 TimeoutFilter）-> Invoker（封装 MonitorFilter）-> Invoker（封装 ExceptionFilter）->
            // InvokerWrapper（封装）-> DelegateProviderMetaDataInvoker（封装）-> AbstractProxyInvoker
            Result result = invoker.invoke(inv);
            // 针对两种不同的结果进行处理，就是将结果包装成为 jkd 的 CompletableFuture
            if (result instanceof AsyncRpcResult) {
                return ((AsyncRpcResult) result).getResultFuture().thenApply(r -> (Object) r);

            } else {
                return CompletableFuture.completedFuture(result);
            }
        }

        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                // 对于 Invocation 处理方式
                reply((ExchangeChannel) channel, message);
            } else {
                // 什么都不干
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            // onconnect，本方法主要是根据 url 的 on-connection 参数构建对应的 invocation，再调用 reply 方法
            invoke(channel, ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isDebugEnabled()) {
                logger.debug("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, ON_DISCONNECT_KEY);
        }

        // channel 实际为 HeaderExchangeChannel，内部持有了 netty 的原生 channel，methodKey 这里是 oncconnect，本方法主要是根据 url 的 on-connection 参数构建对应的 invocation，再调用 reply 方法
        private void invoke(Channel channel, String methodKey) {
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey); // 创建的 Invocation 仅仅是保存了一些从 url 中获取的参数
            if (invocation != null) {
                try {
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        // channel 实际为 HeaderExchangeChannel，内部持有了 netty 的原生 channel，methodKey 这里是 oncconnect（这么看 Invocation 并没有什么实质性的作用，仅仅是保存一些变量）
        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }
            // 如果 url 的 methodKey 参数不为空，就构建一个 RpcInvocation，填充 path、group、interface、version、dubbo.stub.event 参数
            RpcInvocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
            // 分别填充 path、group、interface、version、dubbo.stub.event 值
            invocation.setAttachment(PATH_KEY, url.getPath());
            invocation.setAttachment(GROUP_KEY, url.getParameter(GROUP_KEY));
            invocation.setAttachment(INTERFACE_KEY, url.getParameter(INTERFACE_KEY));
            invocation.setAttachment(VERSION_KEY, url.getParameter(VERSION_KEY));
            if (url.getParameter(STUB_EVENT_KEY, false)) {
                invocation.setAttachment(STUB_EVENT_KEY, Boolean.TRUE.toString());
            }

            return invocation;
        }
    };

    public DubboProtocol() {
        INSTANCE = this;
    }

    public static DubboProtocol getDubboProtocol() {
        if (INSTANCE == null) {
            // load 获取 DubboProtocol
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME);
        }

        return INSTANCE;
    }

    public Collection<ExchangeServer> getServers() {
        return Collections.unmodifiableCollection(serverMap.values());
    }

    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }

    Map<String, Exporter<?>> getExporterMap() {
        return exporterMap;
    }

    private boolean isClientSide(Channel channel) {
        // 获取远端 InetSocketAddress
        InetSocketAddress address = channel.getRemoteAddress(); // remote-host:port 192.168.1.104:50397（估计这是指客户端的 ip 和端口号）
        URL url = channel.getUrl(); // dubbo://192.168.1.104:20880/org.apache.dubbo.demo.DemoService?anyhost=true&application=demo-provider&bean.name=org.apache.dubbo.demo.DemoService&bind.ip=192.168.1.104&bind.port=20880&channel.readonly.sent=true&codec=dubbo&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&heartbeat=60000&interface=org.apache.dubbo.demo.DemoService&methods=sayHello&pid=29285&register=true&release=&side=provider&timestamp=1558668993108
        // url 端口号和远端 port 一致且 url 的 ip 和远端 ip 经过 filterLocalHost 函数处理后一致，返回 true
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke = false;
        boolean isStubServiceInvoke = false;
        int port = channel.getLocalAddress().getPort(); // 20880
        String path = inv.getAttachments().get(PATH_KEY);   // org.apache.dubbo.demo.DemoService

        // if it's callback service on client side
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getAttachments().get(STUB_EVENT_KEY)); // dubbo.stub.event
        if (isStubServiceInvoke) {
            // 如果 Invocation 是 StubServiceInvoke，使用远端端口作为端口号
            port = channel.getRemoteAddress().getPort();
        }

        //callback，StubServiceInvok 和 CallBackServiceInvoke 只能是二选一
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            // 从 Invocation 中获取 callback.service.instid
            path += "." + inv.getAttachments().get(CALLBACK_SERVICE_KEY);   // org.apache.dubbo.demo.DemoService.xxx
            // 添加 _isCallBackServiceInvoke 属性
            inv.getAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }
        // 根据各项参数获取 serviceKey，模式 group/serviceName:serviceVersion:port，实际为 org.apache.dubbo.demo.DemoService:20880
        String serviceKey = serviceKey(port, path, inv.getAttachments().get(VERSION_KEY), inv.getAttachments().get(GROUP_KEY));
        // 从缓存中获取 exporter
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        if (exporter == null) {
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " +
                    ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + inv);
        }
        // 获取 exporter 的 Invoker
        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        // dubbo://192.168.1.104:20880/org.apache.dubbo.demo.DemoService?
        // anyhost=true&
        // application=demo-provider&
        // bean.name=org.apache.dubbo.demo.DemoService&
        // bind.ip=192.168.1.104&
        // bind.port=20880&
        // deprecated=false&
        // dubbo=2.0.2&
        // dynamic=true&
        // generic=false&
        // interface=org.apache.dubbo.demo.DemoService&
        // methods=sayHello&
        // pid=9288&
        // register=true&
        // release=&
        // side=provider&
        // timestamp=1558441849441
        URL url = invoker.getUrl();
        // export service.
        // group/serviceName:serviceVersion:port
        String key = serviceKey(url);
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);
        // 缓存 key -> exporter 键值对信息
        exporterMap.put(key, exporter);

        //export an stub service for dispatching event
        // 获取 dubbo.stub.event 和 is_callback_service 属性值，没有获取到的情况下，默认为 false
        Boolean isStubSupportEvent = url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackservice) {
            // 获取 dubbo.stub.event.methods 属性值
            String stubServiceMethods = url.getParameter(STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                // 这里也就是说 dubbo.stub.event 和 dubbo.stub.event.methods 参数必须同时存在
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }

            } else {
                // 缓存 stubServiceMethods，键为 ServiceKey
                stubServiceMethodsMap.put(url.getServiceKey(), stubServiceMethods);
            }
        }

        openServer(url);
        optimizeSerialization(url);

        return exporter;
    }

    private void openServer(URL url) {
        // find server.
        String key = url.getAddress();
        //client can export a service which's only for server to invoke
        boolean isServer = url.getParameter(IS_SERVER_KEY, true);
        if (isServer) {
            ExchangeServer server = serverMap.get(key);
            if (server == null) {
                synchronized (this) {
                    // 双重检查是否存在 key 所对应的 ExchangeServer
                    server = serverMap.get(key);
                    if (server == null) {
                        // 没有的话，创建并缓存
                        serverMap.put(key, createServer(url));
                    }
                }
            } else {
                // server supports reset, use together with override
                server.reset(url);
            }
        }
    }

    private ExchangeServer createServer(URL url) {
        url = URLBuilder.from(url)
                // send readonly event when server closes, it's enabled by default
                // channel.readonly.sent -> true
                .addParameterIfAbsent(RemotingConstants.CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString())
                // enable heartbeat by default
                // heartbeat 心跳检测 60000
                .addParameterIfAbsent(RemotingConstants.HEARTBEAT_KEY, String.valueOf(RemotingConstants.DEFAULT_HEARTBEAT))
                // codec 编解码 dubbo
                .addParameter(RemotingConstants.CODEC_KEY, DubboCodec.NAME)
                .build();
        // server 服务器信息 netty
        String str = url.getParameter(RemotingConstants.SERVER_KEY, RemotingConstants.DEFAULT_REMOTING_SERVER);

        // 指定的 server 信息必须存在
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);
        }

        ExchangeServer server;
        try {
            // 根据
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }

        str = url.getParameter(RemotingConstants.CLIENT_KEY);
        if (str != null && str.length() > 0) {
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }

        return server;
    }

    // 根据 url 获取 optimizer 参数对应的 class，进行相关的 className 和 class 的缓存
    private void optimizeSerialization(URL url) throws RpcException {
        String className = url.getParameter(OPTIMIZER_KEY, "");
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {   // 获取 url 的 optimizer 参数指定的 class
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {    // 要求此 class 为 SerializationOptimizer 的子类或者一致
                throw new RpcException("The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }

            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c); // 缓存对应的 class
            }

            optimizers.add(className);  // 缓存

        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);

        } catch (InstantiationException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);

        } catch (IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        }
    }

    // 此方法主要是通过 getClients 获取到 List<ReferenceCountExchangeClient>，将其封装成为 DubboInvoker 再缓存到 invokers，最后将构建的 DubboInvoker 返回
    // dubbo://192.168.1.101:20880/org.apache.dubbo.demo.DemoService?anyhost=true&application=demo-consumer&bean.name=org.apache.dubbo.demo.DemoService&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.DemoService&lazy=false&methods=sayHello&pid=9095&register=true&register.ip=192.168.1.101&remote.application=demo-provider&side=consumer&sticky=false&timestamp=1559010937536
    @Override   // interface org.apache.dubbo.demo.DemoService
    public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
        // 根据 url 获取 optimizer 参数对应的 class，进行相关的 className 和 class 的缓存
        optimizeSerialization(url);
        // create rpc invoker. getClients 方法主要是根据 url 确定连接数，然后根据 url 和连接数构建 List<ReferenceCountExchangeClient>，将结果转存到 clients 后返回
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers); // 用 DubboInvoker 封装了得到的 List<ReferenceCountExchangeClient>，持有了 HeaderExchangeClient -> NettyClient
        invokers.add(invoker);  // 构建的 invoker 缓存到 DubboProtocol 的 invokers 属性中，同时 DubboInvoker 也引用了 invokers

        return invoker;
    }

    // 此方法主要是根据 url 确定连接数，然后根据 url 和连接数构建 List<ReferenceCountExchangeClient>，将结果转存到 clients 后返回
    // dubbo://192.168.1.101:20880/org.apache.dubbo.demo.DemoService?anyhost=true&application=demo-consumer&bean.name=org.apache.dubbo.demo.DemoService&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.DemoService&lazy=false&methods=sayHello&pid=9095&register=true&register.ip=192.168.1.101&remote.application=demo-provider&side=consumer&sticky=false&timestamp=1559010937536
    private ExchangeClient[] getClients(URL url) {
        // whether to share connection

        boolean useShareConnect = false;

        int connections = url.getParameter(CONNECTIONS_KEY, 0); // connections 这个参数用于指定可连接客户端的数量
        List<ReferenceCountExchangeClient> shareClients = null;
        // if not configured, connection is shared, otherwise, one connection for one service
        if (connections == 0) {
            useShareConnect = true;

            /**
             * The xml configuration should have a higher priority than properties.
             */
            String shareConnectionsStr = url.getParameter(SHARE_CONNECTIONS_KEY, (String) null);    // share-connections
            connections = Integer.parseInt(StringUtils.isBlank(shareConnectionsStr) ? ConfigUtils.getProperty(SHARE_CONNECTIONS_KEY,
                    DEFAULT_SHARE_CONNECTIONS) : shareConnectionsStr);  // 获取 connections 信息
            shareClients = getSharedClient(url, connections);
        }   // 此方法主要是优先尝试根据 url 从 referenceClientMap 中获取对应的 ReferenceCountExchangeClient 列表项，如果没有的话，那么就需要构建 ReferenceCountExchangeClient 列表项
        ExchangeClient[] clients = new ExchangeClient[connections];
        for (int i = 0; i < clients.length; i++) {
            if (useShareConnect) {
                clients[i] = shareClients.get(i);   // 将 shareClients 内容转存到 clients 中

            } else {
                clients[i] = initClient(url);
            }
        }

        return clients;
    }

    /**
     * Get shared connection
     *
     * @param url        dubbo://192.168.1.101:20880/org.apache.dubbo.demo.DemoService?anyhost=true&application=demo-consumer&bean.name=org.apache.dubbo.demo.DemoService&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.DemoService&lazy=false&methods=sayHello&pid=9095&register=true&register.ip=192.168.1.101&remote.application=demo-provider&side=consumer&sticky=false&timestamp=1559010937536
     * @param connectNum connectNum must be greater than or equal to 1
     */ // 此方法主要是优先尝试根据 url 从 referenceClientMap 中获取对应的 ReferenceCountExchangeClient 列表项，如果没有的话，那么就需要构建 ReferenceCountExchangeClient 列表项
    private List<ReferenceCountExchangeClient> getSharedClient(URL url, int connectNum) {   // ReferenceCountExchangeClient -> HeaderExchangeClient -> NettyClient
        String key = url.getAddress();  // 192.168.1.101:20880
        List<ReferenceCountExchangeClient> clients = referenceClientMap.get(key);

        if (checkClientCanUse(clients)) {   // 检查是否为空，或者项不可用
            batchClientRefIncr(clients);    // 增加 url 所对应的列表项的引用数
            return clients;
        }

        locks.putIfAbsent(key, new Object());   // 添加一个 key 所对应的锁对象
        synchronized (locks.get(key)) {
            clients = referenceClientMap.get(key);
            // dubbo check
            if (checkClientCanUse(clients)) {
                batchClientRefIncr(clients);
                return clients;
            }

            // connectNum must be greater than or equal to 1
            connectNum = Math.max(connectNum, 1);   // 至少为一

            // If the clients is empty, then the first initialization is
            if (CollectionUtils.isEmpty(clients)) {
                clients = buildReferenceCountExchangeClientList(url, connectNum);   // 通过 url 构建 ReferenceCountExchangeClient，引用了 HeaderExchangeClient
                referenceClientMap.put(key, clients);   // 缓存 key 和引用链接 clients 的信息

            } else {
                for (int i = 0; i < clients.size(); i++) {
                    ReferenceCountExchangeClient referenceCountExchangeClient = clients.get(i);
                    // If there is a client in the list that is no longer available, create a new one to replace him.
                    if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                        clients.set(i, buildReferenceCountExchangeClient(url)); // 这里是单独构建
                        continue;
                    }

                    referenceCountExchangeClient.incrementAndGetCount();    // 增加引用数
                }
            }

            /**
             * I understand that the purpose of the remove operation here is to avoid the expired url key
             * always occupying this memory space.
             */
            locks.remove(key);
            // 返回 url 对应的 client 列表项
            return clients;
        }
    }

    /**
     * Check if the client list is all available，即检查是否为空，或者项不可用
     *
     * @param referenceCountExchangeClients
     * @return true-available，false-unavailable
     */
    private boolean checkClientCanUse(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {   // 为空直接不可用
            return false;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            // As long as one client is not available, you need to replace the unavailable client with the available one.
            if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                return false;   // Reference 项为空或者关闭，也是不可用
            }
        }

        return true;
    }

    /**
     * Add client references in bulk，增加列表项的引用数
     *
     * @param referenceCountExchangeClients
     */
    private void batchClientRefIncr(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            if (referenceCountExchangeClient != null) {
                referenceCountExchangeClient.incrementAndGetCount();    // 即增加引用
            }
        }
    }

    /**
     * Bulk build client
     *
     * @param url
     * @param connectNum
     * @return
     */
    private List<ReferenceCountExchangeClient> buildReferenceCountExchangeClientList(URL url, int connectNum) {
        List<ReferenceCountExchangeClient> clients = new CopyOnWriteArrayList<>();

        for (int i = 0; i < connectNum; i++) {  // buildReferenceCountExchangeClient 方法主要就是通过 initClient 获取到 HeaderExchangeClient，然后将其封装成为 ReferenceCountExchangeClient 返回
            clients.add(buildReferenceCountExchangeClient(url));
        }

        return clients;
    }

    /**
     * Build a single client,此方法主要就是通过 initClient 获取到 HeaderExchangeClient，然后将其封装成为 ReferenceCountExchangeClient 返回
     *
     * @param url
     * @return
     */
    private ReferenceCountExchangeClient buildReferenceCountExchangeClient(URL url) {
        ExchangeClient exchangeClient = initClient(url);    //此方法主要就是通过 NettyTransporter 获取到 NettyClient，然后在 Exchangers 中将其封装成为 HeaderExchangeClient 返回
        // 通过 ReferenceCountExchangeClient 对 exchangeClient 进行封装，使得能够对其的引用进行计数
        return new ReferenceCountExchangeClient(exchangeClient); // ReferenceCountExchangeClient -> HeaderExchangeClient -> NettyClient
    }

    /**
     * Create new connection，此方法主要就是通过 NettyTransporter 获取到 NettyClient，然后在 Exchangers 中将其封装成为 HeaderExchangeClient 返回
     *
     * @param url
     */
    private ExchangeClient initClient(URL url) {

        // client type setting.优先 client，其次 server，最后 netty
        String str = url.getParameter(RemotingConstants.CLIENT_KEY, url.getParameter(RemotingConstants.SERVER_KEY, RemotingConstants.DEFAULT_REMOTING_CLIENT));

        url = url.addParameter(RemotingConstants.CODEC_KEY, DubboCodec.NAME);   // codec -> dubbo
        // enable heartbeat by default
        url = url.addParameterIfAbsent(RemotingConstants.HEARTBEAT_KEY, String.valueOf(RemotingConstants.DEFAULT_HEARTBEAT)); // heartbeat -> 60000

        // BIO is not allowed since it has severe performance issue.
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {  // str 对应的 Transporter extension 要存在
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " + StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        ExchangeClient client;
        try {
            // connection should be lazy
            if (url.getParameter(LAZY_CONNECT_KEY, false)) {    // lazy 为 true 的话
                client = new LazyConnectExchangeClient(url, requestHandler);

            } else {
                // 也就是通过 Exchangers 的 getExchanger 拿到 Exchanger（实际是 HeaderExchanger），调用它的 connect 方法，它会通过 Transporters.connect 方法得到 NettyClient（实际是
                // 通过 NettyTransporter 得到），然后将其封装成为 HeaderExchangeClient 返回
                client = Exchangers.connect(url, requestHandler);
            }

        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }

        return client;
    }

    @Override
    public void destroy() {
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ExchangeServer server = serverMap.remove(key);

            if (server == null) {
                continue;
            }

            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Close dubbo server: " + server.getLocalAddress());
                }

                server.close(ConfigurationUtils.getServerShutdownTimeout());

            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

        for (String key : new ArrayList<>(referenceClientMap.keySet())) {
            List<ReferenceCountExchangeClient> clients = referenceClientMap.remove(key);

            if (CollectionUtils.isEmpty(clients)) {
                continue;
            }

            for (ReferenceCountExchangeClient client : clients) {
                closeReferenceCountExchangeClient(client);
            }
        }

        stubServiceMethodsMap.clear();
        super.destroy();
    }

    /**
     * close ReferenceCountExchangeClient
     *
     * @param client
     */
    private void closeReferenceCountExchangeClient(ReferenceCountExchangeClient client) {
        if (client == null) {
            return;
        }

        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
            }

            client.close(ConfigurationUtils.getServerShutdownTimeout());

            // TODO
            /**
             * At this time, ReferenceCountExchangeClient#client has been replaced with LazyConnectExchangeClient.
             * Do you need to call client.close again to ensure that LazyConnectExchangeClient is also closed?
             */

        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }
}
