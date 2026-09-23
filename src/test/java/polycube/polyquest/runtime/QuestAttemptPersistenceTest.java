package polycube.polyquest.runtime;

import net.minecraft.SharedConstants;
import net.minecraft.advancements.triggers.PlayerTrigger;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import polycube.polyquest.condition.BuiltInConditions;
import polycube.polyquest.condition.CompositeConditions;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.RewardApi;
import polycube.polyquest.signal.QuestSignal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuestAttemptPersistenceTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void restoredCompletionRemainsClaimableEvenWhenFreshCriterionCannotReplay() {
        Identifier id = Identifier.fromNamespaceAndPath("polyquest_test", "one_shot_criterion");
        var criterion = new BuiltInConditions.AdvancementCriterion(PlayerTrigger.TriggerInstance.tick());
        QuestModel.Definition definition = new QuestModel.Definition(
                id, QuestModel.Availability.UNIQUE, Optional.empty(), "One-shot quest", List.of(),
                Items.SUNFLOWER, criterion, new RewardApi.Plan(Optional.empty(), List.of()), "behavior");
        QuestModel.Occurrence occurrence = new QuestModel.Occurrence(
                new QuestModel.Key(id, new QuestModel.UniqueScope()), definition, Instant.EPOCH, Optional.empty());
        var attempt = new QuestAttempt(occurrence, new FreshCriterion(criterion), 0L);
        var claimContext = new QuestAttempt.ServerPlayerContext(null, null, 0L);

        assertEquals(QuestModel.AttemptStatus.ACTIVE, attempt.status());
        assertFalse(attempt.prepareClaim(claimContext).ready());

        attempt.restoreReady();

        assertEquals(QuestModel.AttemptStatus.READY_TO_CLAIM, attempt.status());
        assertTrue(attempt.diagnostic().getAsJsonObject("condition").get("completed").getAsBoolean());
        assertTrue(attempt.prepareClaim(claimContext).ready());
        attempt.markPending();
        attempt.markActiveAfterFailedClaim();
        assertEquals(QuestModel.AttemptStatus.READY_TO_CLAIM, attempt.status());
        assertTrue(attempt.prepareClaim(claimContext).ready());
    }

    @Test
    void restoredCompletionDisplaysEveryChoiceAndAnyOfBranchAsComplete() {
        Identifier id = Identifier.fromNamespaceAndPath("polyquest_test", "restored_choice");
        var first = new BuiltInConditions.ExplicitSignal(
                Identifier.fromNamespaceAndPath("polyquest_test", "first"), 3);
        var second = new BuiltInConditions.ExplicitSignal(
                Identifier.fromNamespaceAndPath("polyquest_test", "second"), 2);
        var condition = new CompositeConditions.Choice(List.of(
                new CompositeConditions.Branch("Any path", new CompositeConditions.AnyOf(List.of(first, second))),
                new CompositeConditions.Branch("Repeated path", new CompositeConditions.Repeat(first, 2))));
        var definition = new QuestModel.Definition(
                id, QuestModel.Availability.UNIQUE, Optional.empty(), "Choice quest", List.of(),
                Items.SUNFLOWER, condition, new RewardApi.Plan(Optional.empty(), List.of()), "behavior");
        var occurrence = new QuestModel.Occurrence(
                new QuestModel.Key(id, new QuestModel.UniqueScope()), definition, Instant.EPOCH, Optional.empty());
        var attempt = new QuestAttempt(
                occurrence, ConditionRuntime.create(condition, new ConditionRuntime.CreationContext(() -> 0L)), 0L);

        assertEquals(QuestModel.AttemptStatus.ACTIVE, attempt.status());
        attempt.restoreReady();

        var diagnostic = attempt.diagnostic().getAsJsonObject("condition");
        assertTrue(diagnostic.get("completed").getAsBoolean());
        var branches = diagnostic.getAsJsonArray("branches");
        var anyOf = branches.get(0).getAsJsonObject();
        var repeat = branches.get(1).getAsJsonObject();
        assertTrue(anyOf.get("completed").getAsBoolean());
        anyOf.getAsJsonArray("children").forEach(child -> {
            var node = child.getAsJsonObject();
            assertTrue(node.get("completed").getAsBoolean());
            assertEquals(node.get("target").getAsInt(), node.get("current").getAsInt());
        });
        assertTrue(repeat.get("completed").getAsBoolean());
        assertEquals(2, repeat.get("iterations").getAsInt());
        assertTrue(repeat.getAsJsonObject("child").get("completed").getAsBoolean());
    }

    @Test
    void restoredCompletionAlsoMarksAnAlreadyReadyOptionalChildComplete() {
        Identifier id = Identifier.fromNamespaceAndPath("polyquest_test", "restored_optional");
        var condition = new CompositeConditions.OptionalChild(new BuiltInConditions.ExplicitSignal(id, 1));
        var definition = new QuestModel.Definition(
                id, QuestModel.Availability.UNIQUE, Optional.empty(), "Optional quest", List.of(),
                Items.SUNFLOWER, condition, new RewardApi.Plan(Optional.empty(), List.of()), "behavior");
        var occurrence = new QuestModel.Occurrence(
                new QuestModel.Key(id, new QuestModel.UniqueScope()), definition, Instant.EPOCH, Optional.empty());
        var attempt = new QuestAttempt(
                occurrence, ConditionRuntime.create(condition, new ConditionRuntime.CreationContext(() -> 0L)), 0L);

        assertEquals(QuestModel.AttemptStatus.READY_TO_CLAIM, attempt.status());
        assertFalse(attempt.diagnostic().getAsJsonObject("condition").getAsJsonObject("child")
                .get("completed").getAsBoolean());
        attempt.restoreReady();
        assertTrue(attempt.diagnostic().getAsJsonObject("condition").getAsJsonObject("child")
                .get("completed").getAsBoolean());
    }

    /// Models the incomplete condition tree created on a fresh world load.
    private record FreshCriterion(BuiltInConditions.AdvancementCriterion definition) implements ConditionRuntime.Instance {
        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            return ConditionRuntime.Update.NONE;
        }

        @Override
        public boolean completed() {
            return false;
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            return ConditionRuntime.ClaimPreparation.blocked("Criterion event cannot be replayed");
        }

        @Override
        public void reset() {}
    }
}
