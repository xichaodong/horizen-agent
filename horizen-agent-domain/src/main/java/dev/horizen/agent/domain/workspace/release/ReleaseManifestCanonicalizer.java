package dev.horizen.agent.domain.workspace.release;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.skill.SkillReleaseManifest;

import lombok.Value;

import java.util.Collection;
import java.util.Comparator;

/**
 * 版本化发布内容的身份标识，不包含 URL 和数据库 ID。
 */
public final class ReleaseManifestCanonicalizer {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private ReleaseManifestCanonicalizer() {
    }

    /**
     * 发布清单规范化器内部的正文条目，封装该步骤需要的状态或输入输出。
     */
    @Value
    public static class ContentEntry {
        /**
         * 当前资源路径，路径解释和合法范围由所属文件系统适配器限定。
         */
        String path;

        /**
         * 内容校验值，用于确认传输或存储后的内容一致。
         */
        String checksum;

        /**
         * 当前内容或集合的大小，计量方式由所属资源协议定义。
         */
        long size;
    }

    /**
     * 生成当前操作所需的workspaceHash文本，供调用方继续处理。
     *
     * @param project 当前发布清单规范化器使用的Project，供其处理与状态记录使用。
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param assets  当前发布清单规范化器持有的assets对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    public static String workspaceHash(
            long project, String agent, Collection<ContentEntry> assets) {
        return releaseHash(project, agent, assets, null);
    }

    /**
     * 释放哈希。
     *
     * @param project   当前发布清单规范化器使用的Project，供其处理与状态记录使用。
     * @param agent     当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param assets    当前发布清单规范化器持有的assets对象，供相应处理步骤使用。
     * @param skillHash Skill的内容摘要，供校验或去重使用。
     * @return 本次处理生成或读取的文本。
     */
    public static String releaseHash(
            long project, String agent, Collection<ContentEntry> assets, String skillHash) {
        StringBuilder value = new StringBuilder(project + "\n" + agent + "\n");
        assets.stream()
                .sorted(Comparator.comparing(ContentEntry::getPath))
                .forEach(
                        asset ->
                                value.append(asset.getPath())
                                        .append('\0')
                                        .append(asset.getChecksum())
                                        .append('\0')
                                        .append(asset.getSize())
                                        .append('\n'));
        if (skillHash != null) value.append(skillHash);
        return DigestUtils.sha256Hex(value.toString());
    }

    /**
     * 生成当前操作所需的skillHash文本，供调用方继续处理。
     *
     * @param manifest 当前发布清单规范化器持有的清单对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    public static String skillHash(SkillReleaseManifest manifest) {
        StringBuilder value = new StringBuilder();
        manifest.getItems().stream()
                .sorted(Comparator.comparing(SkillReleaseManifest.Item::getRuntimeName))
                .forEach(
                        item ->
                                value.append(item.getSkillId())
                                        .append(':')
                                        .append(item.getVersionId())
                                        .append(':')
                                        .append(item.getRuntimeName())
                                        .append(':')
                                        .append(item.getSha256())
                                        .append(':')
                                        .append(item.getPackageSize())
                                        .append(':')
                                        .append(item.getMinAgentVersion())
                                        .append('\n'));
        return DigestUtils.sha256Hex(value.toString());
    }

    /**
     * 完成当前操作的verify步骤，按实现更新相应状态或依赖。
     *
     * @param manifest 当前发布清单规范化器持有的清单对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static void verify(AgentReleaseManifest manifest) {
        if (!manifest.isWorkspaceFiles()
                && !skillHash(manifest.getSkillRelease())
                .equals(manifest.getSkillRelease().getReleaseHash()))
            throw new IllegalArgumentException("Skill manifest content hash mismatch");
        String computed =
                releaseHash(
                        manifest.getProjectId(),
                        manifest.getAgentKey(),
                        manifest.getAssets().stream()
                                .map(
                                        asset ->
                                                new ContentEntry(
                                                        asset.getPath(),
                                                        asset.getSha256(),
                                                        asset.getSize()))
                                .toList(),
                        manifest.isWorkspaceFiles()
                                ? null
                                : manifest.getSkillRelease().getReleaseHash());
        if (!computed.equals(manifest.getReleaseHash()))
            throw new IllegalArgumentException("Workspace manifest content hash mismatch");
    }
}
