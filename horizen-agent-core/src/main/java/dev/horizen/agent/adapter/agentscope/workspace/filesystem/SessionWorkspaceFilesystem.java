package dev.horizen.agent.adapter.agentscope.workspace.filesystem;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.*;

import java.util.*;

/** 每次调用使用的代理，仅暴露文件操作，不暴露宿主 Shell。 */
public final class SessionWorkspaceFilesystem implements AbstractFilesystem {
    /**
     * 计算或取得本方法声明的结果，供当前SessionWorkspaceFilesystem处理步骤使用。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 本次操作返回的会话工作区工作卷结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private SessionWorkspaceVolume view(RuntimeContext context) {
        var view = context == null ? null : context.get(SessionWorkspaceVolume.class);
        if (view == null) throw new IllegalStateException("Session workspace is not prepared");
        return view;
    }

    /**
     * 计算或取得本方法声明的结果，供当前SessionWorkspaceFilesystem处理步骤使用。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param path 需要读取、写入或校验的路径。
     * @return 本次操作返回的Ls结果结果。
     */
    @Override
    public LsResult ls(RuntimeContext context, String path) {
        if (context == null || context.get(SessionWorkspaceVolume.class) == null)
            return LsResult.success(List.of());
        return view(context).filesystem().ls(context, path);
    }

    /**
     * 读取会话工作区文件系统。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param filePath 当前会话工作区文件系统使用的文件路径，供其处理与状态记录使用。
     * @param offset 本次读取的起始偏移。
     * @param limit 本次处理或返回数量上限。
     * @return 本次操作返回的读取结果结果。
     */
    @Override
    public ReadResult read(RuntimeContext context, String filePath, int offset, int limit) {
        if (context == null || context.get(SessionWorkspaceVolume.class) == null)
            return ReadResult.fail("Workspace execution is not prepared");
        return view(context).filesystem().read(context, filePath, offset, limit);
    }

    /**
     * 写入会话工作区文件系统。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param filePath 当前会话工作区文件系统使用的文件路径，供其处理与状态记录使用。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @return 本次操作返回的写入结果结果。
     */
    @Override
    public WriteResult write(RuntimeContext context, String filePath, String content) {
        view(context).changed();
        return view(context).filesystem().write(context, filePath, content);
    }

    /**
     * 计算或取得本方法声明的结果，供当前SessionWorkspaceFilesystem处理步骤使用。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param filePath 当前会话工作区文件系统使用的文件路径，供其处理与状态记录使用。
     * @param oldString 当前会话工作区文件系统使用的old文本，供其处理与状态记录使用。
     * @param newString 当前会话工作区文件系统使用的新建文本，供其处理与状态记录使用。
     * @param replaceAll 替换全部的状态标记，用于选择当前组件的处理路径。
     * @return 本次操作返回的Edit结果结果。
     */
    @Override
    public EditResult edit(
            RuntimeContext context,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        view(context).changed();
        return view(context).filesystem().edit(context, filePath, oldString, newString, replaceAll);
    }

    /**
     * 计算或取得本方法声明的结果，供当前SessionWorkspaceFilesystem处理步骤使用。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param pattern 当前会话工作区文件系统使用的校验模式，供其处理与状态记录使用。
     * @param path 需要读取、写入或校验的路径。
     * @param glob 当前会话工作区文件系统使用的路径匹配，供其处理与状态记录使用。
     * @return 本次操作返回的Grep结果结果。
     */
    @Override
    public GrepResult grep(RuntimeContext context, String pattern, String path, String glob) {
        if (context == null || context.get(SessionWorkspaceVolume.class) == null)
            return GrepResult.success(List.of());
        return view(context).filesystem().grep(context, pattern, path, glob);
    }

    /**
     * 计算或取得本方法声明的结果，供当前SessionWorkspaceFilesystem处理步骤使用。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param pattern 当前会话工作区文件系统使用的校验模式，供其处理与状态记录使用。
     * @param path 需要读取、写入或校验的路径。
     * @return 本次操作返回的路径匹配结果结果。
     */
    @Override
    public GlobResult glob(RuntimeContext context, String pattern, String path) {
        if (context == null || context.get(SessionWorkspaceVolume.class) == null)
            return GlobResult.success(List.of());
        return view(context).filesystem().glob(context, pattern, path);
    }

    /**
     * 上传文件集合。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param files 文件集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext context, List<Map.Entry<String, byte[]>> files) {
        view(context).changed();
        return view(context).filesystem().uploadFiles(context, files);
    }

    /**
     * 下载文件集合。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param paths paths的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext context, List<String> paths) {
        return view(context).filesystem().downloadFiles(context, paths);
    }

    /**
     * 检查是否存在会话工作区文件系统。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param path 需要读取、写入或校验的路径。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean exists(RuntimeContext context, String path) {
        if (context == null || context.get(SessionWorkspaceVolume.class) == null) return false;
        return view(context).filesystem().exists(context, path);
    }

    /**
     * 删除会话工作区文件系统。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param path 需要读取、写入或校验的路径。
     * @return 本次操作返回的写入结果结果。
     */
    @Override
    public WriteResult delete(RuntimeContext context, String path) {
        view(context).changed();
        return view(context).filesystem().delete(context, path);
    }

    /**
     * 计算或取得本方法声明的结果，供当前SessionWorkspaceFilesystem处理步骤使用。
     *
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param fromPath 当前会话工作区文件系统使用的来源路径，供其处理与状态记录使用。
     * @param toPath 当前会话工作区文件系统使用的目标路径，供其处理与状态记录使用。
     * @return 本次操作返回的写入结果结果。
     */
    @Override
    public WriteResult move(RuntimeContext context, String fromPath, String toPath) {
        view(context).changed();
        return view(context).filesystem().move(context, fromPath, toPath);
    }
}
