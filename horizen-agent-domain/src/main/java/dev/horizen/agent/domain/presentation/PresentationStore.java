package dev.horizen.agent.domain.presentation;

import java.util.List;
import java.util.Optional;

/** 展示输出的持久化接口；所有查询均必须按 ownerKey 隔离。 */
public interface PresentationStore {
    /**
     * 创建候选查找。
     *
     * @param record 当前呈现存储持有的记录对象，供相应处理步骤使用。
     * @return 本次操作返回的呈现记录结果。
     */
    PresentationRecord createOrFind(PresentationRecord record);

    /**
     * 查找呈现存储。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param blockId 结构化呈现块的标识，客户端用它去重与更新同一张卡片。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<PresentationRecord> find(String ownerKey, String blockId);

    /**
     * 查询列表中的目标范围会话。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    List<PresentationRecord> listForSession(String ownerKey, String sessionId);
}
