package dev.horizen.agent.web.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;

import lombok.Data;
import lombok.ToString;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/** 上下文窗口、历史压缩和大工具结果处理策略。 */
@Validated
@ConfigurationProperties(prefix = "horizen.agent.context")
@Data
public class ContextProperties {
    /** 是否启用压缩处理。 */
    private boolean compactionEnabled = true;

    /** 主模型上下文窗口大小，单位为 token，用于计算压缩触发阈值。 */
    @Min(0)
    private int modelContextWindowTokens;

    /** 上下文压缩所用模型名称，未单独配置时由组装逻辑选择模型。 */
    private String compressionModelName = "";

    /** 压缩模型服务的基础地址。 */
    private String compressionModelBaseUrl = "";

    /** 压缩模型使用的服务端访问凭据。 */
    @ToString.Exclude private String compressionModelApiKey = "";

    /** 压缩模型上下文窗口的 token 数量或预算。 */
    @Min(0)
    private int compressionModelContextWindowTokens;

    /** 单次压缩模型输出的 token 上限。 */
    @Min(0)
    private int compressionMaxOutputTokens;

    /** 压缩模型请求的采样温度。 */
    private double compressionTemperature;

    /** 单次压缩请求允许的最长等待时间。 */
    private Duration compressionTimeout = Duration.ofSeconds(30);

    /** 压缩请求允许的尝试次数上限。 */
    @Min(1)
    private int compressionMaxAttempts = 2;

    /** 工作区上下文最大的 token 数量或预算。 */
    @Min(1)
    private int workspaceContextMaxTokens = 8_000;

    /** 压缩时优先保留的开头消息数量。 */
    @Min(0)
    private int protectHeadMessages = 3;

    /** 单次执行允许触发的普通压缩次数上限。 */
    @Min(1)
    private int maxCompactionsPerTurn = 1;

    /** 达到该消息数量时允许触发上下文压缩。 */
    @Min(0)
    private int triggerMessages = 30;

    /** 触发的 token 数量或预算。 */
    @Min(0)
    private int triggerTokens;

    /** 预留的 token 数量或预算。 */
    @Min(1)
    private int reservedTokens = 20_000;

    /** 压缩后保留的尾部消息数量。 */
    @Min(1)
    private int keepMessages = 10;

    /** 压缩策略保留的上下文 token 预算。 */
    private int keepTokens = -1;

    /** 动态上下文保留预算的下限，单位为 token。 */
    @Min(1)
    private int keepTokensMin = 2_000;

    /** 动态上下文保留预算的上限，单位为 token。 */
    @Min(1)
    private int keepTokensMax = 8_000;

    /** 相对于模型窗口计算上下文保留预算的比例。 */
    private double keepTokensRatio = 0.25;

    /** 刷新处理前Compact的状态标记，用于选择当前组件的处理路径。 */
    private boolean flushBeforeCompact;

    /** offload处理前Compact的状态标记，用于选择当前组件的处理路径。 */
    private boolean offloadBeforeCompact;

    /** abort响应摘要失败的状态标记，用于选择当前组件的处理路径。 */
    private boolean abortOnSummaryFailure;

    /** 是否启用截断参数处理。 */
    private boolean truncateArgumentsEnabled;

    /** 允许裁剪工具参数时使用的消息数量触发阈值。 */
    @Min(0)
    private int truncateArgumentsTriggerMessages = 25;

    /** 截断参数触发的 token 数量或预算。 */
    @Min(0)
    private int truncateArgumentsTriggerTokens = 40_000;

    /** 工具参数裁剪时保留原参数的最近消息数量。 */
    @Min(1)
    private int truncateArgumentsKeepMessages = 20;

    /** 截断参数保留的 token 数量或预算。 */
    @Min(0)
    private int truncateArgumentsKeepTokens;

    /** 工具参数裁剪后允许保留的文本字符数上限。 */
    @Min(1)
    private int truncateArgumentsMaxLength = 2_000;

    /** 是否启用清理工具Results处理。 */
    private boolean pruneToolResultsEnabled = true;

    /** 清理保护的 token 数量或预算。 */
    @Min(0)
    private int pruneProtectTokens = 40_000;

    /** 清理最小的 token 数量或预算。 */
    @Min(0)
    private int pruneMinimumTokens = 20_000;

    /** 工具输出裁剪后保留的最大字符数量。 */
    @Min(1)
    private int pruneMaxOutputChars = 2_000;

    /** 清理排除工具集合的去重集合，供成员查找或范围检查使用。 */
    private Set<String> pruneExcludedTools =
            new LinkedHashSet<>(
                    Set.of("read_file", "memory_search", "memory_get", "session_search"));

    /** 是否启用工具结果Eviction处理。 */
    private boolean toolResultEvictionEnabled;

    /** 触发工具结果移出工作上下文的字符数阈值。 */
    @Min(1)
    private int toolResultEvictionMaxChars = 80_000;

    /** 工具结果移出正文后保留的预览字符数量。 */
    @Min(1)
    private int toolResultEvictionPreviewChars = 2_000;

    /** 持久保存被移出工具结果的目标路径配置。 */
    private String toolResultEvictionPath = "large_tool_results";

    /** 是否启用超限恢复处理。 */
    private boolean overflowRecoveryEnabled = true;

    /** 上下文超限恢复时保留的最近消息数量。 */
    @Min(1)
    private int overflowKeepMessages = 1;

    /**
     * 判断是否存在压缩模型。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean hasCompressionModel() {
        return compressionModelName != null && !compressionModelName.isBlank();
    }

    /**
     * 判断策略Valid。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @AssertTrue(message = "Invalid context policy combination")
    public boolean isPolicyValid() {
        try {
            ContextPolicyValidator.validate(this);
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
