package dev.horizen.agent.web.api;

import dev.horizen.agent.application.ApplicationError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/** 将应用异常转换成稳定的 HTTP 错误响应，保留错误分类并避免泄露内部异常正文。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    /** 当前组件的诊断日志器。 */
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** multipart 入口拒绝超限文件时返回明确的 413，正文尚未进入上传业务。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleUploadTooLarge(
            MaxUploadSizeExceededException error) {
        return ResponseEntity.status(413)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "上传文件超过允许大小"));
    }

    /**
     * 完成当前操作的handleDisconnectedClient步骤，按实现更新相应状态或依赖。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleDisconnectedClient(AsyncRequestNotUsableException error) {
        // 异步响应已不可用，在同一连接写入错误响应体也会失败。
        log.debug("Async response is no longer writable: {}", error.getMessage());
    }

    /**
     * 计算或取得本方法声明的结果，供当前ApiExceptionHandler处理步骤使用。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     * @return 本次操作返回的响应实体结果。
     */
    @ExceptionHandler(ApplicationError.class)
    public ResponseEntity<Map<String, String>> handleApplicationError(ApplicationError error) {
        return handleApiException(AgentApiMapper.apiError(error));
    }

    /**
     * 计算或取得本方法声明的结果，供当前ApiExceptionHandler处理步骤使用。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     * @return 本次操作返回的响应实体结果。
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApiException(ApiException error) {
        return ResponseEntity.status(error.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", error.getMessage()));
    }

    /**
     * 计算或取得本方法声明的结果，供当前ApiExceptionHandler处理步骤使用。
     *
     * @param error 本次失败的异常，用于分类、传播或诊断。
     * @return 本次操作返回的响应实体结果。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpectedException(Exception error) {
        if (error instanceof ErrorResponse response) {
            String text =
                    switch (response.getStatusCode().value()) {
                        case 400 -> "请求格式或参数无效，请检查后重试。";
                        case 401 -> "身份验证失败，请重新登录后重试。";
                        case 403 -> "当前操作未获授权。";
                        case 404 -> "请求的内容不存在或已不可访问。";
                        case 429 -> "服务繁忙，请稍后重试。";
                        default -> "请求处理出现异常，当前结果尚未确认。";
                    };
            return ResponseEntity.status(response.getStatusCode())
                    .headers(response.getHeaders())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", text));
        }
        log.error("Unhandled API request failure", error);
        return ResponseEntity.internalServerError()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "请求处理出现异常，当前结果尚未确认。"));
    }
}
