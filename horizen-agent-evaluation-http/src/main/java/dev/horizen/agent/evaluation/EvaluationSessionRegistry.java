package dev.horizen.agent.evaluation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可信的会话级绑定，不接受模型参数中的评测上下文。
 */
public final class EvaluationSessionRegistry {
    /**
     * 会话对象或会话索引，按相应的归属键定位数据。
     */
    private final Map<String, EvaluationFixture> sessions = new ConcurrentHashMap<>();

    /**
     * 生成当前操作所需的key文本，供调用方继续处理。
     *
     * @param owner   当前评测会话注册表使用的数据归属，供其处理与状态记录使用。
     * @param session 当前评测会话注册表使用的会话，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private String key(String owner, String session) {
        return owner + "\0" + session;
    }

    /**
     * 绑定评测会话注册表。
     *
     * @param owner   当前评测会话注册表使用的数据归属，供其处理与状态记录使用。
     * @param session 当前评测会话注册表使用的会话，供其处理与状态记录使用。
     * @param fixture 当前评测会话注册表持有的样本对象，供相应处理步骤使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void bind(String owner, String session, EvaluationFixture fixture) {
        if (sessions.putIfAbsent(key(owner, session), fixture) != null)
            throw new IllegalStateException("Evaluation session already bound");
    }

    /**
     * 查找评测会话注册表。
     *
     * @param owner   当前评测会话注册表使用的数据归属，供其处理与状态记录使用。
     * @param session 当前评测会话注册表使用的会话，供其处理与状态记录使用。
     * @return 本次操作返回的评测样本结果。
     */
    public EvaluationFixture find(String owner, String session) {
        return sessions.get(key(owner, session));
    }

    /**
     * 移除评测会话注册表。
     *
     * @param owner   当前评测会话注册表使用的数据归属，供其处理与状态记录使用。
     * @param session 当前评测会话注册表使用的会话，供其处理与状态记录使用。
     */
    public void remove(String owner, String session) {
        sessions.remove(key(owner, session));
    }
}
