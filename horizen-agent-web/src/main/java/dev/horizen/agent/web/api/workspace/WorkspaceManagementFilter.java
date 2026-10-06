package dev.horizen.agent.web.api.workspace;

import dev.horizen.agent.web.config.WorkspaceManagementProperties;
import dev.horizen.agent.web.identity.ServiceTokenVerifier;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;

/**
 * 在 Spring 为 JSON 请求体分配内存前完成认证和容量准入检查。
 */
@RequiredArgsConstructor
@Component
@Order(-100)
@ConditionalOnProperty(name = "horizen.agent.workspace-management.enabled", havingValue = "true")
public class WorkspaceManagementFilter extends OncePerRequestFilter {
    /**
     * 上限的固定取值，用于相应策略和边界判断。
     */
    private static final long LIMIT = 32L * 1024 * 1024;

    /**
     * 请求集合的并发准入许可，限制同时进行的处理数量。
     */
    private final Semaphore requests = new Semaphore(2);

    /**
     * 宿主绑定的配置对象，供组件组装与策略校验使用。
     */
    private final WorkspaceManagementProperties properties;

    /**
     * 检查shouldNotFilter对应的条件，供调用方选择后续处理分支。
     *
     * @param request 当前操作的请求参数。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/internal/workspaces/");
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param request  当前操作的请求参数。
     * @param response 当前操作得到的响应。
     * @param chain    当前工作区管理过滤持有的chain对象，供相应处理步骤使用。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!ServiceTokenVerifier.matches(
                request.getHeader("Authorization"), properties.getToken())) {
            reject(response, 401, "Invalid workspace management credential");
            return;
        }
        if (request.getContentLengthLong() > LIMIT) {
            reject(response, 413, "Workspace request exceeds 32 MiB");
            return;
        }
        if (!requests.tryAcquire()) {
            reject(response, 429, "Workspace management capacity exhausted");
            return;
        }
        try {
            chain.doFilter(
                    new HttpServletRequestWrapper(request) {
                        /**
                         * 读取输入事件流。
                         *
                         * @return 本次操作返回的Servlet输入事件流结果。
                         * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
                         */
                        @Override
                        public ServletInputStream getInputStream() throws IOException {
                            ServletInputStream delegate = super.getInputStream();
                            return new ServletInputStream() {
                                /** 用于anonymous内部处理的 read 值；读写位置由该类型的方法限定。 */
                                private long read;

                                /**
                                 * 完成当前操作的count步骤，按实现更新相应状态或依赖。
                                 *
                                 * @param size 当前内容或集合的大小，计量方式由所属资源协议定义。
                                 * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
                                 */
                                private void count(int size) throws IOException {
                                    if (size > 0 && (read += size) > LIMIT)
                                        throw new IOException("Workspace request exceeds 32 MiB");
                                }

                                /**
                                 * 读取匿名实现。
                                 *
                                 * @return 本次操作返回的整数结果。
                                 */
                                @Override
                                public int read() throws IOException {
                                    int value = delegate.read();
                                    count(value < 0 ? 0 : 1);
                                    return value;
                                }

                                /**
                                 * 读取匿名实现。
                                 *
                                 * @param bytes 当前操作处理的内容字节。
                                 * @param offset 本次读取的起始偏移。
                                 * @param length 当前匿名实现使用的长度，供其处理与状态记录使用。
                                 * @return 本次操作返回的整数结果。
                                 */
                                @Override
                                public int read(byte[] bytes, int offset, int length)
                                        throws IOException {
                                    int size = delegate.read(bytes, offset, length);
                                    count(size);
                                    return size;
                                }

                                /**
                                 * 判断结束。
                                 *
                                 * @return 本次检查是否通过或本次更新是否成功。
                                 */
                                @Override
                                public boolean isFinished() {
                                    return delegate.isFinished();
                                }

                                /**
                                 * 判断就绪。
                                 *
                                 * @return 本次检查是否通过或本次更新是否成功。
                                 */
                                @Override
                                public boolean isReady() {
                                    return delegate.isReady();
                                }

                                /**
                                 * 设置读取监听器。
                                 *
                                 * @param listener 当前匿名实现持有的监听器对象，供相应处理步骤使用。
                                 */
                                @Override
                                public void setReadListener(ReadListener listener) {
                                    delegate.setReadListener(listener);
                                }

                                /** 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。 */
                                @Override
                                public void close() throws IOException {
                                    delegate.close();
                                }
                            };
                        }
                    },
                    response);
        } finally {
            requests.release();
        }
    }

    /**
     * 完成当前操作的reject步骤，按实现更新相应状态或依赖。
     *
     * @param response 当前操作得到的响应。
     * @param code     当前工作区管理过滤使用的代码，供其处理与状态记录使用。
     * @param message  用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     */
    private static void reject(HttpServletResponse response, int code, String message)
            throws IOException {
        response.setStatus(code);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
