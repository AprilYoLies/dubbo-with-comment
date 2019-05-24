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
package org.apache.dubbo.rpc.proxy.javassist;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.bytecode.Proxy;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.proxy.AbstractProxyFactory;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;
import org.apache.dubbo.rpc.proxy.InvokerInvocationHandler;

/**
 * JavaassistRpcProxyFactory
 */
public class JavassistProxyFactory extends AbstractProxyFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Invoker<T> invoker, Class<?>[] interfaces) {
        return (T) Proxy.getProxy(interfaces).newInstance(new InvokerInvocationHandler(invoker));
    }

    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) {
        // TODO Wrapper cannot handle this scenario correctly: the classname contains '$'
        // 手动生成字节码，然后构建 Wrapper 实例
        // 这是得到的 wrapper 实例的源码
        // public class Wrapper1 extends org.apache.dubbo.common.bytecode.Wrapper {
        //     // mns 集合专门用来保存字段的名字
        //     public static String[] pns;
        //     // 字段的 name 和 type
        //     public static java.util.Map pts;
        //     // mns 集合专门用来保存方法名
        //     public static String[] mns;
        //     // dmns 集合专门用来保存方法的声明类
        //     public static String[] dmns;
        //     // mts 字段用来保存方法的参数类型
        //     public static Class[] mts0;
        //
        //     public String[] getPropertyNames() {
        //         return pns;
        //     }
        //
        //     public boolean hasProperty(String n) {
        //         return pts.containsKey($1);
        //     }
        //
        //     public Class getPropertyType(String n) {
        //         return (Class) pts.get($1);
        //     }
        //
        //     public String[] getMethodNames() {
        //         return mns;
        //     }
        //
        //     public String[] getDeclaredMethodNames() {
        //         return dmns;
        //     }
        //
        //     public void setPropertyValue(Object o, String n, Object v) {
        //         org.apache.dubbo.demo.provider.DemoServiceImpl w;
        //         try {
        //             w = ((org.apache.dubbo.demo.provider.DemoServiceImpl) $1);
        //         } catch (Throwable e) {
        //             throw new IllegalArgumentException(e);
        //         }
        //         throw new org.apache.dubbo.common.bytecode.NoSuchPropertyException("Not found property \"" + $2 + "\" field or setter method in class org.apache.dubbo.demo.provider.DemoServiceImpl.");
        //     }
        //
        //     public Object getPropertyValue(Object o, String n) {
        //         org.apache.dubbo.demo.provider.DemoServiceImpl w;
        //         try {
        //             w = ((org.apache.dubbo.demo.provider.DemoServiceImpl) $1);
        //         } catch (Throwable e) {
        //             throw new IllegalArgumentException(e);
        //         }
        //         throw new org.apache.dubbo.common.bytecode.NoSuchPropertyException("Not found property \"" + $2 + "\" field or setter method in class org.apache.dubbo.demo.provider.DemoServiceImpl.");
        //     }
        //
        //     public Object invokeMethod(Object o, String n, Class[] p, Object[] v) throws java.lang.reflect.InvocationTargetException {
        //         org.apache.dubbo.demo.provider.DemoServiceImpl w;
        //         try {
        // 将第一个参数转换为 DemoServiceImpl
        //             w = ((org.apache.dubbo.demo.provider.DemoServiceImpl) $1);
        //         } catch (Throwable e) {
        //             throw new IllegalArgumentException(e);
        //         }
        //         try {
        //             if ("sayHello".equals($2) && $3.length == 1) {
        //                 return ($w) w.sayHello((java.lang.String) $4[0]);
        //             }
        //         } catch (Throwable e) {
        //             throw new java.lang.reflect.InvocationTargetException(e);
        //         }
        //         throw new org.apache.dubbo.common.bytecode.NoSuchMethodException("Not found method \"" + $2 + "\" in class org.apache.dubbo.demo.provider.DemoServiceImpl.");
        //     }
        // }
        final Wrapper wrapper = Wrapper.getWrapper(proxy.getClass().getName().indexOf('$') < 0 ? proxy.getClass() : type);
        // 返回的是 Invoker，实际要调用的是 doInvoke 方法，proxy, type, url 都被保存到字段中了
        return new AbstractProxyInvoker<T>(proxy, type, url) {
            @Override
            protected Object doInvoke(T proxy, String methodName,
                                      Class<?>[] parameterTypes,
                                      Object[] arguments) throws Throwable {
                // 根本还是调用的 wrapper 的 invokeMethod 方法
                return wrapper.invokeMethod(proxy, methodName, parameterTypes, arguments);
            }
        };
    }

}
