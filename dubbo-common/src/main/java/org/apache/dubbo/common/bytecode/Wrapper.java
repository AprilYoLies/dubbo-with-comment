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
package org.apache.dubbo.common.bytecode;

import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.ReflectUtils;

import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;

/**
 * Wrapper.
 */
public abstract class Wrapper {
    private static final Map<Class<?>, Wrapper> WRAPPER_MAP = new ConcurrentHashMap<Class<?>, Wrapper>(); //class wrapper map
    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    private static final String[] OBJECT_METHODS = new String[]{"getClass", "hashCode", "toString", "equals"};
    private static final Wrapper OBJECT_WRAPPER = new Wrapper() {
        @Override
        public String[] getMethodNames() {
            return OBJECT_METHODS;
        }

        @Override
        public String[] getDeclaredMethodNames() {
            return OBJECT_METHODS;
        }

        @Override
        public String[] getPropertyNames() {
            return EMPTY_STRING_ARRAY;
        }

        @Override
        public Class<?> getPropertyType(String pn) {
            return null;
        }

        @Override
        public Object getPropertyValue(Object instance, String pn) throws NoSuchPropertyException {
            throw new NoSuchPropertyException("Property [" + pn + "] not found.");
        }

        @Override
        public void setPropertyValue(Object instance, String pn, Object pv) throws NoSuchPropertyException {
            throw new NoSuchPropertyException("Property [" + pn + "] not found.");
        }

        @Override
        public boolean hasProperty(String name) {
            return false;
        }

        @Override
        public Object invokeMethod(Object instance, String mn, Class<?>[] types, Object[] args) throws NoSuchMethodException {
            if ("getClass".equals(mn)) {
                return instance.getClass();
            }
            if ("hashCode".equals(mn)) {
                return instance.hashCode();
            }
            if ("toString".equals(mn)) {
                return instance.toString();
            }
            if ("equals".equals(mn)) {
                if (args.length == 1) {
                    return instance.equals(args[0]);
                }
                throw new IllegalArgumentException("Invoke method [" + mn + "] argument number error.");
            }
            throw new NoSuchMethodException("Method [" + mn + "] not found.");
        }
    };
    private static AtomicLong WRAPPER_CLASS_COUNTER = new AtomicLong(0);

    /**
     * get wrapper.
     *
     * @param c Class instance.
     * @return Wrapper instance(not null).
     */
    public static Wrapper getWrapper(Class<?> c) {
        // 向上寻找父类型，直到父类型不再实现 ClassGenerator.DC 接口
        while (ClassGenerator.isDynamicClass(c)) // can not wrapper on dynamic class.
        {
            c = c.getSuperclass();
        }

        if (c == Object.class) {
            // 直接返回 new Wrapper(){};
            return OBJECT_WRAPPER;
        }

        // 尝试从 wrapper 缓存中进行获取
        Wrapper ret = WRAPPER_MAP.get(c);
        if (ret == null) {
            // 缓存中没有获取到 wrapper，直接进行构造
            ret = makeWrapper(c);
            WRAPPER_MAP.put(c, ret);
        }
        return ret;
    }

    // 根据 interfaceClass 信息构建 wrapper
    private static Wrapper makeWrapper(Class<?> c) {
        // 不能对 9 中基本类型的包装类型构建 Wrapper
        if (c.isPrimitive()) {
            throw new IllegalArgumentException("Can not create wrapper for primitive type: " + c);
        }

        String name = c.getName();
        ClassLoader cl = ClassUtils.getClassLoader(c);

        // 主要是对三个函数进行拼接
        // setPropertyValue
        // getPropertyValue
        // invokeMethod
        // public void setPropertyValue (Object o, String n, Object v){
        StringBuilder c1 = new StringBuilder("public void setPropertyValue(Object o, String n, Object v){ ");
        // public Object getPropertyValue(Object o, String n){
        StringBuilder c2 = new StringBuilder("public Object getPropertyValue(Object o, String n){ ");
        // public Object invokeMethod(Object o, String n, Class[] p, Object[] v) throws java.lang.reflect.InvocationTargetException{
        StringBuilder c3 = new StringBuilder("public Object invokeMethod(Object o, String n, Class[] p, Object[] v) throws " + InvocationTargetException.class.getName() + "{ ");

        // 根据 name 构造出这样的代码
        // public void setPropertyValue (Object o, String n, Object v){
        //     org.apache.dubbo.demo.DemoService w;
        //     try {
        //         w = ((org.apache.dubbo.demo.DemoService) $1);
        //     } catch (Throwable e) {
        //         throw new IllegalArgumentException(e);
        //     }
        c1.append(name).append(" w; try{ w = ((").append(name).append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");
        // public Object getPropertyValue (Object o, String n){
        //     org.apache.dubbo.demo.DemoService w;
        //     try {
        //         w = ((org.apache.dubbo.demo.DemoService) $1);
        //     } catch (Throwable e) {
        //         throw new IllegalArgumentException(e);
        //     }
        c2.append(name).append(" w; try{ w = ((").append(name).append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");
        // public Object invokeMethod (Object o, String n, Class[]p, Object[]v) throws
        // java.lang.reflect.InvocationTargetException {
        //     org.apache.dubbo.demo.DemoService w;
        //     try {
        //         w = ((org.apache.dubbo.demo.DemoService) $1);
        //     } catch (Throwable e) {
        //         throw new IllegalArgumentException(e);
        //     }
        c3.append(name).append(" w; try{ w = ((").append(name).append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");

        // 保存字段的 name 和 type
        Map<String, Class<?>> pts = new HashMap<>(); // <property name, property types>
        // 保存方法的 desc（签名） 和 instance
        // get method desc.
        // int do(int arg1) => "do(I)I"
        // void do(String arg1,boolean arg2) => "do(Ljava/lang/String;Z)V"
        Map<String, Method> ms = new LinkedHashMap<>(); // <method desc, Method instance>
        // 保存方法名，包括 Object 类声明的方法
        List<String> mns = new ArrayList<>(); // method names.
        // 保存方法名，仅限服务接口声明的方法
        List<String> dmns = new ArrayList<>(); // declaring method names.

        // get all public field.
        for (Field f : c.getFields()) {
            String fn = f.getName();
            Class<?> ft = f.getType();
            // 避开 static 和 transient 修饰的字段
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                continue;
            }

            // 根据 field 进行构建
            // public void setPropertyValue (Object o, String n, Object v){
            //     org.apache.dubbo.demo.DemoService w;
            //     try {
            //         w = ((org.apache.dubbo.demo.DemoService) $1);
            //     } catch (Throwable e) {
            //         throw new IllegalArgumentException(e);
            //     }
            //     if( $2.equals("serviceName")){
            //         w.serviceName=(org.apache.dubbo.demo.DemoService) $3;
            //         return;
            //     }
            c1.append(" if( $2.equals(\"").append(fn).append("\") ){ w.").append(fn).append("=").append(arg(ft, "$3")).append("; return; }");
            // public Object getPropertyValue (Object o, String n){
            //     org.apache.dubbo.demo.DemoService w;
            //     try {
            //         w = ((org.apache.dubbo.demo.DemoService) $1);
            //     } catch (Throwable e) {
            //         throw new IllegalArgumentException(e);
            //     }
            //     if( $2.equals("serviceName")){
            //         return ($w)w.serviceName;
            //     }
            c2.append(" if( $2.equals(\"").append(fn).append("\") ){ return ($w)w.").append(fn).append("; }");
            // 保存字段的 name 和 type
            pts.put(fn, ft);
        }

        Method[] methods = c.getMethods();
        // get all public method.
        // methods 方法不为空，且不全为 Object 声明的方法
        boolean hasMethod = hasMethods(methods);
        if (hasMethod) {
            // 通过 methods 对 c3 进行构造
            c3.append(" try{");
            for (Method m : methods) {
                //ignore Object's method.
                if (m.getDeclaringClass() == Object.class) {
                    continue;
                }

                // 通过方法名进行构造
                String mn = m.getName();
                c3.append(" if( \"").append(mn).append("\".equals( $2 ) ");
                int len = m.getParameterTypes().length;
                // 补上方法参数信息
                // public Object invokeMethod (Object o, String n, Class[]p, Object[]v) throws java.lang.reflect.InvocationTargetException {
                //     org.apache.dubbo.demo.DemoService w;
                //     try {
                //         w = ((org.apache.dubbo.demo.DemoService) $1);
                //     } catch (Throwable e) {
                //         throw new IllegalArgumentException(e);
                //     }
                //     try {
                //         if ("sayHello".equals($2) && $3.length == 1
                c3.append(" && ").append(" $3.length == ").append(len);

                boolean override = false;
                for (Method m2 : methods) {
                    // 如果有跟当前方法同名的其它方法，即存在重写（overwrite）
                    if (m != m2 && m.getName().equals(m2.getName())) {
                        override = true;
                        break;
                    }
                }
                if (override) {
                    if (len > 0) {
                        for (int l = 0; l < len; l++) {
                            // 补上方法参数信息
                            // public Object invokeMethod (Object o, String n, Class[]p, Object[]v) throws java.lang.reflect.InvocationTargetException {
                            //     org.apache.dubbo.demo.DemoService w;
                            //     try {
                            //         w = ((org.apache.dubbo.demo.DemoService) $1);
                            //     } catch (Throwable e) {
                            //         throw new IllegalArgumentException(e);
                            //     }
                            //     try {
                            //         if ("sayHello".equals($2) && $3.length == 1 && $3[l].getName().equals("java.lang.String")){;

                            c3.append(" && ").append(" $3[").append(l).append("].getName().equals(\"")
                                    .append(m.getParameterTypes()[l].getName()).append("\")");
                        }
                    }
                }
                // public Object invokeMethod (Object o, String n, Class[]p, Object[]v) throws java.lang.reflect.InvocationTargetException {
                //     org.apache.dubbo.demo.DemoService w;
                //     try {
                //         w = ((org.apache.dubbo.demo.DemoService) $1);
                //     } catch (Throwable e) {
                //         throw new IllegalArgumentException(e);
                //     }
                //     try {
                //         if ("sayHello".equals($2) && $3.length == 1) {
                c3.append(" ) { ");

                // 拼接方法的返回值
                if (m.getReturnType() == Void.TYPE) {
                    c3.append(" w.").append(mn).append('(').append(args(m.getParameterTypes(), "$4")).append(");").append(" return null;");
                } else {
                    c3.append(" return ($w)w.").append(mn).append('(').append(args(m.getParameterTypes(), "$4")).append(");");
                }

                // 拼接完返回值后
                // public Object invokeMethod (Object o, String n, Class[]p, Object[]v) throws java.lang.reflect.InvocationTargetException {
                //     org.apache.dubbo.demo.DemoService w;
                //     try {
                //         w = ((org.apache.dubbo.demo.DemoService) $1);
                //     } catch (Throwable e) {
                //         throw new IllegalArgumentException(e);
                //     }
                //     try {
                //         if ("sayHello".equals($2) && $3.length == 1) {
                //             return ($w) w.sayHello((java.lang.String) $4[0]);
                //         }
                c3.append(" }");

                // 保存拼接的方法名
                mns.add(mn);
                if (m.getDeclaringClass() == c) {
                    // 保存方法名，仅限服务接口声明的方法
                    dmns.add(mn);
                }
                // 保存方法的签名和方法实例
                // get method desc.
                // int do(int arg1) => "do(I)I"
                // void do(String arg1,boolean arg2) => "do(Ljava/lang/String;Z)V"
                ms.put(ReflectUtils.getDesc(m), m);
            }
            c3.append(" } catch(Throwable e) { ");
            c3.append("     throw new java.lang.reflect.InvocationTargetException(e); ");
            c3.append(" }");
        }

        // 补全括号即剩余信息
        // o 方法调用的目标对象，n 方法名，p 方法的参数类型，v 方法的参数
        // public Object invokeMethod (Object o, String n, Class[]p, Object[]v) throws java.lang.reflect.InvocationTargetException {
        //     org.apache.dubbo.demo.DemoService w;
        //     try {
        //         w = ((org.apache.dubbo.demo.DemoService) $1);
        //     } catch (Throwable e) {
        //         throw new IllegalArgumentException(e);
        //     }
        //     try {
        //         if ("sayHello".equals($2) && $3.length == 1) {
        //             return ($w) w.sayHello((java.lang.String) $4[0]);
        //         }
        //     } catch (Throwable e) {
        //         throw new java.lang.reflect.InvocationTargetException(e);
        //     }
        //     throw new org.apache.dubbo.common.bytecode.NoSuchMethodException("Not found method \"" + $2 + "\" in class org.apache.dubbo.demo.DemoService.");
        // }
        c3.append(" throw new " + NoSuchMethodException.class.getName() + "(\"Not found method \\\"\"+$2+\"\\\" in class " + c.getName() + ".\"); }");

        // deal with get/set method.
        Matcher matcher;
        // 这里 ms 保存的是接口中非 Object 类方法的签名和方法实例
        for (Map.Entry<String, Method> entry : ms.entrySet()) {
            // 方法签名
            String md = entry.getKey();
            // 方法实例
            Method method = entry.getValue();
            // 匹配 getter 方法
            if ((matcher = ReflectUtils.GETTER_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                String pn = propertyName(matcher.group(1));
                c2.append(" if( $2.equals(\"").append(pn).append("\") ){ return ($w)w.").append(method.getName()).append("(); }");
                pts.put(pn, method.getReturnType());
            } else if ((matcher = ReflectUtils.IS_HAS_CAN_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                // 匹配 is、has、can 方法
                String pn = propertyName(matcher.group(1));
                c2.append(" if( $2.equals(\"").append(pn).append("\") ){ return ($w)w.").append(method.getName()).append("(); }");
                pts.put(pn, method.getReturnType());
            } else if ((matcher = ReflectUtils.SETTER_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                // 匹配 setter 方法
                Class<?> pt = method.getParameterTypes()[0];
                String pn = propertyName(matcher.group(1));
                c1.append(" if( $2.equals(\"").append(pn).append("\") ){ w.").append(method.getName()).append("(").append(arg(pt, "$3")).append("); return; }");
                pts.put(pn, pt);
            }
        }
        // c1 的拼接结果
        // public void setPropertyValue (Object o, String n, Object v){
        //     org.apache.dubbo.demo.DemoService w;
        //     try {
        //         w = ((org.apache.dubbo.demo.DemoService) $1);
        //     } catch (Throwable e) {
        //         throw new IllegalArgumentException(e);
        //     }
        //     throw new org.apache.dubbo.common.bytecode.NoSuchPropertyException("Not found property \"" + $2 + "\" field or setter method in class org.apache.dubbo.demo.DemoService.");
        // }
        c1.append(" throw new " + NoSuchPropertyException.class.getName() + "(\"Not found property \\\"\"+$2+\"\\\" field or setter method in class " + c.getName() + ".\"); }");

        // c2 的拼接结果
        //  public Object getPropertyValue (Object o, String n){
        //      org.apache.dubbo.demo.DemoService w;
        //      try {
        //          w = ((org.apache.dubbo.demo.DemoService) $1);
        //      } catch (Throwable e) {
        //          throw new IllegalArgumentException(e);
        //      }
        //      throw new org.apache.dubbo.common.bytecode.NoSuchPropertyException("Not found property \"" + $2 + "\" field or setter method in class org.apache.dubbo.demo.DemoService.");
        //  }
        c2.append(" throw new " + NoSuchPropertyException.class.getName() + "(\"Not found property \\\"\"+$2+\"\\\" field or setter method in class " + c.getName() + ".\"); }");

        // make class
        // 对构建的 wrapper 类进行计数
        long id = WRAPPER_CLASS_COUNTER.getAndIncrement();
        // 根据 classLoader 构建 ClassGenerator
        ClassGenerator cc = ClassGenerator.newInstance(cl);
        // 补全一些类信息
        cc.setClassName((Modifier.isPublic(c.getModifiers()) ? Wrapper.class.getName() : c.getName() + "$sw") + id);
        cc.setSuperClass(Wrapper.class);

        cc.addDefaultConstructor();
        cc.addField("public static String[] pns;"); // property name array.
        cc.addField("public static " + Map.class.getName() + " pts;"); // property type map.
        cc.addField("public static String[] mns;"); // all method name array.
        cc.addField("public static String[] dmns;"); // declared method name array.
        for (int i = 0, len = ms.size(); i < len; i++) {    // 方法的参数类型数组，有几个函数就会有几个数组
            cc.addField("public static Class[] mts" + i + ";");
        }

        cc.addMethod("public String[] getPropertyNames(){ return pns; }");
        cc.addMethod("public boolean hasProperty(String n){ return pts.containsKey($1); }");
        cc.addMethod("public Class getPropertyType(String n){ return (Class)pts.get($1); }");
        cc.addMethod("public String[] getMethodNames(){ return mns; }");
        cc.addMethod("public String[] getDeclaredMethodNames(){ return dmns; }");
        cc.addMethod(c1.toString());
        cc.addMethod(c2.toString());
        cc.addMethod(c3.toString());

        try {
            // 根据 ClassGenerator 生成真正的 Class 对象
            Class<?> wc = cc.toClass();
            // setup static field.
            // 根据上边获取的信息对 Class 对象的某些属性进行填充
            // 字段的 name 和 type
            wc.getField("pts").set(null, pts);
            // pns 集合专门用来保存字段的名字
            wc.getField("pns").set(null, pts.keySet().toArray(new String[0]));
            // mns 集合专门用来保存方法名
            wc.getField("mns").set(null, mns.toArray(new String[0]));
            // 保存方法名，仅限服务接口声明的方法
            wc.getField("dmns").set(null, dmns.toArray(new String[0]));
            int ix = 0;
            // ms 保存的是方法签名和方法实例
            // 遍历方法实例
            for (Method m : ms.values()) {
                // mts 字段用来保存方法的参数类型
                wc.getField("mts" + ix++).set(null, m.getParameterTypes());
            }
            // 返回构建的 Wrapper 实例
            return (Wrapper) wc.newInstance();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            // 对相关资源进行释放
            cc.release();
            ms.clear();
            mns.clear();
            dmns.clear();
        }
    }

    private static String arg(Class<?> cl, String name) {
        if (cl.isPrimitive()) {
            if (cl == Boolean.TYPE) {
                return "((Boolean)" + name + ").booleanValue()";
            }
            if (cl == Byte.TYPE) {
                return "((Byte)" + name + ").byteValue()";
            }
            if (cl == Character.TYPE) {
                return "((Character)" + name + ").charValue()";
            }
            if (cl == Double.TYPE) {
                return "((Number)" + name + ").doubleValue()";
            }
            if (cl == Float.TYPE) {
                return "((Number)" + name + ").floatValue()";
            }
            if (cl == Integer.TYPE) {
                return "((Number)" + name + ").intValue()";
            }
            if (cl == Long.TYPE) {
                return "((Number)" + name + ").longValue()";
            }
            if (cl == Short.TYPE) {
                return "((Number)" + name + ").shortValue()";
            }
            throw new RuntimeException("Unknown primitive type: " + cl.getName());
        }
        return "(" + ReflectUtils.getName(cl) + ")" + name;
    }

    private static String args(Class<?>[] cs, String name) {
        int len = cs.length;
        if (len == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(arg(cs[i], name + "[" + i + "]"));
        }
        return sb.toString();
    }

    private static String propertyName(String pn) {
        return pn.length() == 1 || Character.isLowerCase(pn.charAt(1)) ? Character.toLowerCase(pn.charAt(0)) + pn.substring(1) : pn;
    }

    // methods 方法不为空，且不全为 Object 声明的方法
    private static boolean hasMethods(Method[] methods) {
        if (methods == null || methods.length == 0) {
            return false;
        }
        for (Method m : methods) {
            // 方法不能全部是 Object 类所声明
            if (m.getDeclaringClass() != Object.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * get property name array.
     *
     * @return property name array.
     */
    abstract public String[] getPropertyNames();

    /**
     * get property type.
     *
     * @param pn property name.
     * @return Property type or nul.
     */
    abstract public Class<?> getPropertyType(String pn);

    /**
     * has property.
     *
     * @param name property name.
     * @return has or has not.
     */
    abstract public boolean hasProperty(String name);

    /**
     * get property value.
     *
     * @param instance instance.
     * @param pn       property name.
     * @return value.
     */
    abstract public Object getPropertyValue(Object instance, String pn) throws
            NoSuchPropertyException, IllegalArgumentException;

    /**
     * set property value.
     *
     * @param instance instance.
     * @param pn       property name.
     * @param pv       property value.
     */
    abstract public void setPropertyValue(Object instance, String pn, Object pv) throws
            NoSuchPropertyException, IllegalArgumentException;

    /**
     * get property value.
     *
     * @param instance instance.
     * @param pns      property name array.
     * @return value array.
     */
    public Object[] getPropertyValues(Object instance, String[] pns) throws
            NoSuchPropertyException, IllegalArgumentException {
        Object[] ret = new Object[pns.length];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = getPropertyValue(instance, pns[i]);
        }
        return ret;
    }

    /**
     * set property value.
     *
     * @param instance instance.
     * @param pns      property name array.
     * @param pvs      property value array.
     */
    public void setPropertyValues(Object instance, String[] pns, Object[] pvs) throws
            NoSuchPropertyException, IllegalArgumentException {
        if (pns.length != pvs.length) {
            throw new IllegalArgumentException("pns.length != pvs.length");
        }

        for (int i = 0; i < pns.length; i++) {
            setPropertyValue(instance, pns[i], pvs[i]);
        }
    }

    /**
     * get method name array.
     *
     * @return method name array.
     */
    abstract public String[] getMethodNames();

    /**
     * get method name array.
     *
     * @return method name array.
     */
    abstract public String[] getDeclaredMethodNames();

    /**
     * has method.
     *
     * @param name method name.
     * @return has or has not.
     */
    public boolean hasMethod(String name) {
        for (String mn : getMethodNames()) {
            if (mn.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * invoke method.
     *
     * @param instance instance.
     * @param mn       method name.
     * @param types
     * @param args     argument array.
     * @return return value.
     */
    abstract public Object invokeMethod(Object instance, String mn, Class<?>[] types, Object[] args) throws
            NoSuchMethodException, InvocationTargetException;
}
