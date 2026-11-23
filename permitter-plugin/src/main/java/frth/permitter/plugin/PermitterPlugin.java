package frth.permitter.plugin;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;

/** javac plugin entry point discovered through ServiceLoader. */
public final class PermitterPlugin implements Plugin {
    @Override
    public String getName() {
        return "permitter";
    }

    @Override
    public void init(JavacTask task, String... args) {
        String artifact = artifactOption(args);
        Trees trees = Trees.instance(task);
        task.addTaskListener(new TaskListener() {
            private RuleSource source;

            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() == TaskEvent.Kind.ANALYZE) {
                    if (source == null) {
                        source = new AnnotationRuleSource(task.getElements());
                    }
                    var path = trees.getPath(event.getTypeElement());
                    if (path != null) {
                        new UsageScanner(trees, source, artifact, event.getCompilationUnit()).scan(path, null);
                    }
                }
            }
        });
    }

    private static String artifactOption(String[] args) {
        if (args.length == 0) {
            return "";
        }
        if (args.length == 1 && args[0].matches("artifact=[^:\\s=]+:[^:\\s=]+")) {
            return args[0].substring("artifact=".length());
        }
        throw new IllegalArgumentException("permitter: ожидается -Xplugin:\"permitter artifact=groupId:artifactId\"");
    }
}
