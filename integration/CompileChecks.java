import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.spi.ToolProvider;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;

/** Run from the repository root after mvn package. Uses only JDK tools. */
class CompileChecks {
    private static final String API = """
            package lib;
            import frth.permitter.*;
            public class Api {
                @UsagePermitted(message="use open", allowedPackages="friends",
                    allowedClasses={"client.Trusted", "client.Other.Nested"}, allowedArtifacts="demo:friend")
                public static void gated() {}
                public static void gated(int value) {}
                @UsageNotRecommended(message="use fresh")
                public static void old() {}
                @UsageNotRecommended(message="preview", level=UsageNotRecommended.Level.INFO, allowedPackages="friends")
                public static void preview() {}
                @UsagePermitted(message="internal type")
                public static class Hidden {
                    public static int value;
                    public void run() {}
                }
                public static Hidden hidden() { return new Hidden(); }
                @UsageNotRecommended(message="use Modern")
                public static class Legacy {}
                @UsagePermitted(allowedPackages="friends", message="closed type")
                public static class Restricted {
                    @UsagePermitted(message="closed method")
                    public static void special() {}
                }
            }
            """;

    private final Path root;
    private final Path annotations;
    private final Path plugin;
    private final Path library;
    private final int release;
    private int cases;

    CompileChecks(Path annotations, Path plugin, int release) throws IOException {
        this.release = release;
        this.annotations = annotations.toRealPath();
        this.plugin = plugin.toRealPath();
        this.root = Files.createTempDirectory(Files.createDirectories(Path.of("target", "integration")), "run-");
        this.library = root.resolve("library.jar");
    }

    public static void main(String[] args) throws Exception {
        Path apiJar = args.length > 0 ? Path.of(args[0]) : Path.of("permitter/target/permitter-1.0.0.jar");
        Path pluginJar = args.length > 1 ? Path.of(args[1]) : Path.of("permitter-plugin/target/permitter-plugin-1.0.0.jar");
        int jdk = Runtime.version().feature();
        for (int release : jdk == 17 ? List.of(17) : List.of(17, jdk)) {
            var checks = new CompileChecks(apiJar, pluginJar, release);
            checks.run();
            System.out.printf("JDK %d, --release %d: %d проверок пройдено.%n", jdk, release, checks.cases);
        }
    }

    private void run() throws IOException {
        Path api = source("lib/Api.java", API);
        Path pkg = source("internal/package-info.java", """
                @frth.permitter.UsagePermitted(message="package policy", allowedPackages={"internal", "trusted"})
                package internal;
                """);
        Path gateway = source("internal/Gateway.java", """
                package internal;
                import frth.permitter.*;
                public class Gateway {
                    public static void ordinary() {}
                    @UsageNotRecommended(message="method policy")
                    public static void preview() {}
                    @UsageNotRecommended(message="factory policy")
                    public static Nested create() { return null; }
                    @UsagePermitted(allowedPackages="client", message="method gate")
                    public void open() {}
                    public static class Nested {
                        @UsageNotRecommended(message="nested method policy")
                        public static void preview() {}
                    }
                    @UsageNotRecommended(message="class policy", level=UsageNotRecommended.Level.INFO)
                    public static class Legacy {
                        public static void old() {}
                        @UsagePermitted(message="strict method")
                        public static void strict() {}
                    }
                    @UsagePermitted(allowedPackages="client", message="class gate")
                    public static class Partner {
                        public static class Inner { public static void ordinary() {} }
                    }
                }
                """);
        Path advisory = source("internal/sub/package-info.java", """
                @frth.permitter.UsageNotRecommended(message="subpackage policy")
                package internal.sub;
                """);
        Path child = source("internal/sub/Child.java", """
                package internal.sub;
                public class Child {
                    @frth.permitter.UsagePermitted(message="child method") public static void strict() {}
                }
                """);
        Path clear = source("internal/free/Free.java", "package internal.free; public class Free {}");
        Path factory = source("outside/Factory.java", """
                package outside;
                public class Factory {
                    public static internal.Gateway gateway() { return null; }
                }
                """);
        Path output = root.resolve("producer");
        compile(output, List.of(api, pkg, gateway, advisory, child, clear, factory), List.of(), true, null, null);
        var jar = ToolProvider.findFirst("jar").orElseThrow();
        if (jar.run(System.out, System.err, "cf", library.toString(), "-C", output.toString(), ".") != 0) {
            throw new AssertionError("Не удалось собрать библиотеку для теста");
        }
        var error = Diagnostic.Kind.ERROR;
        var warning = Diagnostic.Kind.WARNING;
        var note = Diagnostic.Kind.NOTE;
        if (release >= 25) {
            var compact = source("Launcher.java", "void main() { lib.Api.gated(); }");
            compile(root.resolve("compact"), List.of(compact), options(""), false, error, "use open");
        }
        check("client", "C", "internal.Gateway.ordinary();", "", error, "package policy");
        check("client", "C", "new internal.Gateway();", "", error, "package policy");
        check("client", "C", "Class<?> t = internal.Gateway.class;", "", error, "package policy");
        check("client", "C", "internal.Gateway.Nested value;", "", error, "package policy");
        check("trusted", "C", "internal.Gateway.ordinary();", "", null, null);
        check("client", "C", "internal.Gateway.preview();", "", warning, "method policy");
        check("client", "C", "internal.Gateway.create();", "", warning, "factory policy");
        check("client", "C", "Runnable f = internal.Gateway::preview;", "", warning, "method policy");
        check("client", "C", "internal.Gateway.Nested.preview();", "", warning, "nested method policy");
        check("client", "C", "internal.Gateway.Legacy.old();", "", note, "class policy");
        check("client", "C", "internal.Gateway.Legacy.strict();", "", error, "strict method");
        check("client", "C", "internal.Gateway.Partner.Inner.ordinary();", "", null, null);
        check("trusted", "C", "internal.Gateway.Partner.Inner.ordinary();", "", error, "class gate");
        check("client", "C", "new internal.sub.Child();", "", warning, "subpackage policy");
        check("client", "C", "internal.sub.Child.strict();", "", error, "child method");
        check("client", "C", "new internal.free.Free();", "", null, null);
        var inherited = source("client/Client.java", """
                package client;
                import static internal.Gateway.preview;
                class Client { void f() { preview(); } }
                """);
        compile(root.resolve("package-source"), List.of(inherited, gateway, pkg), options(""), true, warning, "method policy");
        var sourceDefault = source("client/Read.java", "package client; class Read { internal.Gateway field; }");
        compile(root.resolve("source-default"), List.of(sourceDefault, gateway, pkg), options(""), false, error, "package policy");
        check("client", "C", "outside.Factory.gateway().open();", "", null, null);
        check("trusted", "C", "outside.Factory.gateway().open();", "", error, "method gate");
        check("client", "C", "lib.Api.gated();", "", error, "use open");
        check("client", "C", "lib.Api.gated(1);", "", null, null);
        check("client", "C", "Runnable r = lib.Api::gated;", "", error, "use open");
        check("client", "C", "lib.Api.old();", "", warning, "use fresh");
        check("client", "C", "lib.Api.preview();", "", note, "preview");
        check("friends", "C", "lib.Api.preview(); lib.Api.gated();", "", null, null);
        check("friends.sub", "C", "lib.Api.gated();", "", error, "use open");
        check("friendly", "C", "lib.Api.gated();", "", error, "use open");
        check("client", "Trusted", "class Inner { void f() { lib.Api.gated(); } }", "", null, null);
        check("client", "C", "lib.Api.gated();", "demo:friend", null, null);
        check("client", "C", "lib.Api.gated();", "other:friend", error, "use open");
        check("client", "C", "new lib.Api.Hidden();", "", error, "internal type");
        check("client", "C", "Class<?> c = lib.Api.Hidden.class;", "", error, "internal type");
        check("client", "C", "int i = lib.Api.Hidden.value;", "", error, "internal type");
        check("client", "C", "java.util.List<lib.Api.Hidden> list;", "", error, "internal type");
        check("client", "C", "lib.Api.hidden().run();", "", error, "internal type");
        check("client", "C", "lib.Api.Legacy legacy;", "", warning, "use Modern");
        check("friends", "C", "lib.Api.Restricted.special();", "", error, "closed method");
        check("lib", "C", "lib.Api.gated();", "", error, "use open");
        var nested = source("client/Other.java", """
                package client;
                class Other { static class Nested { void f() { lib.Api.gated(); } } }
                """);
        compile(root.resolve("nested"), List.of(nested), options(""), true, null, null);
        var unrelated = source("client/Peer.java", """
                package client;
                class Trusted { void f() { lib.Api.gated(); } }
                class Peer { void f() { lib.Api.gated(); } }
                """);
        compile(root.resolve("two-callers"), List.of(unrelated), options(""), false, error, "use open");
        var werror = source("client/Strict.java", "package client; class Strict { void f() { lib.Api.old(); } }");
        var strictOptions = new ArrayList<>(options(""));
        strictOptions.add("-Werror");
        compile(root.resolve("strict"), List.of(werror), strictOptions, false, warning, "use fresh");
        var imported = source("client/Imported.java", """
                package client;
                import static lib.Api.gated;
                class Imported { void f() { gated(); } }
                """);
        compile(root.resolve("imported"), List.of(imported), options(""), false, error, "use open");
        var sameRun = source("single/Local.java", """
                package single;
                class Local {
                    @frth.permitter.UsagePermitted static void secret() {}
                    void self() { secret(); }
                }
                class Peer { void f() { Local.secret(); } }
                """);
        compile(root.resolve("same-run"), List.of(sameRun), options(""), false, error, "single.Local#secret");
    }

    private void check(String pkg, String name, String body, String artifact, Diagnostic.Kind kind,
                       String message) throws IOException {
        var file = source(pkg.replace('.', '/') + "/" + name + ".java",
                "package " + pkg + "; class " + name + " { void f() { " + body + " } }");
        compile(root.resolve("consumer-" + cases), List.of(file), options(artifact),
                kind != Diagnostic.Kind.ERROR, kind, message);
    }

    private Path source(String name, String content) throws IOException {
        Path file = root.resolve("src").resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private List<String> options(String artifact) {
        return List.of("-processorpath", plugin.toString(),
                artifact.isEmpty() ? "-Xplugin:permitter" : "-Xplugin:permitter artifact=" + artifact);
    }

    private void compile(Path output, List<Path> sources, List<String> extra, boolean expectedSuccess,
                         Diagnostic.Kind kind, String message) throws IOException {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            String classpath = annotations.toString();
            if (Files.exists(library)) {
                classpath += File.pathSeparator + library;
            }
            var args = new ArrayList<>(List.of("--release", Integer.toString(release), "-encoding", "UTF-8", "-cp", classpath,
                    "-d", Files.createDirectories(output).toString()));
            args.addAll(extra);
            boolean success = compiler.getTask(null, files, diagnostics, args, null,
                    files.getJavaFileObjectsFromPaths(sources)).call();
            var reports = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getMessage(Locale.ROOT).startsWith("[permitter]")).toList();
            boolean expectedReports = kind == null ? reports.isEmpty() : reports.size() == 1
                    && reports.get(0).getKind() == kind && reports.get(0).getLineNumber() > 0
                    && reports.get(0).getMessage(Locale.ROOT).contains(message);
            if (success != expectedSuccess || !expectedReports) {
                throw new AssertionError(output + " " + diagnostics.getDiagnostics());
            }
            cases++;
        }
    }
}
