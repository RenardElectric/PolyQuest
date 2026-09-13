package polycube.polyquest.condition;

import com.google.gson.JsonObject;
import java.util.Optional;
import polycube.polyquest.runtime.ConditionRuntime;
import polycube.polyquest.signal.QuestSignal;

/// Repetition, ordering, and deadline-oriented composite runtime nodes.
final class FlowCompositeRuntime {
    static final class RepeatInstance implements ConditionRuntime.Instance {
        private final CompositeConditions.Repeat definition;
        private final ConditionRuntime.CreationContext creationContext;
        private ConditionRuntime.Instance child;
        private int completedIterations;

        RepeatInstance(CompositeConditions.Repeat definition, ConditionRuntime.CreationContext context) {
            if (definition.times() <= 0) {
                throw new IllegalArgumentException("repeat.times must be positive");
            }
            this.definition = definition;
            this.creationContext = context;
            this.child = ConditionRuntime.create(definition.child(), context);
        }

        @Override
        public CompositeConditions.Repeat definition() {
            return definition;
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            return completed()
                    ? ConditionRuntime.Update.NONE
                    : collectCompletion(child.onSignal(signal, context));
        }

        @Override
        public ConditionRuntime.Update tick(ConditionRuntime.EvaluationContext context) {
            return completed()
                    ? ConditionRuntime.Update.NONE
                    : collectCompletion(child.tick(context));
        }

        /// Counts a finished iteration and recreates its child until the target is reached.
        private ConditionRuntime.Update collectCompletion(ConditionRuntime.Update update) {
            if (!child.completed()) {
                return update;
            }
            completedIterations++;
            boolean done = completed();
            if (!done) {
                child = ConditionRuntime.create(definition.child(), creationContext);
            }
            return new ConditionRuntime.Update(true, true, done);
        }

        @Override
        public boolean completed() {
            return completedIterations >= definition.times();
        }

        @Override
        public boolean exhausted() {
            return child.exhausted();
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            if (completed()) {
                return ConditionRuntime.ClaimPreparation.readyPrep();
            }
            if (completedIterations == definition.times() - 1) {
                return child.prepareClaim(context);
            }
            return ConditionRuntime.ClaimPreparation.blocked(
                    "Repeat condition requires more event-driven iterations");
        }

        @Override
        public void reset() {
            completedIterations = 0;
            child = ConditionRuntime.create(definition.child(), creationContext);
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = new JsonObject();
            result.addProperty("type", definition.type().id().toString());
            result.addProperty("completed", completed());
            result.addProperty("iterations", completedIterations);
            result.addProperty("target", definition.times());
            result.add("child", child.diagnostic());
            return result;
        }
    }

    static final class SequenceInstance
            extends CompositeRuntimeSupport.ChildrenInstance<CompositeConditions.Sequence> {
        private int index;

        SequenceInstance(CompositeConditions.Sequence definition, ConditionRuntime.CreationContext context) {
            super(definition, definition.children(), context);
            advancePastCompletedChildren();
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (completed()) {
                return ConditionRuntime.Update.NONE;
            }
            ConditionRuntime.Update update = children.get(index).onSignal(signal, context);
            if (children.get(index).completed()) {
                advance();
                return new ConditionRuntime.Update(true, true, completed());
            }
            return update;
        }

        @Override
        public ConditionRuntime.Update tick(ConditionRuntime.EvaluationContext context) {
            if (completed()) {
                return ConditionRuntime.Update.NONE;
            }
            ConditionRuntime.Update update = children.get(index).tick(context);
            if (children.get(index).completed()) {
                advance();
                return new ConditionRuntime.Update(true, true, completed());
            }
            return update;
        }

        @Override
        public boolean completed() {
            return index >= children.size();
        }

        @Override
        public boolean exhausted() {
            return !completed() && children.get(index).exhausted();
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            if (completed()) {
                return ConditionRuntime.ClaimPreparation.readyPrep();
            }
            if (index == children.size() - 1) {
                return children.get(index).prepareClaim(context);
            }
            return ConditionRuntime.ClaimPreparation.blocked("Earlier sequence steps are incomplete");
        }

        @Override
        public void reset() {
            super.reset();
            index = 0;
            advancePastCompletedChildren();
        }

        /// Moves to the next sequence step and initializes it from a clean state.
        private void advance() {
            index++;
            if (!completed()) {
                children.get(index).reset();
                advancePastCompletedChildren();
            }
        }

        /// Skips steps that are already complete after construction or reset.
        private void advancePastCompletedChildren() {
            while (!completed() && children.get(index).completed()) {
                index++;
            }
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("current_index", index);
            return result;
        }
    }

    static final class TimeWindowInstance implements ConditionRuntime.Instance {
        private final CompositeConditions.TimeWindow definition;
        private final ConditionRuntime.Instance child;
        private final Optional<ConditionRuntime.Instance> startCondition;
        private final ConditionRuntime.CreationContext creationContext;
        private long deadline = -1L;
        private int attempts;
        private boolean exhausted;

        TimeWindowInstance(
                CompositeConditions.TimeWindow definition,
                ConditionRuntime.CreationContext context) {
            if (definition.durationTicks() <= 0) {
                throw new IllegalArgumentException("time_window.duration_ticks must be positive");
            }
            if (definition.startPolicy() == CompositeConditions.StartPolicy.START_CONDITION
                    && definition.startCondition().isEmpty()) {
                throw new IllegalArgumentException("A start_condition is required for START_CONDITION");
            }
            this.definition = definition;
            this.creationContext = context;
            this.child = ConditionRuntime.create(definition.child(), context);
            this.startCondition = definition.startCondition().map(value -> ConditionRuntime.create(value, context));
            if (definition.startPolicy() == CompositeConditions.StartPolicy.IMMEDIATE) {
                deadline = context.currentServerTick() + definition.durationTicks();
            }
        }

        @Override
        public CompositeConditions.TimeWindow definition() {
            return definition;
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            ConditionRuntime.Update timeout = expireIfNeeded(context.serverTick());
            if (exhausted || child.completed()) {
                return timeout;
            }

            if (definition.startPolicy() == CompositeConditions.StartPolicy.START_CONDITION
                    && deadline < 0L) {
                ConditionRuntime.Instance start = startCondition.orElseThrow();
                ConditionRuntime.Update startUpdate = start.onSignal(signal, context);
                if (start.completed()) {
                    deadline = context.serverTick() + definition.durationTicks();
                    return new ConditionRuntime.Update(true, true, false);
                }
                return timeout.merge(startUpdate);
            }

            ConditionRuntime.Update childUpdate = child.onSignal(signal, context);
            if (definition.startPolicy() == CompositeConditions.StartPolicy.FIRST_PROGRESS
                    && deadline < 0L
                    && childUpdate.progressed()) {
                deadline = context.serverTick() + definition.durationTicks();
            }
            return timeout.merge(childUpdate);
        }

        @Override
        public ConditionRuntime.Update tick(ConditionRuntime.EvaluationContext context) {
            ConditionRuntime.Update timeout = expireIfNeeded(context.serverTick());
            if (exhausted || child.completed()) {
                return timeout;
            }
            if (definition.startPolicy() == CompositeConditions.StartPolicy.START_CONDITION
                    && deadline < 0L) {
                ConditionRuntime.Instance start = startCondition.orElseThrow();
                ConditionRuntime.Update startUpdate = start.tick(context);
                if (start.completed()) {
                    deadline = context.serverTick() + definition.durationTicks();
                    return timeout.merge(new ConditionRuntime.Update(true, true, false));
                }
                return timeout.merge(startUpdate);
            }
            ConditionRuntime.Update update = child.tick(context);
            if (definition.startPolicy() == CompositeConditions.StartPolicy.FIRST_PROGRESS
                    && deadline < 0L
                    && update.progressed()) {
                deadline = context.serverTick() + definition.durationTicks();
            }
            return timeout.merge(update);
        }

        /// Applies timeout reset, attempt consumption, exhaustion, and immediate restart semantics.
        private ConditionRuntime.Update expireIfNeeded(long now) {
            if (deadline < 0L || now <= deadline || child.completed()) {
                return ConditionRuntime.Update.NONE;
            }

            attempts++;
            child.reset();
            startCondition.ifPresent(ConditionRuntime.Instance::reset);
            exhausted = definition.timeoutAction() == CompositeConditions.TimeoutAction.EXHAUST
                    || definition.maxAttempts() > 0 && attempts >= definition.maxAttempts();
            deadline = !exhausted && definition.startPolicy() == CompositeConditions.StartPolicy.IMMEDIATE
                    ? now + definition.durationTicks()
                    : -1L;
            return new ConditionRuntime.Update(true, false, false);
        }

        @Override
        public boolean completed() {
            return child.completed();
        }

        @Override
        public boolean exhausted() {
            return exhausted
                    || child.exhausted()
                    || (definition.startPolicy() == CompositeConditions.StartPolicy.START_CONDITION
                            && deadline < 0L
                            && startCondition.orElseThrow().exhausted());
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            expireIfNeeded(context.serverTick());
            if (exhausted()) {
                return ConditionRuntime.ClaimPreparation.blocked("Timed challenge has no attempts remaining");
            }
            if (definition.startPolicy() != CompositeConditions.StartPolicy.IMMEDIATE && deadline < 0L) {
                return ConditionRuntime.ClaimPreparation.blocked("Timed challenge has not started");
            }
            return child.prepareClaim(context);
        }

        @Override
        public void reset() {
            child.reset();
            startCondition.ifPresent(ConditionRuntime.Instance::reset);
            deadline = definition.startPolicy() == CompositeConditions.StartPolicy.IMMEDIATE
                    ? creationContext.currentServerTick() + definition.durationTicks()
                    : -1L;
            attempts = 0;
            exhausted = false;
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = new JsonObject();
            result.addProperty("type", definition.type().id().toString());
            result.addProperty("completed", completed());
            result.addProperty("exhausted", exhausted());
            result.addProperty("attempts", attempts);
            result.addProperty("deadline", deadline);
            result.add("child", child.diagnostic());
            startCondition.ifPresent(start -> result.add("start_condition", start.diagnostic()));
            return result;
        }
    }

    private FlowCompositeRuntime() {
    }
}
