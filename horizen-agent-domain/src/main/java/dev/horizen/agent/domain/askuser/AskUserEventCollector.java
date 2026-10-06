package dev.horizen.agent.domain.askuser;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 接收当前执行产生的澄清请求事件，连接运行时工具与宿主交互流程。
 */
public final class AskUserEventCollector {
    /**
     * 尚未完成处理的工作或计数，供刷新、关闭与容量控制使用。
     */
    private final ConcurrentLinkedQueue<AskUserRequest> pending = new ConcurrentLinkedQueue<>();

    /**
     * 记录提问用户事件收集器。
     *
     * @param request 当前操作的请求参数。
     */
    public void record(AskUserRequest request) {
        pending.add(request);
    }

    /**
     * 轮询提问用户事件收集器。
     *
     * @return 本次操作返回的提问用户请求结果。
     */
    public AskUserRequest poll() {
        return pending.poll();
    }
}
