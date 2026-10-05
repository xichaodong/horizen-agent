package dev.horizen.agent.web.bootstrap.runtime;

import dev.horizen.agent.tool.governance.ToolDescriptor;
import dev.horizen.agent.tool.governance.ToolDescriptorRegistry;

import io.agentscope.core.tool.*;

import lombok.Value;

import java.util.*;

/** 显式注册原生工具，不猜测名称前缀，也不维护独立分组列表。 */
final class NativeToolCatalog {
    /** 宿主原生工具的分组及执行属性声明。 */
    @Value
    private static class Group {
        /** 当前分组的用途说明，供目录或配置阅读者理解。 */
        String description;

        /** 用于Group内部处理的 timeout 值；读写位置由该类型的方法限定。 */
        int timeout;

        /** 工具集合的有序集合，保留当前组件处理或协议输出所需的顺序。 */
        List<String> tools;
    }

    /** 分组集合的固定取值，用于相应策略和边界判断。 */
    private static final Map<String, Group> GROUPS =
            Map.of(
                    "core",
                            new Group(
                                    "会话、记忆、任务与用户交互",
                                    30,
                                    List.of(
                                            "todo_write",
                                            "ask_user",
                                            "memory_save",
                                            "memory_search",
                                            "memory_get",
                                            "memory_manage",
                                            "session_search",
                                            "load_skill_through_path")),
                    "files",
                            new Group(
                                    "工作区文件读取、写入、补丁与搜索",
                                    30,
                                    List.of(
                                            "read_file",
                                            "write_file",
                                            "edit_file",
                                            "grep_files",
                                            "glob_files",
                                            "list_files",
                                            "patch",
                                            "search_files",
                                            "shell_execute")),
                    "web",
                            new Group(
                                    "公开网页提取与视觉读取",
                                    60,
                                    List.of(
                                            "web_extract",
                                            "vision_analyze",
                                            "web_fetch",
                                            "web_search")),
                    "artifact",
                            new Group(
                                    "用户文件加载与产物交付",
                                    30,
                                    List.of("load_artifact", "deliver_artifact")),
                    "subagents",
                            new Group(
                                    "子 Agent 与后台任务",
                                    30,
                                    List.of(
                                            "agent_spawn",
                                            "agent_send",
                                            "agent_list",
                                            "task_list",
                                            "task_output",
                                            "task_cancel")),
                    "processes", new Group("当前沙箱后台进程", 125, List.of("process")),
                    "browser",
                            new Group(
                                    "沙箱浏览器操作",
                                    60,
                                    List.of(
                                            "browser_navigate",
                                            "browser_snapshot",
                                            "browser_click",
                                            "browser_type",
                                            "browser_scroll",
                                            "browser_back",
                                            "browser_press",
                                            "browser_get_images",
                                            "browser_console",
                                            "browser_download",
                                            "browser_screenshot",
                                            "browser_vision")));

    /** CANCELLABLE的固定取值，用于相应策略和边界判断。 */
    private static final Set<String> CANCELLABLE = Set.of("shell_execute", "process");

    /**
     * 计算或取得本方法声明的结果，供当前NativeToolCatalog处理步骤使用。
     *
     * @return 本次处理得到的结果集合。
     */
    static Set<String> groupIds() {
        return GROUPS.keySet();
    }

    /**
     * 创建分组集合。
     *
     * @param toolkit 当前原生工具目录持有的工具集对象，供相应处理步骤使用。
     */
    static void createGroups(Toolkit toolkit) {
        GROUPS.forEach(
                (id, group) ->
                        toolkit.createToolGroup(
                                id, group.getDescription(), true, ToolGroupScope.EXTERNAL));
        toolkit.createToolGroup("external", "外部 Provider 工具", true, ToolGroupScope.EXTERNAL);
    }

    /**
     * 注册原生工具目录。
     *
     * @param toolkit 当前原生工具目录持有的工具集对象，供相应处理步骤使用。
     * @param descriptors 当前原生工具目录持有的描述集合对象，供相应处理步骤使用。
     */
    static void register(Toolkit toolkit, ToolDescriptorRegistry descriptors) {
        Map<String, String> groups = new HashMap<>();
        GROUPS.forEach((id, group) -> group.getTools().forEach(name -> groups.put(name, id)));
        for (String name : toolkit.getToolNames()) {
            if (descriptors.find(name).isPresent()) continue;
            AgentTool tool = toolkit.getTool(name);
            String groupId = groups.getOrDefault(name, "core");
            Group group = GROUPS.get(groupId);
            toolkit.addToolToGroup(groupId, name);
            boolean readOnly = tool.isReadOnly();
            boolean concurrencySafe = !(tool instanceof ToolBase base) || base.isConcurrencySafe();
            descriptors.register(
                    new ToolDescriptor(
                            name,
                            groupId,
                            readOnly,
                            readOnly ? "low" : "medium",
                            group.getTimeout(),
                            readOnly,
                            concurrencySafe,
                            CANCELLABLE.contains(name),
                            "none"));
        }
    }
}
