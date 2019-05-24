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
package org.apache.dubbo.rpc.protocol.dubbo.filter;

import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;

import com.alibaba.fastjson.JSON;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TraceFilter
 */
@Activate(group = CommonConstants.PROVIDER)
public class TraceFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(TraceFilter.class);

    private static final String TRACE_MAX = "trace.max";

    private static final String TRACE_COUNT = "trace.count";

    private static final ConcurrentMap<String, Set<Channel>> tracers = new ConcurrentHashMap<>();

    public static void addTracer(Class<?> type, String method, Channel channel, int max) {
        channel.setAttribute(TRACE_MAX, max);   // trace.max
        channel.setAttribute(TRACE_COUNT, new AtomicInteger()); // trace.count
        String key = method != null && method.length() > 0 ? type.getName() + "." + method : type.getName(); // class-name.method-name
        Set<Channel> channels = tracers.get(key);
        if (channels == null) {
            tracers.putIfAbsent(key, new ConcurrentHashSet<>());
            channels = tracers.get(key);    // key -> set（存储着 Channel）
        }
        channels.add(channel);
    }

    public static void removeTracer(Class<?> type, String method, Channel channel) {
        channel.removeAttribute(TRACE_MAX);
        channel.removeAttribute(TRACE_COUNT);
        String key = method != null && method.length() > 0 ? type.getName() + "." + method : type.getName();
        Set<Channel> channels = tracers.get(key);
        if (channels != null) {
            channels.remove(channel);
        }
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        long start = System.currentTimeMillis();    // 记录开始时间
        Result result = invoker.invoke(invocation); // 传递
        long end = System.currentTimeMillis();      // 记录结束时间
        if (tracers.size() > 0) {
            String key = invoker.getInterface().getName() + "." + invocation.getMethodName();
            Set<Channel> channels = tracers.get(key);
            if (channels == null || channels.isEmpty()) {   // key 可能是 class-name.method-name 或者 class-name，分两种情况查找
                key = invoker.getInterface().getName();
                channels = tracers.get(key);
            }
            if (CollectionUtils.isNotEmpty(channels)) {
                for (Channel channel : new ArrayList<>(channels)) {
                    if (channel.isConnected()) {
                        try {
                            int max = 1;
                            Integer m = (Integer) channel.getAttribute(TRACE_MAX);  // 获取 trace.max
                            if (m != null) {
                                max = m;
                            }
                            int count = 0;
                            AtomicInteger c = (AtomicInteger) channel.getAttribute(TRACE_COUNT);    // 获取 trace.count
                            if (c == null) {
                                c = new AtomicInteger();
                                channel.setAttribute(TRACE_COUNT, c);
                            }
                            count = c.getAndIncrement();    // 对 trace.count 自增
                            if (count < max) {
                                String prompt = channel.getUrl().getParameter(RemotingConstants.PROMPT_KEY, RemotingConstants.DEFAULT_PROMPT);  // prompt
                                // \r\n 127.0.0.1:50038 -> org.apache.dubbo.demo.provider.DemoServiceImpl.sayHello({参数对}) -> hello \r\n elapsed:num ms.\r\n\r\n prompt
                                channel.send("\r\n" + RpcContext.getContext().getRemoteAddress() + " -> "
                                        + invoker.getInterface().getName()
                                        + "." + invocation.getMethodName()
                                        + "(" + JSON.toJSONString(invocation.getArguments()) + ")" + " -> " + JSON.toJSONString(result.getValue())
                                        + "\r\nelapsed: " + (end - start) + " ms."
                                        + "\r\n\r\n" + prompt);
                            }
                            if (count >= max - 1) {
                                channels.remove(channel);   // 超出后移除
                            }
                        } catch (Throwable e) {
                            channels.remove(channel);   // 捕获到异常也进行移除
                            logger.warn(e.getMessage(), e);
                        }
                    } else {
                        channels.remove(channel);
                    }
                }
            }
        }
        return result;
    }

}
