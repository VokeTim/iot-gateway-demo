package net.gateway.protocol.modbus;

import com.serotonin.modbus4j.code.DataType;
import com.serotonin.modbus4j.locator.BaseLocator;
import net.gateway.protocol.OperationDataType;

/**
 * Modbus 点位定位器建造者（Builder 模式）。
 * <p>
 * 职责单一：把与连接无关的点位配置（是否位读取、有无符号、是否字节交换、数据字节长度、
 * 通用数据类型）翻译成 modbus4j 的 {@link DataType} 常量，并构建对应的 {@link BaseLocator}。
 * 这样适配器只需「拿定位器 -> 读值 -> 组装结果」，不再堆积 switch 分支。
 *
 * <pre>{@code
 * BaseLocator<?> locator = ModbusLocatorBuilder.create(slaveId, offset)
 *         .fromConfig(config)
 *         .build();
 * }</pre>
 *
 * 注意：modbus4j 对部分类型有天然限制，见各 resolveXxx 方法注释。
 */
public class ModbusLocatorBuilder {

    /**
     * 寄存器区域
     */
    public enum RegisterArea {
        /** 保持寄存器：03 读，06/16 写 */
        HOLDING,
        /** 输入寄存器：04 读 */
        INPUT
    }

    /** 默认数据长度：2 字节（1 个寄存器） */
    private static final int DEFAULT_DATA_BYTE_LENGTH = 2;

    private final int slaveId;
    private final int offset;

    private RegisterArea area = RegisterArea.HOLDING;

    /** 是否按位读取（线圈/寄存器某一位） */
    private boolean bitRead = false;
    /** 位序号，从 0 开始 */
    private int bit = 0;

    private OperationDataType operationDataType = OperationDataType.INT;
    private boolean signed = false;
    private boolean swapped = false;
    private int dataByteLength = DEFAULT_DATA_BYTE_LENGTH;

    private ModbusLocatorBuilder(int slaveId, int offset) {
        this.slaveId = slaveId;
        this.offset = offset;
    }

    /**
     * 创建建造者。
     *
     * @param slaveId 从机地址
     * @param offset  寄存器/线圈偏移地址
     */
    public static ModbusLocatorBuilder create(int slaveId, int offset) {
        return new ModbusLocatorBuilder(slaveId, offset);
    }

    /**
     * 使用协议专用配置初始化建造者。
     * <p>null 配置将保留默认值（保持寄存器、2 字节无符号整数）。</p>
     */
    public ModbusLocatorBuilder fromConfig(ModbusProtocolSpecificConfig config) {
        if (config == null) {
            return this;
        }
        return this.operationDataType(config.getOperationDataType())
                .signed(config.isSigned())
                .swapped(config.isSwapped())
                .dataByteLength(config.getDataByteLength())
                .bitRead(config.isBinary())
                .bit(config.getBit());
    }

    public ModbusLocatorBuilder area(RegisterArea area) {
        this.area = area == null ? RegisterArea.HOLDING : area;
        return this;
    }

    public ModbusLocatorBuilder holding() {
        return area(RegisterArea.HOLDING);
    }

    public ModbusLocatorBuilder input() {
        return area(RegisterArea.INPUT);
    }

    public ModbusLocatorBuilder bitRead(boolean bitRead) {
        this.bitRead = bitRead;
        return this;
    }

    public ModbusLocatorBuilder bit(int bit) {
        this.bit = bit;
        return this;
    }

    public ModbusLocatorBuilder operationDataType(OperationDataType operationDataType) {
        if (operationDataType != null) {
            this.operationDataType = operationDataType;
        }
        return this;
    }

    public ModbusLocatorBuilder signed(boolean signed) {
        this.signed = signed;
        return this;
    }

    public ModbusLocatorBuilder swapped(boolean swapped) {
        this.swapped = swapped;
        return this;
    }

    public ModbusLocatorBuilder dataByteLength(int dataByteLength) {
        this.dataByteLength = dataByteLength;
        return this;
    }

    /**
     * 构建定位器。
     * <p>位读取（isBinary 或 BOOLEAN 类型）返回 {@code BaseLocator<Boolean>}，
     * 其余返回 {@code BaseLocator<Number>}，统一以 {@code BaseLocator<?>} 暴露。</p>
     */
    public BaseLocator<?> build() {
        if (isBitLocator()) {
            return buildBitLocator();
        }
        return buildRegisterLocator(resolveDataType());
    }

    private boolean isBitLocator() {
        return bitRead || operationDataType == OperationDataType.BOOLEAN;
    }

    private BaseLocator<?> buildBitLocator() {
        if (area == RegisterArea.INPUT) {
            return BaseLocator.inputRegisterBit(slaveId, offset, bit);
        }
        return BaseLocator.holdingRegisterBit(slaveId, offset, bit);
    }

    private BaseLocator<?> buildRegisterLocator(int dataType) {
        if (area == RegisterArea.INPUT) {
            return BaseLocator.inputRegister(slaveId, offset, dataType);
        }
        return BaseLocator.holdingRegister(slaveId, offset, dataType);
    }

    /**
     * 通用类型 -> modbus4j DataType
     */
    private int resolveDataType() {
        switch (operationDataType) {
            case FLOAT:
                return resolveFloatDataType();
            case STRING:
                return resolveStringDataType();
            case INT:
            case BOOLEAN:
            default:
                return resolveIntDataType();
        }
    }

    /**
     * 整数类型映射：按「字节长度 + 有无符号 + 是否交换」选择 modbus4j 常量。
     */
    private int resolveIntDataType() {
        switch (dataByteLength) {
            case 1:
                // modbus4j 无 1 字节有符号类型，只在寄存器高/低字节上取无符号值
                return swapped ? DataType.ONE_BYTE_INT_UNSIGNED_UPPER
                        : DataType.ONE_BYTE_INT_UNSIGNED_LOWER;
            case 2:
                if (signed) {
                    return swapped ? DataType.TWO_BYTE_INT_SIGNED_SWAPPED : DataType.TWO_BYTE_INT_SIGNED;
                }
                return swapped ? DataType.TWO_BYTE_INT_UNSIGNED_SWAPPED : DataType.TWO_BYTE_INT_UNSIGNED;
            case 4:
                if (signed) {
                    return swapped ? DataType.FOUR_BYTE_INT_SIGNED_SWAPPED : DataType.FOUR_BYTE_INT_SIGNED;
                }
                return swapped ? DataType.FOUR_BYTE_INT_UNSIGNED_SWAPPED : DataType.FOUR_BYTE_INT_UNSIGNED;
            case 8:
                if (signed) {
                    return swapped ? DataType.EIGHT_BYTE_INT_SIGNED_SWAPPED : DataType.EIGHT_BYTE_INT_SIGNED;
                }
                return swapped ? DataType.EIGHT_BYTE_INT_UNSIGNED_SWAPPED : DataType.EIGHT_BYTE_INT_UNSIGNED;
            default:
                throw new IllegalArgumentException("不支持的整数字节长度：" + dataByteLength
                        + "（仅支持 1/2/4/8）");
        }
    }

    /**
     * 浮点类型映射：modbus4j 只提供 4 字节与 8 字节浮点。
     */
    private int resolveFloatDataType() {
        switch (dataByteLength) {
            case 4:
                return swapped ? DataType.FOUR_BYTE_FLOAT_SWAPPED : DataType.FOUR_BYTE_FLOAT;
            case 8:
                return swapped ? DataType.EIGHT_BYTE_FLOAT_SWAPPED : DataType.EIGHT_BYTE_FLOAT;
            default:
                throw new IllegalArgumentException("不支持的浮点字节长度：" + dataByteLength
                        + "（仅支持 4/8）");
        }
    }

    /**
     * 字符串：modbus4j 未提供可按长度定位的字符串定位器
     * （{@link DataType#getRegisterCount(int)} 对 CHAR/VARCHAR 返回 0），
     * 需要按寄存器数组自行解码，故此处显式拒绝而不是静默出错。
     */
    private int resolveStringDataType() {
        throw new UnsupportedOperationException(
                "modbus4j 无法按长度定位字符串点位，请读取寄存器后自行按字符集解码");
    }
}
