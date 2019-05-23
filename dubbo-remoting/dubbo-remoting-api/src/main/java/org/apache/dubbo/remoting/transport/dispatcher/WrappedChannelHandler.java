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
package org.apache.dubbo.remoting.transport.dispatcher;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.store.DataStore;
import org.apache.dubbo.common.threadpool.ThreadPool;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.transport.ChannelHandlerDelegate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;

public class WrappedChannelHandler implements ChannelHandlerDelegate {

    protected static final Logger logger = LoggerFactory.getLogger(WrappedChannelHandler.class);
    // 共享的线程池，在 executor 为空或者关闭的情况下，使用这个
    protected static final ExecutorService SHARED_EXECUTOR = Executors.newCachedThreadPool(new NamedThreadFactory("DubboSharedHandler", true));

    protected final ExecutorService executor;

    // 实际为 DecodeHandler 实例
    protected final ChannelHandler handler;

    // dubbo://192.168.1.104:20880/org.apache.dubbo.demo.DemoService?
    // anyhost=true&
    // application=demo-provider&
    // bean.name=org.apache.dubbo.demo.DemoService&
    // bind.ip=192.168.1.104&
    // bind.port=20880&
    // channel.readonly.sent=true&
    // codec=dubbo&
    // deprecated=false&
    // dubbo=2.0.2&
    // dynamic=true&
    // generic=false&
    // heartbeat=60000&
    // interface=org.apache.dubbo.demo.DemoService&
    // methods=sayHello&
    // pid=7595&
    // register=true&
    // release=&
    // side=provider&
    // threadname=DubboS
    // erverHandler-192.168.1.104:20880&
    // timestamp=1558522920136
    protected final URL url;

    // handler 为 DecodeHandler 实例,构造函数中还保存了 componentKey 和线程池相关的关系
    public WrappedChannelHandler(ChannelHandler handler, URL url) {
        this.handler = handler;
        this.url = url;
        // getAdaptiveExtension 方法获取的 extension 实例代码
        // package org.apache.dubbo.common.threadpool;
        // import org.apache.dubbo.common.extension.ExtensionLoader;
        // public class ThreadPool$Adaptive implements org.apache.dubbo.common.threadpool.ThreadPool {
        //     public java.util.concurrent.Executor getExecutor(org.apache.dubbo.common.URL arg0) {
        //         if (arg0 == null) throw new IllegalArgumentException("url == null");
        //         org.apache.dubbo.common.URL url = arg0;
        // 这里获取的 extName 其实是 fixed
        //         String extName = url.getParameter("threadpool", "fixed");
        //         if (extName == null)
        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.common.threadpool.ThreadPool) name from url (" + url.toString() + ") use keys([threadpool])");
        // 这里获取的 extension 实际为 FixedThreadPool
        //         org.apache.dubbo.common.threadpool.ThreadPool extension = (org.apache.dubbo.common.threadpool.ThreadPool) ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.threadpool.ThreadPool.class).getExtension(extName);
        //         return extension.getExecutor(arg0);
        //     }
        // }
        // 这里创建的是 ThreadPoolExecutor，固定线程数大小，因为获取的 extension 是 FixedThreadPool，
        // 它的 org.apache.dubbo.common.threadpool.support.fixed.FixedThreadPool.getExecutor 方法返回 FixedThreadPool
        executor = (ExecutorService) ExtensionLoader.getExtensionLoader(ThreadPool.class).getAdaptiveExtension().getExecutor(url);

        String componentKey = RemotingConstants.EXECUTOR_SERVICE_COMPONENT_KEY;
        if (CONSUMER_SIDE.equalsIgnoreCase(url.getParameter(SIDE_KEY))) {
            // url 的 side 参数值为 consumer，componentKey 赋值为 consumer
            componentKey = CONSUMER_SIDE;
        }
        // 获取的 dataStore 实例为 org.apache.dubbo.common.store.support.SimpleDataStore
        DataStore dataStore = ExtensionLoader.getExtensionLoader(DataStore.class).getDefaultExtension();
        // componentKey -> java.util.concurrent.ExecutorService
        // componentKey 对应一个 ConcurrentHashMap，存储的内容为端口号 -> 线程池
        dataStore.put(componentKey, Integer.toString(url.getPort()), executor);
    }

    public void close() {
        try {
            if (executor != null) {
                executor.shutdown();
            }
        } catch (Throwable t) {
            logger.warn("fail to destroy thread pool of server: " + t.getMessage(), t);
        }
    }

    @Override
    public void connected(Channel channel) throws RemotingException {
        handler.connected(channel);
    }

    @Override
    public void disconnected(Channel channel) throws RemotingException {
        handler.disconnected(channel);
    }

    @Override
    public void sent(Channel channel, Object message) throws RemotingException {
        handler.sent(channel, message);
    }

    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        handler.received(channel, message);
    }

    @Override
    public void caught(Channel channel, Throwable exception) throws RemotingException {
        handler.caught(channel, exception);
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    @Override
    public ChannelHandler getHandler() {
        if (handler instanceof ChannelHandlerDelegate) {
            return ((ChannelHandlerDelegate) handler).getHandler();
        } else {
            return handler;
        }
    }

    public URL getUrl() {
        return url;
    }

    public ExecutorService getExecutorService() {
        ExecutorService cexecutor = executor;
        if (cexecutor == null || cexecutor.isShutdown()) {
            // 在 executor 为空或者关闭的情况下，使用这个 SHARED_EXECUTOR
            cexecutor = SHARED_EXECUTOR;
        }
        return cexecutor;
    }

}
