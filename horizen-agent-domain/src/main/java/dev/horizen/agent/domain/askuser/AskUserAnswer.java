package dev.horizen.agent.domain.askuser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 某一条澄清问题的答案，区分选项选择与自由文本补充。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AskUserAnswer {
    /** 问题的稳定标识，回答通过该标识与问题对应。 */
    private String questionId;

    /** 用户选择的选项标识集合。 */
    private List<String> selectedOptionIds = List.of();

    /** 用户补充的自由文本，与结构化选项分开保存。 */
    private String customText = "";
}
