package polycube.polyquest.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.time.Instant;
import java.time.Clock;
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
    void unclaimedCompletionsSurviveWorldSaveAndReload() {
        UUID playerId = UUID.randomUUID();
        var saved = JsonParser.parseString("""
                { "ready": { "%s": {
                    "polyquest_test:unique": "v1",
                    "polyquest_test:daily": "v1"
                } } }
                """.formatted(playerId));

        QuestLedger loaded = QuestLedger.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        var reloaded = QuestLedger.CODEC.encodeStart(JsonOps.INSTANCE, loaded).getOrThrow().getAsJsonObject();

        assertTrue(reloaded.has("ready"), "An unclaimed completion must remain durable across a restart");
        var playerReady = reloaded.getAsJsonObject("ready").getAsJsonObject(playerId.toString());
        assertEquals(2, playerReady.size());
        assertTrue(playerReady.has("polyquest_test:unique"));
        assertTrue(playerReady.has("polyquest_test:daily"));
    }

    @Test
    void readyCompletionCanBeRestoredAndClaimedAfterNbtRoundTrip() {
        UUID playerId = UUID.randomUUID();
        QuestModel.Occurrence occurrence = occurrence("ready_to_claim");
        QuestLedger ledger = new QuestLedger();
        ledger.markReady(playerId, occurrence);

        var nbt = QuestLedger.CODEC.encodeStart(NbtOps.INSTANCE, ledger).getOrThrow();
        QuestLedger restored = QuestLedger.CODEC.parse(NbtOps.INSTANCE, nbt).getOrThrow();

        assertTrue(restored.isReady(playerId, occurrence));
        assertFalse(restored.isClaimed(playerId, occurrence.key()));

        QuestLedger.PendingTransaction pending = restored.beginClaim(playerId, occurrence, List.of());
        restored.complete(pending);
        assertTrue(restored.isClaimed(playerId, occurrence.key()));
        assertFalse(restored.isReady(playerId, occurrence));
    }

    @Test
    void readyCompletionIsInvalidatedByBehaviorChangeOrReset() {
        UUID playerId = UUID.randomUUID();
        QuestModel.Occurrence before = occurrence("ready_behavior", "before");
        QuestModel.Occurrence after = occurrence("ready_behavior", "after");
        QuestLedger ledger = new QuestLedger();
        ledger.markReady(playerId, before);

        assertTrue(ledger.retainReadyCompletions(Map.of(before.definition().id(), before.definition().behaviorHash())).isEmpty());
        assertTrue(ledger.isReady(playerId, before));
        assertFalse(ledger.isReady(playerId, after));
        assertEquals(1, ledger.retainReadyCompletions(Map.of(after.definition().id(), after.definition().behaviorHash())).size());
        assertFalse(ledger.isReady(playerId, before));

        ledger.markReady(playerId, after);
        assertTrue(ledger.resetClaim(playerId, after.key()));
        assertFalse(ledger.isReady(playerId, after));
    }

    @Test
    void pendingRewardIsNotReportedAsLostReadyProgress() {
        UUID playerId = UUID.randomUUID();
        QuestModel.Occurrence occurrence = occurrence("ready_with_pending_reward");
        QuestLedger ledger = new QuestLedger();
        ledger.markReady(playerId, occurrence);
        ledger.beginClaim(playerId, occurrence, List.of());

        assertTrue(ledger.retainReadyCompletions(Map.of()).isEmpty());
        assertTrue(ledger.hasPending(playerId, occurrence.key()));
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
                        QuestModel.Difficulty.HARD,
                        Instant.parse("2026-09-28T00:00:00Z")));

        assertEquals(
                "polyquest_test:daily_identity|daily:hard:1790553600000",
                key.persistentKey());
    }

    @Test
    void rerollingMissingDifficultyDoesNotChangeTheSavedSlot() {
        QuestLedger ledger = new QuestLedger();
        DailyRotationService rotation = new DailyRotationService(
                config(), Clock.fixed(Instant.parse("2026-09-21T01:00:00Z"), ZoneId.of("UTC")));
        rotation.refresh(QuestModel.Catalog.EMPTY, ledger);
        var before = ledger.rotationSlot(QuestModel.Difficulty.EASY);

        assertEquals(DailyRotationService.RerollResult.NO_CANDIDATES,
                rotation.reroll(QuestModel.Difficulty.EASY, Optional.empty(), QuestModel.Catalog.EMPTY, ledger));
        assertEquals(before, ledger.rotationSlot(QuestModel.Difficulty.EASY));
    }

    @Test
    void emptyRotationStoresDeadlinesWithoutAnnouncingChanges() {
        QuestLedger ledger = new QuestLedger();
        DailyRotationService rotation = new DailyRotationService(
                config(), Clock.fixed(Instant.parse("2026-09-21T01:00:00Z"), ZoneId.of("UTC")));

        DailyRotationService.RefreshResult first = rotation.refresh(QuestModel.Catalog.EMPTY, ledger);
        DailyRotationService.RefreshResult repeated = rotation.refresh(QuestModel.Catalog.EMPTY, ledger);

        assertFalse(first.assignmentChanged());
        assertFalse(repeated.assignmentChanged());
        assertEquals("2026-09-21T12:00:00Z", ledger.rotationSlot(QuestModel.Difficulty.EASY)
                .orElseThrow().nextRoll().orElseThrow());
    }

    @Test
    void rerollClearsOldDailyStateAndAvoidsThePreviousQuest() {
        QuestLedger ledger = new QuestLedger();
        DailyRotationService rotation = new DailyRotationService(config(),
                Clock.fixed(Instant.parse("2026-09-21T01:00:00Z"), ZoneId.of("UTC")));
        QuestModel.Definition first = daily("first");
        QuestModel.Definition second = daily("second");
        var unique = occurrence("unrelated_unique_ready");
        QuestModel.Catalog catalog = new QuestModel.Catalog(
                Map.of(first.id(), first, second.id(), second, unique.definition().id(), unique.definition()), Map.of());
        rotation.refresh(catalog, ledger);
        var before = rotation.current().slots().get(QuestModel.Difficulty.EASY);
        UUID player = UUID.randomUUID();
        ledger.markReady(player, before);
        ledger.markReady(player, unique);
        var pending = ledger.beginClaim(player, before, List.of());
        ledger.markCostsCommitted(pending);
        ledger.markRotationNotified(player);

        assertEquals(DailyRotationService.RerollResult.CHANGED,
                rotation.reroll(QuestModel.Difficulty.EASY, Optional.empty(), catalog, ledger));
        var after = rotation.current().slots().get(QuestModel.Difficulty.EASY);
        assertFalse(before.definition().id().equals(after.definition().id()));
        assertFalse(ledger.isReady(player, before));
        assertTrue(ledger.isReady(player, unique));
        assertTrue(ledger.pendingFor(player).isEmpty());
        assertTrue(ledger.markRotationNotified(player));
        assertEquals(before.availableUntil(), after.availableUntil());

        assertEquals(DailyRotationService.RerollResult.CHANGED,
                rotation.reroll(QuestModel.Difficulty.EASY, Optional.of(before.definition().id()), catalog, ledger));
        assertFalse(ledger.isClaimed(player, rotation.current().slots().get(QuestModel.Difficulty.EASY).key()));
    }

    @Test
    void rotationNotificationReceiptPersistsUntilAChange() {
        QuestLedger ledger = new QuestLedger();
        UUID playerId = UUID.randomUUID();
        assertFalse(ledger.markRotationNotified(playerId));
        ledger.setRotationSlot(QuestModel.Difficulty.EASY, Optional.empty(), Instant.parse("2026-09-22T00:00:00Z"), false);
        assertFalse(ledger.markRotationNotified(playerId));
        ledger.setRotationSlot(QuestModel.Difficulty.EASY, Optional.empty(), Instant.parse("2026-09-22T00:00:00Z"), true);

        assertTrue(ledger.markRotationNotified(playerId));
        assertFalse(ledger.markRotationNotified(playerId));

        var encoded = QuestLedger.CODEC.encodeStart(NbtOps.INSTANCE, ledger).getOrThrow();
        QuestLedger reloaded = QuestLedger.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertFalse(reloaded.markRotationNotified(playerId));

        reloaded.setRotationSlot(QuestModel.Difficulty.EASY, Optional.empty(), Instant.parse("2026-09-23T00:00:00Z"), true);
        assertTrue(reloaded.markRotationNotified(playerId));
    }

    @Test
    void emptySlotChangeStillLeavesAnOfflineCatchupNoticeAfterReload() {
        QuestLedger ledger = new QuestLedger();
        ledger.setRotationSlot(QuestModel.Difficulty.EASY, Optional.empty(),
                Instant.parse("2026-09-22T00:00:00Z"), true);

        var encoded = QuestLedger.CODEC.encodeStart(NbtOps.INSTANCE, ledger).getOrThrow();
        QuestLedger reloaded = QuestLedger.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        UUID absentPlayer = UUID.randomUUID();
        assertTrue(reloaded.markRotationNotified(absentPlayer));
        assertFalse(reloaded.markRotationNotified(absentPlayer));
    }

    @Test
    void localScheduleKeepsEvenHoursAndWeeklyMondaysAcrossDaylightSaving() {
        ZoneId zone = ZoneId.of("Europe/Zurich");
        QuestConfig config = new QuestConfig(1L, zone, Map.of(
                QuestModel.Difficulty.EASY, 2,
                QuestModel.Difficulty.HARD, 168));
        QuestLedger ledger = new QuestLedger();
        new DailyRotationService(config, Clock.fixed(Instant.parse("2026-09-21T10:30:00Z"), zone))
                .refresh(QuestModel.Catalog.EMPTY, ledger);

        assertEquals("2026-09-21T12:00:00Z", ledger.rotationSlot(QuestModel.Difficulty.EASY)
                .orElseThrow().nextRoll().orElseThrow());
        assertEquals("2026-09-27T22:00:00Z", ledger.rotationSlot(QuestModel.Difficulty.HARD)
                .orElseThrow().nextRoll().orElseThrow());

        QuestConfig daily = new QuestConfig(1L, zone, Map.of(QuestModel.Difficulty.EASY, 24));
        QuestLedger spring = new QuestLedger();
        new DailyRotationService(daily, Clock.fixed(Instant.parse("2026-03-28T23:30:00Z"), zone))
                .refresh(QuestModel.Catalog.EMPTY, spring);
        assertEquals("2026-03-29T22:00:00Z", spring.rotationSlot(QuestModel.Difficulty.EASY)
                .orElseThrow().nextRoll().orElseThrow());

        QuestLedger autumn = new QuestLedger();
        new DailyRotationService(daily, Clock.fixed(Instant.parse("2026-10-24T22:30:00Z"), zone))
                .refresh(QuestModel.Catalog.EMPTY, autumn);
        assertEquals("2026-10-25T23:00:00Z", autumn.rotationSlot(QuestModel.Difficulty.EASY)
                .orElseThrow().nextRoll().orElseThrow());
    }

    @Test
    void scheduledBoundaryStartsFreshQuestWithoutRepeatingAnAlternative() {
        QuestLedger ledger = new QuestLedger();
        QuestModel.Definition first = daily("schedule_first");
        QuestModel.Definition second = daily("schedule_second");
        QuestModel.Catalog catalog = new QuestModel.Catalog(Map.of(first.id(), first, second.id(), second), Map.of());
        var beforeRotation = new DailyRotationService(config(),
                Clock.fixed(Instant.parse("2026-09-21T01:00:00Z"), ZoneId.of("UTC")));
        beforeRotation.refresh(catalog, ledger);
        QuestModel.Occurrence before = beforeRotation.current().slots().get(QuestModel.Difficulty.EASY);
        UUID player = UUID.randomUUID();
        ledger.markReady(player, before);

        var afterRotation = new DailyRotationService(config(),
                Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneId.of("UTC")));
        var result = afterRotation.refresh(catalog, ledger);
        QuestModel.Occurrence after = afterRotation.current().slots().get(QuestModel.Difficulty.EASY);

        assertTrue(result.resetSlots().contains(QuestModel.Difficulty.EASY));
        assertNotEquals(before.definition().id(), after.definition().id());
        assertFalse(ledger.isReady(player, before));
        assertNotEquals(before.key(), after.key());
    }

    @Test
    void removedQuestClearsItsSlotAndIsReplacedWhenCandidatesReturn() {
        QuestLedger ledger = new QuestLedger();
        QuestModel.Definition removed = daily("removed");
        QuestModel.Definition replacement = daily("replacement");
        Clock now = Clock.fixed(Instant.parse("2026-09-21T01:00:00Z"), ZoneId.of("UTC"));
        DailyRotationService rotation = new DailyRotationService(config(), now);
        rotation.refresh(new QuestModel.Catalog(Map.of(removed.id(), removed), Map.of()), ledger);
        var original = rotation.current().slots().get(QuestModel.Difficulty.EASY);
        UUID player = UUID.randomUUID();
        ledger.markReady(player, original);

        assertTrue(rotation.refresh(QuestModel.Catalog.EMPTY, ledger).assignmentChanged());
        assertFalse(rotation.current().slots().containsKey(QuestModel.Difficulty.EASY));
        assertFalse(ledger.isReady(player, original));
        assertTrue(ledger.rotationSlot(QuestModel.Difficulty.EASY).orElseThrow().quest().isEmpty());

        var restored = new QuestModel.Catalog(Map.of(replacement.id(), replacement), Map.of());
        assertTrue(rotation.refresh(restored, ledger).assignmentChanged());
        assertEquals(replacement.id(), rotation.current().slots().get(QuestModel.Difficulty.EASY).definition().id());
        assertEquals(original.availableUntil(), rotation.current().slots().get(QuestModel.Difficulty.EASY).availableUntil());
    }

    @Test
    void oneCandidateRerollResetsStateEvenThoughTheOccurrenceKeyIsUnchanged() {
        QuestLedger ledger = new QuestLedger();
        QuestModel.Definition only = daily("only");
        QuestModel.Catalog catalog = new QuestModel.Catalog(Map.of(only.id(), only), Map.of());
        DailyRotationService rotation = new DailyRotationService(config(),
                Clock.fixed(Instant.parse("2026-09-21T01:00:00Z"), ZoneId.of("UTC")));
        rotation.refresh(catalog, ledger);
        var occurrence = rotation.current().slots().get(QuestModel.Difficulty.EASY);
        UUID player = UUID.randomUUID();
        ledger.complete(ledger.beginClaim(player, occurrence, List.of()));

        assertEquals(DailyRotationService.RerollResult.CHANGED,
                rotation.reroll(QuestModel.Difficulty.EASY, Optional.empty(), catalog, ledger));
        assertEquals(occurrence.key(), rotation.current().slots().get(QuestModel.Difficulty.EASY).key());
        assertFalse(ledger.isClaimed(player, occurrence.key()));
    }

    @Test
    void restoredDailyAssignmentsRequireAnEngineRefreshAfterWorldReopen() {
        QuestModel.Definition daily = daily("reopen_visible");
        QuestModel.Catalog catalog = new QuestModel.Catalog(Map.of(daily.id(), daily), Map.of());
        Clock now = Clock.fixed(Instant.parse("2026-09-21T01:00:00Z"), ZoneId.of("UTC"));
        QuestLedger originalLedger = new QuestLedger();
        DailyRotationService originalRotation = new DailyRotationService(config(), now);
        originalRotation.refresh(catalog, originalLedger);
        assertTrue(originalRotation.current().slots().containsKey(QuestModel.Difficulty.EASY));
        UUID player = UUID.randomUUID();
        originalLedger.markReady(player, originalRotation.current().slots().get(QuestModel.Difficulty.EASY));

        var saved = QuestLedger.CODEC.encodeStart(NbtOps.INSTANCE, originalLedger).getOrThrow();
        QuestLedger loadedLedger = QuestLedger.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        DailyRotationService reopenedRotation = new DailyRotationService(config(), now);
        var refresh = reopenedRotation.refresh(catalog, loadedLedger);

        assertEquals(daily.id(), reopenedRotation.current().slots().get(QuestModel.Difficulty.EASY).definition().id());
        assertTrue(refresh.assignmentChanged(), "The engine must rebuild availability after restoring saved slots");
        assertTrue(refresh.resetSlots().isEmpty(), "Restoring unchanged slots must not erase progress or notify players");
        assertTrue(loadedLedger.isReady(player, reopenedRotation.current().slots().get(QuestModel.Difficulty.EASY)));
        assertFalse(reopenedRotation.refresh(catalog, loadedLedger).assignmentChanged());
    }

    @Test
    void changedIntervalOrMalformedSavedSlotReplacesItImmediately() {
        QuestLedger ledger = new QuestLedger();
        QuestModel.Definition only = daily("current");
        QuestModel.Catalog catalog = new QuestModel.Catalog(Map.of(only.id(), only), Map.of());
        Clock now = Clock.fixed(Instant.parse("2026-09-21T01:00:00Z"), ZoneId.of("UTC"));
        DailyRotationService original = new DailyRotationService(config(), now);
        original.refresh(catalog, ledger);
        var oldOccurrence = original.current().slots().get(QuestModel.Difficulty.EASY);
        UUID player = UUID.randomUUID();
        ledger.markReady(player, oldOccurrence);

        QuestConfig changed = new QuestConfig(1L, ZoneId.of("UTC"),
                Map.of(QuestModel.Difficulty.EASY, 24));
        var restarted = new DailyRotationService(changed, now);
        assertTrue(restarted.refresh(catalog, ledger).assignmentChanged());
        assertFalse(ledger.isReady(player, oldOccurrence));
        assertEquals("2026-09-22T00:00:00Z", ledger.rotationSlot(QuestModel.Difficulty.EASY)
                .orElseThrow().nextRoll().orElseThrow());

        QuestLedger malformed = QuestLedger.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                { "rotation": { "easy": { "quest": "bad id", "next_roll": "not a time" } } }
                """)).getOrThrow();
        assertTrue(new DailyRotationService(changed, now).refresh(catalog, malformed).assignmentChanged());
        assertEquals(Optional.of(only.id().toString()), malformed.rotationSlot(QuestModel.Difficulty.EASY)
                .orElseThrow().quest());
    }

    @Test
    void returningToClaimedQuestWithinOneFrameIsASeparateOpportunity() {
        QuestLedger ledger = new QuestLedger();
        QuestModel.Definition first = daily("claimed_first");
        QuestModel.Definition second = daily("claimed_second");
        QuestModel.Catalog catalog = new QuestModel.Catalog(Map.of(first.id(), first, second.id(), second), Map.of());
        DailyRotationService rotation = new DailyRotationService(config(),
                Clock.fixed(Instant.parse("2026-09-21T01:00:00Z"), ZoneId.of("UTC")));
        rotation.refresh(catalog, ledger);
        var original = rotation.current().slots().get(QuestModel.Difficulty.EASY);
        UUID player = UUID.randomUUID();
        ledger.complete(ledger.beginClaim(player, original, List.of()));
        assertTrue(ledger.isClaimed(player, original.key()));

        Identifier other = original.definition().id().equals(first.id()) ? second.id() : first.id();
        assertEquals(DailyRotationService.RerollResult.CHANGED,
                rotation.reroll(QuestModel.Difficulty.EASY, Optional.of(other), catalog, ledger));
        assertEquals(DailyRotationService.RerollResult.CHANGED,
                rotation.reroll(QuestModel.Difficulty.EASY, Optional.of(original.definition().id()), catalog, ledger));
        var returned = rotation.current().slots().get(QuestModel.Difficulty.EASY);
        assertEquals(original.key(), returned.key());
        assertFalse(ledger.isClaimed(player, returned.key()));
        assertEquals(DailyRotationService.RerollResult.ALREADY_SELECTED,
                rotation.reroll(QuestModel.Difficulty.EASY, Optional.of(original.definition().id()), catalog, ledger));
    }

    private static QuestConfig config() {
        return new QuestConfig(1L, ZoneId.of("UTC"), Map.of());
    }

    private static QuestModel.Definition daily(String path) {
        Identifier id = Identifier.fromNamespaceAndPath("polyquest_test", path);
        return new QuestModel.Definition(id, QuestModel.Availability.DAILY,
                Optional.of(QuestModel.Difficulty.EASY), path, List.of(), Items.SUNFLOWER,
                new BuiltInConditions.ExplicitSignal(id, 1),
                new RewardApi.Plan(Optional.empty(), List.of()), "behavior");
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
