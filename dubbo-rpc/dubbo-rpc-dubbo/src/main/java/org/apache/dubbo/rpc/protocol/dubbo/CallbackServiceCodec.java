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
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcInvocation;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.CALLBACK_SERVICE_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.CALLBACK_INSTANCES_LIMIT_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.DEFAULT_CALLBACK_INSTANCES;
import static org.apache.dubbo.common.constants.RpcConstants.CALLBACK_SERVICE_PROXY_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.IS_CALLBACK_SERVICE;
import static org.apache.dubbo.common.constants.RpcConstants.CHANNEL_CALLBACK_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.IS_SERVER_KEY;

/**
 * callback service helper
 */
class CallbackServiceCodec {
    private static final Logger logger = LoggerFactory.getLogger(CallbackServiceCodec.class);

    private static final ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    private static final DubboProtocol protocol = DubboProtocol.getDubboProtocol();
    private static final byte CALLBACK_NONE = 0x0;
    private static final byte CALLBACK_CREATE = 0x1;
    private static final byte CALLBACK_DESTROY = 0x2;
    private static final String INV_ATT_CALLBACK_KEY = "sys_callback_arg-";

    private static byte isCallBack(URL url, String methodName, int argIndex) {
        // parameter callback rule: method-name.parameter-index(starting from 0).callback
        byte isCallback = CALLBACK_NONE;
        if (url != null) {
            String callback = url.getParameter(methodName + "." + argIndex + ".callback");  // sayHello.n.callback
            if (callback != null) {
                if (callback.equalsIgnoreCase("true")) {
                    isCallback = CALLBACK_CREATE;   // 1 为创建
                } else if (callback.equalsIgnoreCase("false")) {
                    isCallback = CALLBACK_DESTROY;  //  2 为销毁
                }
            }
        }
        return isCallback;
    }

    /**
     * export or unexport callback service on client side
     *
     * @param channel
     * @param url
     * @param clazz
     * @param inst
     * @param export
     * @throws IOException
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String exportOrUnexportCallbackService(Channel channel, URL url, Class clazz, Object inst, Boolean export) throws IOException {
        int instid = System.identityHashCode(inst);

        Map<String, String> params = new HashMap<>(3);
        // no need to new client again
        params.put(IS_SERVER_KEY, Boolean.FALSE.toString());
        // mark it's a callback, for troubleshooting
        params.put(IS_CALLBACK_SERVICE, Boolean.TRUE.toString());
        String group = (url == null ? null : url.getParameter(GROUP_KEY));
        if (group != null && group.length() > 0) {
            params.put(GROUP_KEY, group);
        }
        // add method, for verifying against method, automatic fallback (see dubbo protocol)
        params.put(METHODS_KEY, StringUtils.join(Wrapper.getWrapper(clazz).getDeclaredMethodNames(), ","));

        Map<String, String> tmpMap = new HashMap<>(url.getParameters());
        tmpMap.putAll(params);
        tmpMap.remove(VERSION_KEY);// doesn't need to distinguish version for callback
        tmpMap.put(INTERFACE_KEY, clazz.getName());
        URL exportUrl = new URL(DubboProtocol.NAME, channel.getLocalAddress().getAddress().getHostAddress(), channel.getLocalAddress().getPort(), clazz.getName() + "." + instid, tmpMap);

        // no need to generate multiple exporters for different channel in the same JVM, cache key cannot collide.
        String cacheKey = getClientSideCallbackServiceCacheKey(instid);
        String countKey = getClientSideCountKey(clazz.getName());
        if (export) {
            // one channel can have multiple callback instances, no need to re-export for different instance.
            if (!channel.hasAttribute(cacheKey)) {
                if (!isInstancesOverLimit(channel, url, clazz.getName(), instid, false)) {
                    Invoker<?> invoker = PROXY_FACTORY.getInvoker(inst, clazz, exportUrl);
                    // should destroy resource?
                    Exporter<?> exporter = protocol.export(invoker);
                    // this is used for tracing if instid has published service or not.
                    channel.setAttribute(cacheKey, exporter);
                    logger.info("Export a callback service :" + exportUrl + ", on " + channel + ", url is: " + url);
                    increaseInstanceCount(channel, countKey);
                }
            }
        } else {
            if (channel.hasAttribute(cacheKey)) {
                Exporter<?> exporter = (Exporter<?>) channel.getAttribute(cacheKey);
                exporter.unexport();
                channel.removeAttribute(cacheKey);
                decreaseInstanceCount(channel, countKey);
            }
        }
        return String.valueOf(instid);
    }

    /**
     * refer or destroy callback service on server side
     * 处理 channel 中 proxy 和 invoker 等相关的一些属性
     *
     * @param url
     */
    @SuppressWarnings("unchecked")
    private static Object referOrDestroyCallbackService(Channel channel, URL url, Class<?> clazz, Invocation inv, int instid, boolean isRefer) {
        Object proxy = null;
        String invokerCacheKey = getServerSideCallbackInvokerCacheKey(channel, clazz.getName(), instid);
        String proxyCacheKey = getServerSideCallbackServiceCacheKey(channel, clazz.getName(), instid);
        proxy = channel.getAttribute(proxyCacheKey);
        String countkey = getServerSideCountKey(channel, clazz.getName());
        if (isRefer) {  // 这里是 refer
            if (proxy == null) {
                URL referurl = URL.valueOf("callback://" + url.getAddress() + "/" + clazz.getName() + "?" + INTERFACE_KEY + "=" + clazz.getName()); // 重构 URL
                referurl = referurl.addParametersIfAbsent(url.getParameters()).removeParameter(METHODS_KEY);    // 添加缺失的参数
                if (!isInstancesOverLimit(channel, referurl, clazz.getName(), instid, true)) {  // 实例的个数不能超出限制
                    @SuppressWarnings("rawtypes")
                    Invoker<?> invoker = new ChannelWrappedInvoker(clazz, channel, referurl, String.valueOf(instid));
                    proxy = PROXY_FACTORY.getProxy(invoker);
                    channel.setAttribute(proxyCacheKey, proxy); // 缓存 proxy 和 invoker
                    channel.setAttribute(invokerCacheKey, invoker);
                    increaseInstanceCount(channel, countkey);   // 更新 countkey 对应的计数

                    //convert error fail fast .
                    //ignore concurrent problem.
                    Set<Invoker<?>> callbackInvokers = (Set<Invoker<?>>) channel.getAttribute(CHANNEL_CALLBACK_KEY);    // channel.callback.invokers.key
                    if (callbackInvokers == null) {
                        callbackInvokers = new ConcurrentHashSet<Invoker<?>>(1);
                        callbackInvokers.add(invoker);
                        channel.setAttribute(CHANNEL_CALLBACK_KEY, callbackInvokers);   // 更新 invoker 集合的信息
                    }
                    logger.info("method " + inv.getMethodName() + " include a callback service :" + invoker.getUrl() + ", a proxy :" + invoker + " has been created.");
                }
            }
        } else {    // 这里是 destory
            if (proxy != null) {
                Invoker<?> invoker = (Invoker<?>) channel.getAttribute(invokerCacheKey);
                try {
                    Set<Invoker<?>> callbackInvokers = (Set<Invoker<?>>) channel.getAttribute(CHANNEL_CALLBACK_KEY);
                    if (callbackInvokers != null) {
                        callbackInvokers.remove(invoker);   // 从 invoker 集合中移除 invoker
                    }
                    invoker.destroy();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
                // cancel refer, directly remove from the map
                channel.removeAttribute(proxyCacheKey);     // 清除 proxy 和 invoker 等相关的信息
                channel.removeAttribute(invokerCacheKey);
                decreaseInstanceCount(channel, countkey);   // 清理计数
            }
        }
        return proxy;
    }

    private static String getClientSideCallbackServiceCacheKey(int instid) {
        return CALLBACK_SERVICE_KEY + "." + instid;
    }

    private static String getServerSideCallbackServiceCacheKey(Channel channel, String interfaceClass, int instid) {
        return CALLBACK_SERVICE_PROXY_KEY + "." + System.identityHashCode(channel) + "." + interfaceClass + "." + instid;
    }

    private static String getServerSideCallbackInvokerCacheKey(Channel channel, String interfaceClass, int instid) {
        return getServerSideCallbackServiceCacheKey(channel, interfaceClass, instid) + "." + "invoker";
    }

    private static String getClientSideCountKey(String interfaceClass) {
        return CALLBACK_SERVICE_KEY + "." + interfaceClass + ".COUNT";
    }

    private static String getServerSideCountKey(Channel channel, String interfaceClass) {
        return CALLBACK_SERVICE_PROXY_KEY + "." + System.identityHashCode(channel) + "." + interfaceClass + ".COUNT";
    }

    private static boolean isInstancesOverLimit(Channel channel, URL url, String interfaceClass, int instid, boolean isServer) {
        Integer count = (Integer) channel.getAttribute(isServer ? getServerSideCountKey(channel, interfaceClass) : getClientSideCountKey(interfaceClass));
        int limit = url.getParameter(CALLBACK_INSTANCES_LIMIT_KEY, DEFAULT_CALLBACK_INSTANCES); // callbacks -> 默认 1
        if (count != null && count >= limit) {
            //client side error
            throw new IllegalStateException("interface " + interfaceClass + " `s callback instances num exceed providers limit :" + limit
                    + " ,current num: " + (count + 1) + ". The new callback service will not work !!! you can cancle the callback service which exported before. channel :" + channel);
        } else {
            return false;
        }
    }

    private static void increaseInstanceCount(Channel channel, String countkey) {
        try {
            //ignore concurrent problem?
            Integer count = (Integer) channel.getAttribute(countkey);
            if (count == null) {
                count = 1;
            } else {
                count++;
            }
            channel.setAttribute(countkey, count);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private static void decreaseInstanceCount(Channel channel, String countkey) {
        try {
            Integer count = (Integer) channel.getAttribute(countkey);
            if (count == null || count <= 0) {
                return;
            } else {
                count--;
            }
            channel.setAttribute(countkey, count);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    public static Object encodeInvocationArgument(Channel channel, RpcInvocation inv, int paraIndex) throws IOException {
        // get URL directly
        URL url = inv.getInvoker() == null ? null : inv.getInvoker().getUrl();
        byte callbackStatus = isCallBack(url, inv.getMethodName(), paraIndex);
        Object[] args = inv.getArguments();
        Class<?>[] pts = inv.getParameterTypes();
        switch (callbackStatus) {
            case CallbackServiceCodec.CALLBACK_NONE:
                return args[paraIndex];
            case CallbackServiceCodec.CALLBACK_CREATE:
                inv.setAttachment(INV_ATT_CALLBACK_KEY + paraIndex, exportOrUnexportCallbackService(channel, url, pts[paraIndex], args[paraIndex], true));
                return null;
            case CallbackServiceCodec.CALLBACK_DESTROY:
                inv.setAttachment(INV_ATT_CALLBACK_KEY + paraIndex, exportOrUnexportCallbackService(channel, url, pts[paraIndex], args[paraIndex], false));
                return null;
            default:
                return args[paraIndex];
        }
    }

    // 根据 url 的 sayHello.n.callback 参数确定 callbackstatus，然后选择性的执行 referOrDestroyCallbackService 或者直接返回
    public static Object decodeInvocationArgument(Channel channel, RpcInvocation inv, Class<?>[] pts, int paraIndex, Object inObject) throws IOException {
        // if it's a callback, create proxy on client side, callback interface on client side can be invoked through channel
        // need get URL from channel and env when decode
        URL url = null;
        try {
            url = DubboProtocol.getDubboProtocol().getInvoker(channel, inv).getUrl(); // 从 DubboProtocol 缓存的 Exporter 中获取相应的 invoker，invoker 有 url 相关的信息
        } catch (RemotingException e) {
            if (logger.isInfoEnabled()) {
                logger.info(e.getMessage(), e);
            }
            return inObject;
        }
        byte callbackstatus = isCallBack(url, inv.getMethodName(), paraIndex);  // 根据 url 的 sayHello.n.callback 参数确定 callbackstatus
        switch (callbackstatus) {
            case CallbackServiceCodec.CALLBACK_NONE:    // url 的 sayHello.n.callback 参数为 null
                return inObject;
            case CallbackServiceCodec.CALLBACK_CREATE:  // url 的 sayHello.n.callback 参数为 true
                try {
                    // sys_callback_arg-
                    // 处理 channel 中 proxy 和 invoker 等相关的一些属性
                    return referOrDestroyCallbackService(channel, url, pts[paraIndex], inv, Integer.parseInt(inv.getAttachment(INV_ATT_CALLBACK_KEY + paraIndex)), true);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    throw new IOException(StringUtils.toString(e));
                }
            case CallbackServiceCodec.CALLBACK_DESTROY: // url 的 sayHello.n.callback 参数为 false
                try {                                                                                                       // sys_callback_arg-
                    return referOrDestroyCallbackService(channel, url, pts[paraIndex], inv, Integer.parseInt(inv.getAttachment(INV_ATT_CALLBACK_KEY + paraIndex)), false);
                } catch (Exception e) {
                    throw new IOException(StringUtils.toString(e));
                }
            default:
                return inObject;
        }
    }
}
