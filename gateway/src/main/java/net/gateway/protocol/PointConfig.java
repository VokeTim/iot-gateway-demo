package net.gateway.protocol;

import lombok.Data;

/**
 * 点位配置
 */
@Data
public class PointConfig {

    /**
     * 通用字段
     */
    private String pointId;

    /**
     * "modbus", "opcua", "s7"...
     */
    private String protocolType;

    /**
     * 寄存器地址或 NodeId
     */
    private String address;

    // 协议专用配置（结构化、可扩展，但类型受限，避免用无限Map）
    private ProtocolSpecificConfig protocolConfig;
}
