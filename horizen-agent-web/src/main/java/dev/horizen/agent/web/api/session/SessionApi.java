package dev.horizen.agent.web.api.session;

import dev.horizen.agent.web.api.artifact.ArtifactApi;
import dev.horizen.agent.web.api.chat.ChatApi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 此 API 功能域的 HTTP/SSE 传输模型。 */
public final class SessionApi {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private SessionApi() {}

    /** 会话查询的接口请求，承载调用方提交的定位信息与输入。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionQueryRequest {
        /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
        private String sessionId;
    }

    /** 会话订阅的接口请求，承载调用方提交的定位信息与输入。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionSubscribeRequest {
        /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
        private String sessionId;

        /** 调用方期望操作的执行标识，用于防止旧页面误操作后续执行。 */
        private String expectedTurnId;

        /** 客户端已读取的正式时间线游标，后续历史从其后继续读取。 */
        private Long afterTimelineSequence;

        /** 客户端已经观察到的执行事件游标，重连时从其后继续回放。 */
        private Long afterEventSequence;

        /**
         * 创建会话订阅请求，初始化该组件所需的状态、配置或依赖。
         *
         * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
         * @param expectedTurnId 调用方期望操作的执行标识，用于防止旧页面误操作后续执行。
         */
        public SessionSubscribeRequest(String sessionId, String expectedTurnId) {
            this(sessionId, expectedTurnId, null, null);
        }

        /** 兼容新增 Redis List 游标前调用方式的构造方法。 */
        public SessionSubscribeRequest(
                String sessionId, String expectedTurnId, Long afterTimelineSequence) {
            this(sessionId, expectedTurnId, afterTimelineSequence, null);
        }
    }

    /** 会话取消的接口请求，承载调用方提交的定位信息与输入。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionCancelRequest {
        /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
        private String sessionId;

        /** 调用方期望操作的执行标识，用于防止旧页面误操作后续执行。 */
        private String expectedTurnId;
    }

    /** 会话消息集合的接口请求，承载调用方提交的定位信息与输入。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionMessagesRequest {
        /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
        private String sessionId;
    }

    /** 会话列表的接口请求，承载调用方提交的定位信息与输入。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionListRequest {
        /** 本次查询或处理数量的上限。 */
        private Integer limit;

        /** 当前分页或回放位置，用于继续读取而不是资源身份校验。 */
        private String cursor;
    }

    /** 会话重命名的接口请求，承载调用方提交的定位信息与输入。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionRenameRequest {
        /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
        private String sessionId;

        /** 当前会话重命名请求的可读标题，供宿主界面展示。 */
        private String title;
    }

    /** 会话租约引用的接口请求，承载调用方提交的定位信息与输入。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionPinRequest {
        /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
        private String sessionId;

        /** 会话是否置顶，影响会话目录展示顺序。 */
        private boolean pinned;
    }

    /** 会话Delete的接口请求，承载调用方提交的定位信息与输入。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionDeleteRequest {
        /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
        private String sessionId;
    }

    /** 会话列表的接口响应，承载已完成查询或操作的结果。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionListResponse {
        /** 会话对象或会话索引，按相应的归属键定位数据。 */
        private List<SessionSummaryResponse> sessions;

        /** 当前分页结果之后是否仍有数据可继续读取。 */
        private boolean hasMore;

        /** 下一页查询使用的不透明游标；没有下一页时可为空。 */
        private String nextCursor;
    }

    /** 会话摘要的接口响应，承载已完成查询或操作的结果。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionSummaryResponse {
        /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
        private String sessionId;

        /** 当前会话摘要响应的可读标题，供宿主界面展示。 */
        private String title;

        /** 会话是否置顶，影响会话目录展示顺序。 */
        private boolean pinned;

        /** 当前记录或执行的状态，具体取值由所属领域或协议约定。 */
        private String status;

        /** 会话最近一次执行的标识，用于历史展示和状态恢复。 */
        private String lastTurnId;

        /** 会话当前占用的执行标识；无活跃执行时为空。 */
        private String activeTurnId;

        /** 会话最近一条正式消息的时间，供会话排序使用。 */
        private Instant lastMessageAt;

        /** 当前记录的创建时间。 */
        private Instant createdAt;

        /** 当前记录最近一次更新的时间。 */
        private Instant updatedAt;
    }

    /** 会话执行的接口响应，承载已完成查询或操作的结果。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionExecutionResponse {
        /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
        private String sessionId;

        /** 会话当前或最近一次执行的标识，供观察与恢复流程使用。 */
        private String currentTurnId;

        /** 当前记录或执行的状态，具体取值由所属领域或协议约定。 */
        private String status;

        /** 当前执行或执行段的开始时间。 */
        private Instant startedAt;

        /** 执行结束时间；尚未结束的记录可以没有该时间。 */
        private Instant finishedAt;

        /** 机器可识别的失败分类，供状态恢复与错误展示使用。 */
        private String failureCode;
    }

    /** 会话消息集合的接口响应，承载已完成查询或操作的结果。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionMessagesResponse {
        /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
        private String sessionId;

        /** 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。 */
        private List<ConversationMessageResponse> messages;

        /** 需要持久化或展示的结构化呈现块集合。 */
        private List<PresentationResponse> presentations;

        /** 时间线事件集合的有序集合，保留当前组件处理或协议输出所需的顺序。 */
        private List<TimelineEventResponse> timelineEvents;

        /** 当前执行的临时增量与恢复定位，与正式历史分开返回。 */
        private CurrentTurnRecoveryResponse currentTurnRecovery;

        /** 兼容新增活跃 Turn 恢复能力前调用方式的构造方法。 */
        public SessionMessagesResponse(
                String sessionId,
                List<ConversationMessageResponse> messages,
                List<PresentationResponse> presentations,
                List<TimelineEventResponse> timelineEvents) {
            this(sessionId, messages, presentations, timelineEvents, null);
        }

        /**
         * 创建会话消息集合响应，初始化该组件所需的状态、配置或依赖。
         *
         * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
         * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
         */
        public SessionMessagesResponse(
                String sessionId, List<ConversationMessageResponse> messages) {
            this(sessionId, messages, List.of(), List.of());
        }

        /**
         * 创建会话消息集合响应，初始化该组件所需的状态、配置或依赖。
         *
         * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
         * @param messages 消息集合的有序集合，保留当前组件处理或协议输出所需的顺序。
         * @param presentations 需要持久化或展示的结构化呈现块集合。
         */
        public SessionMessagesResponse(
                String sessionId,
                List<ConversationMessageResponse> messages,
                List<PresentationResponse> presentations) {
            this(sessionId, messages, presentations, List.of());
        }
    }

    /** 当前执行恢复的接口响应，承载已完成查询或操作的结果。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentTurnRecoveryResponse {
        /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
        private String turnId;

        /** 当前执行或历史事件集合，供持久化、回放与观测使用。 */
        private List<ChatApi.ChatStreamEvent> events;

        /** 当前执行已经持久到增量日志的最后事件游标。 */
        private long lastEventSequence;
    }

    /** 时间线事件的接口响应，承载已完成查询或操作的结果。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimelineEventResponse {
        /** 当前记录在对应序列中的位置，用于排序或继续读取。 */
        private long sequence;

        /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
        private String turnId;

        /** 当前记录保存的执行事件或已转换的发送事件。 */
        private ChatApi.ChatStreamEvent event;
    }

    /** 呈现的接口响应，承载已完成查询或操作的结果。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PresentationResponse {
        /** 结构化呈现块的标识，客户端用它去重与更新同一张卡片。 */
        private String blockId;

        /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
        private String turnId;

        /** 一次工具调用的标识，用于配对参数、结果和审批事件。 */
        private String toolCallId;

        /** 可调用工具的注册名称，须与目录中声明的名称一致。 */
        private String toolName;

        /** 本对象的协议类别，用于选择对应的解析或呈现规则。 */
        private String type;

        /** Schema的版本，供兼容或并发检查使用。 */
        private int schemaVersion;

        /** 当前块在其列表或时间线中的位置，用于稳定排序。 */
        private int position;

        /** 数据的索引映射，供按键查找或归并当前组件的数据。 */
        private Map<String, Object> data;

        /** 当前记录的创建时间。 */
        private Instant createdAt;
    }

    /** 对话消息的接口响应，承载已完成查询或操作的结果。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConversationMessageResponse {
        /** 会话消息的标识，用于历史查询与过程事件关联。 */
        private String messageId;

        /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
        private String turnId;

        /** 消息、资源引用或调用的角色，供上下文与生命周期规则区分用途。 */
        private String role;

        /** 当前记录或资源的正文内容；与资源标识和存储引用分开保存。 */
        private String content;

        /** 当前记录在对应序列中的位置，用于排序或继续读取。 */
        private long sequence;

        /** 当前记录的创建时间。 */
        private Instant createdAt;

        /** 本次消息附带的输入资源，内容解析由运行时适配器完成。 */
        private List<ArtifactApi.ArtifactResponse> attachments;

        /**
         * 创建对话消息响应，初始化该组件所需的状态、配置或依赖。
         *
         * @param messageId 会话消息的标识，用于历史查询与过程事件关联。
         * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
         * @param role 消息、资源引用或调用的角色，供上下文与生命周期规则区分用途。
         * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
         * @param sequence 当前记录在对应序列中的位置，用于排序或继续读取。
         * @param createdAt 当前记录的创建时间。
         */
        public ConversationMessageResponse(
                String messageId,
                String turnId,
                String role,
                String content,
                long sequence,
                Instant createdAt) {
            this(messageId, turnId, role, content, sequence, createdAt, List.of());
        }

        /**
         * 读取attachments的当前值。
         *
         * @return {@link #attachments} 中保存的值。
         */
        public List<ArtifactApi.ArtifactResponse> attachments() {
            return attachments == null ? List.of() : attachments;
        }
    }
}
