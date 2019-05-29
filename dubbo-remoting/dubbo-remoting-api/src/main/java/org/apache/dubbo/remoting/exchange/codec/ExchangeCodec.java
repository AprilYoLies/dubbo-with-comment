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
package org.apache.dubbo.remoting.exchange.codec;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.common.io.StreamUtils;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Cleanable;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.buffer.ChannelBufferInputStream;
import org.apache.dubbo.remoting.buffer.ChannelBufferOutputStream;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.telnet.codec.TelnetCodec;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.remoting.transport.ExceedPayloadLimitException;

import java.io.IOException;
import java.io.InputStream;

/**
 * ExchangeCodec.
 */
public class ExchangeCodec extends TelnetCodec {

    // header length.
    protected static final int HEADER_LENGTH = 16;
    // magic header.
    protected static final short MAGIC = (short) 0xdabb;
    protected static final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0];
    protected static final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1];
    // message flag.
    protected static final byte FLAG_REQUEST = (byte) 0x80; // request 消息的标志    1000 0000
    protected static final byte FLAG_TWOWAY = (byte) 0x40;  // twoway 消息的标志     0100 0000
    protected static final byte FLAG_EVENT = (byte) 0x20;   // event 消息的标志      0010 0000
    protected static final int SERIALIZATION_MASK = 0x1f;
    private static final Logger logger = LoggerFactory.getLogger(ExchangeCodec.class);

    public Short getMagicCode() {
        return MAGIC;
    }

    @Override
    // 这里的 channel 为对 netty 原生 channel 进行包装后得到的 NettyChannel
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        if (msg instanceof Request) {
            // 如果 msg 的类型为 Request
            encodeRequest(channel, buffer, (Request) msg);  // 对 Request 消息的编码，实际是将请求信息填充到 buffer 中
        } else if (msg instanceof Response) {
            // 如果 msg 的类型为 Response
            encodeResponse(channel, buffer, (Response) msg);
        } else {
            super.encode(channel, buffer, msg);
        }
    }

    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        // 先获取 buffer 的可读字节数
        int readable = buffer.readableBytes();
        // 可读字节数和 16 取较小的那个
        byte[] header = new byte[Math.min(readable, HEADER_LENGTH)];    // 至多预留一个 header 的长度
        // 读取 header 部分的数据
        buffer.readBytes(header);
        // 解码除 header 以外剩余的其它内容，得到的是一个 request 或者 response 实例
        return decode(channel, buffer, readable, header);
    }

    @Override
    protected Object decode(Channel channel, ChannelBuffer buffer, int readable, byte[] header) throws IOException {
        // check magic number.
        // header.
        // 共 16 个字节
        // | magic | flag | status | reqID | len |
        // |   2   |   1  |    1   |   8   |  4  |
        // magic header.
        // protected static final short MAGIC = (short) 0xdabb;
        if (readable > 0 && header[0] != MAGIC_HIGH
                || readable > 1 && header[1] != MAGIC_LOW) {    // 这里说明魔幻头不正确，于是需要重新找到魔幻头的位置，然后将 readerIndex 定位到新的魔幻头位置
            // 如果魔幻数字不对
            int length = header.length;
            if (header.length < readable) { // 这里说明够头部的长度
                // 这里就说明，除了 header 的字节部分，还有真正的数据部分
                header = Bytes.copyOf(header, readable);
                // 这样就是将全部数据读到了 header 中，前 16 个为 header 部分，后边的为剩余数据
                buffer.readBytes(header, length, readable - length);    // 全部的数据都到了 header 中
            }
            for (int i = 1; i < header.length - 1; i++) {
                if (header[i] == MAGIC_HIGH && header[i + 1] == MAGIC_LOW) {    // 这里是重新确定魔幻头的位置
                    // 这里就说明又检测到了一个数据包，那么就将第一个数据包的内容放到 header 中
                    buffer.readerIndex(buffer.readerIndex() - header.length + i);   // 将 readerIndex 重新定位到新的魔幻头位置
                    header = Bytes.copyOf(header, i);
                    break;
                }
            }
            // 一次只解析一个数据包
            return super.decode(channel, buffer, readable, header);
        }
        // check length.
        if (readable < HEADER_LENGTH) { // 接收到的数据还不足以解析出一个完整的数据包
            return DecodeResult.NEED_MORE_INPUT;
        }

        // get data length. 根据 header 获取 len 信息
        int len = Bytes.bytes2int(header, 12);
        checkPayload(channel, len);
        // 总长度为 header + 数据长度（header 后四位进行记录）
        int tt = len + HEADER_LENGTH;
        if (readable < tt) {    // 也就是说目前接受到的数据还不够 header + 包长度得到的总长度
            return DecodeResult.NEED_MORE_INPUT;
        }

        // limit input stream.通过 ChannelBufferInputStream 对 buffer 进行封装，记录了内容读取的上下界
        // ChannelBufferInputStream -> NettyBackedCahnnelBuffer -> Netty 原生 buf
        ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, len);
        try {
            return decodeBody(channel, is, header); // 解码除开 header 以外的信息
        } finally {
            if (is.available() > 0) {
                try {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Skip input stream " + is.available());
                    }
                    StreamUtils.skipUnusedStream(is);
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
    }

    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);
        // get request id.
        long id = Bytes.bytes2long(header, 4);
        if ((flag & FLAG_REQUEST) == 0) {
            // decode response.
            Response res = new Response(id);
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(true);
            }
            // get status.
            byte status = header[3];
            res.setStatus(status);
            try {
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                if (status == Response.OK) {
                    Object data;
                    if (res.isHeartbeat()) {
                        data = decodeHeartbeatData(channel, in);
                    } else if (res.isEvent()) {
                        data = decodeEventData(channel, in);
                    } else {
                        data = decodeResponseData(channel, in, getRequestData(id));
                    }
                    res.setResult(data);
                } else {
                    res.setErrorMessage(in.readUTF());
                }
            } catch (Throwable t) {
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        } else {
            // decode request.
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(true);
            }
            try {
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                Object data;
                if (req.isHeartbeat()) {
                    data = decodeHeartbeatData(channel, in);
                } else if (req.isEvent()) {
                    data = decodeEventData(channel, in);
                } else {
                    data = decodeRequestData(channel, in);
                }
                req.setData(data);
            } catch (Throwable t) {
                // bad request
                req.setBroken(true);
                req.setData(t);
            }
            return req;
        }
    }

    protected Object getRequestData(long id) {
        DefaultFuture future = DefaultFuture.getFuture(id);
        if (future == null) {
            return null;
        }
        Request req = future.getRequest();
        if (req == null) {
            return null;
        }
        return req.getData();
    }

    // 对 Request 消息的编码，实际是将请求信息填充到 buffer 中
    // 这里的 channel 为对 netty 原生 channel 进行包装后得到的 NettyChannel
    protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req) throws IOException {
        // 根据 channel 的 url 的 serialization 参数值获取 Serialization SPI 接口对应的实现类
        Serialization serialization = getSerialization(channel);    // 这里就是获取序列化的方式
        // header.
        // 共 16 个字节
        // | magic |   标志字节位    | status | reqID | len |        |       7      |       6     |      5     |      4 - 0       |
        // |   2   |       1       |    1   |   8   |  4  |        | FLAG_REQUEST | FLAG_TWOWAY | FLAG_EVENT | SERIALIZATION_ID |
        byte[] header = new byte[HEADER_LENGTH];
        // set magic number.
        // 将魔幻数字填充到 header 中，以大端的模式进行填充，占用两个字节
        Bytes.short2bytes(MAGIC, header);

        // set request and serialization flag.
        // 这里使用的是 Hessian2Serialization，所以获取的 ContentTypeId 为 2
        header[2] = (byte) (FLAG_REQUEST | serialization.getContentTypeId());   // 实际的消息 1000 0000 | 0000 0010 -> 1000 0010

        // req 的 two 和 event 信息存储到 header[2]    到这里可以看出来 header[2] 的字节就是各种标志位的集合
        if (req.isTwoWay()) {
            header[2] |= FLAG_TWOWAY;   // 1000 0010 | 0100 0000 -> 1100 0010
        }                               // header[3] 的各个位的作用
        if (req.isEvent()) {            //  |       7      |       6     |      5     |      4 - 0       |
            header[2] |= FLAG_EVENT;    //  | FLAG_REQUEST | FLAG_TWOWAY | FLAG_EVENT | SERIALIZATION_ID |
        }

        // set request id.即请求 id 号，占头部 4 - 11 号字节
        Bytes.long2bytes(req.getId(), header, 4);

        // encode request data.
        // 记录 writerIndex 的位置，此位置将是 header 的起始位置
        int savedWriteIndex = buffer.writerIndex();
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);    // 将 WriterIndex 移动到指定的位置（跳过 header 的区域）
        ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
        // 得到的是 new Hessian2ObjectOutput(out)，它对 Hessian2Output 进行了封装
        ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
        if (req.isEvent()) {
            // 根本是调用了 Hessian2ObjectOutput 的 writeObject，而这个方法中又是调用了它所封装的 Hessian2Output 的 writeObject 方法
            encodeEventData(channel, out, req.getData());
        } else {
            // 此方法的作用和 if 代码块中的方法作用是一样的，都是将 req.getData() 写入到 out 中
            encodeRequestData(channel, out, req.getData(), req.getVersion());
        }   // 最终的写入内容为 2.0.2 -> org.apache.dubbo.demo.DemoService -> 0.0.0 -> sayHello -> parametersDesc -> 参数实例 -> 附件信息
        out.flushBuffer();
        if (out instanceof Cleanable) {
            ((Cleanable) out).cleanup();
        }
        // 调用 flush 函数，才会进行真正的数据写操作，但是个人感觉这个方法没必要执行的
        bos.flush();
        bos.close();
        // 获取写入的字节数
        int len = bos.writtenBytes();
        // 传输的数据量不应该超过 payload 上限
        checkPayload(channel, len);
        // 这是最终的填充的结果
        // | magic | ContentTypeId | status | reqID | len |
        // |   2   |       1       |    1   |   8   |  4  |
        Bytes.int2bytes(len, header, 12);   // 填充 header 最后四个字节的长度信息
        // write
        // 恢复 writerIndex
        buffer.writerIndex(savedWriteIndex);
        // 写入 header 信息
        buffer.writeBytes(header); // write header.
        // 确定新的写入位置
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
    }

    protected void encodeResponse(Channel channel, ChannelBuffer buffer, Response res) throws IOException {
        int savedWriteIndex = buffer.writerIndex();
        try {
            Serialization serialization = getSerialization(channel);
            // header.
            byte[] header = new byte[HEADER_LENGTH];
            // set magic number.
            Bytes.short2bytes(MAGIC, header);
            // set request and serialization flag.
            header[2] = serialization.getContentTypeId();
            if (res.isHeartbeat()) {
                header[2] |= FLAG_EVENT;
            }
            // set response status.
            byte status = res.getStatus();
            header[3] = status;
            // set request id.
            Bytes.long2bytes(res.getId(), header, 4);

            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
            ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
            ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
            // encode response data or error message.
            if (status == Response.OK) {
                if (res.isHeartbeat()) {
                    encodeHeartbeatData(channel, out, res.getResult());
                } else {
                    encodeResponseData(channel, out, res.getResult(), res.getVersion());
                }
            } else {
                out.writeUTF(res.getErrorMessage());
            }
            out.flushBuffer();
            if (out instanceof Cleanable) {
                ((Cleanable) out).cleanup();
            }
            bos.flush();
            bos.close();

            int len = bos.writtenBytes();
            checkPayload(channel, len);
            Bytes.int2bytes(len, header, 12);
            // write
            buffer.writerIndex(savedWriteIndex);
            buffer.writeBytes(header); // write header.
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
        } catch (Throwable t) {
            // clear buffer
            // 恢复 writerIndex 的值
            buffer.writerIndex(savedWriteIndex);
            // send error message to Consumer, otherwise, Consumer will wait till timeout.
            if (!res.isEvent() && res.getStatus() != Response.BAD_RESPONSE) {
                Response r = new Response(res.getId(), res.getVersion());
                r.setStatus(Response.BAD_RESPONSE);

                if (t instanceof ExceedPayloadLimitException) {
                    logger.warn(t.getMessage(), t);
                    try {
                        r.setErrorMessage(t.getMessage());
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + t.getMessage() + ", cause: " + e.getMessage(), e);
                    }
                } else {
                    // FIXME log error message in Codec and handle in caught() of IoHanndler?
                    logger.warn("Fail to encode response: " + res + ", send bad_response info instead, cause: " + t.getMessage(), t);
                    try {
                        r.setErrorMessage("Failed to send response: " + res + ", cause: " + StringUtils.toString(t));
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + res + ", cause: " + e.getMessage(), e);
                    }
                }
            }

            // Rethrow exception
            if (t instanceof IOException) {
                throw (IOException) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t.getMessage(), t);
            }
        }
    }

    @Override
    protected Object decodeData(ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    @Deprecated
    protected Object decodeHeartbeatData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeRequestData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeResponseData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Override
    protected void encodeData(ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    private void encodeEventData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Deprecated
    protected void encodeHeartbeatData(ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    protected void encodeRequestData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    protected void encodeResponseData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Override
    protected Object decodeData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(channel, in);
    }

    protected Object decodeEventData(Channel channel, ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Deprecated
    protected Object decodeHeartbeatData(Channel channel, ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeRequestData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in) throws IOException {
        return decodeResponseData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in, Object requestData) throws IOException {
        return decodeResponseData(channel, in);
    }

    @Override
    protected void encodeData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data);
    }

    // data 为从 request 中获取的 data
    private void encodeEventData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    @Deprecated
    protected void encodeHeartbeatData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeHeartbeatData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeResponseData(out, data);
    }


}
