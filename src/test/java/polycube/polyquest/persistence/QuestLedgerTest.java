package polycube.polyquest.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import polycube.polyquest.condition.BuiltInConditions;
import polycube.polyquest.config.QuestConfig;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.RewardApi;
import polycube.polyquest.rotation.DailyRotationService;

final class QuestLedgerTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void codecPreservesPendingTransactionsAndClaims() {
        QuestLedger source = new QuestLedger();
        UUID playerId = UUID.randomUUID();
        QuestModel.Occurrence occurrence = occurrence("survive_reload");
        QuestLedger.PendingTransaction pending = source.beginClaim(playerId, occurrence, List.of());
        source.markCostsCommitted(pending);
        assertTrue(source.hasPending(playerId, occurrence.key()));

        var encoded = QuestLedger.CODEC.encodeStart(NbtOps.INSTANCE, source).getOrThrow();
        QuestLedger decoded = QuestLedger.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

        assertEquals(1, decoded.pendingFor(playerId).size());
        QuestLedger.PendingTransaction restored = decoded.pendingFor(playerId).getFirst();
        assertEquals(QuestLedger.TransactionState.COSTS_COMMITTED, restored.state());
        decoded.complete(restored);

        var completed = QuestLedger.CODEC.encodeStart(NbtOps.INSTANCE, decoded).getOrThrow();
        QuestLedger reloaded = QuestLedger.CODEC.parse(NbtOps.INSTANCE, completed).getOrThrow();
        assertTrue(reloaded.isClaimed(playerId, occurrence.key()));
    }

    @Test
    void behaviorChangesPreservePendingAndCompletedState() {
        QuestLedger ledger = new QuestLedger();
        UUID playerId = UUID.randomUUID();
        QuestModel.Occurrence before = occurrence("stable_identity", "before");
        QuestModel.Occurrence after = occurrence("stable_identity", "after");

        QuestLedger.PendingTransaction pending = ledger.beginClaim(playerId, before, List.of());
        assertEquals(before.key(), after.key());
        assertEquals("polyquest_test:stable_identity|unique", after.key().persistentKey());
        assertTrue(ledger.hasPending(playerId, after.key()));

        ledger.complete(pending);
        assertTrue(ledger.isClaimed(playerId, after.key()));
    }

    @Test
    void pendingLookupStaysScopedToEachPlayerAcrossRemovalAndReload() {
        QuestLedger ledger = new QuestLedger();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        QuestModel.Occurrence firstQuest = occurrence("first_pending");
        QuestModel.Occurrence secondQuest = occurrence("second_pending");

        QuestLedger.PendingTransaction first = ledger.beginClaim(firstPlayer, firstQuest, List.of());
        QuestLedger.PendingTransaction second = ledger.beginClaim(secondPlayer, firstQuest, List.of());
        ledger.beginClaim(firstPlayer, secondQuest, List.of());
        assertEquals(2, ledger.pendingFor(firstPlayer).size());
        assertEquals(Optional.of(second), ledger.findPending(secondPlayer, firstQuest.key()));

        ledger.cancel(first);
        assertFalse(ledger.hasPending(firstPlayer, firstQuest.key()));
        assertTrue(ledger.hasPending(firstPlayer, secondQuest.key()));
        assertTrue(ledger.hasPending(secondPlayer, firstQuest.key()));

        var encoded = QuestLedger.CODEC.encodeStart(NbtOps.INSTANCE, ledger).getOrThrow();
        QuestLedger restored = QuestLedger.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertEquals(1, restored.pendingFor(firstPlayer).size());
        assertEquals(1, restored.pendingFor(secondPlayer).size());
        restored.complete(restored.findPending(secondPlayer, firstQuest.key()).orElseThrow());
        assertFalse(restored.hasPending(secondPlayer, firstQuest.key()));
        assertTrue(restored.isClaimed(secondPlayer, firstQuest.key()));
    }

    @Test
    void dailyIdentityContainsOnlyQuestAndRotationScope() {
        Identifier id = Identifier.fromNamespaceAndPath("polyquest_test", "daily_identity");
        QuestModel.Key key = new QuestModel.Key(
                id,
                new QuestModel.DailyScope(
                        LocalDate.of(2026, 9, 19),
                        QuestModel.Difficulty.HARD,
                        3));

        assertEquals(
                "polyquest_test:daily_identity|daily:2026-09-19:hard:3",
                key.persistentKey());
    }

    @Test
    void rerollingMissingDifficultyDoesNotMutateGeneration() {
        QuestLedger ledger = new QuestLedger();
        DailyRotationService rotation = new DailyRotationService(
                new QuestConfig(1L, ZoneId.of("UTC"), 30));

        assertFalse(rotation.reroll(QuestModel.Difficulty.EASY, QuestModel.Catalog.EMPTY, ledger));
        assertEquals(0, ledger.rotationGeneration(QuestModel.Difficulty.EASY));
    }

    @Test
    void rotationRefreshDistinguishesAChangedDateFromARepeatedCheck() {
        QuestLedger ledger = new QuestLedger();
        DailyRotationService rotation = new DailyRotationService(
                new QuestConfig(1L, ZoneId.of("UTC"), 30));

        DailyRotationService.RefreshResult first = rotation.refresh(QuestModel.Catalog.EMPTY, ledger);
        DailyRotationService.RefreshResult repeated = rotation.refresh(QuestModel.Catalog.EMPTY, ledger);

        assertTrue(first.dateChanged());
        assertTrue(first.assignmentChanged());
        assertFalse(repeated.dateChanged());
        assertFalse(repeated.assignmentChanged());
    }

    @Test
    void rotationCacheRebuildsAfterARerollGenerationChanges() {
        QuestLedger ledger = new QuestLedger();
        DailyRotationService rotation = new DailyRotationService(new QuestConfig(1L, ZoneId.of("UTC"), 30));
        Identifier id = Identifier.fromNamespaceAndPath("polyquest_test", "daily_reroll");
        QuestModel.Definition daily = new QuestModel.Definition(
                id, QuestModel.Availability.DAILY, Optional.of(QuestModel.Difficulty.EASY),
                "Daily", List.of(), Items.SUNFLOWER,
                new BuiltInConditions.ExplicitSignal(id, 1),
                new RewardApi.Plan(Optional.empty(), List.of()), "behavior");
        QuestModel.Catalog catalog = new QuestModel.Catalog(Map.of(id, daily), Map.of());

        rotation.refresh(catalog, ledger);
        assertFalse(rotation.refresh(catalog, ledger).assignmentChanged());
        ledger.incrementRotationGeneration(QuestModel.Difficulty.EASY);
        assertTrue(rotation.refresh(catalog, ledger).assignmentChanged());
        var scope = (QuestModel.DailyScope) rotation.current().slots().get(QuestModel.Difficulty.EASY).key().scope();
        assertEquals(1, scope.generation());
    }

    @Test
    void rotationNotificationReceiptPersistsAndClearsForTheNextDate() {
        QuestLedger ledger = new QuestLedger();
        UUID playerId = UUID.randomUUID();
        ledger.beginRotation(LocalDate.of(2026, 9, 19));

        assertTrue(ledger.markRotationNotified(playerId));
        assertFalse(ledger.markRotationNotified(playerId));

        var encoded = QuestLedger.CODEC.encodeStart(NbtOps.INSTANCE, ledger).getOrThrow();
        QuestLedger reloaded = QuestLedger.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertFalse(reloaded.markRotationNotified(playerId));

        reloaded.beginRotation(LocalDate.of(2026, 9, 20));
        assertTrue(reloaded.markRotationNotified(playerId));
    }

    private static QuestModel.Occurrence occurrence(String path) {
        return occurrence(path, "behavior");
    }

    private static QuestModel.Occurrence occurrence(String path, String behaviorHash) {
        Identifier id = Identifier.fromNamespaceAndPath("polyquest_test", path);
        QuestModel.Definition definition = new QuestModel.Definition(
                id,
                QuestModel.Availability.UNIQUE,
                Optional.empty(),
                "Test",
                List.of(),
                Items.SUNFLOWER,
                new BuiltInConditions.ExplicitSignal(id, 1),
                new RewardApi.Plan(Optional.empty(), List.of()),
                behaviorHash);
        QuestModel.Key key = new QuestModel.Key(id, new QuestModel.UniqueScope());
        return new QuestModel.Occurrence(key, definition, Instant.EPOCH, Optional.empty());
    }
}
