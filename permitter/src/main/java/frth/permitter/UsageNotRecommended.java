package frth.permitter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Reports discouraged use of the API during compilation. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PACKAGE, ElementType.TYPE, ElementType.METHOD})
public @interface UsageNotRecommended {
    /** Message for the caller, such as the name of a replacement API. */
    String message() default "";

    /** Allowed caller artifacts in {@code groupId:artifactId} format. */
    String[] allowedArtifacts() default {};

    /** Exact package names of allowed callers. */
    String[] allowedPackages() default {};

    /** Fully qualified caller class names. An allowed class also grants access to its nested code. */
    String[] allowedClasses() default {};

    /** Severity of the compiler diagnostic. */
    Level level() default Level.WARNING;

    enum Level {
        INFO, WARNING
    }
}
