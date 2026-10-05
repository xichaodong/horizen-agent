package dev.horizen.agent.application.interaction;

import com.fasterxml.jackson.core.type.TypeReference;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.domain.askuser.AskUserAnswer;
import dev.horizen.agent.domain.askuser.AskUserQuestion;

import java.util.*;

/** 依据可信的持久化问题校验类型化答案，不依赖 HTTP 传输。 */
public final class AskUserAnswerValidator {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private AskUserAnswerValidator() {}

    /**
     * 编码提问用户回答校验器。
     *
     * @param questionsJson 问题集合的 JSON 表示，供持久化或协议转换使用。
     * @param answers 用户对澄清问题的回答集合，按问题标识关联选项和补充文本。
     * @return 本次处理生成或读取的文本。
     */
    public static String encode(String questionsJson, List<Map<String, Object>> answers) {
        return JsonUtils.toJson(validate(questionsJson, answers));
    }

    /**
     * 核对问题标识、选项选择、自由文本与必填规则，拒绝不属于原澄清请求的答案。
     *
     * @param questionsJson 问题集合的 JSON 表示，供持久化或协议转换使用。
     * @param rawAnswers 原始回答集合的索引映射，供按键查找或归并当前组件的数据。
     * @return 本次处理得到的结果集合。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static List<AskUserAnswer> validate(
            String questionsJson, List<Map<String, Object>> rawAnswers) {
        final List<AskUserQuestion> questions;
        try {
            questions =
                    JsonUtils.read(questionsJson, new TypeReference<List<AskUserQuestion>>() {});
        } catch (Exception error) {
            throw new ApplicationError(ApplicationError.Code.CONFLICT, "问题单内容无效");
        }
        Map<String, AskUserQuestion> byId = new LinkedHashMap<>();
        for (var question : questions) byId.put(question.getQuestionId(), question);
        var values = rawAnswers == null ? List.<Map<String, Object>>of() : rawAnswers;
        if (values.size() > questions.size()) invalid("回答数量超过问题数量");
        Set<String> answered = new HashSet<>();
        List<AskUserAnswer> normalized = new ArrayList<>();
        for (var raw : values) {
            if (raw == null) invalid("回答格式无效");
            if (raw.containsKey("selectedOptionIds")
                    && !(raw.get("selectedOptionIds") instanceof List<?>))
                invalid("selectedOptionIds 必须是数组");
            final AskUserAnswer answer;
            try {
                answer = JsonUtils.newMapper().convertValue(raw, AskUserAnswer.class);
            } catch (IllegalArgumentException error) {
                throw new ApplicationError(ApplicationError.Code.INVALID_ARGUMENT, "回答格式无效");
            }
            String id = required(answer.getQuestionId());
            var question = byId.get(id);
            if (question == null || !answered.add(id)) invalid("questionId 不存在或重复");
            Set<String> validOptions = new HashSet<>();
            if (question.getOptions() != null)
                question.getOptions().forEach(option -> validOptions.add(option.getOptionId()));
            Set<String> selected = new LinkedHashSet<>();
            for (String optionId :
                    answer.getSelectedOptionIds() == null
                            ? List.<String>of()
                            : answer.getSelectedOptionIds()) {
                String option = required(optionId);
                if (!validOptions.contains(option) || !selected.add(option)) invalid("选项不存在或重复");
            }
            if ("single".equals(question.getType()) && selected.size() > 1) invalid("单选题只能选择一个选项");
            String custom = answer.getCustomText() == null ? "" : answer.getCustomText().trim();
            if (custom.length() > 2000) invalid("补充文本过长");
            if (question.isRequired() && selected.isEmpty() && custom.isEmpty()) invalid("请回答必答题");
            normalized.add(new AskUserAnswer(id, List.copyOf(selected), custom));
        }
        for (var question : questions)
            if (question.isRequired() && !answered.contains(question.getQuestionId()))
                invalid("请回答必答题");
        return List.copyOf(normalized);
    }

    /**
     * 生成当前操作所需的required文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String required(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) invalid("回答内容不能为空");
        return text;
    }

    /**
     * 完成当前操作的invalid步骤，按实现更新相应状态或依赖。
     *
     * @param text 面向消息或事件消费者的文本内容。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void invalid(String text) {
        throw new ApplicationError(ApplicationError.Code.INVALID_ARGUMENT, text);
    }
}
