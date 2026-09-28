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

    protected ModbusRTUAdapter(SerialPortWrapper wrapper,int slaveId, int timeout, int maxRegistersPerRequest) {
        super(slaveId, timeout, maxRegistersPerRequest);
        this.wrapper=wrapper;
    }

    @Override
    protected ModbusMaster createMaster() throws Exception {
        return modbusFactory.createRtuMaster(wrapper);
    }

    @Override
    public ProtocolType getType() {
        return ProtocolType.MODBUS_RTU;
    }
}
