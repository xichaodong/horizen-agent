import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.ToolProvider;

/** 检查 Java 生产源码的类、字段和方法是否提供 Javadoc，不解析项目依赖或修改源码。 */
public final class CheckJavaDocs {
    /** 仅通过静态入口运行，避免创建不持有独立状态的工具实例。 */
    private CheckJavaDocs() {}

    /** 工具入口；从仓库根目录运行，缺少注释时以非零状态退出。 */
    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        List<Path> sources;
        try (var paths = Files.walk(root)) {
            sources =
                    paths.filter(path -> path.toString().contains("/src/main/java/"))
                            .filter(path -> path.toString().endsWith(".java"))
                            .filter(path -> !path.toString().contains("/target/"))
                            .sorted()
                            .toList();
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK 17+ is required");
        int[] missing = {0};
        int[] declarations = {0};
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var task =
                    (JavacTask)
                            compiler.getTask(
                                    null,
                                    files,
                                    diagnostic -> {},
                                    List.of("-proc:none", "--release", "17"),
                                    null,
                                    files.getJavaFileObjectsFromPaths(sources));
            var docs = DocTrees.instance(task);
            var positions = docs.getSourcePositions();
            for (var unit : task.parse()) {
                new TreePathScanner<Void, Void>() {
                    /** 按 AST 声明检查关联注释，避开字符串、局部变量和生成代码。 */
                    private void requireDoc(Tree declaration, String kind) {
                        declarations[0]++;
                        if (docs.getDocCommentTree(getCurrentPath()) == null) {
                            long line =
                                    unit.getLineMap()
                                            .getLineNumber(
                                                    positions.getStartPosition(unit, declaration));
                            System.err.println(
                                    root.relativize(Path.of(unit.getSourceFile().toUri()))
                                            + ":"
                                            + line
                                            + ": missing "
                                            + kind
                                            + " Javadoc");
                            missing[0]++;
                        }
                    }

                    /** 具名类型需要类注释；匿名实现的成员由后续访问继续检查。 */
                    @Override
                    public Void visitClass(ClassTree node, Void unused) {
                        if (!node.getSimpleName().toString().isEmpty()) requireDoc(node, "type");
                        return super.visitClass(node, unused);
                    }

                    /** 只检查类型直接声明的字段，不要求局部变量或方法参数单独写 Javadoc。 */
                    @Override
                    public Void visitVariable(VariableTree node, Void unused) {
                        if (getCurrentPath().getParentPath().getLeaf() instanceof ClassTree) {
                            requireDoc(node, "field");
                        }
                        return super.visitVariable(node, unused);
                    }

                    /** 检查源码实际声明的方法和构造器，包括接口方法。 */
                    @Override
                    public Void visitMethod(MethodTree node, Void unused) {
                        requireDoc(node, "method");
                        return super.visitMethod(node, unused);
                    }
                }.scan(unit, null);
            }
        }
        if (missing[0] != 0) System.exit(1);
        System.out.println(
                "Java documentation check passed: "
                        + sources.size()
                        + " files, "
                        + declarations[0]
                        + " declarations.");
    }
}
