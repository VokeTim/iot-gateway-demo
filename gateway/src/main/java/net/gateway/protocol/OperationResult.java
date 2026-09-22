package net.gateway.protocol;

import lombok.Data;

/**
 * 结果通用类
 */
@Data
public class OperationResult {

    /**
     * 是否成功
     */
    private boolean isSuccess;

    /**
     * 错误码
     */
    private int errorCode;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 时间戳
     */
    private long timestamp;
}
