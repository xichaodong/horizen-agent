package dev.horizen.agent.provider.spi;

/** 跨协议传输值的规范化辅助类型，限制可序列化结构。 */
interface WireValue {
    /**
     * 生成当前操作所需的wireValue文本，供调用方继续处理。
     *
     * @return 本次处理生成或读取的文本。
     */
    String wireValue();
}
