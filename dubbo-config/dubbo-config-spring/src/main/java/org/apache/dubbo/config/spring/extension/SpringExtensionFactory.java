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
package org.apache.dubbo.config.spring.extension;

import org.apache.dubbo.common.extension.ExtensionFactory;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.config.DubboShutdownHook;
import org.apache.dubbo.config.spring.util.BeanFactoryUtils;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.util.Set;

/**
 * SpringExtensionFactory
 */
public class SpringExtensionFactory implements ExtensionFactory {   // 此 ExtensionFactory 就是从 Spring 容器中获取非 SPI 接口对应的 Adaptive 实现类
    private static final Logger logger = LoggerFactory.getLogger(SpringExtensionFactory.class);

    private static final Set<ApplicationContext> CONTEXTS = new ConcurrentHashSet<ApplicationContext>();
    private static final ApplicationListener SHUTDOWN_HOOK_LISTENER = new ShutdownHookListener();

    /**
     * 保存 ApplicationContext，注册关闭钩子函数，取消在 dubbo 上的关闭钩子函数，同时向 spring 容器添加一个监听器
     *
     * @param context
     */
    public static void addApplicationContext(ApplicationContext context) {
        // 保存 applicationContext
        CONTEXTS.add(context);
        // 如果 context 是 ConfigurableApplicationContext 接口的实例
        if (context instanceof ConfigurableApplicationContext) {
            // spring 框架的方法，向 jvm 注册一个关闭钩子函数，在 jvm 关闭时会调用这个钩子函数来关闭 applicationContext
            ((ConfigurableApplicationContext) context).registerShutdownHook();
            // 因为关闭任务已经由 spring 向 jvm 进行注册了，所以取消 dubbo 自身的关闭钩子函数的注册
            DubboShutdownHook.getDubboShutdownHook().unregister();
        }
        // 通过反射的方式为 context 添加监听器，此监听器会关注 ContextClosedEvent 事件，如果发生了对应的事件，就会销毁对应的 registry 和 protocol
        BeanFactoryUtils.addApplicationListener(context, SHUTDOWN_HOOK_LISTENER);
    }

    public static void removeApplicationContext(ApplicationContext context) {
        CONTEXTS.remove(context);
    }

    public static Set<ApplicationContext> getContexts() {
        return CONTEXTS;
    }

    // currently for test purpose
    public static void clearContexts() {
        CONTEXTS.clear();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getExtension(Class<T> type, String name) {

        //SPI should be get from SpiExtensionFactory
        if (type.isInterface() && type.isAnnotationPresent(SPI.class)) {    // 跟 SpiExtensionFactory 恰好相反，它是完全忽略 SPI 注解的 Type 类型
            return null;
        }

        for (ApplicationContext context : CONTEXTS) {   // 尝试从 Spring 容器中获取 name 名称 type 类型的 bean
            if (context.containsBean(name)) {
                Object bean = context.getBean(name);
                if (type.isInstance(bean)) {    // 找到了同名同类型的 bean 就直接返回
                    return (T) bean;
                }
            }
        }

        logger.warn("No spring extension (bean) named:" + name + ", try to find an extension (bean) of type " + type.getName());

        if (Object.class == type) { // 如果类型为 Object 那就直接返回 null
            return null;
        }

        for (ApplicationContext context : CONTEXTS) {
            try {
                return context.getBean(type);   // 如果没有同时满足 name 和 type 都相同的 bean，那么就尝试着只找到类型匹配的 bean，优先返回找到的第一个 bean
            } catch (NoUniqueBeanDefinitionException multiBeanExe) {
                logger.warn("Find more than 1 spring extensions (beans) of type " + type.getName() + ", will stop auto injection. Please make sure you have specified the concrete parameter type and there's only one extension of that type.");
            } catch (NoSuchBeanDefinitionException noBeanExe) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Error when get spring extension(bean) for type:" + type.getName(), noBeanExe);
                }
            }
        }

        logger.warn("No spring extension (bean) named:" + name + ", type:" + type.getName() + " found, stop get bean.");

        return null;
    }

    private static class ShutdownHookListener implements ApplicationListener {
        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            // 此监听器只会监听 context 关闭事件
            if (event instanceof ContextClosedEvent) {
                // 单例模式获取 DubboShutdownHook 实例
                DubboShutdownHook shutdownHook = DubboShutdownHook.getDubboShutdownHook();
                // z执行销毁方法
                shutdownHook.doDestroy();
            }
        }
    }
}
