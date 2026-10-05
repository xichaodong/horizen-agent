package dev.horizen.agent.web.api;

import lombok.Getter;

import org.springframework.http.HttpStatus;

/** 仅由 MVC 适配器抛出的传输错误。 */
@Getter
public class ApiException extends RuntimeException {
    /** 当前记录或执行的状态，具体取值由所属领域或协议约定。 */
    private final HttpStatus status;

    /**
     * 创建API异常，初始化该组件所需的状态、配置或依赖。
     *
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param message 用户输入、响应说明或诊断消息，含义由所属协议对象限定。
     */
    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
