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
package org.apache.dubbo.rpc.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.RpcInvocation;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.dubbo.common.constants.RpcConstants.$INVOKE;
import static org.apache.dubbo.common.constants.RpcConstants.AUTO_ATTACH_INVOCATIONID_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.ID_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.ASYNC_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.FUTURE_GENERATED_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.FUTURE_RETURNTYPE_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.RETURN_KEY;

/**
 * RpcUtils
 */
public class RpcUtils {

    private static final Logger logger = LoggerFactory.getLogger(RpcUtils.class);
    private static final AtomicLong INVOKE_ID = new AtomicLong(0);

    public static Class<?> getReturnType(Invocation invocation) {
        try {
            if (invocation != null && invocation.getInvoker() != null
                    && invocation.getInvoker().getUrl() != null
                    && !invocation.getMethodName().startsWith("$")) {
                String service = invocation.getInvoker().getUrl().getServiceInterface();
                if (StringUtils.isNotEmpty(service)) {
                    Class<?> invokerInterface = invocation.getInvoker().getInterface();
                    Class<?> cls = invokerInterface != null ? ReflectUtils.forName(invokerInterface.getClassLoader(), service)
                            : ReflectUtils.forName(service);
                    Method method = cls.getMethod(invocation.getMethodName(), invocation.getParameterTypes());
                    if (method.getReturnType() == void.class) {
                        return null;
                    }
                    return method.getReturnType();
                }
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        return null;
    }

    // TODO why not get return type when initialize Invocation?
    public static Type[] getReturnTypes(Invocation invocation) {
        try {
            if (invocation != null && invocation.getInvoker() != null
                    && invocation.getInvoker().getUrl() != null
                    && !invocation.getMethodName().startsWith("$")) {
                String service = invocation.getInvoker().getUrl().getServiceInterface();
                if (StringUtils.isNotEmpty(service)) {
                    Class<?> invokerInterface = invocation.getInvoker().getInterface();
                    Class<?> cls = invokerInterface != null ? ReflectUtils.forName(invokerInterface.getClassLoader(), service)
                            : ReflectUtils.forName(service);
                    Method method = cls.getMethod(invocation.getMethodName(), invocation.getParameterTypes());
                    if (method.getReturnType() == void.class) {
                        return null;
                    }
                    Class<?> returnType = method.getReturnType();
                    Type genericReturnType = method.getGenericReturnType();
                    if (Future.class.isAssignableFrom(returnType)) {
                        if (genericReturnType instanceof ParameterizedType) {
                            Type actualArgType = ((ParameterizedType) genericReturnType).getActualTypeArguments()[0];
                            if (actualArgType instanceof ParameterizedType) {
                                returnType = (Class<?>) ((ParameterizedType) actualArgType).getRawType();
                                genericReturnType = actualArgType;
                            } else {
                                returnType = (Class<?>) actualArgType;
                                genericReturnType = returnType;
                            }
                        } else {
                            returnType = null;
                            genericReturnType = null;
                        }
                    }
                    return new Type[]{returnType, genericReturnType};
                }
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        return null;
    }

    public static Long getInvocationId(Invocation inv) {
        String id = inv.getAttachment(ID_KEY);
        return id == null ? null : new Long(id);
    }

    /**
     * Idempotent operation: invocation id will be added in async operation by default
     *
     * @param url
     * @param inv
     */ // 如果 invocation 的 async 参数为 true，或者 url 的 methodName 或 methodName.invocationid.autoattach 属性为 true，就为 inv 添加一个 id 属性
    public static void attachInvocationIdIfAsync(URL url, Invocation inv) {
        if (isAttachInvocationId(url, inv) && getInvocationId(inv) == null && inv instanceof RpcInvocation) {
            ((RpcInvocation) inv).setAttachment(ID_KEY, String.valueOf(INVOKE_ID.getAndIncrement()));
        }
    }

    private static boolean isAttachInvocationId(URL url, Invocation invocation) {
        String value = url.getMethodParameter(invocation.getMethodName(), AUTO_ATTACH_INVOCATIONID_KEY);    // 优先获取 methodName 再 methodName.invocationid.autoattach
        if (value == null) {
            // add invocationid in async operation by default
            return isAsync(url, invocation);    // 看 invocation 的 async 参数是否为 true
        } else if (Boolean.TRUE.toString().equalsIgnoreCase(value)) {
            return true;
        } else {
            return false;
        }
    }

    public static String getMethodName(Invocation invocation) {
        if ($INVOKE.equals(invocation.getMethodName())
                && invocation.getArguments() != null
                && invocation.getArguments().length > 0
                && invocation.getArguments()[0] instanceof String) {
            return (String) invocation.getArguments()[0];
        }
        return invocation.getMethodName();
    }

    public static Object[] getArguments(Invocation invocation) {
        if ($INVOKE.equals(invocation.getMethodName())
                && invocation.getArguments() != null
                && invocation.getArguments().length > 2
                && invocation.getArguments()[2] instanceof Object[]) {
            return (Object[]) invocation.getArguments()[2];
        }
        return invocation.getArguments();
    }

    public static Class<?>[] getParameterTypes(Invocation invocation) {
        if ($INVOKE.equals(invocation.getMethodName())
                && invocation.getArguments() != null
                && invocation.getArguments().length > 1
                && invocation.getArguments()[1] instanceof String[]) {
            String[] types = (String[]) invocation.getArguments()[1];
            if (types == null) {
                return new Class<?>[0];
            }
            Class<?>[] parameterTypes = new Class<?>[types.length];
            for (int i = 0; i < types.length; i++) {
                parameterTypes[i] = ReflectUtils.forName(types[0]);
            }
            return parameterTypes;
        }
        return invocation.getParameterTypes();
    }

    // 看 invocation 的 async 参数是否为 true，然后是查看 url 的 methodName.async 是否为 true
    public static boolean isAsync(URL url, Invocation inv) {
        boolean isAsync;
        if (Boolean.TRUE.toString().equals(inv.getAttachment(ASYNC_KEY))) {
            isAsync = true; // 优先从 inv 中获取 ASYNC_KEY，如果获取到了并且为 true，返回 true
        } else {
            isAsync = url.getMethodParameter(getMethodName(inv), ASYNC_KEY, false); // 尝试从 url 中获取 method-name.async 属性
        }
        return isAsync; // 这里说明 url 和 inv 任意 async 属性为 true 就返回 true
    }

    // 如果 inv 中的 future_returntype 参数指定为 true
    public static boolean isReturnTypeFuture(Invocation inv) {
        return Boolean.TRUE.toString().equals(inv.getAttachment(FUTURE_RETURNTYPE_KEY));
    }

    public static boolean hasFutureReturnType(Method method) {
        return CompletableFuture.class.isAssignableFrom(method.getReturnType());
    }
    // 根据 url 和 inv 确定 one-way 信息
    public static boolean isOneway(URL url, Invocation inv) {
        boolean isOneway;
        if (Boolean.FALSE.toString().equals(inv.getAttachment(RETURN_KEY))) {   // inv 的 return 属性为 false
            isOneway = true;
        } else {
            isOneway = !url.getMethodParameter(getMethodName(inv), RETURN_KEY, true);   // 或者 url 的 method-name.return 属性为 true
        }
        return isOneway;    // 此两种情况都说明是 one-way
    }
    // 去掉 inv 附件中的 async 和 future_generated 后返回
    public static Map<String, String> getNecessaryAttachments(Invocation inv) {
        Map<String, String> attachments = new HashMap<>(inv.getAttachments());
        attachments.remove(ASYNC_KEY);  // async
        attachments.remove(FUTURE_GENERATED_KEY);   // future_generated
        return attachments;
    }

}
