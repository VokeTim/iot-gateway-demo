package net.gateway.test;

import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.code.DataType;
import com.serotonin.modbus4j.ip.IpParameters;
import com.serotonin.modbus4j.locator.BaseLocator;

public class ModbusClientTest {

    private static final ModbusFactory modbusFactory = new ModbusFactory();
    private ModbusMaster master;

    public void connect(String ip, int port, int slaveId) throws Exception {
        IpParameters params = new IpParameters();
        params.setHost(ip);
        params.setPort(port);  // 默认 502
        master = modbusFactory.createTcpMaster(params, false);
        master.init();
        System.out.println("Modbus TCP 连接成功");
    }

    // 读取保持寄存器（功能码 03）
    public Number readHoldingRegister(int slaveId, int offset) throws Exception {
        BaseLocator<Number> locator = BaseLocator.holdingRegister(
                slaveId, offset, DataType.TWO_BYTE_INT_UNSIGNED
        );
        return master.getValue(locator);
    }

    // 写入保持寄存器（功能码 06）
    public void writeHoldingRegister(int slaveId, int offset, short value) throws Exception {
        master.setValue(
                BaseLocator.holdingRegister(slaveId, offset, DataType.TWO_BYTE_INT_UNSIGNED),
                value
        );
    }

    public void close() {
        if (master != null) master.destroy();
    }

    public static void main(String[] args) {
        ModbusClientTest clientTest=new ModbusClientTest();
        try {
            clientTest.connect("127.0.0.1", 502, 1);
            short value=clientTest.readHoldingRegister(1,0).shortValue();
            System.out.println("读取寄存器："+value);
            clientTest.close();
        }catch (Exception e){
            System.out.println("创建连接异常");
        }
    }
}
