package net.gateway.protocol.modbus;

public enum ModbusByteOrder {
    BIG_ENDIAN_BIG_WORD,    // ABCD（标准）
    BIG_ENDIAN_LITTLE_WORD, // CDAB（字交换）
    LITTLE_ENDIAN_BIG_WORD, // BADC（字节交换）
    LITTLE_ENDIAN_LITTLE_WORD // DCBA（全反转）
}
