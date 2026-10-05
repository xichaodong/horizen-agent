package dev.horizen.agent.domain.askuser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 持久化问题协议的类型化视图，允许展示扩展。 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AskUserQuestion {
    /** 问题的稳定标识，回答通过该标识与问题对应。 */
    private String questionId;

    /** 本对象的协议类别，用于选择对应的解析或呈现规则。 */
    private String type;

    /** 当前参数、问题或资源是否为必需项。 */
    private boolean required;

    /** 可供当前请求选择的选项或策略集合。 */
    private List<Option> options = List.of();

    /** 提问用户Question内部的选项，封装该步骤需要的状态或输入输出。 */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Option {
        /** 可选答案的标识，供回答记录引用。 */
        private String optionId;
    }
}
