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

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public class MultiThreadApplication {
    // 拟测试的线程数
    private static int THREADS = 9;

    public static void main(String[] args) throws Exception {
        // 计数栅栏
        CountDownLatch latch = new CountDownLatch(THREADS);
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("spring/dubbo-consumer.xml");
        context.start();
//        BeehiveService demoService = context.getBean("demoService", BeehiveService.class);
        ExecutorService executor = Executors.newCachedThreadPool(new DemoThreadFactory());
        // 多个线程进行访问
        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> {
                try {
                    for (int i1 = 0; i1 < 100; i1++) {
                        DemoService service = context.getBean("demoService", DemoService.class);
                        String hello = service.sayHello(Thread.currentThread().getName() + " - " + i1);
//                        System.out.println("result: " + hello);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }
//        for (int i = 0; i < 2000; i++) {
//            String hello = demoService.say("world - " + i);
//            System.out.println("result: " + hello);
//        }
        latch.await();
    }

    private static class DemoThreadFactory implements ThreadFactory {
        AtomicLong id = new AtomicLong(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setName("MultiThreadConsumer-pool-thread-" + id.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
