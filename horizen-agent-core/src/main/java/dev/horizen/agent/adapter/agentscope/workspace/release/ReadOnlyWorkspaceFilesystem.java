package dev.horizen.agent.adapter.agentscope.workspace.release;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.*;

import java.util.*;

/** 对已发布工作区提供只读视图，防止执行时修改发布内容。 */
public final class ReadOnlyWorkspaceFilesystem implements AbstractFilesystem {
    /** 被包装的原始实现，由本组件补充隔离、观测或恢复行为。 */
    private final AbstractFilesystem delegate;

    /** 错误使用的固定标识或协议文本。 */
    private static final String ERROR = "Published workspace content is read-only";

    /**
     * 创建读取只读工作区文件系统，初始化该组件所需的状态、配置或依赖。
     *
     * @param delegate 被包装的原始实现，由本组件补充隔离、观测或恢复行为。
     */
    public ReadOnlyWorkspaceFilesystem(AbstractFilesystem delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    /**
     * 计算或取得本方法声明的结果，供当前ReadOnlyWorkspaceFilesystem处理步骤使用。
     *
     * @param c 当前读取只读工作区文件系统持有的候选对象，供相应处理步骤使用。
     * @param p 当前读取只读工作区文件系统使用的参数，供其处理与状态记录使用。
     * @return 本次操作返回的Ls结果结果。
     */
    @Override
    public LsResult ls(RuntimeContext c, String p) {
        return delegate.ls(c, p);
    }

    /**
     * 读取读取只读工作区文件系统。
     *
     * @param c 当前读取只读工作区文件系统持有的候选对象，供相应处理步骤使用。
     * @param p 当前读取只读工作区文件系统使用的参数，供其处理与状态记录使用。
     * @param o 参与当前转换或比较的对象。
     * @param l 当前读取只读工作区文件系统使用的l，供其处理与状态记录使用。
     * @return 本次操作返回的读取结果结果。
     */
    @Override
    public ReadResult read(RuntimeContext c, String p, int o, int l) {
        return delegate.read(c, p, o, l);
    }

    /**
     * 计算或取得本方法声明的结果，供当前ReadOnlyWorkspaceFilesystem处理步骤使用。
     *
     * @param c 当前读取只读工作区文件系统持有的候选对象，供相应处理步骤使用。
     * @param s 待处理文本。
     * @param p 当前读取只读工作区文件系统使用的参数，供其处理与状态记录使用。
     * @param g 当前读取只读工作区文件系统使用的g，供其处理与状态记录使用。
     * @return 本次操作返回的Grep结果结果。
     */
    @Override
    public GrepResult grep(RuntimeContext c, String s, String p, String g) {
        return delegate.grep(c, s, p, g);
    }

    /**
     * 计算或取得本方法声明的结果，供当前ReadOnlyWorkspaceFilesystem处理步骤使用。
     *
     * @param c 当前读取只读工作区文件系统持有的候选对象，供相应处理步骤使用。
     * @param g 当前读取只读工作区文件系统使用的g，供其处理与状态记录使用。
     * @param p 当前读取只读工作区文件系统使用的参数，供其处理与状态记录使用。
     * @return 本次操作返回的路径匹配结果结果。
     */
    @Override
    public GlobResult glob(RuntimeContext c, String g, String p) {
        return delegate.glob(c, g, p);
    }

    /**
     * 检查是否存在读取只读工作区文件系统。
     *
     * @param c 当前读取只读工作区文件系统持有的候选对象，供相应处理步骤使用。
     * @param p 当前读取只读工作区文件系统使用的参数，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean exists(RuntimeContext c, String p) {
        return delegate.exists(c, p);
    }

    /**
     * 下载文件集合。
     *
     * @param c 当前读取只读工作区文件系统持有的候选对象，供相应处理步骤使用。
     * @param p 参数的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext c, List<String> p) {
        return delegate.downloadFiles(c, p);
    }

    /**
     * 写入读取只读工作区文件系统。
     *
     * @param c 当前读取只读工作区文件系统持有的候选对象，供相应处理步骤使用。
     * @param p 当前读取只读工作区文件系统使用的参数，供其处理与状态记录使用。
     * @param s 待处理文本。
     * @return 本次操作返回的写入结果结果。
     */
    @Override
    public WriteResult write(RuntimeContext c, String p, String s) {
        return WriteResult.fail(ERROR);
    }

    /**
     * 计算或取得本方法声明的结果，供当前ReadOnlyWorkspaceFilesystem处理步骤使用。
     *
     * @param c 当前读取只读工作区文件系统持有的候选对象，供相应处理步骤使用。
     * @param p 当前读取只读工作区文件系统使用的参数，供其处理与状态记录使用。
     * @param o 参与当前转换或比较的对象。
     * @param n 当前读取只读工作区文件系统使用的n，供其处理与状态记录使用。
     * @param a A的状态标记，用于选择当前组件的处理路径。
     * @return 本次操作返回的Edit结果结果。
     */
    @Override
    public EditResult edit(RuntimeContext c, String p, String o, String n, boolean a) {
        return EditResult.fail(ERROR);
    }

    /**
     * 删除读取只读工作区文件系统。
     *
     * @param c 当前读取只读工作区文件系统持有的候选对象，供相应处理步骤使用。
     * @param p 当前读取只读工作区文件系统使用的参数，供其处理与状态记录使用。
     * @return 本次操作返回的写入结果结果。
     */
    @Override
    public WriteResult delete(RuntimeContext c, String p) {
        return WriteResult.fail(ERROR);
    }

    /**
     * 计算或取得本方法声明的结果，供当前ReadOnlyWorkspaceFilesystem处理步骤使用。
     *
     * @param c 当前读取只读工作区文件系统持有的候选对象，供相应处理步骤使用。
     * @param f 当前读取只读工作区文件系统使用的f，供其处理与状态记录使用。
     * @param t 当前读取只读工作区文件系统使用的类型参数，供其处理与状态记录使用。
     * @return 本次操作返回的写入结果结果。
     */
    @Override
    public WriteResult move(RuntimeContext c, String f, String t) {
        return WriteResult.fail(ERROR);
    }

    /**
     * 上传文件集合。
     *
     * @param c 当前读取只读工作区文件系统持有的候选对象，供相应处理步骤使用。
     * @param f f的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext c, List<Map.Entry<String, byte[]>> f) {
        return f.stream().map(v -> FileUploadResponse.fail(v.getKey(), ERROR)).toList();
    }
}
