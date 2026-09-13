package polycube.polyquest.condition;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import polycube.polyquest.runtime.ConditionRuntime;

/// Shared tree plumbing for composite runtime nodes.
final class CompositeRuntimeSupport {
    abstract static class ChildrenInstance<D extends ConditionApi.Definition>
            implements ConditionRuntime.Instance {
        protected final D definition;
        protected final List<ConditionRuntime.Instance> children;

        protected ChildrenInstance(
                D definition,
                List<ConditionApi.Definition> childDefinitions,
                ConditionRuntime.CreationContext context) {
            this.definition = definition;
            this.children = requireChildren(childDefinitions, definition.type().id().toString()).stream()
                    .map(child -> ConditionRuntime.create(child, context))
                    .toList();
        }

        @Override
        public D definition() {
            return definition;
        }

        @Override
        public ConditionRuntime.Update tick(ConditionRuntime.EvaluationContext context) {
            ConditionRuntime.Update result = ConditionRuntime.Update.NONE;
            for (ConditionRuntime.Instance child : children) {
                result = result.merge(child.tick(context));
            }
            return result;
        }

        @Override
        public boolean exhausted() {
            return children.stream().anyMatch(ConditionRuntime.Instance::exhausted);
        }

        @Override
        public void reset() {
            children.forEach(ConditionRuntime.Instance::reset);
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = new JsonObject();
            result.addProperty("type", definition.type().id().toString());
            result.addProperty("completed", completed());
            result.addProperty("exhausted", exhausted());
            JsonArray childDiagnostics = new JsonArray();
            children.forEach(child -> childDiagnostics.add(child.diagnostic()));
            result.add("children", childDiagnostics);
            return result;
        }
    }

    /// Flattens child claim operations only when every child is ready.
    static ConditionRuntime.ClaimPreparation combine(
            List<ConditionRuntime.ClaimPreparation> preparations) {
        List<ConditionRuntime.ClaimOperation> operations = new ArrayList<>();
        for (ConditionRuntime.ClaimPreparation preparation : preparations) {
            if (!preparation.ready()) {
                return preparation;
            }
            operations.addAll(preparation.operations());
        }
        return ConditionRuntime.ClaimPreparation.readyPrep(operations);
    }

    static List<ConditionApi.Definition> requireChildren(
            List<ConditionApi.Definition> children,
            String type) {
        if (children.isEmpty()) {
            throw new IllegalArgumentException(type + " requires at least one child");
        }
        return children;
    }

    private CompositeRuntimeSupport() {
    }
}
