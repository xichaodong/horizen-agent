package dev.horizen.agent.interaction.approval;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 审批决定的原子更新结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalDecisionResult {
    /** 本次领域操作的结果分类，区分已更新、重复与并发冲突等情形。 */
    private Outcome outcome;

    /** 本次决定所涉及的原审批记录。 */
    private ApprovalRequest approval;

    /** 审批决定提交的领域结果类别，用于区分成功、重复与无效决定。 */
    public enum Outcome {
        /** 本次更新已经提交。 */
        UPDATED,
        /** 原审批已经提交过决定，本次不重复改变决定。 */
        ALREADY_DECIDED,
        /** 在当前访问范围内没有找到所请求对象。 */
        NOT_FOUND
    }
}
