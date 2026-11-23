package frth.permitter.plugin;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.HashSet;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;

final class UsageScanner extends TreePathScanner<Void, UsageScanner.Caller> {
    record Caller(Set<TypeElement> owners, Set<String> names) {
        Caller nested(TypeElement type) {
            var nestedOwners = new HashSet<>(owners);
            var nestedNames = new HashSet<>(names);
            nestedOwners.add(type);
            if (type.getNestingKind() == NestingKind.TOP_LEVEL || type.getNestingKind() == NestingKind.MEMBER) {
                nestedNames.add(type.getQualifiedName().toString());
            }
            return new Caller(nestedOwners, nestedNames);
        }
    }

    private record Report(long position, Element declaration, Diagnostic.Kind severity) {}

    private final Trees trees;
    private final RuleSource source;
    private final String artifact;
    private final CompilationUnitTree unit;
    private final String packageName;
    private final Set<Report> reported = new HashSet<>();
    private final Set<Tree> qualifiers = new HashSet<>();

    UsageScanner(Trees trees, RuleSource source, String artifact, CompilationUnitTree unit) {
        this.trees = trees;
        this.source = source;
        this.artifact = artifact;
        this.unit = unit;
        this.packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
    }

    @Override
    public Void visitClass(ClassTree tree, Caller caller) {
        var element = trees.getElement(getCurrentPath());
        if (caller == null) {
            caller = new Caller(Set.of(), Set.of());
        }
        return super.visitClass(tree, element instanceof TypeElement type ? caller.nested(type) : caller);
    }

    @Override
    public Void visitIdentifier(IdentifierTree tree, Caller caller) {
        checkReference(null, caller);
        return super.visitIdentifier(tree, caller);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree tree, Caller caller) {
        if (!tree.getIdentifier().contentEquals("class") && !tree.getIdentifier().contentEquals("this")
                && !tree.getIdentifier().contentEquals("super")) {
            checkReference(tree.getExpression(), caller);
        }
        return super.visitMemberSelect(tree, caller);
    }

    @Override
    public Void visitMemberReference(MemberReferenceTree tree, Caller caller) {
        checkReference(tree.getQualifierExpression(), caller);
        return super.visitMemberReference(tree, caller);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree tree, Caller caller) {
        checkExpressionType(trees.getElement(getCurrentPath()), caller);
        return super.visitMethodInvocation(tree, caller);
    }

    private void checkReference(Tree qualifier, Caller caller) {
        var symbol = trees.getElement(getCurrentPath());
        if (symbol instanceof TypeElement type) {
            markQualifier(qualifier);
            if (!qualifiers.contains(getCurrentPath().getLeaf())) {
                checkAccess(null, type, type, caller);
            }
            return;
        }
        if (symbol != null && symbol.getEnclosingElement() instanceof TypeElement owner) {
            if (symbol.getKind().isField() || symbol.getKind() == ElementKind.METHOD
                    || symbol.getKind() == ElementKind.CONSTRUCTOR) {
                TypeElement receiver = qualifier == null ? owner : typeAt(new TreePath(getCurrentPath(), qualifier));
                markQualifier(qualifier);
                checkAccess(symbol, owner, receiver == null ? owner : receiver, caller);
            }
        }
        checkExpressionType(symbol, caller);
    }

    // The qualifier belongs to the member access. The normal traversal checks
    // call arguments and type arguments as separate uses.
    private void markQualifier(Tree tree) {
        while (tree != null) {
            qualifiers.add(tree);
            if (tree instanceof ParameterizedTypeTree generic) {
                tree = generic.getType();
            } else if (tree instanceof AnnotatedTypeTree annotated) {
                tree = annotated.getUnderlyingType();
            } else if (tree instanceof ParenthesizedTree parens) {
                tree = parens.getExpression();
            } else {
                return;
            }
        }
    }

    private TypeElement typeAt(TreePath path) {
        var mirror = trees.getTypeMirror(path);
        return mirror instanceof DeclaredType declared && declared.asElement() instanceof TypeElement type ? type : null;
    }

    private void checkExpressionType(Element symbol, Caller caller) {
        if (qualifiers.contains(getCurrentPath().getLeaf())) {
            return;
        }
        TypeElement type = typeAt(getCurrentPath());
        if (type == null) {
            return;
        }
        Element policy = typePolicy(type);
        if (policy == null || symbol != null && symbol.getEnclosingElement() instanceof TypeElement && encloses(policy, symbol)) {
            return;
        }
        report(policy, type, null, type, caller);
    }

    private static boolean encloses(Element enclosing, Element element) {
        for (Element scope = element; scope != null; scope = scope.getEnclosingElement()) {
            if (scope.equals(enclosing)) {
                return true;
            }
        }
        return false;
    }

    private Element typePolicy(TypeElement type) {
        for (Element scope = type; scope != null; scope = scope.getEnclosingElement()) {
            if ((scope instanceof TypeElement || scope instanceof PackageElement) && !source.rulesFor(scope).isEmpty()) {
                return scope;
            }
        }
        return null;
    }

    private void checkAccess(Element member, TypeElement owner, TypeElement receiver, Caller caller) {
        Element policy = member;
        if (member == null || source.rulesFor(member).isEmpty()) {
            policy = typePolicy(receiver);
            if (!receiver.equals(owner) && !(policy instanceof TypeElement)) {
                Element declaredPolicy = typePolicy(owner);
                if (policy == null || declaredPolicy instanceof TypeElement) {
                    policy = declaredPolicy;
                }
            }
        }
        if (policy == null) {
            return;
        }
        report(policy, owner, member, receiver, caller);
    }

    private void report(Element policy, TypeElement owner, Element member, TypeElement receiver, Caller caller) {
        TypeElement policyOwner = owner;
        if (policy instanceof TypeElement type) {
            policyOwner = type;
        } else if (policy instanceof PackageElement && encloses(policy, receiver)) {
            policyOwner = receiver;
        }
        if (caller.owners().contains(policyOwner)) {
            return;
        }
        for (var rule : source.rulesFor(policy)) {
            if (rule.permits(artifact, packageName, caller.names())) {
                continue;
            }
            var tree = getCurrentPath().getLeaf();
            var positions = trees.getSourcePositions();
            long end = positions.getEndPosition(unit, tree);
            if (end == Diagnostic.NOPOS) {
                continue;
            }
            // A qualifier and its member share a start position. Chained calls have distinct end positions.
            long position = policy.getKind() == ElementKind.METHOD ? end : positions.getStartPosition(unit, tree);
            if (reported.add(new Report(position, policy, rule.severity()))) {
                String target = owner.getQualifiedName().toString();
                if (member != null) {
                    target += "#" + member;
                }
                String action = rule.severity() == Diagnostic.Kind.ERROR ? "Использование запрещено" : "Использование не рекомендуется";
                String message = "[permitter] " + action + ": " + target;
                if (!rule.message().isBlank()) {
                    message += ". " + rule.message();
                }
                trees.printMessage(rule.severity(), message, tree, unit);
            }
        }
    }
}
