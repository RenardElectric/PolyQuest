package polycube.polyquest.condition;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import polycube.polyquest.runtime.ConditionRuntime;
import polycube.polyquest.signal.QuestSignal;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/// Concurrent, threshold, optional, and branching composite runtime nodes.
final class LogicalCompositeRuntime {
    /// A composite condition that is satisfied when any child is satisfied.
    static final class AllOfInstance extends CompositeRuntimeSupport.ChildrenInstance<CompositeConditions.AllOf> {
        AllOfInstance(CompositeConditions.AllOf definition, ConditionRuntime.CreationContext context) {
            super(definition, definition.children(), context);
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            boolean wasComplete = completed();
            ConditionRuntime.Update result = ConditionRuntime.Update.NONE;
            for (ConditionRuntime.Instance child : children) {
                result = result.merge(child.onSignal(signal, context));
            }
            return new ConditionRuntime.Update(result.changed(), result.progressed(), !wasComplete && completed());
        }

        @Override
        public boolean completed() {
            return children.stream().allMatch(ConditionRuntime.Instance::completed);
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            return CompositeRuntimeSupport.combine(children.stream().map(child -> child.prepareClaim(context)).toList());
        }
    }

    /// A composite condition that is satisfied when any child is satisfied.
    static final class AnyOfInstance extends CompositeRuntimeSupport.ChildrenInstance<CompositeConditions.AnyOf> {
        AnyOfInstance(CompositeConditions.AnyOf definition, ConditionRuntime.CreationContext context) {
            super(definition, definition.children(), context);
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            return updateIncomplete(child -> child.onSignal(signal, context));
        }

        @Override
        public ConditionRuntime.Update tick(ConditionRuntime.EvaluationContext context) {
            return updateIncomplete(child -> child.tick(context));
        }

        /// Updates only viable alternatives until any child completes.
        private ConditionRuntime.Update updateIncomplete(Function<ConditionRuntime.Instance, ConditionRuntime.Update> updateChild) {
            if (completed()) {
                return ConditionRuntime.Update.NONE;
            }
            ConditionRuntime.Update result = ConditionRuntime.Update.NONE;
            for (ConditionRuntime.Instance child : children) {
                if (!child.completed() && !child.exhausted()) {
                    result = result.merge(updateChild.apply(child));
                }
            }
            return new ConditionRuntime.Update(result.changed(), result.progressed(), completed());
        }

        @Override
        public boolean completed() {
            return children.stream().anyMatch(ConditionRuntime.Instance::completed);
        }

        @Override
        public boolean exhausted() {
            return children.stream().allMatch(ConditionRuntime.Instance::exhausted);
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            return children.stream()
                    .map(child -> child.prepareClaim(context))
                    .filter(ConditionRuntime.ClaimPreparation::ready)
                    .findFirst()
                    .orElseGet(() -> ConditionRuntime.ClaimPreparation.blocked("No alternative is complete"));
        }
    }

    /// A composite condition that is satisfied when a threshold of children are satisfied.
    static final class NOfMInstance extends CompositeRuntimeSupport.ChildrenInstance<CompositeConditions.NOfM> {
        NOfMInstance(CompositeConditions.NOfM definition, ConditionRuntime.CreationContext context) {
            super(definition, definition.children(), context);
            if (definition.required() <= 0 || definition.required() > children.size()) {
                throw new IllegalArgumentException("n_of_m.required must be between 1 and child count");
            }
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            return updateIncomplete(child -> child.onSignal(signal, context));
        }

        @Override
        public ConditionRuntime.Update tick(ConditionRuntime.EvaluationContext context) {
            return updateIncomplete(child -> child.tick(context));
        }

        /// Updates only viable children until the required completion threshold is reached.
        private ConditionRuntime.Update updateIncomplete(Function<ConditionRuntime.Instance, ConditionRuntime.Update> updateChild) {
            if (completed()) {
                return ConditionRuntime.Update.NONE;
            }
            ConditionRuntime.Update result = ConditionRuntime.Update.NONE;
            for (ConditionRuntime.Instance child : children) {
                if (!child.completed() && !child.exhausted()) {
                    result = result.merge(updateChild.apply(child));
                }
            }
            return new ConditionRuntime.Update(result.changed(), result.progressed(), completed());
        }

        @Override
        public boolean completed() {
            return children.stream().filter(ConditionRuntime.Instance::completed).count() >= definition.required();
        }

        @Override
        public boolean exhausted() {
            long viable = children.stream()
                    .filter(child -> child.completed() || !child.exhausted())
                    .count();
            return viable < definition.required();
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            List<ConditionRuntime.ClaimPreparation> ready = children.stream()
                    .map(child -> child.prepareClaim(context))
                    .filter(ConditionRuntime.ClaimPreparation::ready)
                    .sorted(Comparator.comparingInt(value -> value.operations().size()))
                    .limit(definition.required())
                    .toList();
            return ready.size() >= definition.required()
                    ? CompositeRuntimeSupport.combine(ready)
                    : ConditionRuntime.ClaimPreparation.blocked(
                            "Only " + ready.size() + " of " + definition.required() + " conditions are ready");
        }
    }

    /// A composite condition that is satisfied when its child is satisfied, but does not require it to be satisfied.
    static final class OptionalInstance implements ConditionRuntime.Instance {
        private final CompositeConditions.OptionalChild definition;
        private final ConditionRuntime.Instance child;

        OptionalInstance(
                CompositeConditions.OptionalChild definition,
                ConditionRuntime.CreationContext context) {
            this.definition = definition;
            this.child = ConditionRuntime.create(definition.child(), context);
        }

        @Override
        public CompositeConditions.OptionalChild definition() {
            return definition;
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            return child.onSignal(signal, context);
        }

        @Override
        public ConditionRuntime.Update tick(ConditionRuntime.EvaluationContext context) {
            return child.tick(context);
        }

        @Override
        public boolean completed() {
            return true;
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            return ConditionRuntime.ClaimPreparation.readyPrep();
        }

        @Override
        public void reset() {
            child.reset();
        }

        @Override
        public void close() {
            child.close();
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = new JsonObject();
            result.addProperty("type", definition.type().id().toString());
            result.addProperty("completed", true);
            result.addProperty("optional_child_completed", child.completed());
            result.add("child", child.diagnostic());
            return result;
        }
    }

    /// A composite condition that is satisfied when any child is satisfied.
    static final class ChoiceInstance implements ConditionRuntime.Instance {
        private final CompositeConditions.Choice definition;
        private final List<ConditionRuntime.Instance> branches;
        private int selected = -1;

        ChoiceInstance(CompositeConditions.Choice definition, ConditionRuntime.CreationContext context) {
            if (definition.branches().isEmpty()) {
                throw new IllegalArgumentException("choice requires at least one branch");
            }
            this.definition = definition;
            this.branches = definition.branches().stream()
                    .map(branch -> ConditionRuntime.create(branch.condition(), context))
                    .toList();
        }

        @Override
        public CompositeConditions.Choice definition() {
            return definition;
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (selected >= 0) {
                return branches.get(selected).onSignal(signal, context);
            }
            return updateUnselected(branch -> branch.onSignal(signal, context));
        }

        @Override
        public ConditionRuntime.Update tick(ConditionRuntime.EvaluationContext context) {
            if (selected >= 0) {
                return branches.get(selected).tick(context);
            }
            return updateUnselected(branch -> branch.tick(context));
        }

        /// Evaluates branches in declaration order and locks the first one that progresses.
        private ConditionRuntime.Update updateUnselected(Function<ConditionRuntime.Instance, ConditionRuntime.Update> updateBranch) {
            ConditionRuntime.Update result = ConditionRuntime.Update.NONE;
            for (int index = 0; index < branches.size(); index++) {
                ConditionRuntime.Instance branch = branches.get(index);
                if (branch.exhausted()) {
                    continue;
                }
                ConditionRuntime.Update update = updateBranch.apply(branch);
                if (update.progressed()) {
                    select(index);
                    return update;
                }
                result = result.merge(update);
            }
            return result;
        }

        @Override
        public boolean completed() {
            return selected >= 0 && branches.get(selected).completed();
        }

        @Override
        public boolean exhausted() {
            return selected >= 0
                    ? branches.get(selected).exhausted()
                    : branches.stream().allMatch(ConditionRuntime.Instance::exhausted);
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            if (selected >= 0) {
                return branches.get(selected).prepareClaim(context);
            }
            for (ConditionRuntime.Instance branch : branches) {
                ConditionRuntime.ClaimPreparation preparation = branch.prepareClaim(context);
                if (preparation.ready()) {
                    return preparation;
                }
            }
            return ConditionRuntime.ClaimPreparation.blocked("No choice branch is ready");
        }

        @Override
        public void reset() {
            selected = -1;
            branches.forEach(ConditionRuntime.Instance::reset);
        }

        @Override
        public void close() {
            branches.forEach(ConditionRuntime.Instance::close);
        }

        /// Locks the choice to one branch and clears progress from every alternative.
        private void select(int branchIndex) {
            selected = branchIndex;
            for (int other = 0; other < branches.size(); other++) {
                if (other != selected) {
                    branches.get(other).reset();
                }
            }
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = new JsonObject();
            result.addProperty("type", definition.type().id().toString());
            result.addProperty("completed", completed());
            result.addProperty("exhausted", exhausted());
            result.addProperty("selected", selected < 0 ? "" : definition.branches().get(selected).name());
            JsonArray diagnostics = new JsonArray();
            branches.forEach(branch -> diagnostics.add(branch.diagnostic()));
            result.add("branches", diagnostics);
            return result;
        }
    }

    private LogicalCompositeRuntime() {}
}
