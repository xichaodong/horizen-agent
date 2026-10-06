package dev.horizen.agent.transaction;

import java.util.function.Supplier;

/**
 * 统一提交相关仓储变更，失败时全部回滚。
 */
public interface UnitOfWork {
    /**
     * 在同一工作单元中执行回调，使相关仓储修改一起提交；失败时由具体实现回滚。
     *
     * @param work 需要在当前工作单元内执行的回调。
     * @return 工作回调的返回结果；更新的提交与回滚由工作单元实现统一处理。
     */
    <T> T execute(Supplier<T> work);

    /**
     * 在工作单元中执行无返回值的回调，复用同一提交与回滚边界。
     *
     * @param work 需要在当前工作单元内执行的回调。
     */
    default void executeWithoutResult(Runnable work) {
        execute(
                () -> {
                    work.run();
                    return null;
                });
    }
}
