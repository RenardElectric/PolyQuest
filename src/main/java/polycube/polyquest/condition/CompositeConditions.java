package polycube.polyquest.condition;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.util.StringRepresentable;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.runtime.ConditionRuntime;
import polycube.polyquest.signal.QuestSignal;

/// Composite condition definitions and their mutable tree nodes.
public final class CompositeConditions {
    public record AllOf(List<ConditionApi.Definition> children) implements ConditionApi.Definition {
        public AllOf {
            children = List.copyOf(children);
        }

        public static final MapCodec<AllOf> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ConditionApi.codec().listOf().fieldOf("children").forGetter(AllOf::children)
        ).apply(instance, AllOf::new));

        public static final ConditionApi.Type<AllOf> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("all_of"), CODEC, AllOfInstance::new);

        @Override
        public ConditionApi.Type<AllOf> type() {
            return TYPE;
        }
    }

    public record AnyOf(List<ConditionApi.Definition> children) implements ConditionApi.Definition {
        public AnyOf {
            children = List.copyOf(children);
        }

        public static final MapCodec<AnyOf> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ConditionApi.codec().listOf().fieldOf("children").forGetter(AnyOf::children)
        ).apply(instance, AnyOf::new));

        public static final ConditionApi.Type<AnyOf> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("any_of"), CODEC, AnyOfInstance::new);

        @Override
        public ConditionApi.Type<AnyOf> type() {
            return TYPE;
        }
    }

    public record Repeat(ConditionApi.Definition child, int times) implements ConditionApi.Definition {
        public static final MapCodec<Repeat> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ConditionApi.codec().fieldOf("child").forGetter(Repeat::child),
                Codec.INT.fieldOf("times").forGetter(Repeat::times)
        ).apply(instance, Repeat::new));

        public static final ConditionApi.Type<Repeat> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("repeat"), CODEC, RepeatInstance::new);

        @Override
        public ConditionApi.Type<Repeat> type() {
            return TYPE;
        }
    }

    public record Sequence(List<ConditionApi.Definition> children) implements ConditionApi.Definition {
        public Sequence {
            children = List.copyOf(children);
        }

        public static final MapCodec<Sequence> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ConditionApi.codec().listOf().fieldOf("children").forGetter(Sequence::children)
        ).apply(instance, Sequence::new));

        public static final ConditionApi.Type<Sequence> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("sequence"), CODEC, SequenceInstance::new);

        @Override
        public ConditionApi.Type<Sequence> type() {
            return TYPE;
        }
    }

    public record TimeWindow(
            ConditionApi.Definition child,
            long durationTicks,
            StartPolicy startPolicy,
            Optional<ConditionApi.Definition> startCondition,
            TimeoutAction timeoutAction,
            int maxAttempts) implements ConditionApi.Definition {
        public static final MapCodec<TimeWindow> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ConditionApi.codec().fieldOf("child").forGetter(TimeWindow::child),
                Codec.LONG.fieldOf("duration_ticks").forGetter(TimeWindow::durationTicks),
                StartPolicy.CODEC.optionalFieldOf("start_policy", StartPolicy.FIRST_PROGRESS)
                        .forGetter(TimeWindow::startPolicy),
                ConditionApi.codec().optionalFieldOf("start_condition").forGetter(TimeWindow::startCondition),
                TimeoutAction.CODEC.optionalFieldOf("timeout_action", TimeoutAction.RESET)
                        .forGetter(TimeWindow::timeoutAction),
                Codec.INT.optionalFieldOf("max_attempts", 0).forGetter(TimeWindow::maxAttempts)
        ).apply(instance, TimeWindow::new));

        public static final ConditionApi.Type<TimeWindow> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("time_window"), CODEC, TimeWindowInstance::new);

        @Override
        public ConditionApi.Type<TimeWindow> type() {
            return TYPE;
        }
    }

    public record NOfM(int required, List<ConditionApi.Definition> children) implements ConditionApi.Definition {
        public NOfM {
            children = List.copyOf(children);
        }

        public static final MapCodec<NOfM> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.INT.fieldOf("required").forGetter(NOfM::required),
                ConditionApi.codec().listOf().fieldOf("children").forGetter(NOfM::children)
        ).apply(instance, NOfM::new));

        public static final ConditionApi.Type<NOfM> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("n_of_m"), CODEC, NOfMInstance::new);

        @Override
        public ConditionApi.Type<NOfM> type() {
            return TYPE;
        }
    }

    public record OptionalChild(ConditionApi.Definition child) implements ConditionApi.Definition {
        public static final MapCodec<OptionalChild> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ConditionApi.codec().fieldOf("child").forGetter(OptionalChild::child)
        ).apply(instance, OptionalChild::new));

        public static final ConditionApi.Type<OptionalChild> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("optional"), CODEC, OptionalInstance::new);

        @Override
        public ConditionApi.Type<OptionalChild> type() {
            return TYPE;
        }
    }

    /// An alternative locks to the first branch that makes meaningful progress.
    public record Choice(List<Branch> branches) implements ConditionApi.Definition {
        public Choice {
            branches = List.copyOf(branches);
        }

        public static final MapCodec<Choice> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Branch.CODEC.listOf().fieldOf("branches").forGetter(Choice::branches)
        ).apply(instance, Choice::new));

        public static final ConditionApi.Type<Choice> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("choice"), CODEC, ChoiceInstance::new);

        @Override
        public ConditionApi.Type<Choice> type() {
            return TYPE;
        }
    }

    public record Branch(String name, ConditionApi.Definition condition) {
        public static final Codec<Branch> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("name").forGetter(Branch::name),
                ConditionApi.codec().fieldOf("condition").forGetter(Branch::condition)
        ).apply(instance, Branch::new));
    }

    public enum StartPolicy implements StringRepresentable {
        IMMEDIATE("immediate"),
        FIRST_PROGRESS("first_progress"),
        START_CONDITION("start_condition");

        public static final Codec<StartPolicy> CODEC = StringRepresentable.fromEnum(StartPolicy::values);
        private final String serializedName;

        StartPolicy(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }

    public enum TimeoutAction implements StringRepresentable {
        RESET("reset"),
        EXHAUST("exhaust");

        public static final Codec<TimeoutAction> CODEC = StringRepresentable.fromEnum(TimeoutAction::values);
        private final String serializedName;

        TimeoutAction(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }

    public static void register() {
        ConditionApi.register(AllOf.TYPE);
        ConditionApi.register(AnyOf.TYPE);
        ConditionApi.register(Repeat.TYPE);
        ConditionApi.register(Sequence.TYPE);
        ConditionApi.register(TimeWindow.TYPE);
        ConditionApi.register(NOfM.TYPE);
        ConditionApi.register(OptionalChild.TYPE);
        ConditionApi.register(Choice.TYPE);
    }

    private abstract static class ChildrenInstance<D extends ConditionApi.Definition>
            implements ConditionRuntime.Instance {
        protected final D definition;
        protected final List<ConditionRuntime.Instance> children;

        protected ChildrenInstance(
                D definition,
                List<ConditionApi.Definition> childDefinitions,
                ConditionRuntime.CreationContext context) {
            this.definition = definition;
            this.children = childDefinitions.stream()
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

        protected static ConditionRuntime.ClaimPreparation combine(
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
    }

    private static final class AllOfInstance extends ChildrenInstance<AllOf> {
        private AllOfInstance(AllOf definition, ConditionRuntime.CreationContext context) {
            super(definition, requireChildren(definition.children(), "all_of"), context);
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            boolean wasComplete = completed();
            ConditionRuntime.Update result = ConditionRuntime.Update.NONE;
            for (ConditionRuntime.Instance child : children) {
                result = result.merge(child.onSignal(signal, context));
            }
            return new ConditionRuntime.Update(
                    result.changed(), result.progressed(), !wasComplete && completed());
        }

        @Override
        public boolean completed() {
            return children.stream().allMatch(ConditionRuntime.Instance::completed);
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            return combine(children.stream().map(child -> child.prepareClaim(context)).toList());
        }
    }

    private static final class AnyOfInstance extends ChildrenInstance<AnyOf> {
        private AnyOfInstance(AnyOf definition, ConditionRuntime.CreationContext context) {
            super(definition, requireChildren(definition.children(), "any_of"), context);
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            boolean wasComplete = completed();
            ConditionRuntime.Update result = ConditionRuntime.Update.NONE;
            for (ConditionRuntime.Instance child : children) {
                if (!child.completed()) {
                    result = result.merge(child.onSignal(signal, context));
                }
            }
            return new ConditionRuntime.Update(
                    result.changed(), result.progressed(), !wasComplete && completed());
        }

        @Override
        public boolean completed() {
            return children.stream().anyMatch(ConditionRuntime.Instance::completed);
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

    private static final class RepeatInstance implements ConditionRuntime.Instance {
        private final Repeat definition;
        private final ConditionRuntime.CreationContext creationContext;
        private ConditionRuntime.Instance child;
        private int completedIterations;

        private RepeatInstance(Repeat definition, ConditionRuntime.CreationContext context) {
            if (definition.times() <= 0) {
                throw new IllegalArgumentException("repeat.times must be positive");
            }
            this.definition = definition;
            this.creationContext = context;
            this.child = ConditionRuntime.create(definition.child(), context);
        }

        @Override
        public Repeat definition() {
            return definition;
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (completed()) {
                return ConditionRuntime.Update.NONE;
            }
            ConditionRuntime.Update update = child.onSignal(signal, context);
            return collectCompletion(update);
        }

        @Override
        public ConditionRuntime.Update tick(ConditionRuntime.EvaluationContext context) {
            if (completed()) {
                return ConditionRuntime.Update.NONE;
            }
            return collectCompletion(child.tick(context));
        }

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

    private static final class SequenceInstance extends ChildrenInstance<Sequence> {
        private int index;

        private SequenceInstance(Sequence definition, ConditionRuntime.CreationContext context) {
            super(definition, requireChildren(definition.children(), "sequence"), context);
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (completed()) {
                return ConditionRuntime.Update.NONE;
            }
            ConditionRuntime.Update update = children.get(index).onSignal(signal, context);
            if (children.get(index).completed()) {
                index++;
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
                index++;
                return new ConditionRuntime.Update(true, true, completed());
            }
            return update;
        }

        @Override
        public boolean completed() {
            return index >= children.size();
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
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("current_index", index);
            return result;
        }
    }

    private static final class TimeWindowInstance implements ConditionRuntime.Instance {
        private final TimeWindow definition;
        private final ConditionRuntime.Instance child;
        private final ConditionRuntime.Instance startCondition;
        private final net.minecraft.server.MinecraftServer server;
        private long deadline = -1L;
        private int attempts;
        private boolean exhausted;

        private TimeWindowInstance(TimeWindow definition, ConditionRuntime.CreationContext context) {
            if (definition.durationTicks() <= 0) {
                throw new IllegalArgumentException("time_window.duration_ticks must be positive");
            }
            if (definition.startPolicy() == StartPolicy.START_CONDITION
                    && definition.startCondition().isEmpty()) {
                throw new IllegalArgumentException("A start_condition is required for START_CONDITION");
            }
            this.definition = definition;
            this.server = context.server();
            this.child = ConditionRuntime.create(definition.child(), context);
            this.startCondition = definition.startCondition()
                    .map(value -> ConditionRuntime.create(value, context))
                    .orElse(null);
            if (definition.startPolicy() == StartPolicy.IMMEDIATE) {
                this.deadline = context.server().getTickCount() + definition.durationTicks();
            }
        }

        @Override
        public TimeWindow definition() {
            return definition;
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            ConditionRuntime.Update timeout = expireIfNeeded(context.serverTick());
            if (exhausted || child.completed()) {
                return timeout;
            }

            if (definition.startPolicy() == StartPolicy.START_CONDITION && deadline < 0L) {
                ConditionRuntime.Update startUpdate = startCondition.onSignal(signal, context);
                if (startCondition.completed()) {
                    deadline = context.serverTick() + definition.durationTicks();
                    return new ConditionRuntime.Update(true, true, false);
                }
                return timeout.merge(startUpdate);
            }

            ConditionRuntime.Update childUpdate = child.onSignal(signal, context);
            if (definition.startPolicy() == StartPolicy.FIRST_PROGRESS
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
            ConditionRuntime.Update update = child.tick(context);
            if (definition.startPolicy() == StartPolicy.FIRST_PROGRESS
                    && deadline < 0L
                    && update.progressed()) {
                deadline = context.serverTick() + definition.durationTicks();
            }
            return timeout.merge(update);
        }

        private ConditionRuntime.Update expireIfNeeded(long now) {
            if (deadline < 0L || now <= deadline || child.completed()) {
                return ConditionRuntime.Update.NONE;
            }

            attempts++;
            child.reset();
            if (startCondition != null) {
                startCondition.reset();
            }
            exhausted = definition.timeoutAction() == TimeoutAction.EXHAUST
                    || definition.maxAttempts() > 0 && attempts >= definition.maxAttempts();
            deadline = !exhausted && definition.startPolicy() == StartPolicy.IMMEDIATE
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
            return exhausted || child.exhausted();
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            expireIfNeeded(context.serverTick());
            if (exhausted()) {
                return ConditionRuntime.ClaimPreparation.blocked("Timed challenge has no attempts remaining");
            }
            return child.prepareClaim(context);
        }

        @Override
        public void reset() {
            child.reset();
            if (startCondition != null) {
                startCondition.reset();
            }
            deadline = definition.startPolicy() == StartPolicy.IMMEDIATE
                    ? server.getTickCount() + definition.durationTicks()
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
            if (startCondition != null) {
                result.add("start_condition", startCondition.diagnostic());
            }
            return result;
        }
    }

    private static final class NOfMInstance extends ChildrenInstance<NOfM> {
        private NOfMInstance(NOfM definition, ConditionRuntime.CreationContext context) {
            super(definition, requireChildren(definition.children(), "n_of_m"), context);
            if (definition.required() <= 0 || definition.required() > children.size()) {
                throw new IllegalArgumentException("n_of_m.required must be between 1 and child count");
            }
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            boolean wasComplete = completed();
            ConditionRuntime.Update result = ConditionRuntime.Update.NONE;
            for (ConditionRuntime.Instance child : children) {
                if (!child.completed()) {
                    result = result.merge(child.onSignal(signal, context));
                }
            }
            return new ConditionRuntime.Update(
                    result.changed(), result.progressed(), !wasComplete && completed());
        }

        @Override
        public boolean completed() {
            return children.stream().filter(ConditionRuntime.Instance::completed).count() >= definition.required();
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
                    ? combine(ready)
                    : ConditionRuntime.ClaimPreparation.blocked(
                            "Only " + ready.size() + " of " + definition.required() + " conditions are readyPrep");
        }
    }

    private static final class OptionalInstance implements ConditionRuntime.Instance {
        private final OptionalChild definition;
        private final ConditionRuntime.Instance child;

        private OptionalInstance(OptionalChild definition, ConditionRuntime.CreationContext context) {
            this.definition = definition;
            this.child = ConditionRuntime.create(definition.child(), context);
        }

        @Override
        public OptionalChild definition() {
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

        /// Optional nodes are always satisfied, but continue receiving signals until claim.
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
        public JsonObject diagnostic() {
            JsonObject result = new JsonObject();
            result.addProperty("type", definition.type().id().toString());
            result.addProperty("completed", true);
            result.addProperty("optional_child_completed", child.completed());
            result.add("child", child.diagnostic());
            return result;
        }
    }

    private static final class ChoiceInstance implements ConditionRuntime.Instance {
        private final Choice definition;
        private final List<ConditionRuntime.Instance> branches;
        private int selected = -1;

        private ChoiceInstance(Choice definition, ConditionRuntime.CreationContext context) {
            if (definition.branches().isEmpty()) {
                throw new IllegalArgumentException("choice requires at least one branch");
            }
            this.definition = definition;
            this.branches = definition.branches().stream()
                    .map(branch -> ConditionRuntime.create(branch.condition(), context))
                    .toList();
        }

        @Override
        public Choice definition() {
            return definition;
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (selected >= 0) {
                return branches.get(selected).onSignal(signal, context);
            }
            for (int index = 0; index < branches.size(); index++) {
                ConditionRuntime.Update update = branches.get(index).onSignal(signal, context);
                if (update.progressed()) {
                    selected = index;
                    for (int other = 0; other < branches.size(); other++) {
                        if (other != selected) {
                            branches.get(other).reset();
                        }
                    }
                    return update;
                }
            }
            return ConditionRuntime.Update.NONE;
        }

        @Override
        public ConditionRuntime.Update tick(ConditionRuntime.EvaluationContext context) {
            return selected >= 0
                    ? branches.get(selected).tick(context)
                    : ConditionRuntime.Update.NONE;
        }

        @Override
        public boolean completed() {
            return selected >= 0 && branches.get(selected).completed();
        }

        @Override
        public boolean exhausted() {
            return selected >= 0 && branches.get(selected).exhausted();
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            if (selected >= 0) {
                return branches.get(selected).prepareClaim(context);
            }
            for (int index = 0; index < branches.size(); index++) {
                ConditionRuntime.ClaimPreparation preparation = branches.get(index).prepareClaim(context);
                if (preparation.ready()) {
                    selected = index;
                    return preparation;
                }
            }
            return ConditionRuntime.ClaimPreparation.blocked("No choice branch is readyPrep");
        }

        @Override
        public void reset() {
            selected = -1;
            branches.forEach(ConditionRuntime.Instance::reset);
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = new JsonObject();
            result.addProperty("type", definition.type().id().toString());
            result.addProperty("completed", completed());
            result.addProperty("selected", selected < 0 ? "" : definition.branches().get(selected).name());
            JsonArray diagnostics = new JsonArray();
            branches.forEach(branch -> diagnostics.add(branch.diagnostic()));
            result.add("branches", diagnostics);
            return result;
        }
    }

    private static List<ConditionApi.Definition> requireChildren(
            List<ConditionApi.Definition> children,
            String type) {
        if (children.isEmpty()) {
            throw new IllegalArgumentException(type + " requires at least one child");
        }
        return children;
    }

    private CompositeConditions() {
    }
}
