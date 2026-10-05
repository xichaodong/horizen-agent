package dev.horizen.agent.domain.artifact;

/** 创建 Artifact 的主体类型。 */
public enum ArtifactSource {
    /** 由用户输入或提交的内容。 */
    USER,
    /** 由工具调用产生的内容。 */
    TOOL,
    /** 由 Agent 输出或处理产生的内容。 */
    AGENT,
    /** 由宿主或系统流程生成的内容。 */
    SYSTEM
}
