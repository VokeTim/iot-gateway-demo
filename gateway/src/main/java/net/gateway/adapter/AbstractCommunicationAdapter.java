package net.gateway.adapter;

import net.gateway.protocol.CommunicationAdapter;
import net.gateway.protocol.DataPoint;
import net.gateway.protocol.ReadResult;
import net.gateway.protocol.WriteResult;

import java.util.Collections;
import java.util.List;

/**
 * 协议无关的抽象通信适配器
 *
 * 职责：连接生命周期管理（懒加载单例/锁/超时/连接-断开-重连）、统一结果构建
 * 数据模型与读写语义留给各协议子类实现（Modbus/PLC/BACnet/OPC UA 等各自独立）
 *
 * @param <T> 各协议自己的连接对象类型
 *           Modbus  → ModbusMaster
 *           BACNet  → 对应连接对象
 *           OPC_UA  → UaClient/Session
 */
public abstract class AbstractCommunicationAdapter<T> implements CommunicationAdapter {

    /** 连接对象（懒加载单例，volatile 保证可见性） */
    private volatile T connection;

    /** 连接锁：读写/批量操作共用，保证并发互斥 */
    protected final Object connectionLock = new Object();

    /** 超时时间（毫秒），子类可覆盖默认值 */
    private int timeoutMillis = 3000;

    /** 重试次数 */
    private int retries = 0;

    // ==================== 抽象方法（子类必须实现） ====================
    /** 创建协议连接对象（各协议不同） */
    protected abstract T createConnection();

    /** 设置连接超时（各协议 API 不同，如 master.setTimeout / client.setTimeout） */
    protected abstract void setTimeout(T connection, int timeoutMillis);

    /** 设置重试（各协议 API 不同） */
    protected abstract void setRetries(T connection, int retries);

    // ==================== 连接生命周期管理（公共实现） ====================
    /**
     * 获取连接对象（懒加载单例 + 统一配置）
     * 首次调用时创建，之后复用；每次返回前统一设置超时/重试
     */
    protected T getConnection() {
        if (connection == null) {
            synchronized (connectionLock) {
                if (connection == null) {
                    connection = createConnection();
                    setTimeout(connection, timeoutMillis);
                    setRetries(connection, retries);
                }
            }
        }
        return connection;
    }

    /** 执行操作时的锁（子类在读写/批量操作时使用） */
    protected Object getLock() {
        return connectionLock;
    }

    // ==================== 生命周期回调（由具体连接语义决定） ====================
    @Override
    public void connect() {
        // 触发懒加载创建连接
        getConnection();
    }

    @Override
    public void disconnect() {
        synchronized (connectionLock) {
            if (connection != null) {
                doDisconnect(connection);   // 子类实现各协议的断开
                connection = null;          // 释放引用，下次重新创建
            }
        }
    }

    /** 子类实现各协议实际的断开逻辑 */
    protected abstract void doDisconnect(T connection);

    @Override
    public void reconnect() {
        synchronized (connectionLock) {
            if (connection != null) {
                doDisconnect(connection);
                connection = null;
            }
            connection = createConnection();
            setTimeout(connection, timeoutMillis);
            setRetries(connection, retries);
        }
    }

    // ==================== 配置入口 ====================
    /** 设置超时（供请求级覆盖或配置注入） */
    public void setTimeout(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }


    // ==================== 统一结果构建（协议无关） ====================
    protected ReadResult buildSuccessReadResult(List<DataPoint> dataPoints, long timestamp) {
        return buildReadResult(dataPoints, true, null, timestamp);
    }

    protected ReadResult buildErrorReadResult(Throwable ex, long timestamp) {
        return buildReadResult(Collections.emptyList(),false, ex, timestamp);
    }

    private ReadResult buildReadResult(List<DataPoint> dataPoints,boolean success, Throwable ex, long timestamp) {
        ReadResult result = new ReadResult();
        result.setSuccess(success);
        result.setTimestamp(timestamp);
        if(dataPoints.size()>0){
            result.setDataPoints(dataPoints);
        }else{
            result.setDataPoints(Collections.emptyList());
        }
        if (ex != null) {
            result.setErrorMessage(ex.getMessage());
        }
        return result;
    }

    protected WriteResult buildSuccessWriteResult(long timestamp) {
        return buildWriteResult(true, null, timestamp);
    }

    protected WriteResult buildErrorWriteResult(Throwable ex, long timestamp) {
        return buildWriteResult(false, ex, timestamp);
    }

    private WriteResult buildWriteResult(boolean success, Throwable ex, long timestamp) {
        WriteResult result = new WriteResult();
        result.setSuccess(success);
        result.setTimestamp(timestamp);
        if (ex != null) {
            result.setErrorMessage(ex.getMessage());
        }
        return result;
    }

    public int getTimeoutMillis(){
        return timeoutMillis;
    }
}
