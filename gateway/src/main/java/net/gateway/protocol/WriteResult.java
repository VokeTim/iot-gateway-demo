package net.gateway.protocol;

import lombok.Data;

@Data
public class WriteResult extends OperationResult{

    /**
     * 写入失败点数
     */
    private int failedCount;
}
