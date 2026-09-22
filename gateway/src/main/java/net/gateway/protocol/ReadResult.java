package net.gateway.protocol;

import lombok.Data;

import java.util.List;

/**
 * 读取结果
 */
@Data
public class ReadResult extends OperationResult{
    /**
     * 点位数据集合
     */
    private List<DataPoint> dataPoints;
}
