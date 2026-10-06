package dev.horizen.agent.domain.artifact;

/**
 * Artifact 在一次 Turn 中承担的作用。
 */
public enum ArtifactReferenceRole {
    /**
     * 作为当前消息或执行输入的产物引用。
     */
    INPUT,
    /**
     * 当前执行交付的输出产物引用。
     */
    OUTPUT,
    /**
     * 执行过程使用的工作产物引用。
     */
    WORKING
}
