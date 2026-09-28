package net.gateway.adapter;

import com.serotonin.modbus4j.BatchRead;
import com.serotonin.modbus4j.BatchResults;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.locator.BaseLocator;
import com.serotonin.modbus4j.msg.WriteRegistersRequest;
import com.serotonin.modbus4j.msg.WriteRegistersResponse;
import lombok.extern.slf4j.Slf4j;
import net.gateway.protocol.*;
import net.gateway.protocol.modbus.ModbusLocatorBuilder;
import net.gateway.protocol.modbus.ModbusProtocolSpecificConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Modbus协议桥接器
 */
@Slf4j
public abstract class AbstractModbusAdapter extends AbstractCommunicationAdapter<ModbusMaster> {

    protected final int slaveId;
    protected final int maxRegistersPerRequest;   // 厂商单帧寄存器上限
    protected static final int MAX_REGISTERS_PER_FRAME = 123;  // 协议上限

    protected final ModbusFactory modbusFactory = new ModbusFactory();

    protected AbstractModbusAdapter(int slaveId, int maxRegistersPerRequest) {
        this.slaveId = slaveId;
        // 用 > 0 判断回退默认 125（寄存器读上限），不用 ObjectUtils.isNotEmpty
        this.maxRegistersPerRequest = maxRegistersPerRequest > 0
                ? maxRegistersPerRequest : 125;
    }

    // ==================== 子类实现（RTU/TCP） ====================
    /** RTU→ModbusRtuMaster，TCP→ModbusTcpMaster */
    @Override
    protected abstract ModbusMaster createConnection();

    @Override
    protected void setTimeout(ModbusMaster master, int timeoutMillis) {
        master.setTimeout(timeoutMillis);
    }

    @Override
    protected void setRetries(ModbusMaster master, int retries) {
        master.setRetries(retries);
    }

    @Override
    protected void doDisconnect(ModbusMaster master) {
        master.destroy();   // modbus4j 释放资源
    }

    // ==================== 单个读取 ====================
    @Override
    public ReadResult read(PointConfig point){
        long timestamp = System.currentTimeMillis();
        try {
            ModbusMaster m = getConnection();
            ModbusProtocolSpecificConfig config = toModbusConfig(point);
            int offset = Integer.parseInt(point.getAddress());

            BaseLocator<?> locator = getLocator(slaveId, offset, config);
            Object value;
            synchronized (connectionLock){
                value = m.getValue(locator);
            }
            return buildSuccessReadResult(Collections.singletonList(
                    buildDataPoint(point, value, config, timestamp)),timestamp
            );
        } catch (Exception ex) {
            log.error("读取点位 [{}] 失败", point == null ? null : point.getPointId(), ex);
            return buildErrorReadResult(ex, timestamp);
        }
    }

    // ==================== 批量读取 ====================
    @Override
    public ReadResult readBatch(List<PointConfig> points){
        long timestamp = System.currentTimeMillis();
        if (points == null || points.isEmpty()) {
            return buildSuccessReadResult(Collections.emptyList(), timestamp);
        }
        try {
            ModbusMaster m = getConnection();
            // 锁外分组
            List<RegisterGroup> groups = groupPoints(points, maxRegistersPerRequest);
            Map<String, Object> valueMap = new HashMap<>();
            synchronized (connectionLock) {
                for (RegisterGroup group : groups) {
                    // 构造 batchRead（内部用 pointId 作为 key）
                    BatchRead<String> batchRead = buildBatchRead(group, slaveId);
                    BatchResults<String> results = m.send(batchRead);

                    // ★ 修正：不能用 getKeys()，改为基于 group 的 pointId 取值
                    for (PointConfig point : group.points) {
                        String pointId = point.getPointId();
                        // getValue 可能返回 null（该点位无响应/失败）
                        Object v = results.getValue(pointId);
                        if (v != null) {
                            valueMap.put(pointId, v);
                        }
                    }
                }
            }

            // 锁外组装（按传入 points 原始顺序）
            List<DataPoint> dataPoints = new ArrayList<>();
            for (PointConfig point : points) {
                Object v = valueMap.get(point.getPointId());
                if (v != null) {
                    dataPoints.add(buildDataPoint(point, v, toModbusConfig(point), timestamp));
                }
            }
            return buildSuccessReadResult(dataPoints, timestamp);
        } catch (Exception ex) {
            log.error("批量读取失败（{} 个点位）", points.size(), ex);
            return buildErrorReadResult(ex, timestamp);
        }
    }

    // ==================== 单个写入 ====================
    @Override
    public WriteResult write(PointConfig point, DataPoint value) {
        long timestamp = System.currentTimeMillis();
        try {
            Object decidedValue=value.getValue();
            ModbusMaster m = getConnection();
            BaseLocator<?> locator = getLocator(slaveId, Integer.parseInt(point.getAddress()), toModbusConfig(point));

            synchronized (connectionLock) {           // 锁只包 writeRegister
                m.setValue(locator, decidedValue);
            }
            return buildSuccessWriteResult(timestamp);
        } catch (Exception ex) {
            log.error("写入失败: {}", point.getPointId(), ex);
            return buildErrorWriteResult(ex, timestamp);
        }
    }

    // ==================== 批量写入 ====================
    @Override
    public WriteResult batchWrite(WriteRequest writeRequest){
        long timestamp = System.currentTimeMillis();
        if(writeRequest==null||writeRequest.getDataPoints().size()==0){
            return buildSuccessWriteResult(timestamp);
        }
        try{
            ModbusMaster m = getConnection();
            synchronized (connectionLock) {           // 整批写持锁，与读互斥
                List<WriteItem> items = new ArrayList<>();
                for (Map.Entry<PointConfig, DataPoint> entry : writeRequest.getDataPoints().entrySet()) {
                    items.add(buildWriteItem(entry.getKey(), entry.getValue()));
                }

                // 按寄存器段连续分组
                List<List<WriteItem>> groups = groupByContiguousRegisters(items);

                for (List<WriteItem> group : groups) {
                    if (isContiguousRegisters(group)) {
                        writeRegistersBatch(m, group);      // 连续 → FC16 合并帧（超限自动拆帧）
                    } else {
                        for (WriteItem item : group) {
                            writeSingle(m, item);           // 离散 → BaseLocator 单点写
                        }
                    }
                }
            }
            return buildSuccessWriteResult(timestamp);
        } catch (Exception ex) {
            return buildErrorWriteResult(ex, timestamp);
        }
    }

    private void writeSingle(ModbusMaster m, WriteItem item) throws Exception {
        BaseLocator<?> locator = getLocator(slaveId, item.startAddress, item.config);
        m.setValue(locator, item.value);
    }

    private boolean isContiguousRegisters(List<WriteItem> group) {
        for (int i = 1; i < group.size(); i++) {
            WriteItem prev = group.get(i - 1);
            WriteItem curr = group.get(i);
            if (curr.startAddress != prev.startAddress + prev.regCount) {
                return false;
            }
        }
        return true;
    }

    /** 发一次 FC16 帧 */
    private void sendWriteRegisters(ModbusMaster m, int startOffset, List<Short> registers) throws Exception {
        short[] values = new short[registers.size()];
        for (int i = 0; i < registers.size(); i++) {
            values[i] = registers.get(i);
        }
        WriteRegistersRequest request = new WriteRegistersRequest(slaveId, startOffset, values);
        WriteRegistersResponse response = (WriteRegistersResponse) m.send(request);
        if (response.isException()) {
            throw new RuntimeException("批量写寄存器失败: " + response.getExceptionMessage());
        }
    }

    private void writeRegistersBatch(ModbusMaster m, List<WriteItem> group) throws Exception {
        // 摊平组内所有点的寄存器值
        List<Short> allRegisters = new ArrayList<>();
        for (WriteItem item : group) {
            short[] regs = toRegisters(item.value, item.config);
            for (short r : regs) allRegisters.add(r);
        }

        int startOffset = group.get(0).startAddress;
        // 拆帧上限：协议 123 与厂商配置取小
        int frameLimit = Math.min(MAX_REGISTERS_PER_FRAME, maxRegistersPerRequest);

        if (allRegisters.size() <= frameLimit) {
            sendWriteRegisters(m, startOffset, allRegisters);
        } else {
            // 按 frameLimit 拆成多帧，每帧地址递增 frameLimit
            for (int pos = 0; pos < allRegisters.size(); pos += frameLimit) {
                int chunkSize = Math.min(frameLimit, allRegisters.size() - pos);
                sendWriteRegisters(m, startOffset + pos, allRegisters.subList(pos, pos + chunkSize));
            }
        }
    }

    private short[] toRegisters(Object value, ModbusProtocolSpecificConfig config) {
        int dataByteLength = config.getDataByteLength();   // 2/4/8
        int regCount = dataByteLength / 2;                 // 寄存器数

        if (config.isBinary() || value instanceof Boolean) {
            // 布尔/bit 写单个寄存器 0 或 1
            return new short[] { (short)(Boolean.TRUE.equals(value) ? 1 : 0) };
        }

        switch (config.getOperationDataType()) {
            case FLOAT: {
                float f = ((Number) value).floatValue();
                return floatToRegisters(f, regCount, config);
            }
            case INT: {
                // 16 位：直接；32/64 位：按字节拆分
                long l = ((Number) value).longValue();
                return longToRegisters(l, dataByteLength, config);
            }
            case BOOLEAN:
                return new short[] { (short)(Boolean.TRUE.equals(value) ? 1 : 0) };
            case STRING:
                // 字符串按字符拆寄存器，取决于实现，这里示意
                return stringToRegisters((String) value, dataByteLength);
            default:
                throw new IllegalArgumentException("不支持的数据类型: " + config.getOperationDataType());
        }
    }

    private short[] floatToRegisters(float f, int regCount, ModbusProtocolSpecificConfig config) {
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(f).array();
        short hi = (short) ((bytes[0] & 0xFF) << 8 | (bytes[1] & 0xFF));
        short lo = (short) ((bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF));
        return config.isSwapped() ? new short[] { lo, hi } : new short[] { hi, lo };
    }

    private short[] longToRegisters(long value, int dataByteLength, ModbusProtocolSpecificConfig config) {
        int regCount = dataByteLength / 2;
        short[] registers = new short[regCount];
        // 大端：从高字节到低字节填寄存器
        for (int i = 0; i < regCount; i++) {
            int shift = (regCount - 1 - i) * 16;   // 大端从最高16位开始
            registers[i] = (short)((value >>> shift) & 0xFFFF);
        }
        // isSwapped 时反转寄存器顺序（字序交换）
        if (config.isSwapped()) {
            reverse(registers);
        }
        return registers;
    }

    private static short[] reverse(short[] registers) {
        short[] reversed = new short[registers.length];
        for (int i = 0; i < registers.length; i++) {
            reversed[i] = registers[registers.length - 1 - i];
        }
        return reversed;
    }

    private short[] stringToRegisters(String s, int dataByteLength) {
        int maxChars = dataByteLength / 2;
        short[] registers = new short[Math.min(s.length(), maxChars)];
        for (int i = 0; i < registers.length; i++) {
            registers[i] = (short) s.charAt(i);
        }
        return registers;
    }

    /**
     * 寄存器地址连续的批量写入，不连续的单个写入
     * @param items
     * @return
     */
    private List<List<WriteItem>> groupByContiguousRegisters(List<WriteItem> items) {
        // 按起始地址排序，保证连续性判断正确
        items = items.stream()
                .sorted(Comparator.comparingInt(WriteItem::getStartAddress)).collect(Collectors.toList());

        List<List<WriteItem>> groups = new ArrayList<>();
        List<WriteItem> current = new ArrayList<>();
        int nextExpectedAddress = -1;

        for (WriteItem item : items) {
            if (current.isEmpty() || item.startAddress == nextExpectedAddress) {
                current.add(item);
            } else {
                groups.add(current);
                current = new ArrayList<>();
                current.add(item);
            }
            nextExpectedAddress = item.startAddress + item.regCount;
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private WriteItem buildWriteItem(PointConfig point, DataPoint dataPoint) {
        ModbusProtocolSpecificConfig config = toModbusConfig(point);
        int startAddress = Integer.parseInt(point.getAddress());
        int regCount = Math.max(1, config.getDataByteLength() / 2);
        return new WriteItem(point, config, startAddress, regCount, dataPoint.getValue());
    }

    private static class WriteItem {
        final PointConfig point;
        final ModbusProtocolSpecificConfig config;
        final int startAddress;      // 起始寄存器地址
        final int regCount;          // 占用寄存器数
        final Object value;          // 待写值

        WriteItem(PointConfig point, ModbusProtocolSpecificConfig config,
                  int startAddress, int regCount, Object value) {
            this.point = point;
            this.config = config;
            this.startAddress = startAddress;
            this.regCount = regCount;
            this.value = value;
        }

        public PointConfig getPoint() {
            return point;
        }

        public int getStartAddress(){
            return startAddress;
        }
    }


    private BatchRead<String> buildBatchRead(RegisterGroup group, int slaveId) {
        BatchRead<String> batchRead = new BatchRead<>();
        for (PointConfig point : group.points) {
            batchRead.addLocator(point.getPointId(),
                    getLocator(slaveId, Integer.parseInt(point.getAddress()), toModbusConfig(point)));
        }
        batchRead.setContiguousRequests(false);
        return batchRead;
    }

    /** 一个分组的描述：地址连续、寄存器总数不超过上限 */
    private static class RegisterGroup {
        int startAddress;               // 组内最小地址（起始寄存器）
        int registerCount;              // 组内累计寄存器数
        List<PointConfig> points = new ArrayList<>();  // 属于该组的点位
        RegisterGroup(int startAddress) {
            this.startAddress = startAddress;
        }
    }

    /**
     * 将点位按"地址连续 + 寄存器数不超过 maxRegistersPerRequest"分组。
     * 完全基于 PointConfig 内容，不碰 BatchRead。
     */
    private List<RegisterGroup> groupPoints(List<PointConfig> points, int maxRegistersPerRequest) {
        List<RegisterGroup> groups = new ArrayList<>();
        //TODO: 后期需要再前端做数字文本限制输入的操作，否则老是通过try-catch抛出异常会出现现在这样抛不出来的结构

        // 1. 先按地址排序，保证连续性判断正确
        List<PointConfig> sorted = points.stream()
                .sorted(Comparator.comparingInt(p -> Integer.parseInt(p.getAddress()))).collect(Collectors.toList());

        // 2. 分组
        RegisterGroup current = null;
        int lastAddress = -1;
        int currentRegisters = 0;

        for (PointConfig point : sorted) {
            int address = Integer.parseInt(point.getAddress());
            ModbusProtocolSpecificConfig config = toModbusConfig(point);
            int regCount = config.getDataByteLength() / 2;   // 字节 → 寄存器数

            // 需要新组：地址不连续，或寄存器数将超上限
            boolean needNewGroup = current == null
                    || (address != lastAddress + 1)
                    || (currentRegisters + regCount > maxRegistersPerRequest);

            if (needNewGroup) {
                current = new RegisterGroup(address);
                groups.add(current);
                currentRegisters = 0;
            }

            current.points.add(point);
            currentRegisters += regCount;
            lastAddress = address;
        }
        return groups;
    }


    /** 组装一个数据点（读单点和批量都用得到） */
    private static DataPoint buildDataPoint(PointConfig point, Object value,
                                            ModbusProtocolSpecificConfig config,
                                            long timestamp) {
        DataPoint dataPoint = new DataPoint();
        dataPoint.setPointId(point.getPointId());
        dataPoint.setValue(value);
        dataPoint.setOperationDataType(config.getOperationDataType());
        dataPoint.setTimestamp(timestamp);
        dataPoint.setQuality(PointQuality.GOOD);
        return dataPoint;
    }

    /**
     * 依据点位配置构建 modbus4j 定位器。
     * <p>「配置 -> DataType -> BaseLocator」的翻译全部交给 {@link ModbusLocatorBuilder}。</p>
     */
    private BaseLocator<?> getLocator(int slaveId, int offset, ModbusProtocolSpecificConfig config) {
        return ModbusLocatorBuilder.create(slaveId, offset)
                .fromConfig(config)
                .build();
    }

    /** 校验并提取点位协议配置（错误时抛 IllegalArgumentException 并带点位 ID） */
    private static ModbusProtocolSpecificConfig toModbusConfig(PointConfig point) {
        if (!(point.getProtocolConfig() instanceof ModbusProtocolSpecificConfig)) {
            throw new IllegalArgumentException(
                    "点位 [" + point.getPointId() + "] 缺少 Modbus 协议配置");
        }
        return (ModbusProtocolSpecificConfig) point.getProtocolConfig();
    }
}
