package frth.permitter.plugin;

import java.util.Set;
import javax.tools.Diagnostic;

record UsageRule(Diagnostic.Kind severity, String message, Set<String> artifacts,
                 Set<String> packages, Set<String> classes) {
    boolean permits(String artifact, String packageName, Set<String> enclosingClasses) {
        return artifacts.contains(artifact) && !artifact.isEmpty()
                || packages.contains(packageName)
                || enclosingClasses.stream().anyMatch(classes::contains);
    }
}
