package net.gateway.protocol;

import lombok.Data;

import java.util.LinkedHashMap;

/**
 * 写入请求（用于批量写入）
 */
@Data
public class WriteRequest {
    /**
     * 请求ID
     */
    private String requestId;

    /**
     * 协议类型
     */
    private String protocolType;

    /**
     * 超时时间
     */
    private int timeoutMillis;

    /**
     * 时间戳
     */
    private long timestamp;

    // 核心数据容器：有序的键值对
    private LinkedHashMap<PointConfig, DataPoint> dataPoints = new LinkedHashMap<>();
}
