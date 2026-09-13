package polycube.polyquest.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.util.StringRepresentable;
import polycube.polyquest.PolyQuest;

/// Immutable datapack definitions for composite quest conditions.
/// Runtime tree state is split into logical and flow-oriented implementations.
public final class CompositeConditions {
    public record AllOf(List<ConditionApi.Definition> children) implements ConditionApi.Definition {
        public AllOf {
            children = List.copyOf(children);
        }

        public static final MapCodec<AllOf> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ConditionApi.codec().listOf().fieldOf("children").forGetter(AllOf::children)
        ).apply(instance, AllOf::new));
        public static final ConditionApi.Type<AllOf> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("all_of"), CODEC, LogicalCompositeRuntime.AllOfInstance::new);

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
                PolyQuest.id("any_of"), CODEC, LogicalCompositeRuntime.AnyOfInstance::new);

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
                PolyQuest.id("repeat"), CODEC, FlowCompositeRuntime.RepeatInstance::new);

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
                PolyQuest.id("sequence"), CODEC, FlowCompositeRuntime.SequenceInstance::new);

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
        public TimeWindow {
            Objects.requireNonNull(child, "child");
            Objects.requireNonNull(startPolicy, "startPolicy");
            Objects.requireNonNull(startCondition, "startCondition");
            Objects.requireNonNull(timeoutAction, "timeoutAction");
        }

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
                PolyQuest.id("time_window"), CODEC, FlowCompositeRuntime.TimeWindowInstance::new);

        @Override
        public ConditionApi.Type<TimeWindow> type() {
            return TYPE;
        }
    }

    public record NOfM(int required, List<ConditionApi.Definition> children)
            implements ConditionApi.Definition {
        public NOfM {
            children = List.copyOf(children);
        }

        public static final MapCodec<NOfM> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.INT.fieldOf("required").forGetter(NOfM::required),
                ConditionApi.codec().listOf().fieldOf("children").forGetter(NOfM::children)
        ).apply(instance, NOfM::new));
        public static final ConditionApi.Type<NOfM> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("n_of_m"), CODEC, LogicalCompositeRuntime.NOfMInstance::new);

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
                PolyQuest.id("optional"), CODEC, LogicalCompositeRuntime.OptionalInstance::new);

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
                PolyQuest.id("choice"), CODEC, LogicalCompositeRuntime.ChoiceInstance::new);

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

    private CompositeConditions() {
    }
}
