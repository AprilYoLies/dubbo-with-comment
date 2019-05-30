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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOVE_VALUE_PREFIX;

/**
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    // 应该是一个 Class 到 ExtensionLoader 键值对的缓存
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<>();

    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<>();

    // ==============================

    private final Class<?> type;

    private final ExtensionFactory objectFactory;

    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();

    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    private final Map<String, Object> cachedActivates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();
    private volatile Class<?> cachedAdaptiveClass = null;
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;

    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();

    // 在 EXTENSION_LOADERS 中获取 ExtensionLoader 无果的情况下，就会新建一个 ExtensionLoader（此时 type != ExtensionFactory.class）
    // 这里 ExtensionLoader 引用了接口类型 type 和 ExtensionFactory
    private ExtensionLoader(Class<?> type) {
        this.type = type;
        // 通过获取引用 ExtensionFactory.class 的 ExtensionLoader 来获取 ExtensionFactory
        // objectFactory 为 ExtensionFactory 实例
        // 引用 ExtensionFactory 类型的 ExtensionLoader 不会调用 getAdaptiveExtension，其他类型的有 SPI 注解的接口，
        // 第一次调用 getExtensionLoader 方法时，都会执行 getAdaptiveExtension，因为此时 EXTENSION_LOADERS 还没有缓存
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    // 判断接口是否带有 SPI 注解
    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    /**
     * 在初次加载 ServiceBean 时，初始化静态字段会调用此方法
     *
     * @param type 接口类型，代表要获取该接口所对应的 ExtensionLoader，该接口必须要有 SPI
     * @param <T>
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        // 对参数进行验证,不能为空
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        // 必须是接口
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        // 必须带有 SPI 注解
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type +
                    ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }

        // 尝试从 EXTENSION_LOADERS 缓存中进行获取
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            // 没有获取到则新建一个，然后将其放入缓存，这里的 ExtensionLoader 保存了一个 相应接口的引用
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    // For testing purposes only
    public static void resetExtensionLoader(Class type) {
        ExtensionLoader loader = EXTENSION_LOADERS.get(type);
        if (loader != null) {
            // Remove all instances associated with this loader as well
            Map<String, Class<?>> classes = loader.getExtensionClasses();
            for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                EXTENSION_INSTANCES.remove(entry.getValue());
            }
            classes.clear();
            EXTENSION_LOADERS.remove(type);
        }
    }

    // 获取类加载器
    private static ClassLoader findClassLoader() {
        return ClassUtils.getClassLoader(ExtensionLoader.class);
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        // 不考虑分组的情况
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * 获取 url 中的
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names，为 exporter.listener
     * @param group group 此时为 null
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        // 获取 url 中的 key 参数（比如 exporter.listener）
        String value = url.getParameter(key);
        // 这里第二个参数为全部的 listener 的信息，group 为分组信息，这里为 null，条件性的获取 extension
        return getActivateExtension(url, StringUtils.isEmpty(value) ? null : COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    // 第二个参数为全部的 listener 的信息（从 url 中获取），group 为分组信息，这里为 null（特殊情况）
    // 获取的 extension 有一定的排序规则，先是 values 中 default 之前的全部元素，再是非 values 中的全部元素，
    // 最后才是 default 之后的全部 extension 元素
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<>();
        List<String> names = values == null ? new ArrayList<>(0) : Arrays.asList(values);
        // listener 不包括 -default 的参数
        if (!names.contains(REMOVE_VALUE_PREFIX + DEFAULT_KEY)) {
            // 加载全部的 ExtensionClasses 到 cachedClasses 中，同时也会对 cachedActivates 进行填充
            getExtensionClasses();
            // cachedActivates 存储的是类全限定名和对应的 Activate 注解
            for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();
                // 这是 Activate 注解
                Object activate = entry.getValue();

                String[] activateGroup, activateValue;

                if (activate instanceof Activate) {
                    // 获取 Activate 注解的 group 和 value 信息
                    activateGroup = ((Activate) activate).group();
                    activateValue = ((Activate) activate).value();
                } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                    // 这是兼容老版本的 Activate 注解的信息
                    activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                    activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                } else {
                    continue;
                }
                // group 为空或者空串，group 存在于 activateGroup 中
                if (isMatchGroup(group, activateGroup)) {
                    T ext = getExtension(name);
                    // 那这里就是获取 url 中没有指定的 extension ？？
                    if (!names.contains(name)
                            && !names.contains(REMOVE_VALUE_PREFIX + name)
                            // activateValue 为 Activate 注解的 value 值，如果 activateValue 的某一项 存在于 url 的某一个参数，
                            // 并且值不为空，返回 true，keys 为空也是返回 true
                            && isActive(activateValue, url)) {
                        exts.add(ext);
                    }
                }
            }
            // 对这些 extension 进行排序
            exts.sort(ActivateComparator.COMPARATOR);
        }
        // 这里就是用来处理 url 中不包含的 extension 了
        List<T> usrs = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            // 如果 name 不是 - 开头，且 names 不存在 -name 的元素
            if (!name.startsWith(REMOVE_VALUE_PREFIX)
                    && !names.contains(REMOVE_VALUE_PREFIX + name)) {
                // 如果是 default
                if (DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        // 这里也就是说先加 usrs 的 extension，再是 url 中不包括的 extension
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        if (!usrs.isEmpty()) {
            // 再把剩余的 usrs 添加到 exts 中
            exts.addAll(usrs);
        }
        return exts;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    // keys 为 Activate 注解的 value 值，如果 keys 的某一项存在于 url 的某一个参数，并且值不为空，返回 true，keys 为空也是返回 true
    private boolean isActive(String[] keys, URL url) {
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            // 遍历 url 的每一个参数项
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                // 如果参数的 key 存在于 Activate 注解的 value 中，并且 值不为空
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        // 名字不能为空或空串
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    private Holder<Object> getOrCreateHolder(String name) {
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    public Object getLoadedAdaptiveExtensionInstances() {
        return cachedAdaptiveInstance.get();
    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        // 别名不能为空
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        if ("true".equals(name)) {
            return getDefaultExtension();
        }

        // 从 cachedInstances 获取 holder，从 holder -> 获取 Extension 实例
        Holder<Object> holder = getOrCreateHolder(name);
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    // 没有缓存的情况下就直接创建 Extension
                    instance = createExtension(name);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        // 加载所有的 ExtensionClasses
        getExtensionClasses();
        // 这里如果不剔除 true 的情况，就会死循环
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            return null;
        }
        // 根据默认名获取 Extension
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    public Set<String> getSupportedExtensions() {
        // 获取 org.apache.dubbo.common.extension.ExtensionFactory 对应配置文件下的所指定的类
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    @SuppressWarnings("unchecked")
    // 获取自适配的 Extension
    public T getAdaptiveExtension() {
        // 尝试从缓存中获取 AdaptiveExtensionFactory
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    // double check 双重检查
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                            // 创建 AdaptiveExtensionFactory
                            instance = createAdaptiveExtension();
                            // 将创建的 AdaptiveExtensionFactory 放入缓存
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("Failed to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    // 根据别名创建 Extension
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        // 先尝试从 cachedClasses 获取别名为 name 的 class
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
            // 尝试从 EXTENSION_INSTANCES 获取 Extension 实例，此 EXTENSION_INSTANCES 存放的是 Class 到实例的映射对
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                // 没有获取到，那就直接通过 class 创建即可
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            // 根据 setter 方法，为 instance 实例填充 extension 属性
            injectExtension(instance);
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (CollectionUtils.isNotEmpty(wrapperClasses)) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    // 根据 setter 方法，为 wrapper 实例填充 extension 属性
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance (name: " + name + ", class: " +
                    type + ") couldn't be instantiated: " + t.getMessage(), t);
        }
    }

    private T injectExtension(T instance) {
        try {
            // SPI 接口的 type 和 ExtensionLoader 都缓存在 EXTENSION_LOADERS 中， SPI 接口 type -> ExtensionLoader
            // 对于 SPI 接口，除了 ExtensionFactory 的 ExtensionLoader 的 objectFactory 为空以外，其它的 SPI 接口所对应
            // ExtensionLoader 的 objectFactory 属性均一致为 AdaptiveExtensionFactory
            // 这里 objectFactory 不为空，说明 instance 不为 ExtensionFactory 的 Adaptive 实例
            if (objectFactory != null) {
                // 遍历 Adaptive 实例的全部方法
                for (Method method : instance.getClass().getMethods()) {
                    // 这里只关注 Adaptive 实例的 setter 方法
                    if (isSetter(method)) {
                        /**
                         * Check {@link DisableInject} to see if we need auto injection for this property
                         * setter 方法不能有 DisableInject 注解
                         */
                        if (method.getAnnotation(DisableInject.class) != null) {
                            continue;
                        }
                        // 获取 setter 方法的第一个参数类型
                        Class<?> pt = method.getParameterTypes()[0];
                        // pt 不能是九种基本类型和 Array、String、Boolean、Charater、Number、Date，特别的 Array 元素也不能是
                        // Array、String、Boolean、Charater、Number、Date
                        if (ReflectUtils.isPrimitives(pt)) {
                            continue;
                        }
                        try {
                            // 通过 setter 方法得到其所对应的属性名
                            String property = getSetterProperty(method);
                            // objectFactory 实际为 AdaptiveExtensionFactory，根据 setter 方法参数类型和相对应的属性名获取 Extension
                            // 这里的返回值 object 应该为 pt 类型
                            Object object = objectFactory.getExtension(pt, property);
                            if (object != null) {
                                // 为 instance 填充这个 extension 实例
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("Failed to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * <p>
     * return "", if setter name with length less than 3
     */
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     */
    private boolean isSetter(Method method) {
        return method.getName().startsWith("set")
                && method.getParameterTypes().length == 1
                && Modifier.isPublic(method.getModifiers());
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    // 获取 ExtensionClasses
    private Map<String, Class<?>> getExtensionClasses() {
        // 先尝试从缓存中获取
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    // 此方法会加载 type 属性对应的接口的实现类的 class 到 extensionClasses，同时根据不同特性的 class 还会有着针对性的缓存，比如 AdaptiveClass
                    classes = loadExtensionClasses();
                    // 将加载的 ExtensionClasses 放入缓存
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    // synchronized in getExtensionClasses，此方法会加载 type 属性对应的接口的实现类的 class 到 extensionClasses，同时根据不同特性的 class 还会有着针对性的缓存，比如 AdaptiveClass
    private Map<String, Class<?>> loadExtensionClasses() {
        // 缓存 SPI 接口的默认名（如果存在的话）
        cacheDefaultExtensionName();

        Map<String, Class<?>> extensionClasses = new HashMap<>();
        // 从资源路径中获取 extensionClasses
        // 固定就几个路径，文件名为接口的全限定名
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        return extensionClasses;
    }

    /**
     * extract and cache default extension name if exists
     * 如果存在 Extension 的默认名，就将其缓存
     */
    private void cacheDefaultExtensionName() {
        // 缓存 SPI 接口类型的默认名
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        // 一定要有 SPI 注解
        if (defaultAnnotation != null) {
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                // 默认名只能有一个
                if (names.length > 1) {
                    throw new IllegalStateException("More than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                if (names.length == 1) {
                    // 缓存此默认 Extension 的名字
                    cachedDefaultName = names[0];
                }
            }
        }
    }
    // dir -> META-INF/dubbo/internal/      type -> org.apache.dubbo.configcenter.DynamicConfigurationFactory
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type) {
        // META-INF/dubbo/internal/com.alibaba.dubbo.common.extension.ExtensionFactory
        String fileName = dir + type;   // META-INF/dubbo/internal/org.apache.dubbo.configcenter.DynamicConfigurationFactory
        try {
            Enumeration<java.net.URL> urls;
            ClassLoader classLoader = findClassLoader();
            if (classLoader != null) {
                // 通过 classloader 获取 filename 资源的全部 urls
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    // /Users/eva/IdeaProjects/dubbo-with-comment/dubbo-common/target/classes/META-INF/dubbo/internal/org.apache.dubbo.common.extension.ExtensionFactory
                    // /Users/eva/IdeaProjects/dubbo-with-comment/dubbo-config/dubbo-config-spring/target/classes/META-INF/dubbo/internal/org.apache.dubbo.common.extension.ExtensionFactory
                    java.net.URL resourceURL = urls.nextElement();
                    // 将 resourceURL 所对应的文件中的 class 进行加载，然后保存到 extensionClasses
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    // 从 resourceURL 对应文件的属性中加载相应的类，根据类的特性进行不同的缓存，将加载的 class 和 别名保存到 extensionClasses
    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {
            // 通过 BufferedReader 逐行读取 resourceURL 对应的文件
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 剔除 # 及后边的全部内容
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {
                                // 获取等号前后的内容，name 为别名，line 为类的全限定名
                                name = line.substring(0, i).trim();
                                line = line.substring(i + 1).trim();
                            }
                            if (line.length() > 0) {
                                // 加载 line 所代表的类，保存到 extensionClasses
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class (interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    /**
     * 对加载的类 clazz 进行处理，如果是 Adaptive 或者 Wrapper 类，进行缓存
     *
     * @param extensionClasses 保存加载的非 Adaptive 或者 Wrapper 类
     * @param resourceURL      当前加载的资源路径
     * @param clazz            当前加载的类
     * @param name             当前加载的类的别名
     * @throws NoSuchMethodException
     */
    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
        // 加载的类必须是 type 的实现类
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error occurred when loading extension class (interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + " is not subtype of interface.");
        }
        // 如果这个实现类有 Adaptive 注解
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            // 缓存这个 cachedAdaptiveClass
            cacheAdaptiveClass(clazz);
        } else if (isWrapperClass(clazz)) {
            // 如果加载的这个类是一个 wrapper 类，将其进行缓存
            cacheWrapperClass(clazz);
        } else {
            // 不是 Adaptive 类，也不是 Wrapper 类
            clazz.getConstructor();
            if (StringUtils.isEmpty(name)) {
                // 如果资源文件中，没有为实现类设置别名，就根据该类的注解获取别名，没找到就报错
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                // 针对新旧版本的 Activate 注解的类进行缓存
                cacheActivateClass(clazz, names[0]);
                for (String n : names) {
                    // 缓存 clazz 对应的别名
                    cacheName(clazz, n);
                    // 将 class 和 别名保存到 extensionClasses
                    saveInExtensionClass(extensionClasses, clazz, name);
                }
            }
        }
    }

    /**
     * cache name（别名）
     */
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     */
    private void saveInExtensionClass(Map<String, Class<?>> extensionClasses, Class<?> clazz, String name) {
        Class<?> c = extensionClasses.get(name);
        if (c == null) {
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {
            // 不能存在相同别名的不同 class
            throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + name + " on " + c.getName() + " and " + clazz.getName());
        }
    }

    /**
     * 针对新旧版本的 Activate 注解的类进行缓存
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     */
    private void cacheActivateClass(Class<?> clazz, String name) {
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {
            // 如果 class 有 Activate 注解，对其进行缓存，缓存的 activate 类不止一个
            cachedActivates.put(name, activate);
        } else {
            // support com.alibaba.dubbo.common.extension.Activate
            // 兼容旧版本的注解
            com.alibaba.dubbo.common.extension.Activate oldActivate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
            if (oldActivate != null) {
                // 同样是进行缓存
                cachedActivates.put(name, oldActivate);
            }
        }
    }

    /**
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     */
    private void cacheAdaptiveClass(Class<?> clazz) {
        // 缓存这个 cachedAdaptiveClass
        if (cachedAdaptiveClass == null) {
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            // 这就说明对于某一 SPI 接口的实现类而言，只能有一个可以有 Adaptive 注解
            throw new IllegalStateException("More than 1 adaptive class found: "
                    + cachedAdaptiveClass.getClass().getName()
                    + ", " + clazz.getClass().getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     */
    private void cacheWrapperClass(Class<?> clazz) {
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        // 这里说明 wrapper 类的缓存可以缓存不止一个类
        cachedWrapperClasses.add(clazz);
    }

    /**
     * 如果一个类有以某个 SPI 接口为参数的构造函数，说明这个类为 Wrapper 类
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     */
    private boolean isWrapperClass(Class<?> clazz) {
        try {
            // 不报错，就说明有对应的构造函数，是一个 wrapper 类
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        org.apache.dubbo.common.Extension extension = clazz.getAnnotation(org.apache.dubbo.common.Extension.class);
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        return extension.value();
    }

    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }


    private Class<?> getAdaptiveExtensionClass() {
        // 主要是将 META-INF/dubbo、META-INF/dubbo/internal、META-INF/services 目录下的的 SPI 接口名文件中的 class 信息缓存到 cachedClasses 中
        // 同时根据加载的不同的类信息，对其进行缓存，比如 cachedAdaptiveClass
        getExtensionClasses();
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        // 如果在 SPI 接口名文件中，没有找到使用了 Adaptive 注解的实现类（这样 cachedAdaptiveClass 就为空），那么就直接创建 AdaptiveExtensionClass
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    // 直接创建 AdaptiveExtensionClass
    private Class<?> createAdaptiveExtensionClass() {
        // type 为 SPI 接口全限定名，cachedDefaultName 为从注解中获取的 name 信息
        // generate 方法生成 AdaptiveExtensionClass 的代码文本

        // 以 Protocol SPI 接口为例，最终生成的 code 信息
        // package org.apache.dubbo.rpc;
        // import org.apache.dubbo.common.extension.ExtensionLoader;
        // public class Protocol$Adaptive implements org.apache.dubbo.rpc.Protocol {
        //     public void destroy() {
        //         throw new UnsupportedOperationException("The method public abstract void org.apache.dubbo.rpc.Protocol.destroy() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
        //
        //     public int getDefaultPort() {
        //         throw new UnsupportedOperationException("The method public abstract int org.apache.dubbo.rpc.Protocol.getDefaultPort() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
        //
        //     public org.apache.dubbo.rpc.Exporter export(org.apache.dubbo.rpc.Invoker arg0) throws org.apache.dubbo.rpc.RpcException {
        //         if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
        //         if (arg0.getUrl() == null)
        //             throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
        //         org.apache.dubbo.common.URL url = arg0.getUrl();
        //         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
        //         if (extName == null)
        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
        //         org.apache.dubbo.rpc.Protocol extension = (org.apache.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.Protocol.class).getExtension(extName);
        //         return extension.export(arg0);
        //
        //     public org.apache.dubbo.rpc.Invoker refer(java.lang.Class arg0, org.apache.dubbo.common.URL arg1) throws org.apache.dubbo.rpc.RpcException {
        //         if (arg1 == null) throw new IllegalArgumentException("url == null");
        //         org.apache.dubbo.common.URL url = arg1;
        //         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
        //         if (extName == null)
        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
        //         org.apache.dubbo.rpc.Protocol extension = (org.apache.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.Protocol.class).getExtension(extName);
        //         return extension.refer(arg0, arg1);
        //     }
        // }
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();
        // 获取类加载器
        ClassLoader classLoader = findClassLoader();
        // 获取 Compiler 的 ExtensionLoader
        org.apache.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        return compiler.compile(code, classLoader);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
