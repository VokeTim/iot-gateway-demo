package net.gateway.adapter.impl;

import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.ip.IpParameters;
import net.gateway.adapter.AbstractModbusAdapter;
import net.gateway.protocol.ProtocolType;;

/**
 * Modbus-TCP通信桥接器
 */
public class ModbusTCPAdapter extends AbstractModbusAdapter {

    private final String host;
    private final int port;
    private final boolean keepAlive;

    public ModbusTCPAdapter(String host, int port, int slaveId, boolean keepAlive, int timeout,int maxRegistersPerRequest) {
        super(slaveId, timeout,maxRegistersPerRequest);
        this.host = host;
        this.port = port;
        this.keepAlive = keepAlive;
    }

    @Override
    public ProtocolType getType() {
        return ProtocolType.MODBUS_TCP;
    }

    @Override
    protected ModbusMaster createMaster() throws Exception {
        IpParameters params = new IpParameters();
        params.setHost(host);
        params.setPort(port);
        return modbusFactory.createTcpMaster(params, keepAlive, timeout);
    }
}