package dev.horizen.agent.tool.governance;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的工具执行元数据和当前宿主可用性来源。
 */
public final class ToolDescriptorRegistry {
    /**
     * 描述集合的索引映射，供按键查找或归并当前组件的数据。
     */
    private final Map<String, ToolDescriptor> descriptors = new ConcurrentHashMap<>();

    /**
     * 可用性的索引映射，供按键查找或归并当前组件的数据。
     */
    private final Map<String, Availability> availability = new ConcurrentHashMap<>();

    /**
     * 注册工具描述注册表。
     *
     * @param descriptor 当前工具描述注册表持有的描述对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void register(ToolDescriptor descriptor) {
        ToolDescriptor previous = descriptors.putIfAbsent(descriptor.getName(), descriptor);
        if (previous != null)
            throw new IllegalArgumentException("duplicate descriptor: " + descriptor.getName());
        availability.putIfAbsent(descriptor.getName(), new Availability(true, null));
    }

    /**
     * 查找工具描述注册表。
     *
     * @param name 需要定位或处理的名称。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    public Optional<ToolDescriptor> find(String name) {
        return Optional.ofNullable(descriptors.get(name));
    }

    /**
     * 判断提供方工具。
     *
     * @param name 需要定位或处理的名称。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean isProviderTool(String name) {
        ToolDescriptor value = descriptors.get(name);
        return value != null && value.isProviderTool();
    }

    /**
     * 判断可用。
     *
     * @param name 需要定位或处理的名称。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean isAvailable(String name) {
        Availability value = availability.get(name);
        return descriptors.containsKey(name) && value != null && value.available;
    }

    /**
     * 设置可用性。
     *
     * @param name      需要定位或处理的名称。
     * @param available 可用的状态标记，用于选择当前组件的处理路径。
     * @param reason    当前工具描述注册表使用的原因，供其处理与状态记录使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void setAvailability(String name, boolean available, String reason) {
        if (!descriptors.containsKey(name))
            throw new IllegalArgumentException("unknown tool: " + name);
        availability.put(name, new Availability(available, reason));
    }

    /**
     * 生成当前操作所需的unavailableReason文本，供调用方继续处理。
     *
     * @param name 需要定位或处理的名称。
     * @return 本次处理生成或读取的文本。
     */
    public String unavailableReason(String name) {
        Availability value = availability.get(name);
        return value == null ? "not_registered" : value.reason;
    }

    /**
     * 工具描述注册表内部的可用性，封装该步骤需要的状态或输入输出。
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class Availability {
        /**
         * 可用的状态标记，用于选择当前组件的处理路径。
         */
        private final boolean available;

        /**
         * 当前限制、治理或审批要求的可读原因。
         */
        private final String reason;
    }
}
