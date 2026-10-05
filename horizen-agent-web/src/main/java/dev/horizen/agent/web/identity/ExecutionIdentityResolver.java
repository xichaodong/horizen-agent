package dev.horizen.agent.web.identity;

import dev.horizen.agent.identity.ExecutionIdentity;

import jakarta.servlet.http.HttpServletRequest;

/** 宿主把已经认证的请求转换为 Runtime 使用的不透明隔离身份。 */
public interface ExecutionIdentityResolver {
    /**
     * 解析执行身份解析器。
     *
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的执行身份结果。
     */
    ExecutionIdentity resolve(HttpServletRequest request);
}
