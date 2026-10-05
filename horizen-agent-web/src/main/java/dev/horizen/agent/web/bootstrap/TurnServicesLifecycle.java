package dev.horizen.agent.web.bootstrap;

import dev.horizen.agent.application.turn.TurnServices;

import lombok.Getter;

import org.springframework.context.SmartLifecycle;

import java.util.Objects;
import java.util.Optional;

/**
 * 在常规单例初始化完成后启动，并在资源销毁前停止其管理的服务。
 */
public final class TurnServicesLifecycle implements SmartLifecycle, AutoCloseable {
    /** 当前宿主组装的执行、控制、恢复和持久化服务集合。 */
    @Getter private final Optional<TurnServices> services;

    /** 运行中的状态标记，用于选择当前组件的处理路径。 */
    private boolean running;

    /** 组件是否已关闭，用于避免重复释放或继续接收新工作。 */
    private boolean closed;

    /**
     * 创建执行服务集合生命周期，初始化该组件所需的状态、配置或依赖。
     *
     * @param services 当前执行服务集合生命周期持有的服务集合对象，供相应处理步骤使用。
     */
    public TurnServicesLifecycle(Optional<TurnServices> services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    /** 启动执行服务集合生命周期。 */
    @Override
    public synchronized void start() {
        if (closed || running) return;
        services.ifPresent(TurnServices::startRecovery);
        running = services.isPresent();
    }

    /**
     * 读取运行中。
     *
     * @return {@link #running} 中保存的值。
     */
    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    /**
     * 读取Phase。
     *
     * @return 本次操作返回的整数结果。
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /** 完成当前操作的stop步骤，按实现更新相应状态或依赖。 */
    @Override
    public void stop() {
        close();
    }

    /**
     * 完成当前操作的stop步骤，按实现更新相应状态或依赖。
     *
     * @param callback 接收当前执行结果的回调。
     */
    @Override
    public void stop(Runnable callback) {
        try {
            close();
        } finally {
            callback.run();
        }
    }

    /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        running = false;
        services.ifPresent(TurnServices::close);
    }
}
