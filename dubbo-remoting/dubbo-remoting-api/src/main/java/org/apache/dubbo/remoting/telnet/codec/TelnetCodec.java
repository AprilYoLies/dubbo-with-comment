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
package org.apache.dubbo.remoting.telnet.codec;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.transport.codec.TransportCodec;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * TelnetCodec
 */
public class TelnetCodec extends TransportCodec {

    private static final Logger logger = LoggerFactory.getLogger(TelnetCodec.class);

    private static final String HISTORY_LIST_KEY = "telnet.history.list";

    private static final String HISTORY_INDEX_KEY = "telnet.history.index";

    private static final byte[] UP = new byte[]{27, 91, 65};

    private static final byte[] DOWN = new byte[]{27, 91, 66};

    private static final List<?> ENTER = Arrays.asList(
            new byte[]{'\r', '\n'} /* Windows Enter */,
            new byte[]{'\n'} /* Linux Enter */);

    private static final List<?> EXIT = Arrays.asList(
            new byte[]{3} /* Windows Ctrl+C */,
            new byte[]{-1, -12, -1, -3, 6} /* Linux Ctrl+C */,
            new byte[]{-1, -19, -1, -3, 6} /* Linux Pause */);

    private static Charset getCharset(Channel channel) {
        if (channel != null) {
            Object attribute = channel.getAttribute(RemotingConstants.CHARSET_KEY);
            if (attribute instanceof String) {
                try {
                    return Charset.forName((String) attribute);
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            } else if (attribute instanceof Charset) {
                return (Charset) attribute;
            }
            URL url = channel.getUrl();
            if (url != null) {
                String parameter = url.getParameter(RemotingConstants.CHARSET_KEY);
                if (StringUtils.isNotEmpty(parameter)) {
                    try {
                        return Charset.forName(parameter);
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }
            }
        }
        try {
            return Charset.forName(RemotingConstants.DEFAULT_CHARSET);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        return Charset.defaultCharset();
    }

    private static String toString(byte[] message, Charset charset) throws UnsupportedEncodingException {
        byte[] copy = new byte[message.length];
        int index = 0;
        for (int i = 0; i < message.length; i++) {
            byte b = message[i];
            if (b == '\b') { // backspace
                if (index > 0) {
                    index--;
                }
                if (i > 2 && message[i - 2] < 0) { // double byte char
                    if (index > 0) {
                        index--;
                    }
                }
            } else if (b == 27) { // escape
                if (i < message.length - 4 && message[i + 4] == 126) {
                    i = i + 4;
                } else if (i < message.length - 3 && message[i + 3] == 126) {
                    i = i + 3;
                } else if (i < message.length - 2) {
                    i = i + 2;
                }
            } else if (b == -1 && i < message.length - 2
                    && (message[i + 1] == -3 || message[i + 1] == -5)) { // handshake
                i = i + 2;
            } else {
                copy[index++] = message[i];
            }
        }
        if (index == 0) {
            return "";
        }
        return new String(copy, 0, index, charset.name()).trim();
    }

    private static boolean isEquals(byte[] message, byte[] command) throws IOException {
        return message.length == command.length && endsWith(message, command);
    }

    private static boolean endsWith(byte[] message, byte[] command) throws IOException {
        if (message.length < command.length) {
            return false;
        }
        int offset = message.length - command.length;
        for (int i = command.length - 1; i >= 0; i--) {
            if (message[offset + i] != command[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object message) throws IOException {
        if (message instanceof String) {
            if (isClientSide(channel)) {
                message = message + "\r\n";
            }
            byte[] msgData = ((String) message).getBytes(getCharset(channel).name());
            buffer.writeBytes(msgData);
        } else {
            super.encode(channel, buffer, message);
        }
    }

    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        int readable = buffer.readableBytes();
        byte[] message = new byte[readable];
        buffer.readBytes(message);
        return decode(channel, buffer, readable, message);
    }

    @SuppressWarnings("unchecked")
    protected Object decode(Channel channel, ChannelBuffer buffer, int readable, byte[] message) throws IOException {
        if (isClientSide(channel)) {
            // 是客户端的话，直接将 byte 数组转换为字符串
            return toString(message, getCharset(channel));
        }
        // readable 不能超过 8MB
        checkPayload(channel, readable);
        if (message == null || message.length == 0) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        if (message[message.length - 1] == '\b') { // Windows backspace echo
            try {
                // 这里是啥意思？？
                boolean doublechar = message.length >= 3 && message[message.length - 3] < 0; // double byte char
                channel.send(new String(doublechar ? new byte[]{32, 32, 8, 8} : new byte[]{32, 8}, getCharset(channel).name()));
            } catch (RemotingException e) {
                throw new IOException(StringUtils.toString(e));
            }
            return DecodeResult.NEED_MORE_INPUT;
        }

        for (Object command : EXIT) {
            // 处理退出消息
            if (isEquals(message, (byte[]) command)) {
                if (logger.isInfoEnabled()) {
                    logger.info(new Exception("Close channel " + channel + " on exit command: " + Arrays.toString((byte[]) command)));
                }
                channel.close();
                return null;
            }
        }

        boolean up = endsWith(message, UP);
        boolean down = endsWith(message, DOWN);
        if (up || down) {
            // 处理上下按键
            LinkedList<String> history = (LinkedList<String>) channel.getAttribute(HISTORY_LIST_KEY);
            if (CollectionUtils.isEmpty(history)) {
                return DecodeResult.NEED_MORE_INPUT;
            }
            Integer index = (Integer) channel.getAttribute(HISTORY_INDEX_KEY);
            Integer old = index;
            if (index == null) {
                index = history.size() - 1;
            } else {
                if (up) {
                    index = index - 1;
                    if (index < 0) {
                        index = history.size() - 1;
                    }
                } else {
                    index = index + 1;
                    if (index > history.size() - 1) {
                        index = 0;
                    }
                }
            }
            if (old == null || !old.equals(index)) {
                channel.setAttribute(HISTORY_INDEX_KEY, index);
                String value = history.get(index);
                if (old != null && old >= 0 && old < history.size()) {
                    String ov = history.get(old);
                    StringBuilder buf = new StringBuilder();
                    for (int i = 0; i < ov.length(); i++) {
                        buf.append("\b");
                    }
                    for (int i = 0; i < ov.length(); i++) {
                        buf.append(" ");
                    }
                    for (int i = 0; i < ov.length(); i++) {
                        buf.append("\b");
                    }
                    value = buf.toString() + value;
                }
                try {
                    channel.send(value);
                } catch (RemotingException e) {
                    throw new IOException(StringUtils.toString(e));
                }
            }
            return DecodeResult.NEED_MORE_INPUT;
        }
        for (Object command : EXIT) {
            // 处理退出消息
            if (isEquals(message, (byte[]) command)) {
                if (logger.isInfoEnabled()) {
                    logger.info(new Exception("Close channel " + channel + " on exit command " + command));
                }
                channel.close();
                return null;
            }
        }
        byte[] enter = null;
        for (Object command : ENTER) {
            // 处理回车消息
            if (endsWith(message, (byte[]) command)) {
                enter = (byte[]) command;
                break;
            }
        }
        if (enter == null) {
            return DecodeResult.NEED_MORE_INPUT;
        }
        // 历史列表
        LinkedList<String> history = (LinkedList<String>) channel.getAttribute(HISTORY_LIST_KEY);
        // 历史索引信息
        Integer index = (Integer) channel.getAttribute(HISTORY_INDEX_KEY);
        // 清除历史索引信息
        channel.removeAttribute(HISTORY_INDEX_KEY);
        if (CollectionUtils.isNotEmpty(history) && index != null && index >= 0 && index < history.size()) {
            String value = history.get(index);
            // 根据历史列表和索引获取相应的 value
            if (value != null) {
                byte[] b1 = value.getBytes();
                byte[] b2 = new byte[b1.length + message.length];
                // 历史的 b1 拷贝到新建的 b2
                System.arraycopy(b1, 0, b2, 0, b1.length);
                // 待解码的拷贝到 b2，也就是做了合并操作
                System.arraycopy(message, 0, b2, b1.length, message.length);
                // message 指向 b2
                message = b2;
            }
        }
        // 针对 message 新建字符串
        String result = toString(message, getCharset(channel));
        if (result.trim().length() > 0) {
            if (history == null) {
                history = new LinkedList<String>();
                // 向 channel 添加一个 telnet.history.list
                channel.setAttribute(HISTORY_LIST_KEY, history);
            }
            if (history.isEmpty()) {
                // 历史列表为空直接进行添加
                history.addLast(result);
            } else if (!result.equals(history.getLast())) {
                // result 不为 history 的最后一个
                history.remove(result);
                history.addLast(result);
                // 那么就从 history 中移除 result ，重新添加到末尾
                if (history.size() > 10) {
                    // 历史记录保留不超过 10 个
                    history.removeFirst();
                }
            }
        }
        return result;
    }

}
