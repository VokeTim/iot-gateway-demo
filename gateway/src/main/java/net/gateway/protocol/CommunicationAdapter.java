package net.gateway.protocol;

import java.util.List;

/**
 * 连接适配器用于兼容不同的工业协议（ModbusRTU/TCP，OPC，S7等）
 */
public interface CommunicationAdapter {

    /**
     * 连接
     */
    void connect();

    /**
     * 断开
     */
    void disconnect();

    /**
     * 重连
     * @return 是否重连成功
     */
    boolean reconnect();

    /**
     * 读取单个点位
     */
    ReadResult read(PointConfig point);

    /**
     * 批量读取数据
     */
    ReadResult readBatch(List<PointConfig> points);

    /**
     * 写入单个点位
     */
    WriteResult write(PointConfig point, DataPoint value);

    /**
     * 批量写入（显式有序结构，避免Map无序问题）
     */
    WriteResult batchWrite(List<WriteRequest> writeRequests);
}
