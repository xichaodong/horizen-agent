package dev.horizen.agent.application;

import lombok.Getter;

import java.util.Objects;

/**
 * 由应用用例抛出的错误，不依赖具体传输协议。
 */
@Getter
public final class ApplicationError extends RuntimeException {
    /**
     * 应用错误的稳定分类，供 HTTP 门面转换响应状态。
     */
    private final Code code;

    /**
     * 创建应用错误，初始化该组件所需的状态、配置或依赖。
     *
     * @param code    当前应用错误持有的代码对象，供相应处理步骤使用。
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     */
    public ApplicationError(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 应用错误使用的状态或策略分类，具体分支按枚举成员区分。
     */
    public enum Code {
        /**
         * 输入未满足当前接口或领域规则。
         */
        INVALID_ARGUMENT,
        /**
         * 在当前访问范围内没有找到所请求对象。
         */
        NOT_FOUND,
        /**
         * 当前状态或版本与所请求更新不一致。
         */
        CONFLICT,
        /**
         * 记录已超过有效期，不再接受原决定或访问。
         */
        EXPIRED,
        /**
         * 当前依赖或执行环境不可用，无法完成操作。
         */
        UNAVAILABLE
    }
}
