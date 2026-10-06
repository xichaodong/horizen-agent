package dev.horizen.agent.application.interaction;

import java.util.List;
import java.util.Map;

/**
 * 依据序列化问题结构校验答案的输入接口。
 */
@FunctionalInterface
public interface AskUserAnswerEncoder {
    /**
     * 根据原问题的选项、必填与自由文本规则校验回答，然后编码成运行时恢复负载。
     *
     * @param questionsJson 问题集合的 JSON 表示，供持久化或协议转换使用。
     * @param answers       用户对澄清问题的回答集合，按问题标识关联选项和补充文本。
     * @return 本次处理生成或读取的文本。
     */
    String validateAndEncode(String questionsJson, List<Map<String, Object>> answers);
}
