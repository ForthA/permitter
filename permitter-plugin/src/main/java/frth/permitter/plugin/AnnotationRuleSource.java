package frth.permitter.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

final class AnnotationRuleSource implements RuleSource {
    private final Elements elements;
    private final Map<Element, List<UsageRule>> cache = new HashMap<>();

    AnnotationRuleSource(Elements elements) {
        this.elements = elements;
    }

    @Override
    public List<UsageRule> rulesFor(Element declaration) {
        return cache.computeIfAbsent(declaration, this::readRules);
    }

    private List<UsageRule> readRules(Element declaration) {
        var rules = new ArrayList<UsageRule>();
        for (AnnotationMirror annotation : declaration.getAnnotationMirrors()) {
            var type = (TypeElement) annotation.getAnnotationType().asElement();
            String name = type.getQualifiedName().toString();
            if (!name.equals("frth.permitter.UsagePermitted") && !name.equals("frth.permitter.UsageNotRecommended")) {
                continue;
            }
            var values = new HashMap<String, AnnotationValue>();
            elements.getElementValuesWithDefaults(annotation).forEach((key, value) ->
                    values.put(key.getSimpleName().toString(), value));
            Diagnostic.Kind severity = Diagnostic.Kind.ERROR;
            if (name.equals("frth.permitter.UsageNotRecommended")) {
                var level = (VariableElement) values.get("level").getValue();
                severity = level.getSimpleName().contentEquals("INFO") ? Diagnostic.Kind.NOTE : Diagnostic.Kind.WARNING;
            }
            rules.add(new UsageRule(severity, (String) values.get("message").getValue(),
                    strings(values.get("allowedArtifacts")), strings(values.get("allowedPackages")),
                    strings(values.get("allowedClasses"))));
        }
        return List.copyOf(rules);
    }

    private static Set<String> strings(AnnotationValue array) {
        var result = new HashSet<String>();
        for (Object entry : (List<?>) array.getValue()) {
            result.add((String) ((AnnotationValue) entry).getValue());
        }
        return Set.copyOf(result);
    }
}
