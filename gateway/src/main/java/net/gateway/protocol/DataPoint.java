package net.gateway.protocol;

import lombok.Data;

import java.util.Map;

/**
 * 数据点位
 */
@Data
public class DataPoint {

    /**
     * 点标识，如 "fan1.temp"
     */
    private String pointId;

    /**
     * 值（运行时实际是Double、String等）
     */
    private Object value;

    /**
     * 数据类型（FLOAT, INT, BOOLEAN, STRING）
     */
    private DataType dataType;

    /**
     * 采集时间
     */
    private long timestamp;

    /**
     * 质量戳（GOOD, BAD等，工业场景必须）
     */
    private PointQuality quality;

    /**
     * 还可携带额外上下文属性（用于扩展）
     */
    private Map<String, Object> attributes;
}
