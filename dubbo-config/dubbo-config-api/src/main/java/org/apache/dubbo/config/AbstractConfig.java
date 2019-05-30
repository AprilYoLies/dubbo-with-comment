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
package org.apache.dubbo.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.CompositeConfiguration;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.config.InmemoryConfiguration;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.MethodUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.rpc.model.ConsumerMethodModel;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOVE_VALUE_PREFIX;

/**
 * Utility methods and public methods for parsing configuration
 *
 * @export
 */
public abstract class AbstractConfig implements Serializable {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractConfig.class);
    private static final long serialVersionUID = 4267533505537413570L;

    /**
     * The maximum length of a <b>parameter's value</b>
     */
    private static final int MAX_LENGTH = 200;

    /**
     * The maximum length of a <b>path</b>
     */
    private static final int MAX_PATH_LENGTH = 200;

    /**
     * The rule qualification for <b>name</b>
     */
    private static final Pattern PATTERN_NAME = Pattern.compile("[\\-._0-9a-zA-Z]+");

    /**
     * The rule qualification for <b>multiply name</b>
     */
    private static final Pattern PATTERN_MULTI_NAME = Pattern.compile("[,\\-._0-9a-zA-Z]+");

    /**
     * The rule qualification for <b>method names</b>
     */
    private static final Pattern PATTERN_METHOD_NAME = Pattern.compile("[a-zA-Z][0-9a-zA-Z]*");

    /**
     * The rule qualification for <b>path</b>
     */
    private static final Pattern PATTERN_PATH = Pattern.compile("[/\\-$._0-9a-zA-Z]+");

    /**
     * The pattern matches a value who has a symbol
     */
    private static final Pattern PATTERN_NAME_HAS_SYMBOL = Pattern.compile("[:*,\\s/\\-._0-9a-zA-Z]+");

    /**
     * The pattern matches a property key
     */
    private static final Pattern PATTERN_KEY = Pattern.compile("[*,\\-._0-9a-zA-Z]+");

    /**
     * The legacy properties container
     */
    private static final Map<String, String> LEGACY_PROPERTIES = new HashMap<String, String>();

    /**
     * The suffix container
     */
    private static final String[] SUFFIXES = new String[]{"Config", "Bean"};

    static {
        LEGACY_PROPERTIES.put("dubbo.protocol.name", "dubbo.service.protocol");
        LEGACY_PROPERTIES.put("dubbo.protocol.host", "dubbo.service.server.host");
        LEGACY_PROPERTIES.put("dubbo.protocol.port", "dubbo.service.server.port");
        LEGACY_PROPERTIES.put("dubbo.protocol.threads", "dubbo.service.max.thread.pool.size");
        LEGACY_PROPERTIES.put("dubbo.consumer.timeout", "dubbo.service.invoke.timeout");
        LEGACY_PROPERTIES.put("dubbo.consumer.retries", "dubbo.service.max.retry.providers");
        LEGACY_PROPERTIES.put("dubbo.consumer.check", "dubbo.service.allow.no.provider");
        LEGACY_PROPERTIES.put("dubbo.service.url", "dubbo.service.address");

        // this is only for compatibility
        DubboShutdownHook.getDubboShutdownHook().register();
    }

    /**
     * The config id
     */
    protected String id;
    protected String prefix;

    private static String convertLegacyValue(String key, String value) {
        if (value != null && value.length() > 0) {
            if ("dubbo.service.max.retry.providers".equals(key)) {
                return String.valueOf(Integer.parseInt(value) - 1);
            } else if ("dubbo.service.allow.no.provider".equals(key)) {
                return String.valueOf(!Boolean.parseBoolean(value));
            }
        }
        return value;
    }

    private static String getTagName(Class<?> cls) {
        String tag = cls.getSimpleName();
        for (String suffix : SUFFIXES) {
            if (tag.endsWith(suffix)) {
                tag = tag.substring(0, tag.length() - suffix.length());
                break;
            }
        }
        return StringUtils.camelToSplitName(tag, "-");
    }

    protected static void appendParameters(Map<String, String> parameters, Object config) {
        appendParameters(parameters, config, null);
    }

    // 从 config 中获取相关参数，添加到 parameters 中
    @SuppressWarnings("unchecked")
    protected static void appendParameters(Map<String, String> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                if (MethodUtils.isGetter(method)) {
                    // 属性的获取是通过 getter 方法
                    // 检查 getter 方法上的注解信息
                    Parameter parameter = method.getAnnotation(Parameter.class);
                    // getter 方法的返回值不能为 Object，必须带 Parameter 注解，注解的 excluded 信息应该为 false
                    if (method.getReturnType() == Object.class || parameter != null && parameter.excluded()) {
                        continue;
                    }
                    String key;
                    // 获取 key，要不就是通过 Parameter 注解获取，要不就是通过方法名得到
                    if (parameter != null && parameter.key().length() > 0) {
                        key = parameter.key();  // 优先使用 Parameter 的 key 作为属性名
                    } else {
                        // 通过方法名得到 key
                        key = calculatePropertyFromGetter(name);
                    }
                    // 通过反射拿到 getter 方法对应的属性
                    Object value = method.invoke(config);
                    String str = String.valueOf(value).trim();
                    if (value != null && str.length() > 0) {
                        if (parameter != null && parameter.escaped()) { // Parameter 注解的 escaped 表示要要进行 URL 编码
                            // 对获取到的属性值进行编码
                            str = URL.encode(str);
                        }
                        if (parameter != null && parameter.append()) {  // Parameter 注解的 append 表示要要进行属性内容的追加，属性的 key 为 default.key 或者 key 本省
                            // parameter 注解的 append 属性为 true
                            String pre = parameters.get(DEFAULT_KEY + "." + key);
                            // default.key 追加属性
                            if (pre != null && pre.length() > 0) {
                                str = pre + "," + str;
                            }
                            // key 追加属性
                            pre = parameters.get(key);
                            if (pre != null && pre.length() > 0) {
                                str = pre + "," + str;
                            }
                        }
                        // 补上参数指定的 prefix
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }
                        parameters.put(key, str);
                    } else if (parameter != null && parameter.required()) { // Parameter 注解的 required 表示该属性值必须存在
                        // 在 parameter 注解的 required 属性为 true 时，相应的 ServiceBean 的属性值不能为空
                        throw new IllegalStateException(config.getClass().getSimpleName() + "." + key + " == null");
                    }
                } else if ("getParameters".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && method.getReturnType() == Map.class) {
                    // 批量处理参数
                    Map<String, String> map = (Map<String, String>) method.invoke(config, new Object[0]);
                    if (map != null && map.size() > 0) {
                        String pre = (prefix != null && prefix.length() > 0 ? prefix + "." : "");
                        // 将获取的参数添加到 parameters 中
                        for (Map.Entry<String, String> entry : map.entrySet()) {
                            parameters.put(pre + entry.getKey().replace('-', '.'), entry.getValue());
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    protected static void appendAttributes(Map<String, Object> parameters, Object config) {
        appendAttributes(parameters, config, null);
    }

    protected static void appendAttributes(Map<String, Object> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                Parameter parameter = method.getAnnotation(Parameter.class);
                if (parameter == null || !parameter.attribute()) {
                    continue;
                }
                String name = method.getName();
                if (MethodUtils.isGetter(method)) {
                    String key;
                    if (parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        key = calculateAttributeFromGetter(name);
                    }
                    Object value = method.invoke(config);
                    if (value != null) {
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }
                        parameters.put(key, value);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    protected static ConsumerMethodModel.AsyncMethodInfo convertMethodConfig2AyncInfo(MethodConfig methodConfig) {
        if (methodConfig == null || (methodConfig.getOninvoke() == null && methodConfig.getOnreturn() == null && methodConfig.getOnthrow() == null)) {
            return null;    // methodConfig 或者 onInvoke、onReturn、onThrow 都为空就直接返回 null
        }

        //check config conflict，即 isReturn 为 false 且 onReturn 不为空或者 onThrow 不为空，这种情况就是属于冲突的情况
        if (Boolean.FALSE.equals(methodConfig.isReturn()) && (methodConfig.getOnreturn() != null || methodConfig.getOnthrow() != null)) {
            throw new IllegalStateException("method config error : return attribute must be set true when onreturn or onthrow has been set.");
        }

        ConsumerMethodModel.AsyncMethodInfo asyncMethodInfo = new ConsumerMethodModel.AsyncMethodInfo();

        asyncMethodInfo.setOninvokeInstance(methodConfig.getOninvoke());    // 设置三种 Instance
        asyncMethodInfo.setOnreturnInstance(methodConfig.getOnreturn());
        asyncMethodInfo.setOnthrowInstance(methodConfig.getOnthrow());

        try {
            String oninvokeMethod = methodConfig.getOninvokeMethod();
            if (StringUtils.isNotEmpty(oninvokeMethod)) {   // 将 methodConfig 中的 onXXX 对象的 onXXXMethod 添加到 asyncMethodInfo 中
                asyncMethodInfo.setOninvokeMethod(getMethodByName(methodConfig.getOninvoke().getClass(), oninvokeMethod));
            }

            String onreturnMethod = methodConfig.getOnreturnMethod();
            if (StringUtils.isNotEmpty(onreturnMethod)) {   // 将 methodConfig 中的 onXXX 对象的 onXXXMethod 添加到 asyncMethodInfo 中
                asyncMethodInfo.setOnreturnMethod(getMethodByName(methodConfig.getOnreturn().getClass(), onreturnMethod));
            }

            String onthrowMethod = methodConfig.getOnthrowMethod();
            if (StringUtils.isNotEmpty(onthrowMethod)) {    // 将 methodConfig 中的 onXXX 对象的 onXXXMethod 添加到 asyncMethodInfo 中
                asyncMethodInfo.setOnthrowMethod(getMethodByName(methodConfig.getOnthrow().getClass(), onthrowMethod));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }

        return asyncMethodInfo;
    }

    private static Method getMethodByName(Class<?> clazz, String methodName) {
        try {
            return ReflectUtils.findMethodByMethodName(clazz, methodName);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }


    protected static void checkExtension(Class<?> type, String property, String value) {
        checkName(property, value);
        if (StringUtils.isNotEmpty(value)
                && !ExtensionLoader.getExtensionLoader(type).hasExtension(value)) {
            throw new IllegalStateException("No such extension " + value + " for " + property + "/" + type.getName());
        }
    }

    /**
     * Check whether there is a <code>Extension</code> who's name (property) is <code>value</code> (special treatment is
     * required)
     *
     * @param type     The Extension type
     * @param property The extension key
     * @param value    The Extension name
     */
    protected static void checkMultiExtension(Class<?> type, String property, String value) {
        checkMultiName(property, value);
        if (StringUtils.isNotEmpty(value)) {
            String[] values = value.split("\\s*[,]+\\s*");
            for (String v : values) {
                if (v.startsWith(REMOVE_VALUE_PREFIX)) {
                    v = v.substring(1);
                }
                if (DEFAULT_KEY.equals(v)) {
                    continue;
                }
                if (!ExtensionLoader.getExtensionLoader(type).hasExtension(v)) {
                    throw new IllegalStateException("No such extension " + v + " for " + property + "/" + type.getName());
                }
            }
        }
    }

    protected static void checkLength(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, null);
    }

    protected static void checkPathLength(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, null);
    }

    protected static void checkName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME);
    }

    protected static void checkNameHasSymbol(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME_HAS_SYMBOL);
    }

    protected static void checkKey(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_KEY);
    }

    protected static void checkMultiName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_MULTI_NAME);
    }

    protected static void checkPathName(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, PATTERN_PATH);
    }

    protected static void checkMethodName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_METHOD_NAME);
    }

    protected static void checkParameterName(Map<String, String> parameters) {
        if (CollectionUtils.isEmptyMap(parameters)) {
            return;
        }
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            checkNameHasSymbol(entry.getKey(), entry.getValue());
        }
    }

    protected static void checkProperty(String property, String value, int maxlength, Pattern pattern) {
        if (StringUtils.isEmpty(value)) {
            return;
        }
        if (value.length() > maxlength) {
            throw new IllegalStateException("Invalid " + property + "=\"" + value + "\" is longer than " + maxlength);
        }
        if (pattern != null) {
            Matcher matcher = pattern.matcher(value);
            if (!matcher.matches()) {
                throw new IllegalStateException("Invalid " + property + "=\"" + value + "\" contains illegal " +
                        "character, only digit, letter, '-', '_' or '.' is legal.");
            }
        }
    }

    protected static Set<String> getSubProperties(Map<String, String> properties, String prefix) {
        return properties.keySet().stream().filter(k -> k.contains(prefix)).map(k -> {
            k = k.substring(prefix.length());
            return k.substring(0, k.indexOf("."));
        }).collect(Collectors.toSet());
    }

    // 根据 setter 方法来获取属性名，这里应该考虑到对应的 getter 方法的 Parameter 注解的 key 和 useKeyAsProperty 值
    private static String extractPropertyName(Class<?> clazz, Method setter) throws Exception {
        String propertyName = setter.getName().substring("set".length());
        Method getter = null;
        try {
            getter = clazz.getMethod("get" + propertyName); // 尝试获取 getter 方法
        } catch (NoSuchMethodException e) {
            getter = clazz.getMethod("is" + propertyName);  // 没找到的话，就用 is 方法替代
        }
        Parameter parameter = getter.getAnnotation(Parameter.class);    // 获取方法的 Parameter 注解
        if (parameter != null && StringUtils.isNotEmpty(parameter.key()) && parameter.useKeyAsProperty()) {
            propertyName = parameter.key(); // 使用注解指定的 property 名字
        } else {
            propertyName = propertyName.substring(0, 1).toLowerCase() + propertyName.substring(1);  // 否则使用传统的属性名获取方式
        }
        return propertyName;
    }

    // 根据 getter 方法还是 is 方法来获取对应的 key 信息
    private static String calculatePropertyFromGetter(String name) {
        int i = name.startsWith("get") ? 3 : 2;
        return StringUtils.camelToSplitName(name.substring(i, i + 1).toLowerCase() + name.substring(i + 1), ".");
    }

    private static String calculateAttributeFromGetter(String getter) {
        int i = getter.startsWith("get") ? 3 : 2;
        return getter.substring(i, i + 1).toLowerCase() + getter.substring(i + 1);
    }

    @Parameter(excluded = true)
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void updateIdIfAbsent(String value) {
        if (StringUtils.isNotEmpty(value) && StringUtils.isEmpty(id)) {
            this.id = value;
        }
    }

    protected void appendAnnotation(Class<?> annotationClass, Object annotation) {
        Method[] methods = annotationClass.getMethods();
        for (Method method : methods) {
            if (method.getDeclaringClass() != Object.class
                    && method.getReturnType() != void.class
                    && method.getParameterTypes().length == 0
                    && Modifier.isPublic(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())) {
                try {
                    String property = method.getName();
                    if ("interfaceClass".equals(property) || "interfaceName".equals(property)) {
                        property = "interface";
                    }
                    String setter = "set" + property.substring(0, 1).toUpperCase() + property.substring(1);
                    Object value = method.invoke(annotation);
                    if (value != null && !value.equals(method.getDefaultValue())) {
                        Class<?> parameterType = ReflectUtils.getBoxedClass(method.getReturnType());
                        if ("filter".equals(property) || "listener".equals(property)) {
                            parameterType = String.class;
                            value = StringUtils.join((String[]) value, ",");
                        } else if ("parameters".equals(property)) {
                            parameterType = Map.class;
                            value = CollectionUtils.toStringMap((String[]) value);
                        }
                        try {
                            Method setterMethod = getClass().getMethod(setter, parameterType);
                            setterMethod.invoke(this, value);
                        } catch (NoSuchMethodException e) {
                            // ignore
                        }
                    }
                } catch (Throwable e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Should be called after Config was fully initialized.
     * // FIXME: this method should be completely replaced by appendParameters
     * <p>
     * 获取当前类的属性值，获取的方式比较特别，是通过 get 方法名来查找的
     *
     * @return
     * @see AbstractConfig#appendParameters(Map, Object, String)
     * <p>
     * Notice! This method should include all properties in the returning map, treat @Parameter differently compared to appendParameters.
     */
    public Map<String, String> getMetaData() {
        Map<String, String> metaData = new HashMap<>();
        Method[] methods = this.getClass().getMethods();
        // 遍历当前类的所有方法
        for (Method method : methods) {
            try {
                String name = method.getName();
                // 方法的判断条件
                if (isMetaMethod(method)) {
                    // 方法所对应的属性
                    String prop = calculateAttributeFromGetter(name);
                    String key;
                    // 方法上的注解
                    Parameter parameter = method.getAnnotation(Parameter.class);
                    // 判断是否使用注解的 key 值作为元信息 key 值，就是 Parameter 注解的 useKeyAsProperty 值为 true，否则直接使用方法名对应的属性名
                    if (parameter != null && parameter.key().length() > 0 && parameter.useKeyAsProperty()) {
                        key = parameter.key();
                    } else {
                        key = prop;
                    }
                    // treat url and configuration differently, the value should always present in configuration though it may not need to present in url.
                    //if (method.getReturnType() == Object.class || parameter != null && parameter.excluded()) {
                    // 方法的返回类型为 Object，直接向 metaData 添加 key null 对
                    if (method.getReturnType() == Object.class) {
                        metaData.put(key, null);
                        continue;
                    }
                    // 通过反射获取属性值
                    Object value = method.invoke(this);
                    String str = String.valueOf(value).trim();  // 应该是就是调用了它的 toString 方法吧
                    if (value != null && str.length() > 0) {
                        // 将 key 和 属性值添加到 metaData 中
                        metaData.put(key, str);
                    } else {
                        metaData.put(key, null);
                    }
                } else if ("getParameters".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && method.getReturnType() == Map.class) {
                    // 如果是 getParameters，那就执行批量操作，即逐个获取 parameters 的每一项，添加到 metaData 中
                    Map<String, String> map = (Map<String, String>) method.invoke(this, new Object[0]);
                    if (map != null && map.size() > 0) {
//                            String pre = (prefix != null && prefix.length() > 0 ? prefix + "." : "");
                        for (Map.Entry<String, String> entry : map.entrySet()) {
                            metaData.put(entry.getKey().replace('-', '.'), entry.getValue());
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        return metaData;
    }

    // 如果标签有 prefix 属性，那么就直接使用，否则使用 dubbo.类名
    @Parameter(excluded = true)
    public String getPrefix() {
        return StringUtils.isNotEmpty(prefix) ? prefix : (CommonConstants.DUBBO + "." + getTagName(this.getClass()));
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    /**
     * TODO: Currently, only support overriding of properties explicitly defined in Config class, doesn't support
     * overriding of customized parameters stored in 'parameters'.
     * ConfigCenterConfig 的刷新方法
     */
    public void refresh() {
        try {
            // 通过单例的 Environment 获取 prefix 和 id 对应的 CompositeConfiguration，这里的 prefix 和 id 应该都是不同标签的属性
            // 如果标签有 prefix 属性，那么就直接使用，否则使用 dubbo.类名
            // compositeConfiguration 存储着不同的配置信息
            CompositeConfiguration compositeConfiguration = Environment.getInstance().getConfiguration(getPrefix(), getId());   // compositeConfiguration 包含了四种类型的配置
            InmemoryConfiguration config = new InmemoryConfiguration(getPrefix(), getId()); // 用于保存当前 bean 的 metadata 信息
            // 将当前类的属性值添加到 config 中，获取的方式比较特别，是通过 getter 方法名来查找的
            config.addProperties(getMetaData());    // 相当于是把当前类中通过 getter 方法获取的属性全部添加到 config 中
            // 根据配置信息确定 config 在 compositeConfiguration 中的位置
            if (Environment.getInstance().isConfigCenterFirst()) {
                // The sequence would be: SystemConfiguration -> AppExternalConfiguration -> ExternalConfiguration -> AbstractConfig -> PropertiesConfiguration
                compositeConfiguration.addConfiguration(3, config);
            } else {
                // The sequence would be: SystemConfiguration -> AbstractConfig -> AppExternalConfiguration -> ExternalConfiguration -> PropertiesConfiguration
                compositeConfiguration.addConfiguration(1, config);
            }

            // loop methods, get override value and set the new value back to method
            Method[] methods = getClass().getMethods();
            for (Method method : methods) {
                if (MethodUtils.isSetter(method)) {
                    try {
                        // 根据对应的 setter 方法，从 compositeConfiguration 中获取相应的属性值，getString 将根据 key 获取的属性值转换为 cls 类型的值返回
                        String value = StringUtils.trim(compositeConfiguration.getString(extractPropertyName(getClass(), method)));
                        // isTypeMatch() is called to avoid duplicate and incorrect update, for example, we have two 'setGeneric' methods in ReferenceConfig.
                        if (StringUtils.isNotEmpty(value) && ClassUtils.isTypeMatch(method.getParameterTypes()[0], value)) {
                            // 如果属性值获取到了，并且和方法的参数类型一致，那么调用相应的方法，将配置的属性填充到当前类中，即起到了刷新作用
                            method.invoke(this, ClassUtils.convertPrimitive(method.getParameterTypes()[0], value)); // 所以这里就是用不同的配置根据优先级对当前类的属性进行填充
                        }
                    } catch (NoSuchMethodException e) {
                        logger.info("Failed to override the property " + method.getName() + " in " +
                                this.getClass().getSimpleName() +
                                ", please make sure every property has getter/setter method provided.");
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to override ", e);
        }
    }

    @Override
    public String toString() {
        try {
            StringBuilder buf = new StringBuilder();
            buf.append("<dubbo:");
            buf.append(getTagName(getClass()));
            Method[] methods = getClass().getMethods();
            for (Method method : methods) {
                try {
                    if (MethodUtils.isGetter(method)) {
                        String name = method.getName();
                        String key = calculateAttributeFromGetter(name);
                        Object value = method.invoke(this);
                        if (value != null) {
                            buf.append(" ");
                            buf.append(key);
                            buf.append("=\"");
                            buf.append(value);
                            buf.append("\"");
                        }
                    }
                } catch (Exception e) {
                    logger.warn(e.getMessage(), e);
                }
            }
            buf.append(" />");
            return buf.toString();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
            return super.toString();
        }
    }

    /**
     * FIXME check @Parameter(required=true) and any conditions that need to match.
     */
    @Parameter(excluded = true)
    public boolean isValid() {
        return true;
    }

    private boolean isMetaMethod(Method method) {
        String name = method.getName();
        if (!(name.startsWith("get") || name.startsWith("is"))) {   // 方法名必须 get 或者 is 开头
            return false;
        }
        if ("get".equals(name)) {   // 非 get 和 getClass 方法
            return false;
        }
        if ("getClass".equals(name)) {
            return false;
        }
        if (!Modifier.isPublic(method.getModifiers())) {    // public 修饰
            return false;
        }
        if (method.getParameterTypes().length != 0) {   // 方法参数为空
            return false;
        }
        if (!ClassUtils.isPrimitive(method.getReturnType())) {  // 方法的返回值要是基本类型，或者其包装类型
            return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj.getClass().getName().equals(this.getClass().getName()))) {
            return false;
        }

        Method[] methods = this.getClass().getMethods();
        for (Method method1 : methods) {
            if (MethodUtils.isGetter(method1) && ClassUtils.isPrimitive(method1.getReturnType())) {
                Parameter parameter = method1.getAnnotation(Parameter.class);
                if (parameter != null && parameter.excluded()) {
                    continue;
                }
                try {
                    Method method2 = obj.getClass().getMethod(method1.getName(), method1.getParameterTypes());
                    Object value1 = method1.invoke(this, new Object[]{});
                    Object value2 = method2.invoke(obj, new Object[]{});
                    if ((value1 != null && value2 != null) && !value1.equals(value2)) {
                        return false;
                    }
                } catch (Exception e) {
                    return true;
                }
            }
        }
        return true;
    }
}
