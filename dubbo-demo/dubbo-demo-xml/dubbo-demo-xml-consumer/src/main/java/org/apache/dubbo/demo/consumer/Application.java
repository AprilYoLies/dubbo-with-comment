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

import org.springframework.context.support.ClassPathXmlApplicationContext;

public class Application {
    /**
     * In order to make sure multicast registry works, need to specify '-Djava.net.preferIPv4Stack=true' before
     * launch the application
     */
    public static void main(String[] args) {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("spring/dubbo-consumer.xml");
        context.start();
        // 留意 RegistryDirectory 的 invokers 是怎么获得的？DubboInvoker 的 clients 属性是怎么得到的？
        // proxy0 -> InvokerInvocationHandler -> MockClusterInvoker -> FailoverClusterInvoker -> （从 RegistryDirectory 中获取）RegistryDirectory$InvokerDelegate ->
        // ListenerInvokerWrapper -> ProtocolFilterWrapper$1（可能是多个，形成 Invoker 链） -> DubboInvoker -> （从 DubboInvoker 的 clients 属性获取）ReferenceCountExchangeClient ->
        // HeaderExchangeHandler -> HeaderExchangeChannel -> NettyClient.send -> （NettyClient 持有 NettyChannel）NettyChannel.send -> （NettyChannel 持有 NioSocketChannel）NioSocketChannel
        DemoService demoService = context.getBean("demoService", DemoService.class);
        String hello = demoService.sayHello("world");
        System.out.println("result: " + hello);
    }
}
