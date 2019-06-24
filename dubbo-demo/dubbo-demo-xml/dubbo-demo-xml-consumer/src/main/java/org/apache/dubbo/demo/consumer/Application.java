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
package org.apache.dubbo.demo.consumer;

import org.apache.dubbo.demo.DemoService;

import org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.io.IOException;
import java.util.Scanner;

public class Application {
    /**
     * In order to make sure multicast registry works, need to specify '-Djava.net.preferIPv4Stack=true' before
     * launch the application
     */
    public static void main(String[] args) throws IOException {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("spring/dubbo-consumer.xml");
        context.start();
        // 留意 RegistryDirectory 的 invokers 是怎么获得的？DubboInvoker 的 clients 属性是怎么得到的？
        // proxy0 -> InvokerInvocationHandler -> MockClusterInvoker -> FailoverClusterInvoker -> （从 RegistryDirectory 中获取）RegistryDirectory$InvokerDelegate ->
        // ListenerInvokerWrapper -> ProtocolFilterWrapper$1（可能是多个，形成 Invoker 链） -> DubboInvoker -> （从 DubboInvoker 的 clients 属性获取）ReferenceCountExchangeClient ->
        // HeaderExchangeHandler -> HeaderExchangeChannel -> NettyClient.send -> （NettyClient 持有 NettyChannel）NettyChannel.send -> （NettyChannel 持有 NioSocketChannel）NioSocketChannel

        DemoService demoService = context.getBean("demoService", DemoService.class);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1; i++) {
            String hello = demoService.sayHello("world - " + i);
            System.out.println("result: " + hello);
        }
        System.out.println(System.currentTimeMillis() - start);
        System.in.read();
        // public class org.apache.dubbo.common.bytecode.Proxy0 extends org.apache.dubbo.common.bytecode.Proxy {
        //     public Object newInstance(java.lang.reflect.InvocationHandler h) {
        //         return new org.apache.dubbo.common.bytecode.proxy0($1);
        //     }
        // }

        // public class org.apache.dubbo.common.bytecode.proxy0 implements com.alibaba.dubbo.rpc.service.EchoService, org.apache.dubbo.demo.DemoService {
        //     public static java.lang.reflect.Method[] methods;
        //     private java.lang.reflect.InvocationHandler handler;
        //     public <init>(
        //     java.lang.reflect.InvocationHandler arg0)
        //
        //     {
        //         handler = $1;
        //     }
        //
        //     public java.lang.String sayHello(java.lang.String arg0) {
        //         Object[] args = new Object[1];
        //         args[0] = ($w) $1;
        //         Object ret = handler.invoke(this, methods[0], args);
        //         return (java.lang.String) ret;
        //     }
        //
        //     public java.lang.Object $echo(java.lang.Object arg0) {
        //         Object[] args = new Object[1];
        //         args[0] = ($w) $1;
        //         Object ret = handler.invoke(this, methods[1], args);
        //         return (java.lang.Object) ret;
        //     }
        // }
    }
}
