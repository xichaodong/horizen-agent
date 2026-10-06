package dev.horizen.agent.tools.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.common.process.ShellQuoteUtils;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turn 作用域的后台进程控制，完全在活跃沙箱内实现。
 */
public final class SandboxProcessTool extends ToolBase {
    /**
     * 最大PROCESSES的固定取值，用于相应策略和边界判断。
     */
    static final int MAX_PROCESSES = 16;

    /**
     * 最大命令字符数的固定取值，用于相应策略和边界判断。
     */
    static final int MAX_COMMAND_CHARS = 32_768;

    /**
     * 最大输入字节的固定取值，用于相应策略和边界判断。
     */
    static final int MAX_INPUT_BYTES = 16 * 1024;

    /**
     * 最大日志字节的固定取值，用于相应策略和边界判断。
     */
    static final int MAX_LOG_BYTES = 64 * 1024;

    /**
     * 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。
     */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /**
     * 根使用的固定标识或协议文本。
     */
    private static final String ROOT = ".horizen/processes";

    /**
     * 测试时替代真实沙箱命令的执行入口。
     */
    private final CommandExecutor testExecutor;

    /**
     * 创建沙箱进程工具，初始化该组件所需的状态、配置或依赖。
     */
    public SandboxProcessTool() {
        this(null);
    }

    /**
     * 创建沙箱进程工具，初始化该组件所需的状态、配置或依赖。
     *
     * @param testExecutor 当前沙箱进程工具持有的test执行方对象，供相应处理步骤使用。
     */
    SandboxProcessTool(CommandExecutor testExecutor) {
        super(
                ToolBase.builder()
                        .name("process")
                        .description(
                                "管理当前 Turn 沙箱中的后台进程。支持 start/status/list/logs/stdin/wait/kill；"
                                        + "进程不会跨沙箱释放继续存在。")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "action",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "enum",
                                                        List.of(
                                                                "start", "status", "list",
                                                                "logs", "stdin", "wait",
                                                                "kill")),
                                                "command",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "start 使用的 shell 命令"),
                                                "process_id",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "proc_ 开头的进程句柄"),
                                                "stdout_offset",
                                                Map.of("type", "integer", "minimum", 0),
                                                "stderr_offset",
                                                Map.of("type", "integer", "minimum", 0),
                                                "limit",
                                                Map.of(
                                                        "type",
                                                        "integer",
                                                        "minimum",
                                                        1,
                                                        "maximum",
                                                        MAX_LOG_BYTES),
                                                "data",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "stdin 数据"),
                                                "append_newline", Map.of("type", "boolean"),
                                                "timeout_seconds",
                                                Map.of(
                                                        "type", "integer", "minimum", 1,
                                                        "maximum", 120),
                                                "signal",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "enum",
                                                        List.of("term", "kill"))),
                                        "required",
                                        List.of("action"),
                                        "additionalProperties",
                                        false))
                        .readOnly(false)
                        .concurrencySafe(false));
        this.testExecutor = testExecutor;
    }

    /**
     * 以异步结果承接本工具调用，由当前适配器完成输入解析与结果转换。
     *
     * @param param 当前沙箱进程工具持有的参数对象，供相应处理步骤使用。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(
                () -> {
                    try {
                        return invoke(param);
                    } catch (IllegalArgumentException error) {
                        return ToolResultBlock.error(error.getMessage());
                    } catch (Exception error) {
                        return ToolResultBlock.error(
                                "process operation failed: " + error.getClass().getSimpleName());
                    }
                });
    }

    /**
     * 调用沙箱进程工具。
     *
     * @param param 当前沙箱进程工具持有的参数对象，供相应处理步骤使用。
     * @return 本次操作返回的工具结果块结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private ToolResultBlock invoke(ToolCallParam param) throws Exception {
        String action = text(param, "action", "");
        return switch (action) {
            case "start" -> start(param);
            case "status" -> status(param, requireProcessId(param));
            case "list" -> list(param);
            case "logs" -> logs(param, requireProcessId(param));
            case "stdin" -> stdin(param, requireProcessId(param));
            case "wait" -> waitFor(param, requireProcessId(param));
            case "kill" -> kill(param, requireProcessId(param));
            default -> throw new IllegalArgumentException("unsupported process action");
        };
    }

    /**
     * 启动沙箱进程工具。
     *
     * @param param 当前沙箱进程工具持有的参数对象，供相应处理步骤使用。
     * @return 本次操作返回的工具结果块结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private ToolResultBlock start(ToolCallParam param) throws Exception {
        String command = text(param, "command", "");
        if (command.isBlank()) throw new IllegalArgumentException("command is required for start");
        if (command.length() > MAX_COMMAND_CHARS)
            throw new IllegalArgumentException("command is too long");
        String processId = "proc_" + UUID.randomUUID().toString().replace("-", "");
        String encoded =
                Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8));
        ExecuteResponse result = execute(param, startCommand(processId, encoded), 30);
        if (!result.isSuccess()) return failure("start", result);
        ObjectNode output = base(processId).put("status", "running");
        output.put("pid", parseLong(result.output().strip(), "pid"));
        return ToolResultBlock.text(output.toString());
    }

    /**
     * 计算或取得本方法声明的结果，供当前SandboxProcessTool处理步骤使用。
     *
     * @param param     当前沙箱进程工具持有的参数对象，供相应处理步骤使用。
     * @param processId 进程的标识，用于关联相应记录或执行。
     * @return 本次操作返回的工具结果块结果。
     */
    private ToolResultBlock status(ToolCallParam param, String processId) throws Exception {
        ExecuteResponse result = execute(param, statusCommand(processId), 10);
        if (!result.isSuccess()) return failure("status", result);
        return ToolResultBlock.text(parseStatus(processId, result.output()).toString());
    }

    /**
     * 查询列表中的沙箱进程工具。
     *
     * @param param 当前沙箱进程工具持有的参数对象，供相应处理步骤使用。
     * @return 本次操作返回的工具结果块结果。
     */
    private ToolResultBlock list(ToolCallParam param) throws Exception {
        String command =
                "test -d "
                        + quote(ROOT)
                        + " && find "
                        + quote(ROOT)
                        + " -mindepth 1 -maxdepth 1 -type d -exec basename {} \\; | sort || true";
        ExecuteResponse result = execute(param, command, 10);
        if (!result.isSuccess()) return failure("list", result);
        ArrayNode ids = JSON.createArrayNode();
        if (result.output() != null)
            result.output()
                    .lines()
                    .map(String::trim)
                    .filter(SandboxProcessTool::validProcessId)
                    .forEach(ids::add);
        ObjectNode output = JSON.createObjectNode().put("status", "success");
        output.set("process_ids", ids);
        return ToolResultBlock.text(output.toString());
    }

    /**
     * 计算或取得本方法声明的结果，供当前SandboxProcessTool处理步骤使用。
     *
     * @param param     当前沙箱进程工具持有的参数对象，供相应处理步骤使用。
     * @param processId 进程的标识，用于关联相应记录或执行。
     * @return 本次操作返回的工具结果块结果。
     */
    private ToolResultBlock logs(ToolCallParam param, String processId) throws Exception {
        long stdoutOffset = longValue(param, "stdout_offset", 0, 0, Long.MAX_VALUE);
        long stderrOffset = longValue(param, "stderr_offset", 0, 0, Long.MAX_VALUE);
        int limit = (int) longValue(param, "limit", 8192, 1, MAX_LOG_BYTES);
        ExecuteResponse result =
                execute(param, logsCommand(processId, stdoutOffset, stderrOffset, limit), 20);
        if (!result.isSuccess()) return failure("logs", result);
        String raw = result.output();
        while (raw.endsWith("\n") || raw.endsWith("\r")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        String[] fields = raw.split("\\t", -1);
        if (fields.length != 6) return ToolResultBlock.error("process logs returned invalid data");
        long stdoutTotal = parseLong(fields[0], "stdout_total");
        long stdoutNext = parseLong(fields[1], "stdout_next");
        long stderrTotal = parseLong(fields[3], "stderr_total");
        long stderrNext = parseLong(fields[4], "stderr_next");
        ObjectNode output =
                base(processId)
                        .put("status", "success")
                        .put("stdout", decode(fields[2]))
                        .put("stderr", decode(fields[5]))
                        .put("stdout_next_offset", stdoutNext)
                        .put("stderr_next_offset", stderrNext)
                        .put("stdout_total_bytes", stdoutTotal)
                        .put("stderr_total_bytes", stderrTotal)
                        .put("has_more", stdoutNext < stdoutTotal || stderrNext < stderrTotal);
        return ToolResultBlock.text(output.toString());
    }

    /**
     * 计算或取得本方法声明的结果，供当前SandboxProcessTool处理步骤使用。
     *
     * @param param     当前沙箱进程工具持有的参数对象，供相应处理步骤使用。
     * @param processId 进程的标识，用于关联相应记录或执行。
     * @return 本次操作返回的工具结果块结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private ToolResultBlock stdin(ToolCallParam param, String processId) throws Exception {
        String data = text(param, "data", "");
        if (Boolean.TRUE.equals(param.getInput().get("append_newline"))) data += "\n";
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_INPUT_BYTES)
            throw new IllegalArgumentException("stdin data is too large");
        String encoded = Base64.getEncoder().encodeToString(bytes);
        ExecuteResponse result = execute(param, inputCommand(processId, encoded), 10);
        if (!result.isSuccess()) return failure("stdin", result);
        return ToolResultBlock.text(
                base(processId).put("status", "input_sent").put("bytes", bytes.length).toString());
    }

    /**
     * 等待目标范围。
     *
     * @param param     当前沙箱进程工具持有的参数对象，供相应处理步骤使用。
     * @param processId 进程的标识，用于关联相应记录或执行。
     * @return 本次操作返回的工具结果块结果。
     */
    private ToolResultBlock waitFor(ToolCallParam param, String processId) throws Exception {
        int timeout = (int) longValue(param, "timeout_seconds", 30, 1, 120);
        ExecuteResponse result = execute(param, waitCommand(processId, timeout), timeout + 5);
        if (!result.isSuccess()) return failure("wait", result);
        ObjectNode output = parseStatus(processId, result.output());
        output.put("wait_timed_out", "running".equals(output.path("status").asText()));
        return ToolResultBlock.text(output.toString());
    }

    /**
     * 计算或取得本方法声明的结果，供当前SandboxProcessTool处理步骤使用。
     *
     * @param param     当前沙箱进程工具持有的参数对象，供相应处理步骤使用。
     * @param processId 进程的标识，用于关联相应记录或执行。
     * @return 本次操作返回的工具结果块结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private ToolResultBlock kill(ToolCallParam param, String processId) throws Exception {
        String signal = text(param, "signal", "term");
        if (!signal.equals("term") && !signal.equals("kill")) {
            throw new IllegalArgumentException("signal must be term or kill");
        }
        ExecuteResponse result = execute(param, killCommand(processId, signal), 15);
        if (!result.isSuccess()) return failure("kill", result);
        return ToolResultBlock.text(parseStatus(processId, result.output()).toString());
    }

    /**
     * 执行沙箱进程工具。
     *
     * @param param   当前沙箱进程工具持有的参数对象，供相应处理步骤使用。
     * @param command 当前沙箱进程工具使用的命令，供其处理与状态记录使用。
     * @param timeout 本次等待允许持续的最长时间。
     * @return 本次操作返回的Execute响应结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private ExecuteResponse execute(ToolCallParam param, String command, int timeout)
            throws Exception {
        if (param.getRuntimeContext() == null)
            throw new IllegalArgumentException("process requires runtime context");
        if (testExecutor != null)
            return testExecutor.execute(param.getRuntimeContext(), command, timeout);
        AbstractFilesystem filesystem = param.getRuntimeContext().get(AbstractFilesystem.class);
        if (!(filesystem instanceof AbstractSandboxFilesystem sandbox)) {
            throw new IllegalArgumentException("process requires an active sandbox");
        }
        return sandbox.execute(param.getRuntimeContext(), command, timeout);
    }

    /**
     * 解析状态。
     *
     * @param processId 进程的标识，用于关联相应记录或执行。
     * @param raw       当前沙箱进程工具使用的原始，供其处理与状态记录使用。
     * @return 本次操作返回的对象节点结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static ObjectNode parseStatus(String processId, String raw) {
        String[] fields = raw.strip().split("\\t", -1);
        if (fields.length < 2)
            throw new IllegalArgumentException("process status returned invalid data");
        ObjectNode output =
                base(processId).put("status", fields[0]).put("pid", parseLong(fields[1], "pid"));
        if (fields.length > 2 && !fields[2].isBlank()) {
            output.put("exit_code", parseLong(fields[2], "exit_code"));
        }
        return output;
    }

    /**
     * 启动命令。
     *
     * @param processId      进程的标识，用于关联相应记录或执行。
     * @param encodedCommand 当前沙箱进程工具使用的编码结果命令，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String startCommand(String processId, String encodedCommand) {
        String relative = ROOT + "/" + processId;
        return "set -eu; root="
                + quote(ROOT)
                + "; mkdir -p \"$root\"; "
                + "count=$(find \"$root\" -mindepth 1 -maxdepth 1 -type d | wc -l); "
                + "if [ \"$count\" -ge "
                + MAX_PROCESSES
                + " ]; then echo process_limit_exceeded >&2; exit 2; fi; "
                + "dir="
                + quote(relative)
                + "; mkdir \"$dir\"; : >\"$dir/stdout\"; : >\"$dir/stderr\"; "
                + "mkfifo \"$dir/stdin\"; printf %s "
                + quote(encodedCommand)
                + " | base64 -d >\"$dir/command.sh\"; chmod 700 \"$dir/command.sh\"; "
                + "abs=$(cd \"$dir\" && pwd); "
                + "setsid bash -c 'd=\"$1\"; exec 3<>\"$d/stdin\"; "
                + "bash -l \"$d/command.sh\" <&3 >>\"$d/stdout\" 2>>\"$d/stderr\"; "
                + "printf \"%s\" \"$?\" >\"$d/exit_code\"' _ \"$abs\" </dev/null >/dev/null 2>&1 & "
                + "pid=$!; printf %s \"$pid\" >\"$dir/pid\"; printf %s \"$pid\"";
    }

    /**
     * 生成当前操作所需的statusCommand文本，供调用方继续处理。
     *
     * @param processId 进程的标识，用于关联相应记录或执行。
     * @return 本次处理生成或读取的文本。
     */
    private static String statusCommand(String processId) {
        return statusScript(ROOT + "/" + processId);
    }

    /**
     * 生成当前操作所需的statusScript文本，供调用方继续处理。
     *
     * @param relative 当前沙箱进程工具使用的相对路径，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String statusScript(String relative) {
        return "d="
                + quote(relative)
                + "; test -d \"$d\" || { echo process_not_found >&2; exit 3; }; pid=$(cat \"$d/pid\"); if ["
                + " -f \"$d/exit_code\" ]; then printf 'exited\\t%s\\t%s' \"$pid\" \"$(cat"
                + " \"$d/exit_code\")\"; elif kill -0 \"$pid\" 2>/dev/null; then printf 'running\\t%s\\t'"
                + " \"$pid\"; elif [ -f \"$d/termination\" ]; then printf '%s\\t%s\\t' \"$(cat"
                + " \"$d/termination\")\" \"$pid\"; else printf 'lost\\t%s\\t' \"$pid\"; fi";
    }

    /**
     * 生成当前操作所需的logsCommand文本，供调用方继续处理。
     *
     * @param processId    进程的标识，用于关联相应记录或执行。
     * @param stdoutOffset 当前沙箱进程工具使用的stdout偏移，供其处理与状态记录使用。
     * @param stderrOffset 当前沙箱进程工具使用的stderr偏移，供其处理与状态记录使用。
     * @param limit        本次处理或返回数量上限。
     * @return 本次处理生成或读取的文本。
     */
    private static String logsCommand(
            String processId, long stdoutOffset, long stderrOffset, int limit) {
        String d = ROOT + "/" + processId;
        return "d="
                + quote(d)
                + "; test -d \"$d\" || { echo process_not_found >&2; exit 3; }; "
                + "ot=$(wc -c <\"$d/stdout\"); et=$(wc -c <\"$d/stderr\"); "
                + "os="
                + stdoutOffset
                + "; es="
                + stderrOffset
                + "; "
                + "[ \"$os\" -le \"$ot\" ] || os=$ot; [ \"$es\" -le \"$et\" ] || es=$et; "
                + "on=$((os+"
                + limit
                + ")); en=$((es+"
                + limit
                + ")); [ \"$on\" -le \"$ot\" ] || on=$ot; [ \"$en\" -le \"$et\" ] || en=$et; ob=$(dd"
                + " if=\"$d/stdout\" bs=1 skip=$os count=$((on-os)) 2>/dev/null | base64 | tr -d '\\n"
                + "'); eb=$(dd if=\"$d/stderr\" bs=1 skip=$es count=$((en-es)) 2>/dev/null | base64 | tr -d"
                + " '\\n"
                + "'); printf '%s\\t%s\\t%s\\t%s\\t%s\\t%s' \"$ot\" \"$on\" \"$ob\" \"$et\" \"$en\""
                + " \"$eb\"";
    }

    /**
     * 生成当前操作所需的inputCommand文本，供调用方继续处理。
     *
     * @param processId 进程的标识，用于关联相应记录或执行。
     * @param encoded   当前沙箱进程工具使用的编码结果，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String inputCommand(String processId, String encoded) {
        String d = ROOT + "/" + processId;
        return "d="
                + quote(d)
                + "; test -d \"$d\" || { echo process_not_found >&2; exit 3; }; pid=$(cat \"$d/pid\"); kill"
                + " -0 \"$pid\" 2>/dev/null || { echo process_not_running >&2; exit 4; }; bash -c 'printf"
                + " %s \"$1\" | base64 -d >\"$2/stdin\"' _ "
                + quote(encoded)
                + " \"$d\" & writer=$!; for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22"
                + " 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50; do"
                + " kill -0 \"$writer\" 2>/dev/null || { wait \"$writer\"; exit $?; }; sleep 0.1; done;"
                + " kill -KILL \"$writer\" 2>/dev/null || true; echo stdin_write_timeout >&2; exit 5";
    }

    /**
     * 等待命令。
     *
     * @param processId 进程的标识，用于关联相应记录或执行。
     * @param timeout   本次等待允许持续的最长时间。
     * @return 本次处理生成或读取的文本。
     */
    private static String waitCommand(String processId, int timeout) {
        String d = ROOT + "/" + processId;
        return "d="
                + quote(d)
                + "; test -d \"$d\" || { echo process_not_found >&2; exit 3; }; "
                + "pid=$(cat \"$d/pid\"); end=$((SECONDS+"
                + timeout
                + ")); while kill -0 \"$pid\" 2>/dev/null && [ ! -f \"$d/exit_code\" ] && [ $SECONDS -lt"
                + " $end ]; do sleep 0.2; done; "
                + statusScript(d);
    }

    /**
     * 生成当前操作所需的killCommand文本，供调用方继续处理。
     *
     * @param processId 进程的标识，用于关联相应记录或执行。
     * @param signal    当前沙箱进程工具使用的信号，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String killCommand(String processId, String signal) {
        String d = ROOT + "/" + processId;
        String unixSignal = signal.equals("kill") ? "KILL" : "TERM";
        String terminal = signal.equals("kill") ? "killed" : "terminated";
        return "d="
                + quote(d)
                + "; test -d \"$d\" || { echo process_not_found >&2; exit 3; }; "
                + "pid=$(cat \"$d/pid\"); if kill -0 \"$pid\" 2>/dev/null; then "
                + "printf %s "
                + quote(terminal)
                + " >\"$d/termination\"; kill -"
                + unixSignal
                + " -- \"-$pid\" 2>/dev/null || true; for i in 1 2 3 4 5 6 7 8 9 10; do kill -0 \"$pid\""
                + " 2>/dev/null || break; sleep 0.1; done; fi; "
                + statusScript(d);
    }

    /**
     * 计算或取得本方法声明的结果，供当前SandboxProcessTool处理步骤使用。
     *
     * @param action 在当前处理边界中执行的操作。
     * @param result 本次处理已有的结果。
     * @return 本次操作返回的工具结果块结果。
     */
    private static ToolResultBlock failure(String action, ExecuteResponse result) {
        String output = result.output() == null ? "" : result.output().strip();
        if (output.length() > 500) output = output.substring(0, 500);
        return ToolResultBlock.error(
                "process " + action + " failed" + (output.isBlank() ? "" : ": " + output));
    }

    /**
     * 取得并校验进程标识。
     *
     * @param param 当前沙箱进程工具持有的参数对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String requireProcessId(ToolCallParam param) {
        String value = text(param, "process_id", "");
        if (!validProcessId(value)) throw new IllegalArgumentException("invalid process_id");
        return value;
    }

    /**
     * 检查validProcessId对应的条件，供调用方选择后续处理分支。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean validProcessId(String value) {
        return value != null && value.matches("proc_[a-f0-9]{32}");
    }

    /**
     * 计算或取得本方法声明的结果，供当前SandboxProcessTool处理步骤使用。
     *
     * @param processId 进程的标识，用于关联相应记录或执行。
     * @return 本次操作返回的对象节点结果。
     */
    private static ObjectNode base(String processId) {
        return JSON.createObjectNode().put("process_id", processId);
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param param    当前沙箱进程工具持有的参数对象，供相应处理步骤使用。
     * @param key      当前对象的查找或写入键。
     * @param fallback 当前沙箱进程工具使用的回退，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String text(ToolCallParam param, String key, String fallback) {
        Object value = param.getInput() == null ? null : param.getInput().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * 计算或取得本方法声明的结果，供当前SandboxProcessTool处理步骤使用。
     *
     * @param param    当前沙箱进程工具持有的参数对象，供相应处理步骤使用。
     * @param key      当前对象的查找或写入键。
     * @param fallback 当前沙箱进程工具使用的回退，供其处理与状态记录使用。
     * @param min      当前沙箱进程工具使用的最小，供其处理与状态记录使用。
     * @param max      当前沙箱进程工具使用的最大，供其处理与状态记录使用。
     * @return 本次操作返回的长整型结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static long longValue(
            ToolCallParam param, String key, long fallback, long min, long max) {
        Object value = param.getInput() == null ? null : param.getInput().get(key);
        long resolved = value instanceof Number number ? number.longValue() : fallback;
        if (resolved < min || resolved > max)
            throw new IllegalArgumentException(key + " is out of range");
        return resolved;
    }

    /**
     * 解析长整型。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param field 当前沙箱进程工具使用的字段，供其处理与状态记录使用。
     * @return 本次操作返回的长整型结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static long parseLong(String value, String field) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }

    /**
     * 解码沙箱进程工具。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String decode(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("process logs are not valid base64");
        }
    }

    /**
     * 转义沙箱进程工具。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String quote(String value) {
        return ShellQuoteUtils.quote(value);
    }

    /**
     * 沙箱进程工具内部的命令执行方，封装该步骤需要的状态或输入输出。
     */
    @FunctionalInterface
    interface CommandExecutor {
        /**
         * 执行命令执行方。
         *
         * @param context        当前执行上下文，提供关联标识和宿主绑定信息。
         * @param command        当前命令执行方使用的命令，供其处理与状态记录使用。
         * @param timeoutSeconds 超时，单位为秒。
         * @return 本次操作返回的Execute响应结果。
         */
        ExecuteResponse execute(RuntimeContext context, String command, int timeoutSeconds)
                throws Exception;
    }
}
