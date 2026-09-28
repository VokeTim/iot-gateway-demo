package net.gateway.adapter.impl;

import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.serial.SerialPortWrapper;
import net.gateway.adapter.AbstractModbusAdapter;
import net.gateway.protocol.ProtocolType;

/**
 * Modbus-RTU通信桥接器
 */
public class ModbusRTUAdapter extends AbstractModbusAdapter {

    private final SerialPortWrapper wrapper;

    protected ModbusRTUAdapter(SerialPortWrapper wrapper,int slaveId, int timeout, int maxRegistersPerRequest,int retries) {
        super(slaveId, maxRegistersPerRequest);
        this.wrapper=wrapper;
        setTimeout(timeout);
        setRetries(retries);
    }

    @Override
    protected ModbusMaster createConnection() {
        return modbusFactory.createRtuMaster(wrapper);
    }

    @Override
    public ProtocolType getType() {
        return ProtocolType.MODBUS_RTU;
    }
}
