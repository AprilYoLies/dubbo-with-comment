/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.dubbo.common.threadlocal;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The internal data structure that stores the threadLocal variables for Netty and all {@link InternalThread}s.
 * Note that this class is for internal use only. Use {@link InternalThread}
 * unless you know what you are doing.
 */
public final class InternalThreadLocalMap {

    private Object[] indexedVariables;

    private static ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = new ThreadLocal<InternalThreadLocalMap>();

    private static final AtomicInteger NEXT_INDEX = new AtomicInteger();

    public static final Object UNSET = new Object();

    public static InternalThreadLocalMap getIfSet() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            return ((InternalThread) thread).threadLocalMap();
        }
        return slowThreadLocalMap.get();
    }

    public static InternalThreadLocalMap get() {
        // 要获取 InternalThreadLocalMap，需要当前线程是 InternalThread
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            // fastGet 应该是相对于 jdk 默认 ThreadLocal 的一种线程本地变量获取方式
            return fastGet((InternalThread) thread);
        }
        // 这就是传统的 jdk ThreadLocal 获取线程本地变量的方式
        return slowGet();
    }

    public static void remove() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            ((InternalThread) thread).setThreadLocalMap(null);
        } else {
            slowThreadLocalMap.remove();
        }
    }

    public static void destroy() {
        slowThreadLocalMap = null;
    }

    // 获取下一个可用的 index
    public static int nextVariableIndex() {
        // NEXT_INDEX 是 AtomicInteger 原子类，可以保证获取的 index 的唯一性、可见性
        int index = NEXT_INDEX.getAndIncrement();
        if (index < 0) {
            NEXT_INDEX.decrementAndGet();
            throw new IllegalStateException("Too many thread-local indexed variables");
        }
        return index;
    }

    public static int lastVariableIndex() {
        return NEXT_INDEX.get() - 1;
    }

    private InternalThreadLocalMap() {
        indexedVariables = newIndexedVariableTable();
    }

    // 通过 InternalThreadLocal 的唯一 id 进行查找,数组结构，查找快
    public Object indexedVariable(int index) {
        // 通过这句代码，可以想到 InternalThreadLocalMap 的内部是通过数组的方式进行存储的
        Object[] lookup = indexedVariables;
        // 通过 InternalThreadLocal 的唯一 id 进行查找，因为内部是数组结构，所以被称为 fastGet？？
        // 如果查找的 InternalThreadLocal 的唯一 id 大于 indexedVariables 的长度，直接返回一个新 Object
        return index < lookup.length ? lookup[index] : UNSET;
    }

    /**
     * @return {@code true} if and only if a new thread-local variable has been created
     */
    public boolean setIndexedVariable(int index, Object value) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object oldValue = lookup[index];
            lookup[index] = value;
            return oldValue == UNSET;
        } else {
            expandIndexedVariableTableAndSet(index, value);
            return true;
        }
    }

    public Object removeIndexedVariable(int index) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object v = lookup[index];
            lookup[index] = UNSET;
            return v;
        } else {
            return UNSET;
        }
    }

    public int size() {
        int count = 0;
        for (Object o : indexedVariables) {
            if (o != UNSET) {
                ++count;
            }
        }

        //the fist element in `indexedVariables` is a set to keep all the InternalThreadLocal to remove
        //look at method `addToVariablesToRemove`
        return count - 1;
    }

    private static Object[] newIndexedVariableTable() {
        Object[] array = new Object[32];
        Arrays.fill(array, UNSET);
        return array;
    }

    // fastGet 应该是相对于 jdk 默认 ThreadLocal 的一种线程本地变量获取方式
    private static InternalThreadLocalMap fastGet(InternalThread thread) {
        // 获取 InternalThread 的 threadLoaclMap，这是自定义的 InternalThread 的一个特殊 map
        InternalThreadLocalMap threadLocalMap = thread.threadLocalMap();
        if (threadLocalMap == null) {
            // 采用的延迟加载，即在真正使用时才会进行初始化
            thread.setThreadLocalMap(threadLocalMap = new InternalThreadLocalMap());
        }
        // 返回自定义线程中的 threadLocalMap
        return threadLocalMap;
    }

    // 这就是传统的 jdk ThreadLocal 获取线程本地变量的方式
    private static InternalThreadLocalMap slowGet() {
        // slowThreadLocalMap 就是一个 jdk 的 ThreadLocal 变量，内部保存的是一个 InternalThreadLocalMap
        ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = InternalThreadLocalMap.slowThreadLocalMap;
        // 拿到 ThreadLocal 中保存的那个 InternalThreadLocalMap
        InternalThreadLocalMap ret = slowThreadLocalMap.get();
        if (ret == null) {
            // 也是采用的延迟加载的方式，在真正使用的时候才进行初始化
            ret = new InternalThreadLocalMap();
            slowThreadLocalMap.set(ret);
        }
        return ret;
    }

    private void expandIndexedVariableTableAndSet(int index, Object value) {
        Object[] oldArray = indexedVariables;
        final int oldCapacity = oldArray.length;
        int newCapacity = index;
        newCapacity |= newCapacity >>> 1;
        newCapacity |= newCapacity >>> 2;
        newCapacity |= newCapacity >>> 4;
        newCapacity |= newCapacity >>> 8;
        newCapacity |= newCapacity >>> 16;
        newCapacity++;

        Object[] newArray = Arrays.copyOf(oldArray, newCapacity);
        Arrays.fill(newArray, oldCapacity, newArray.length, UNSET);
        newArray[index] = value;
        indexedVariables = newArray;
    }
}
