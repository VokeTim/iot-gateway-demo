package net.gateway.protocol.modbus;

import lombok.Data;
import net.gateway.protocol.OperationDataType;
import net.gateway.protocol.ProtocolSpecificConfig;

/**
 * Modbus协议专用拓展
 */
@Data
public class ModbusProtocolSpecificConfig implements ProtocolSpecificConfig {

    private boolean isBinary=false;

    /**
     * 有符号
     */
    private boolean isSigned=false;

    /**
     * 需要交换
     */
    private boolean isSwapped=false;

    /**
     * 通用数据类型
     */
    private OperationDataType operationDataType;

    /**
     * 数据字节长度
     */
    private int dataByteLength=2;

    private int bit=0;
}
