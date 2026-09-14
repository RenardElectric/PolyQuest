package polycube.polyquest.resource;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import polycube.polyquest.condition.BuiltInConditions;
import polycube.polyquest.condition.CompositeConditions;
import polycube.polyquest.condition.ConditionApi;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.RewardApi;

final class QuestDefinitionValidatorTest {
    @Test
    void rejectsEmptyRewardProfile() {
        RewardApi.Profile profile = new RewardApi.Profile(
                Identifier.fromNamespaceAndPath("polyquest_test", "empty"),
                List.of());

        List<String> errors = new QuestDefinitionValidator().validate(profile);

        assertTrue(errors.stream().anyMatch(message -> message.contains("at least one reward")));
    }

    @Test
    void rejectsClaimOnlyStartConditionThatCanNeverStartWindow() {
        Identifier id = Identifier.fromNamespaceAndPath("polyquest_test", "timed");
        var condition = new CompositeConditions.TimeWindow(
                new BuiltInConditions.ExplicitSignal(id, 1),
                20L,
                CompositeConditions.StartPolicy.START_CONDITION,
                Optional.of(ClaimOnly.INSTANCE),
                CompositeConditions.TimeoutAction.RESET,
                0);
        var quest = new QuestModel.Definition(
                id,
                QuestModel.Availability.UNIQUE,
                Optional.empty(),
                "Timed",
                List.of(),
                condition,
                new RewardApi.Plan(Optional.empty(), List.of()),
                "behavior",
                "presentation");

        List<String> errors = new QuestDefinitionValidator().validate(quest, Map.of());

        assertTrue(errors.stream().anyMatch(message ->
                message.contains("start_condition must be completable from quest signals")));
    }

    private record ClaimOnly() implements ConditionApi.Definition {
        private static final ClaimOnly INSTANCE = new ClaimOnly();
        private static final ConditionApi.Type<ClaimOnly> TYPE = new ConditionApi.Type<>(
                Identifier.fromNamespaceAndPath("polyquest_test", "claim_only"),
                MapCodec.unit(INSTANCE),
                (definition, context) -> {
                    throw new UnsupportedOperationException("No runtime is needed by this test");
                },
                ConditionApi.Semantics.constant(ConditionApi.Capabilities.CLAIM_TIME_COST));

        @Override
        public ConditionApi.Type<ClaimOnly> type() {
            return TYPE;
        }
    }
}
