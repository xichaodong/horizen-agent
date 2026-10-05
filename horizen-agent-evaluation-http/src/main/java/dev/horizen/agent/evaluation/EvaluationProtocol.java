package dev.horizen.agent.evaluation;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 传输协议 v1，不将预期答案或评测器规则传入 Agent。 */
public final class EvaluationProtocol {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private EvaluationProtocol() {}

    /** 评测协议内部的启动，封装该步骤需要的状态或输入输出。 */
    @Data
    public static class Start {
        /** 协议的版本，供兼容或并发检查使用。 */
        private int protocolVersion = 1;

        /** 执行的标识，用于关联相应记录或执行。 */
        private String executionId;

        /** 工作区或发布所属 Project 的标识，参与资源归属校验。 */
        private Long projectId;

        /** suite运行的标识，用于关联相应记录或执行。 */
        private Long suiteRunId;

        /** 用例运行的标识，用于关联相应记录或执行。 */
        private Long caseRunId;

        /** 当前操作的输入数据，格式由所属命令、协议或工具定义。 */
        private Object input;

        /** 本次评测运行的固定调用样本与故障脚本。 */
        private Map<String, Object> fixture = Map.of("modeLabel", "LIVE");

        /** 超时，单位为毫秒。 */
        private long timeoutMs = 600000;
    }

    /** 评测协议内部的交互，封装该步骤需要的状态或输入输出。 */
    @Data
    public static class Interaction {
        /** 本对象的协议类别，用于选择对应的解析或呈现规则。 */
        private String type;

        /** 工具名称集合的有序集合，保留当前组件处理或协议输出所需的顺序。 */
        private List<String> toolNames = List.of();

        /** 本次审批是否允许执行；拒绝时不会恢复为已批准的工具调用。 */
        private Boolean approved;

        /** 用户对澄清问题的回答集合，按问题标识关联选项和补充文本。 */
        private List<Map<String, Object>> answers = List.of();

        /** selected选项Labels的有序集合，保留当前组件处理或协议输出所需的顺序。 */
        private List<String> selectedOptionLabels = List.of();

        /** 是否跳过本次澄清请求；跳过与提交具体答案分开表示。 */
        private boolean skip;

        /** 明确标为可选的问题可以缺省，审批仍必须在脚本中提供。 */
        private boolean optional;

        /** 延迟，单位为毫秒。 */
        private long delayMs;
    }

    /** 评测协议内部的步骤，封装该步骤需要的状态或输入输出。 */
    @Data
    public static class Step {
        /** 当前步骤的定位标识。 */
        private String id;

        /** 评测步骤要提交给 Agent 的合成用户输入。 */
        private String userInput;

        /** 本次操作引用的产物标识集合，内容读取由产物服务处理。 */
        private List<String> artifactIds = List.of();
    }

    /** 评测协议内部的事件，封装该步骤需要的状态或输入输出。 */
    @Data
    public static class Event {
        /** 当前观测或评测事件在其所属序列中的位置。 */
        private int eventIndex;

        /** 当前观测或评测事件的协议类别。 */
        private String eventType;

        /** 当前过程事件的名称，用于展示与证据关联。 */
        private String eventName;

        /** 消息、资源引用或调用的角色，供上下文与生命周期规则区分用途。 */
        private String role;

        /** 时间戳，单位为毫秒。 */
        private long timestampMs;

        /** 当前事件的结构化负载，与定位标识和事件类别分开保存。 */
        private Object payload;
    }

    /** 的状态投影或状态分类，供查询、治理与恢复流程使用。 */
    @Data
    public static class Status {
        /** 协议的版本，供兼容或并发检查使用。 */
        private int protocolVersion = 1;

        /** 执行的标识，用于关联相应记录或执行。 */
        private String executionId;

        /** 用例运行的标识，用于关联相应记录或执行。 */
        private Long caseRunId;

        /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
        private String sessionId;

        /** 当前记录或执行的状态，具体取值由所属领域或协议约定。 */
        private String status;

        /** 开始时间，单位为毫秒。 */
        private long startedAtMs;

        /** 结束时间，单位为毫秒。 */
        private Long finishedAtMs;

        /** 机器可识别的错误代码，用于选择对应的错误处理方式。 */
        private String errorCode;

        /** 面向调用方的错误说明文本。 */
        private String errorMessage;

        /** 实际输出的索引映射，供按键查找或归并当前组件的数据。 */
        private Map<String, Object> actualOutput = new LinkedHashMap<>();

        /** 当前执行或历史事件集合，供持久化、回放与观测使用。 */
        private List<Event> events = new ArrayList<>();

        /** 样本记录的索引映射，供按键查找或归并当前组件的数据。 */
        private List<Map<String, Object>> fixtureRecord = new ArrayList<>();
    }
}
