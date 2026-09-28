package net.gateway.adapter;

import com.serotonin.modbus4j.BatchRead;
import com.serotonin.modbus4j.BatchResults;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.locator.BaseLocator;
import lombok.extern.slf4j.Slf4j;
import net.gateway.protocol.*;
import net.gateway.protocol.modbus.ModbusLocatorBuilder;
import net.gateway.protocol.modbus.ModbusProtocolSpecificConfig;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Modbus协议桥接器
 */
@Slf4j
public abstract class AbstractModbusAdapter extends AbstractCommunicationAdapter {

    private static final int ERROR_CODE_PARSE_OR_READ = 400;

    protected static ModbusFactory modbusFactory = new ModbusFactory();  // 静态共享

    private final Object masterLock = new Object();  // 读写共用一把锁

    private static final int DEFAULT_MAX_REGISTERS = 125;

    protected ModbusMaster master;

    /**
     * 从机地址
     */
    protected int slaveId;

    /**
     * 连接超时
     */
    protected int timeout = 3000;        // 默认 3 秒

    /**
     * 尝试重连次数
     */
    protected int retries = 3;

    /**
     * 单包发送的最大限制
     */
    private int maxRegistersPerRequest = 125;

    protected AbstractModbusAdapter(int slaveId, int timeout,int maxRegistersPerRequest) {
        this.slaveId = slaveId;
        this.timeout = timeout;
        if(maxRegistersPerRequest > 0){
            this.maxRegistersPerRequest=maxRegistersPerRequest;
        }
    }

    /** 子类实现连接创建 */
    protected abstract ModbusMaster createMaster() throws Exception;

    protected ModbusMaster getMaster() throws Exception {
        if (master == null) {
            synchronized (this) {
                if (master == null) {
                    master = createMaster();
                    master.setTimeout(timeout);   // ★ 超时统一设置
                    master.setRetries(0);
                }
            }
        }
        return master;
    }

    @Override
    public void connect() {
        try {
            master = createMaster();
            master.setTimeout(timeout);
            master.setRetries(retries);
            master.init();
        } catch (Exception e) {
            throw new RuntimeException("Modbus 连接失败", e);
        }
    }

    @Override
    public void disconnect() {
        if (master != null) {
            master.destroy();
            master = null;
        }
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

    @Override
    public ReadResult read(PointConfig point) {
        long timestamp = System.currentTimeMillis();
        try {
            ModbusMaster m = getMaster();
            ModbusProtocolSpecificConfig config = toModbusConfig(point);
            int offset = Integer.parseInt(point.getAddress());

            BaseLocator<?> locator = getLocator(slaveId, offset, config);
            Object value;
            synchronized (masterLock){
                value = m.getValue(locator);
            }
            return buildSuccessResult(Collections.singletonList(
                    buildDataPoint(point, value, config, timestamp)),timestamp
            );
        } catch (Exception ex) {
            log.error("读取点位 [{}] 失败", point == null ? null : point.getPointId(), ex);
            return buildErrorResult(ex, timestamp);
        }
    }

    @Override
    public ReadResult readBatch(List<PointConfig> points) {
        long timestamp = System.currentTimeMillis();
        if (points == null || points.isEmpty()) {
            return buildSuccessResult(Collections.emptyList(), timestamp);
        }
        try {
            ModbusMaster m = getMaster();

            // 锁外分组
            List<RegisterGroup> groups = groupPoints(points, maxRegistersPerRequest);

            Map<String, Object> valueMap = new HashMap<>();
            synchronized (masterLock) {
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
            return buildSuccessResult(dataPoints, timestamp);
        } catch (Exception ex) {
            log.error("批量读取失败（{} 个点位）", points.size(), ex);
            return buildErrorResult(ex, timestamp);
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
     * 由分组构造批量读取的请求
     * @param groups 分组集合
     * @param slaveId 从机地址
     * @return 批量读取的请求集合
     */
    private List<BatchRead<String>> buildBatchReads(List<RegisterGroup> groups, int slaveId) {
        List<BatchRead<String>> batchReads = new ArrayList<>();
        for (RegisterGroup group : groups) {
            BatchRead<String> batchRead = new BatchRead<>();
            for (PointConfig point : group.points) {
                ModbusProtocolSpecificConfig config = toModbusConfig(point);
                batchRead.addLocator(point.getPointId(),
                        getLocator(slaveId, Integer.parseInt(point.getAddress()), config));
            }
            batchRead.setContiguousRequests(false);
            batchReads.add(batchRead);
        }
        return batchReads;
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


    /** 校验并提取点位协议配置（错误时抛 IllegalArgumentException 并带点位 ID） */
    private static ModbusProtocolSpecificConfig toModbusConfig(PointConfig point) {
        if (!(point.getProtocolConfig() instanceof ModbusProtocolSpecificConfig)) {
            throw new IllegalArgumentException(
                    "点位 [" + point.getPointId() + "] 缺少 Modbus 协议配置");
        }
        return (ModbusProtocolSpecificConfig) point.getProtocolConfig();
    }

    private static ReadResult buildSuccessResult(List<DataPoint> dataPoints,long timestamp){
        ReadResult result = new ReadResult();
        if(dataPoints.size()>0){
            result.setDataPoints(dataPoints);
        }else{
            result.setDataPoints(Collections.emptyList());
        }
        result.setSuccess(true);
        result.setTimestamp(timestamp);
        return result;
    }

    /** 构造错误 ReadResult（读单点和批量共用） */
    private static ReadResult buildErrorResult(Throwable ex, long timestamp) {
        ReadResult result = new ReadResult();
        result.setDataPoints(Collections.emptyList());
        result.setSuccess(false);
        result.setTimestamp(timestamp);
        result.setErrorMessage(ex.getMessage());
        result.setErrorCode(ERROR_CODE_PARSE_OR_READ);
        return result;
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


    /** 带重试的读取（通用定位器，兼容保持/输入寄存器、位读取、多字节类型） */
    public Object safeRead(BaseLocator<?> locator) throws Exception {
        Exception lastException = null;
        for (int i = 1; i <= retries; i++) {
            try {
                if (master == null) connect();
                return master.getValue(locator);
            } catch (Exception e) {
                lastException = e;
                if (i < retries) Thread.sleep(1000 * i);  // 退避等待
            }
        }
        throw new RuntimeException("读取点位失败，已重试 " + retries + " 次", lastException);
    }

    /** 带重试的读取寄存器 */
    public Object safeReadRegister(int address,int dataType) throws Exception {
        return safeRead(BaseLocator.holdingRegister(slaveId, address, dataType));
    }

    @Override
    public WriteResult write(PointConfig point, DataPoint value) {
        return null;
    }

    @Override
    public WriteResult batchWrite(List<WriteRequest> writeRequests) {
        return null;
    }

    @Override
    public boolean reconnect() {
        disconnect();
        try {
            connect();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
