package dev.horizen.agent.skill;

import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 已校验 Skill 发布版本的不可变内容，不依赖具体运行时。 */
public final class SkillReleaseSnapshot {
    /** 已验证发布的描述清单，保存版本与制品完整性信息。 */
    @Getter private final SkillReleaseManifest manifest;

    /** 当前发布中可渐进读取的 Skill 正文与资源快照。 */
    private final Map<String, SnapshotSkill> skills;

    /**
     * 创建Skill发布快照，初始化该组件所需的状态、配置或依赖。
     *
     * @param manifest 当前Skill发布快照持有的清单对象，供相应处理步骤使用。
     * @param skills Skill集合的索引映射，供按键查找或归并当前组件的数据。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public SkillReleaseSnapshot(SkillReleaseManifest manifest, Map<String, SnapshotSkill> skills) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.skills = Collections.unmodifiableMap(new LinkedHashMap<>(skills));
        if (manifest.getSkillCount() != this.skills.size()) {
            throw new IllegalArgumentException(
                    "Materialized skill count differs from release manifest");
        }
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param replacement 当前Skill发布快照持有的替换对象，供相应处理步骤使用。
     * @return 本次操作返回的Skill发布快照结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public SkillReleaseSnapshot withManifest(SkillReleaseManifest replacement) {
        if (!manifest.getReleaseHash().equals(replacement.getReleaseHash())
                || replacement.getSkillCount() != skills.size()) {
            throw new IllegalArgumentException(
                    "Replacement manifest describes different skill content");
        }
        return new SkillReleaseSnapshot(replacement, skills);
    }

    /**
     * 计算或取得本方法声明的结果，供当前SkillReleaseSnapshot处理步骤使用。
     *
     * @return 本次处理得到的结果集合。
     */
    public List<String> skillNames() {
        return List.copyOf(skills.keySet());
    }

    /**
     * 计算或取得本方法声明的结果，供当前SkillReleaseSnapshot处理步骤使用。
     *
     * @param name 需要定位或处理的名称。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    public Optional<String> markdown(String name) {
        SnapshotSkill value = skills.get(name);
        return value == null ? Optional.empty() : Optional.of(value.getMarkdown());
    }

    /**
     * 读取Skill发布快照。
     *
     * @param name 需要定位或处理的名称。
     * @param relativePath 相对于工作区根目录的资源路径，不能借此越过根目录。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    public Optional<String> read(String name, String relativePath) {
        return readBinary(name, relativePath)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * 读取二进制。
     * 处理数组时使用副本，避免直接共享原数组内容。
     *
     * @param name 需要定位或处理的名称。
     * @param relativePath 相对于工作区根目录的资源路径，不能借此越过根目录。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    public Optional<byte[]> readBinary(String name, String relativePath) {
        SnapshotSkill value = skills.get(name);
        if (value == null) return Optional.empty();
        byte[] bytes = value.resources.get(safePath(relativePath));
        return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
    }

    /**
     * 查询列表中的资源集合。
     *
     * @param name 需要定位或处理的名称。
     * @return 本次处理得到的结果集合。
     */
    public List<String> listResources(String name) {
        SnapshotSkill value = skills.get(name);
        return value == null ? List.of() : List.copyOf(value.resources.keySet());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param markdown 当前Skill发布快照使用的markdown，供其处理与状态记录使用。
     * @param resources 资源集合的索引映射，供按键查找或归并当前组件的数据。
     * @return 本次操作返回的快照Skill结果。
     */
    public static SnapshotSkill skill(String markdown, Map<String, byte[]> resources) {
        return new SnapshotSkill(markdown, resources);
    }

    /**
     * 计算或取得本方法声明的结果，供当前SkillReleaseSnapshot处理步骤使用。
     * 处理数组时使用副本，避免直接共享原数组内容。
     *
     * @param input 本次处理的输入。
     * @return 按返回类型约定组织的结果映射。
     */
    private static Map<String, byte[]> immutableBytes(Map<String, byte[]> input) {
        LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
        input.forEach((path, content) -> copy.put(safePath(path), content.clone()));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 生成当前操作所需的safePath文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String safePath(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) return "";
        return normalized;
    }

    /** 发布快照中的不可变 Skill 视图，供渐进读取正文与资源。 */
    public static final class SnapshotSkill {
        /** 当前 Skill 的 Markdown 正文，供运行时按需读取。 */
        @Getter private final String markdown;

        /** 当前宿主依赖的资源状态投影。 */
        private final Map<String, byte[]> resources;

        /**
         * 创建快照Skill，初始化该组件所需的状态、配置或依赖。
         *
         * @param markdown 当前快照Skill使用的markdown，供其处理与状态记录使用。
         * @param resources 资源集合的索引映射，供按键查找或归并当前组件的数据。
         * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
         */
        public SnapshotSkill(String markdown, Map<String, byte[]> resources) {
            if (markdown == null || markdown.isBlank()) {
                throw new IllegalArgumentException("SKILL.md content is required");
            }
            this.markdown = markdown;
            this.resources = immutableBytes(resources == null ? Map.of() : resources);
        }

        /**
         * 读取资源集合的当前值。
         *
         * @return {@link #resources} 中保存的值。
         */
        public Map<String, byte[]> resources() {
            return immutableBytes(resources);
        }
    }
}
