package polycube.polyquest.resource;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;
import polycube.polyquest.condition.BuiltInConditions;
import polycube.polyquest.condition.CompositeConditions;
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
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Identifier id = Identifier.fromNamespaceAndPath("polyquest_test", "timed");
        var condition = new CompositeConditions.TimeWindow(
                new BuiltInConditions.ExplicitSignal(id, 1),
                20L,
                CompositeConditions.StartPolicy.START_CONDITION,
                Optional.of(new BuiltInConditions.ConsumeItems(
                        ItemPredicate.Builder.item().build(), 1)),
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
}
