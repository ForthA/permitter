# Permitter

Permitter lets a Java library author mark public API as restricted or discouraged. When a consumer enables the javac plugin, the compiler points to the offending line and prints the author's message. Rules travel with the library JAR and work across projects.

[Example](#example) · [Maven](#maven) · [Gradle](#gradle-kotlin-dsl) · [Annotation reference](#annotation-reference) · [Plain javac](#plain-javac) · [Troubleshooting](#scope-and-troubleshooting)

## Example

```java
package com.example.payments;

import frth.permitter.UsageNotRecommended;
import frth.permitter.UsagePermitted;

public class Payments {
    @UsagePermitted(
        allowedArtifacts = "com.example:billing",
        message = "Use Payments.submit() for public submissions."
    )
    public static void submitInternal() {}

    @UsageNotRecommended(message = "Migrate to Payments.submit().")
    public static void send() {}

    public static void submit() {}
}
```

With the plugin enabled, the same calls have these outcomes:

| Call                        | From `com.example:shop`                                                | From `com.example:billing` |
|-----------------------------|------------------------------------------------------------------------|----------------------------|
| `Payments.submitInternal()` | Compilation error with “Use Payments.submit() for public submissions.” | Compiles                   |
| `Payments.send()`           | Warning with “Migrate to Payments.submit().”                           | Same warning               |
| `Payments.submit()`         | Compiles                                                               | Compiles                   |

For example, save this consumer as `Checkout.java`. Uncomment the last call to see the error in the `shop` module.

```java
package com.example.shop;

import com.example.payments.Payments;

class Checkout {
    void pay() {
        Payments.submit();
        Payments.send();            // Warning with a migration hint
        // Payments.submitInternal();  // Restricted to com.example:billing
    }
}
```

## What to install

| Project role               | What to configure                                                                                                                                                                               |
|----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Library author marking API | Add `frth.permitter:permitter:1.0.0` as an exported compile dependency. It contains the annotations.                                                                                             |
| Consumer checking its code | Keep your dependency on the author's library. Put `frth.permitter:permitter-plugin:1.0.0` on the compiler's processor path and enable `-Xplugin:permitter`. The annotations arrive transitively. |
| Project doing both         | Apply both configurations in the module.                                                                                                                                                        |

Use a full JDK 17 or newer with javac. Both Permitter JARs target Java 17 and use only JDK APIs. The compatibility matrix covers JDK 17, 21, 25 and 27. Your application can use its own Java release.

**Install from source.** Run the following in the root of this repository. This builds both modules and installs version `1.0.0` into your local Maven repository. The Maven and Gradle examples below can then resolve them on that machine. For shared builds, make the artifacts and their parent POM available in a repository your CI workers can access.

```sh
mvn install
```

## Maven

**Library author.** Add this to `<dependencies>` in the library POM. Maven's default compile scope makes the annotation types available to downstream compilers.

```xml
<dependency>
  <groupId>frth.permitter</groupId>
  <artifactId>permitter</artifactId>
  <version>1.0.0</version>
</dependency>
```

**Consumer.** Add this to `<build><plugins>` in the consuming module. If you already configure `maven-compiler-plugin`, merge the path and the argument into its existing lists so that processors such as Lombok remain available.

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <version>3.14.1</version>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>frth.permitter</groupId>
        <artifactId>permitter-plugin</artifactId>
        <version>1.0.0</version>
      </path>
    </annotationProcessorPaths>
    <compilerArgs>
      <arg>-Xplugin:permitter artifact=${project.groupId}:${project.artifactId}</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

Use your project's `maven.compiler.release`, for example `25` for Java 25. Run `mvn clean compile` to check main sources or `mvn clean test-compile` to include test sources. In a multi-module build, the coordinates must identify the module **containing the call**. [Maven compiler configuration](https://maven.apache.org/plugins-archives/maven-compiler-plugin-3.14.1/compile-mojo.html)

## Gradle (Kotlin DSL)

The same javac plugin works in Java modules built with Gradle. The example uses two subprojects, `:payments` (library) and `:shop` (consumer), declared in `settings.gradle.kts` with `include("payments", "shop")`. Use a Gradle version compatible with your chosen JDK.

**Library author** — `payments/build.gradle.kts`. The `java-library` plugin's `api` configuration exposes the annotations on the consumer's compile classpath.

```kotlin
plugins {
    `java-library`
}

repositories { mavenLocal() }

dependencies {
    api("frth.permitter:permitter:1.0.0")
}
```

**Consumer** — `shop/build.gradle.kts`. For a library published separately, replace `project(":payments")` with its dependency coordinates.

```kotlin
plugins { java }

group = "com.example"
repositories { mavenLocal() }

dependencies {
    implementation(project(":payments"))
    annotationProcessor("frth.permitter:permitter-plugin:1.0.0")
    testAnnotationProcessor("frth.permitter:permitter-plugin:1.0.0")
}

val callerArtifact = "${project.group}:${project.name}"
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xplugin:permitter artifact=$callerArtifact")
}
```

Run `./gradlew :shop:compileJava` or `./gradlew :shop:testClasses` (use `gradlew.bat` on Windows). Gradle gives each source set its own processor path. For a custom source set such as `integrationTest`, also add the plugin JAR to `integrationTestAnnotationProcessor` if its Java compile task receives the plugin argument. If your build centralizes repositories, put `mavenLocal()` in `dependencyResolutionManagement.repositories` in settings. [Gradle Java plugin](https://docs.gradle.org/current/userguide/java_plugin.html)

The example uses the project group and name as the calling artifact. If your published `artifactId` differs from `project.name`, set `callerArtifact` to the coordinates used in your rules. In either build tool, keep the entire `-Xplugin:permitter artifact=groupId:artifactId` option as **one compiler argument**. If you only use package or class exceptions, `-Xplugin:permitter` alone is enough.

## Annotation reference

Both annotations accept types, methods and packages as targets and use `RetentionPolicy.CLASS`.

| Annotation | Diagnostic for a caller without an exception |
| --- | --- |
| `@UsagePermitted` | A compiler error. With empty exception lists, external callers cannot use the API in a checked build. |
| `@UsageNotRecommended` | A compiler warning by default. Set `level = UsageNotRecommended.Level.INFO` for a javac note. With `-Werror`, warnings fail the build. |

| Shared parameter | Example | Meaning |
| --- | --- | --- |
| `message` | `"Use Payments.submit() instead."` | Text appended to the diagnostic. Defaults to an empty string. |
| `allowedArtifacts` | `{"com.example:billing", "com.example:batch"}` | Exact `groupId:artifactId` of the caller, without a version. Requires the compiler's `artifact=` argument. |
| `allowedPackages` | `"com.example.migration"` | Exact package of the calling code. List subpackages individually. |
| `allowedClasses` | `"com.example.jobs.Replay"` | Fully qualified calling class. Its nested code shares the exception. Use `Outer.Inner` to name a particular nested class. |

A match in **any one** of the three lists exempts the caller from that rule. All lists default to empty. Code inside the owning type may use its own API. Other classes in the same package or artifact need an explicit match.

### Mark a whole class

```java
import frth.permitter.UsageNotRecommended;

@UsageNotRecommended(
    level = UsageNotRecommended.Level.INFO,
    message = "Use Payments for new integrations.",
    allowedPackages = "com.example.migration",
    allowedClasses = "com.example.jobs.Replay"
)
public class LegacyPayments {}
```

Ordinary callers receive a note when using `LegacyPayments`. Both the migration package and the replay job can use it quietly.

### Mark a package and override individual declarations

Put the following in `src/main/java/com/example/payments/internal/package-info.java`. Include the generated `package-info.class` in your published JAR. The rule covers present and future types in **that exact package**.

```java
@UsagePermitted(
    allowedArtifacts = "com.example:billing",
    message = "Use the public Payments API."
)
package com.example.payments.internal;

import frth.permitter.UsagePermitted;
```

Inside that package, a method can define an exception to the package-wide policy:

```java
package com.example.payments.internal;

import frth.permitter.UsageNotRecommended;

public class Gateway {
    @UsageNotRecommended(message = "The preview API signature may change.")
    public static void preview() {}

    public static void rebuild() {}
}
```

From `com.example:shop`, `Gateway.preview()` produces only the method warning and `Gateway.rebuild()` produces the package error. The plugin selects the closest annotated level: **method → type → enclosing types → exact package**. That level supplies the full rule, including its exception lists. If both annotations appear on the selected declaration, each is evaluated independently. An explicit type use such as `new Gateway()` is checked separately from a method call.

## Plain javac

With the two Permitter JARs, the author's `payments.jar` and a consumer source file in the current directory, run:

```sh
javac -cp "payments.jar:permitter-1.0.0.jar" \
  -processorpath permitter-plugin-1.0.0.jar \
  "-Xplugin:permitter artifact=com.example:shop" Checkout.java
```

On Windows, use `;` between classpath entries and put the command on one line. The compiler discovers the plugin on the processor path. The flag activates it for this compilation. [JDK javac plugin API](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.compiler/com/sun/source/util/Plugin.html)

## Scope and troubleshooting

Permitter checks statically resolved Java references, including calls, method references, construction, field access on restricted types, signatures, generic arguments and calls through static imports. For overloads and overrides, the method declaration selected by javac determines the method-level policy. Reflection, already compiled caller bytecode and Kotlin compilation are outside its scope. Enforcement depends on the consumer's build configuration.

| Symptom                                 | What to check                                                                                                                                                                                                                                             |
|-----------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `plug-in not found: permitter`          | The task running javac needs `permitter-plugin` on its processor path. For Gradle tests, check `testAnnotationProcessor`.                                                                                                                                 |
| The build succeeds without a diagnostic | Confirm the consumer passes `-Xplugin:permitter`, the annotations JAR is on its compile classpath and the source actually uses the API. For a package rule, check the dependency JAR for `package-info.class`. Recompile the caller after changing rules. |
| An exception seems to have no effect    | Check the calling module's coordinates, the exact package or class name, and whether a closer rule supplies its own lists.                                                                                                                                |
| A recommendation stops the build        | Check javac's `-Werror` setting and whether a separate use of the type has a blocking rule.                                                                                                                                                               |

## Development

For the project layout, regression test guidance and pull request workflow, see [Contributing to Permitter](CONTRIBUTING.md).

```sh
mvn package
java integration/CompileChecks.java
```

The integration checks compile an annotated library, package it as a JAR, and compile separate consumers. They verify exit status, diagnostic severity, message and source location with both the Java 17 target and the running JDK's release. The plugin reads rules through the internal `RuleSource` interface, so another source of policies can reuse the same checking logic.

## Versioning and license

The annotations and the plugin share a version. A **major** release changes the public contract incompatibly, a **minor** release adds compatible features, and a **patch** release fixes behavior within the documented contract. The contract includes the annotations, compiler options and diagnostic rules. [Semantic Versioning](https://semver.org/)

Permitter is licensed under the [Apache License 2.0](LICENSE).
