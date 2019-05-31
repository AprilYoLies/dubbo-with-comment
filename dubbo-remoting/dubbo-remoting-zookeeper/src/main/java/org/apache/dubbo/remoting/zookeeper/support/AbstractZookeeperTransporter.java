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
package org.apache.dubbo.remoting.zookeeper.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * AbstractZookeeperTransporter is abstract implements of ZookeeperTransporter.
 * <p>
 * If you want to extends this, implements createZookeeperClient.
 */
public abstract class AbstractZookeeperTransporter implements ZookeeperTransporter {
    private static final Logger logger = LoggerFactory.getLogger(ZookeeperTransporter.class);
    private final Map<String, ZookeeperClient> zookeeperClientMap = new ConcurrentHashMap<>();

    /**
     * share connnect for registry, metadata, etc..
     * <p>
     * Make sure the connection is connected.
     *
     * @param url
     * @return
     */
    @Override
    public ZookeeperClient connect(URL url) {
        ZookeeperClient zookeeperClient;
        // 获取 url 主机地址及备用地址
        List<String> addressList = getURLBackupAddress(url);
        // The field define the zookeeper server , including protocol, host, port, username, password
        // fetchAndUpdateZookeeperClientCache 这个方法，会查看 addressList 中是否有一个地址是出于连接状态，
        // 如果有，就返回这个连接，同时将所有的地址都使用这个 zookeeperClient，并缓存到 zookeeperClientMap 中
        if ((zookeeperClient = fetchAndUpdateZookeeperClientCache(addressList)) != null && zookeeperClient.isConnected()) {
            logger.info("find valid zookeeper client from the cache for address: " + url);
            return zookeeperClient;
        }
        // avoid creating too many connections， so add lock
        // 如果执行到这里，就说明所有的地址，没有一个是出于连接状态的
        synchronized (zookeeperClientMap) {
            // 双重检查
            if ((zookeeperClient = fetchAndUpdateZookeeperClientCache(addressList)) != null && zookeeperClient.isConnected()) {
                logger.info("find valid zookeeper client from the cache for address: " + url);
                return zookeeperClient;
            }   // toClientUrl 之前：zookeeper://127.0.0.1:2181/ConfigCenterConfig?address=zookeeper://127.0.0.1:2181&check=true&configFile=dubbo.properties&group=dubbo&highestPriority=false&namespace=dubbo&prefix=dubbo.config-center&timeout=3000&valid=true
                // toClientUrl 之后：zookeeper://127.0.0.1:2181/org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter?timeout=3000      替换了路径，只保留了 timeout 和 backup 参数
            // 创建 ZookeeperClient 实际是创建的 CuratorZookeeperClient
            zookeeperClient = createZookeeperClient(toClientURL(url));
            logger.info("No valid zookeeper client found from cache, therefore create a new client for url. " + url);
            // 所有的地址都使用这个已经连接的 zookeeperClient，并保存到 zookeeperClientMap
            writeToClientMap(addressList, zookeeperClient);
        }
        return zookeeperClient;
    }

    /**
     * @param url the url that will create zookeeper connection .
     *            The url in AbstractZookeeperTransporter#connect parameter is rewritten by this one.
     *            such as: zookeeper://127.0.0.1:2181/org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter
     * @return
     */
    protected abstract ZookeeperClient createZookeeperClient(URL url);

    /**
     * get the ZookeeperClient from cache, the ZookeeperClient must be connected.
     * <p>
     * It is not private method for unit test.
     *
     * @param addressList
     * @return
     */
    ZookeeperClient fetchAndUpdateZookeeperClientCache(List<String> addressList) {

        ZookeeperClient zookeeperClient = null;
        // 遍历地址，如果缓存中存在这个地址，并且已经是连接状态就终止遍历
        for (String address : addressList) {
            if ((zookeeperClient = zookeeperClientMap.get(address)) != null && zookeeperClient.isConnected()) {
                break;
            }
        }
        if (zookeeperClient != null && zookeeperClient.isConnected()) {
            // 所有的地址都使用这个已经连接的 zookeeperClient，并保存到 zookeeperClientMap
            writeToClientMap(addressList, zookeeperClient);
        }
        return zookeeperClient;
    }

    /**
     * get all zookeeper urls (such as :zookeeper://127.0.0.1:2181?127.0.0.1:8989,127.0.0.1:9999)
     * 获取 url 主机地址及备用地址
     *
     * @param url such as:zookeeper://127.0.0.1:2181?127.0.0.1:8989,127.0.0.1:9999
     * @return such as 127.0.0.1:2181,127.0.0.1:8989,127.0.0.1:9999
     */
    List<String> getURLBackupAddress(URL url) {
        List<String> addressList = new ArrayList<String>();
        // 获取 url 主机地址
        addressList.add(url.getAddress());

        // 获取 url 备用地址（由 backup 参数进行传入）
        addressList.addAll(url.getParameter(RemotingConstants.BACKUP_KEY, Collections.EMPTY_LIST));
        return addressList;
    }

    /**
     * write address-ZookeeperClient relationship to Map
     *
     * @param addressList
     * @param zookeeperClient
     */
    void writeToClientMap(List<String> addressList, ZookeeperClient zookeeperClient) {
        for (String address : addressList) {
            // 所有的地址都使用这个已经连接的 zookeeperClient，并保存到 zookeeperClientMap
            zookeeperClientMap.put(address, zookeeperClient);
        }
    }

    /**
     * redefine the url for zookeeper. just keep protocol, username, password, host, port, and individual parameter.
     *
     * @param url
     * @return
     */
    URL toClientURL(URL url) {
        Map<String, String> parameterMap = new HashMap<>();
        // for CuratorZookeeperClient
        if (url.getParameter(TIMEOUT_KEY) != null) {
            // 获取 timeout 参数
            parameterMap.put(TIMEOUT_KEY, url.getParameter(TIMEOUT_KEY));   // 原 url 的 timeout 属性
        }
        if (url.getParameter(RemotingConstants.BACKUP_KEY) != null) {
            // 获取备用地址
            parameterMap.put(RemotingConstants.BACKUP_KEY, url.getParameter(RemotingConstants.BACKUP_KEY)); // 原 url 的 backup 属性
        }
        // 这里的 path 为 ZookeeperTransporter，参数就只有 timeout 和 backup
        return new URL(url.getProtocol(), url.getUsername(), url.getPassword(), url.getHost(), url.getPort(),
                ZookeeperTransporter.class.getName(), parameterMap);
    }

    /**
     * for unit test
     *
     * @return
     */
    Map<String, ZookeeperClient> getZookeeperClientMap() {
        return zookeeperClientMap;
    }
}
