package polycube.polyquest.condition;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import polycube.polyquest.runtime.ConditionRuntime;
import polycube.polyquest.signal.QuestSignal;

final class CompositeConditionsTest {
    @Test
    void anyOfIsExhaustedOnlyWhenEveryAlternativeIsExhausted() {
        ConditionRuntime.Instance oneAlternativeLeft = create(new CompositeConditions.AnyOf(List.of(
                new Stub(false, true),
                new Stub(false, false))));
        ConditionRuntime.Instance noAlternativesLeft = create(new CompositeConditions.AnyOf(List.of(
                new Stub(false, true),
                new Stub(false, true))));

        assertFalse(oneAlternativeLeft.exhausted());
        assertTrue(noAlternativesLeft.exhausted());
    }

    @Test
    void nOfMIsExhaustedOnlyWhenTooFewViableChildrenRemain() {
        List<ConditionApi.Definition> children = List.of(
                new Stub(true, false),
                new Stub(false, true),
                new Stub(false, false));

        assertFalse(create(new CompositeConditions.NOfM(2, children)).exhausted());
        assertTrue(create(new CompositeConditions.NOfM(3, children)).exhausted());
    }

    @Test
    void resettingImmediateTimeWindowUsesCurrentTick() {
        AtomicLong tick = new AtomicLong(10L);
        CompositeConditions.TimeWindow definition = new CompositeConditions.TimeWindow(
                new Stub(false, false),
                5L,
                CompositeConditions.StartPolicy.IMMEDIATE,
                Optional.empty(),
                CompositeConditions.TimeoutAction.RESET,
                0);
        ConditionRuntime.Instance instance = ConditionRuntime.create(
                definition,
                new ConditionRuntime.CreationContext(tick::get));

        assertEquals(15L, instance.diagnostic().get("deadline").getAsLong());
        tick.set(100L);
        instance.reset();
        assertEquals(105L, instance.diagnostic().get("deadline").getAsLong());
    }

    @Test
    void sequenceSkipsOptionalStepsThatAreAlreadyComplete() {
        ConditionRuntime.Instance instance = create(new CompositeConditions.Sequence(List.of(
                new CompositeConditions.OptionalChild(new Stub(false, false)),
                new Stub(false, false))));

        assertEquals(1, instance.diagnostic().get("current_index").getAsInt());
        assertFalse(instance.completed());
    }

    @Test
    void registeredSemanticsComposeThroughConditionTrees() {
        ConditionApi.Definition signal = new Stub(
                false, false, ConditionApi.Capabilities.SIGNAL_DRIVEN);
        ConditionApi.Definition claim = new Stub(
                false, false, ConditionApi.Capabilities.CLAIM_TIME_COST);
        ConditionApi.Capabilities mixedAll = new ConditionApi.Capabilities(false, true, true);
        ConditionApi.Capabilities mixedAny = new ConditionApi.Capabilities(true, true, true);

        assertAll(
                () -> assertEquals(mixedAll, ConditionApi.capabilities(
                        new CompositeConditions.AllOf(List.of(signal, claim)))),
                () -> assertEquals(mixedAny, ConditionApi.capabilities(
                        new CompositeConditions.AnyOf(List.of(signal, claim)))),
                () -> assertEquals(mixedAll, ConditionApi.capabilities(
                        new CompositeConditions.NOfM(2, List.of(signal, claim)))),
                () -> assertEquals(ConditionApi.Capabilities.CLAIM_TIME_COST,
                        ConditionApi.capabilities(new CompositeConditions.Repeat(claim, 2))),
                () -> assertEquals(mixedAll, ConditionApi.capabilities(
                        new CompositeConditions.Sequence(List.of(signal, claim)))),
                () -> assertEquals(new ConditionApi.Capabilities(true, false, true),
                        ConditionApi.capabilities(new CompositeConditions.OptionalChild(claim))),
                () -> assertEquals(mixedAny, ConditionApi.capabilities(
                        new CompositeConditions.Choice(List.of(
                                new CompositeConditions.Branch("signal", signal),
                                new CompositeConditions.Branch("claim", claim))))),
                () -> assertEquals(new ConditionApi.Capabilities(true, false, true),
                        ConditionApi.capabilities(new CompositeConditions.TimeWindow(
                                signal,
                                20L,
                                CompositeConditions.StartPolicy.START_CONDITION,
                                Optional.of(claim),
                                CompositeConditions.TimeoutAction.RESET,
                                0)))
        );
    }

    private static ConditionRuntime.Instance create(ConditionApi.Definition definition) {
        return ConditionRuntime.create(definition, new ConditionRuntime.CreationContext(() -> 0L));
    }

    private record Stub(
            boolean initiallyCompleted,
            boolean initiallyExhausted,
            ConditionApi.Capabilities capabilities
    ) implements ConditionApi.Definition {
        private Stub(boolean initiallyCompleted, boolean initiallyExhausted) {
            this(initiallyCompleted, initiallyExhausted, ConditionApi.Capabilities.SIGNAL_DRIVEN);
        }

        private static final MapCodec<Stub> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.BOOL.fieldOf("completed").forGetter(Stub::initiallyCompleted),
                Codec.BOOL.fieldOf("exhausted").forGetter(Stub::initiallyExhausted)
        ).apply(instance, Stub::new));
        private static final ConditionApi.Type<Stub> TYPE = new ConditionApi.Type<>(
                Identifier.fromNamespaceAndPath("polyquest_test", "stub"),
                CODEC,
                StubInstance::new,
                (definition, children) -> definition.capabilities());

        @Override
        public ConditionApi.Type<Stub> type() {
            return TYPE;
        }
    }

    private static final class StubInstance implements ConditionRuntime.Instance {
        private final Stub definition;

        private StubInstance(Stub definition, ConditionRuntime.CreationContext ignored) {
            this.definition = definition;
        }

        @Override
        public Stub definition() {
            return definition;
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            return ConditionRuntime.Update.NONE;
        }

        @Override
        public boolean completed() {
            return definition.initiallyCompleted();
        }

        @Override
        public boolean exhausted() {
            return definition.initiallyExhausted();
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            return completed()
                    ? ConditionRuntime.ClaimPreparation.readyPrep()
                    : ConditionRuntime.ClaimPreparation.blocked("incomplete");
        }

        @Override
        public void reset() {
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = ConditionRuntime.Instance.super.diagnostic();
            result.addProperty("exhausted", exhausted());
            return result;
        }
    }
}
