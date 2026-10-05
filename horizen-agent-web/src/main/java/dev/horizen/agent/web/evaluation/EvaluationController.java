package dev.horizen.agent.web.evaluation;

import dev.horizen.agent.evaluation.EvaluationProtocol;
import dev.horizen.agent.evaluation.EvaluationRunService;
import dev.horizen.agent.web.identity.ExecutionIdentityResolver;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.NoSuchElementException;
import java.util.concurrent.RejectedExecutionException;

/** 承接云端评测协议的 HTTP 请求，把执行与交互操作交给评测服务。 */
@RestController
@RequestMapping("/api/evaluation/v1/runs")
@ConditionalOnProperty(name = "horizen.agent.evaluation.enabled", havingValue = "true")
public class EvaluationController {
    /** 按运行标识登记并管理评测执行的服务。 */
    private final EvaluationRunService runs;

    /** 可信宿主解析的执行身份，供访问范围与审计使用。 */
    private final ExecutionIdentityResolver identity;

    /** 服务访问令牌，由宿主配置提供，用于请求认证。 */
    private final String token;

    /**
     * 创建评测接口控制器，初始化该组件所需的状态、配置或依赖。
     *
     * @param runs 提供runs能力的依赖，具体实现由当前组件的组装方传入。
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param token 服务访问令牌，由宿主配置提供，用于请求认证。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public EvaluationController(
            EvaluationRunService runs,
            ExecutionIdentityResolver identity,
            @Value("${horizen.agent.evaluation.token:}") String token) {
        if (token.isBlank())
            throw new IllegalArgumentException(
                    "Evaluation endpoint requires an authentication token");
        this.runs = runs;
        this.identity = identity;
        this.token = token;
    }

    /**
     * 完成当前操作的authorize步骤，按实现更新相应状态或依赖。
     *
     * @param request 当前操作的请求参数。
     * @throws ResponseStatusException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void authorize(HttpServletRequest request) {
        String supplied = request.getHeader("Authorization");
        if (supplied == null
                || !MessageDigest.isEqual(
                        ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    /**
     * 启动评测接口控制器。
     *
     * @param body 当前评测接口控制器持有的正文对象，供相应处理步骤使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的Deferred结果结果。
     */
    @PostMapping
    public DeferredResult<EvaluationProtocol.Status> start(
            @RequestBody EvaluationProtocol.Start body, HttpServletRequest request) {
        authorize(request);
        var owner = identity.resolve(request);
        var completion = runs.start(owner, body);
        var response = new DeferredResult<EvaluationProtocol.Status>(body.getTimeoutMs() + 5000);
        response.onTimeout(
                () -> {
                    try {
                        runs.cancel(owner, body.getExecutionId());
                    } catch (NoSuchElementException ignored) {
                    }
                    response.setErrorResult(
                            new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT));
                });
        response.onError(
                error -> {
                    try {
                        runs.cancel(owner, body.getExecutionId());
                    } catch (NoSuchElementException ignored) {
                    }
                });
        completion.whenComplete(
                (result, error) -> {
                    if (error == null) response.setResult(result);
                    else
                        response.setErrorResult(
                                new ResponseStatusException(HttpStatus.BAD_GATEWAY));
                });
        return response;
    }

    /**
     * 计算或取得本方法声明的结果，供当前EvaluationController处理步骤使用。
     *
     * @param id 目标对象的标识。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的状态结果。
     */
    @GetMapping("/{id}")
    public EvaluationProtocol.Status status(@PathVariable String id, HttpServletRequest request) {
        authorize(request);
        return runs.status(identity.resolve(request), id);
    }

    /**
     * 取消评测接口控制器。
     *
     * @param id 目标对象的标识。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的状态结果。
     */
    @PostMapping("/{id}/cancel")
    public EvaluationProtocol.Status cancel(@PathVariable String id, HttpServletRequest request) {
        authorize(request);
        return runs.cancel(identity.resolve(request), id);
    }

    /** 完成当前操作的invalid步骤，按实现更新相应状态或依赖。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void invalid() {}

    /** 完成当前操作的missing步骤，按实现更新相应状态或依赖。 */
    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void missing() {}

    /** 完成当前操作的busy步骤，按实现更新相应状态或依赖。 */
    @ExceptionHandler(RejectedExecutionException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public void busy() {}
}
