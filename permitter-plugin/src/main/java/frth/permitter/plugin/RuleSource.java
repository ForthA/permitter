package frth.permitter.plugin;

import java.util.List;
import javax.lang.model.element.Element;

/** Supplies rules for the declaration referenced by the caller. */
interface RuleSource {
    List<UsageRule> rulesFor(Element declaration);
}
