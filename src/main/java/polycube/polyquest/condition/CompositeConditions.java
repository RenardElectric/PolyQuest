package polycube.polyquest.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.condition.ConditionApi.Capabilities;
import polycube.polyquest.condition.ConditionApi.SemanticLookup;

import java.util.List;
import java.util.Optional;

/// Immutable datapack definitions for composite quest conditions.
/// Runtime tree state is split into logical and flow-oriented implementations.
public final class CompositeConditions {
    /// A condition that is satisfied when all children are satisfied.
    public record AllOf(List<ConditionApi.Definition> children) implements ConditionApi.Definition {
        public AllOf {
            children = List.copyOf(children);
        }

        public static final MapCodec<AllOf> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ConditionApi.codec().listOf().fieldOf("children").forGetter(AllOf::children)
        ).apply(instance, AllOf::new));
        public static final ConditionApi.Type<AllOf> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("all_of"), CODEC,
                LogicalCompositeRuntime.AllOfInstance::new, AllOf::analyze);

        private static Capabilities analyze(AllOf definition, SemanticLookup lookup) {
            List<Capabilities> children = CompositeConditions.analyze(definition.children(), lookup);
            return new Capabilities(
                    children.stream().allMatch(Capabilities::canCompleteFromSignals),
                    children.stream().anyMatch(Capabilities::canProgressFromSignals),
                    children.stream().anyMatch(Capabilities::containsClaimCost)
            );
        }

        @Override
        public ConditionApi.Type<AllOf> type() {
            return TYPE;
        }
    }

    /// A condition that is satisfied when any child is satisfied.
    public record AnyOf(List<ConditionApi.Definition> children) implements ConditionApi.Definition {
        public AnyOf {
            children = List.copyOf(children);
        }

        public static final MapCodec<AnyOf> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ConditionApi.codec().listOf().fieldOf("children").forGetter(AnyOf::children)
        ).apply(instance, AnyOf::new));
        public static final ConditionApi.Type<AnyOf> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("any_of"), CODEC,
                LogicalCompositeRuntime.AnyOfInstance::new, AnyOf::analyze);

        private static Capabilities analyze(AnyOf definition, SemanticLookup lookup) {
            List<Capabilities> children = CompositeConditions.analyze(definition.children(), lookup);
            return new Capabilities(
                    children.stream().anyMatch(Capabilities::canCompleteFromSignals),
                    children.stream().anyMatch(Capabilities::canProgressFromSignals),
                    children.stream().anyMatch(Capabilities::containsClaimCost)
            );
        }

        @Override
        public ConditionApi.Type<AnyOf> type() {
            return TYPE;
        }
    }

    /// A condition that repeats a child condition a fixed number of times.
    public record Repeat(ConditionApi.Definition child, int times) implements ConditionApi.Definition {
        public static final MapCodec<Repeat> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ConditionApi.codec().fieldOf("child").forGetter(Repeat::child),
                Codec.INT.fieldOf("times").forGetter(Repeat::times)
        ).apply(instance, Repeat::new));
        public static final ConditionApi.Type<Repeat> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("repeat"), CODEC,
                FlowCompositeRuntime.RepeatInstance::new,
                (definition, lookup) -> lookup.evaluate(definition.child()));

        @Override
        public ConditionApi.Type<Repeat> type() {
            return TYPE;
        }
    }

    /// A condition that is satisfied when all children are satisfied in order.
    public record Sequence(List<ConditionApi.Definition> children) implements ConditionApi.Definition {
        public Sequence {
            children = List.copyOf(children);
        }

        public static final MapCodec<Sequence> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ConditionApi.codec().listOf().fieldOf("children").forGetter(Sequence::children)
        ).apply(instance, Sequence::new));
        public static final ConditionApi.Type<Sequence> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("sequence"), CODEC,
                FlowCompositeRuntime.SequenceInstance::new, Sequence::analyze);

        private static Capabilities analyze(Sequence definition, SemanticLookup lookup) {
            List<Capabilities> children = CompositeConditions.analyze(definition.children(), lookup);
            return new Capabilities(
                    children.stream().allMatch(Capabilities::canCompleteFromSignals),
                    !children.isEmpty() && children.getFirst().canProgressFromSignals(),
                    children.stream().anyMatch(Capabilities::containsClaimCost)
            );
        }

        @Override
        public ConditionApi.Type<Sequence> type() {
            return TYPE;
        }
    }

    /// A condition that is satisfied when a child condition is satisfied within a time window.
    public record TimeWindow(
            ConditionApi.Definition child, long durationTicks,
            StartPolicy startPolicy, Optional<ConditionApi.Definition> startCondition,
            TimeoutAction timeoutAction, int maxAttempts
    ) implements ConditionApi.Definition {
        public static final MapCodec<TimeWindow> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ConditionApi.codec().fieldOf("child").forGetter(TimeWindow::child),
                Codec.LONG.fieldOf("duration_ticks").forGetter(TimeWindow::durationTicks),
                StartPolicy.CODEC.optionalFieldOf("start_policy", StartPolicy.FIRST_PROGRESS).forGetter(TimeWindow::startPolicy),
                ConditionApi.codec().optionalFieldOf("start_condition").forGetter(TimeWindow::startCondition),
                TimeoutAction.CODEC.optionalFieldOf("timeout_action", TimeoutAction.RESET).forGetter(TimeWindow::timeoutAction),
                Codec.INT.optionalFieldOf("max_attempts", 0).forGetter(TimeWindow::maxAttempts)
        ).apply(instance, TimeWindow::new));
        public static final ConditionApi.Type<TimeWindow> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("time_window"), CODEC,
                FlowCompositeRuntime.TimeWindowInstance::new, TimeWindow::analyze);

        private static Capabilities analyze(TimeWindow definition, SemanticLookup lookup) {
            Capabilities child = lookup.evaluate(definition.child());
            Optional<Capabilities> start = definition.startCondition().map(lookup::evaluate);
            boolean canProgress = definition.startPolicy() == StartPolicy.START_CONDITION
                    ? start.map(Capabilities::canProgressFromSignals).orElse(false)
                    : child.canProgressFromSignals();
            return new Capabilities(
                    child.canCompleteFromSignals(),
                    canProgress,
                    child.containsClaimCost()
                            || start.map(Capabilities::containsClaimCost).orElse(false));
        }

        @Override
        public ConditionApi.Type<TimeWindow> type() {
            return TYPE;
        }
    }

    /// A condition that is satisfied when at least N of M children are satisfied.
    public record NOfM(int required, List<ConditionApi.Definition> children) implements ConditionApi.Definition {
        public NOfM {
            children = List.copyOf(children);
        }

        public static final MapCodec<NOfM> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.INT.fieldOf("required").forGetter(NOfM::required),
                ConditionApi.codec().listOf().fieldOf("children").forGetter(NOfM::children)
        ).apply(instance, NOfM::new));
        public static final ConditionApi.Type<NOfM> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("n_of_m"), CODEC,
                LogicalCompositeRuntime.NOfMInstance::new, NOfM::analyze);

        private static Capabilities analyze(NOfM definition, SemanticLookup lookup) {
            List<Capabilities> children = CompositeConditions.analyze(definition.children(), lookup);
            return new Capabilities(
                    children.stream().filter(Capabilities::canCompleteFromSignals).count() >= definition.required(),
                    children.stream().anyMatch(Capabilities::canProgressFromSignals),
                    children.stream().anyMatch(Capabilities::containsClaimCost)
            );
        }

        @Override
        public ConditionApi.Type<NOfM> type() {
            return TYPE;
        }
    }

    /// A condition that is satisfied when a child condition is satisfied, but does not require it to be satisfied.
    public record OptionalChild(ConditionApi.Definition child) implements ConditionApi.Definition {
        public static final MapCodec<OptionalChild> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ConditionApi.codec().fieldOf("child").forGetter(OptionalChild::child)
        ).apply(instance, OptionalChild::new));
        public static final ConditionApi.Type<OptionalChild> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("optional"), CODEC,
                LogicalCompositeRuntime.OptionalInstance::new, OptionalChild::analyze);

        private static Capabilities analyze(OptionalChild definition, SemanticLookup lookup) {
            Capabilities child = lookup.evaluate(definition.child());
            return new Capabilities(true, child.canProgressFromSignals(), child.containsClaimCost());
        }

        @Override
        public ConditionApi.Type<OptionalChild> type() {
            return TYPE;
        }
    }

    /// A condition that is satisfied when one of several branches is satisfied, and the branch taken is recorded.
    public record Choice(List<Branch> branches) implements ConditionApi.Definition {
        public Choice {
            branches = List.copyOf(branches);
        }

        public static final MapCodec<Choice> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Branch.CODEC.listOf().fieldOf("branches").forGetter(Choice::branches)
        ).apply(instance, Choice::new));
        public static final ConditionApi.Type<Choice> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("choice"), CODEC,
                LogicalCompositeRuntime.ChoiceInstance::new, Choice::analyze);

        private static Capabilities analyze(Choice definition, SemanticLookup lookup) {
            List<Capabilities> branches = definition.branches().stream()
                    .map(Branch::condition)
                    .map(lookup::evaluate)
                    .toList();
            return new Capabilities(
                    branches.stream().anyMatch(Capabilities::canCompleteFromSignals),
                    branches.stream().anyMatch(Capabilities::canProgressFromSignals),
                    branches.stream().anyMatch(Capabilities::containsClaimCost)
            );
        }

        @Override
        public ConditionApi.Type<Choice> type() {
            return TYPE;
        }
    }

    /// A branch in a choice condition, consisting of a name and a condition.
    public record Branch(String name, ConditionApi.Definition condition) {
        public static final Codec<Branch> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("name").forGetter(Branch::name),
                ConditionApi.codec().fieldOf("condition").forGetter(Branch::condition)
        ).apply(instance, Branch::new));
    }

    /// Describes how a time window condition starts counting down.
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

    /// Describes what happens when a time window condition times out.
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

    /// Recursively analyzes a list of condition definitions and returns their capabilities.
    private static List<Capabilities> analyze(List<ConditionApi.Definition> definitions, SemanticLookup lookup) {
        return definitions.stream().map(lookup::evaluate).toList();
    }

    private CompositeConditions() {}
}
