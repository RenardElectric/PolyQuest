package polycube.polyquest.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import polycube.polyquest.condition.BuiltInConditions;
import polycube.polyquest.config.QuestConfig;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.RewardApi;
import polycube.polyquest.rotation.DailyRotationService;

final class QuestLedgerTest {
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
    void rerollingMissingDifficultyDoesNotMutateGeneration() {
        QuestLedger ledger = new QuestLedger();
        DailyRotationService rotation = new DailyRotationService(
                new QuestConfig(1L, ZoneId.of("UTC"), false, 30));

        assertFalse(rotation.reroll(QuestModel.Difficulty.EASY, QuestModel.Catalog.EMPTY, ledger));
        assertEquals(0, ledger.rotationGeneration(QuestModel.Difficulty.EASY));
    }

    private static QuestModel.Occurrence occurrence(String path) {
        Identifier id = Identifier.fromNamespaceAndPath("polyquest_test", path);
        QuestModel.Definition definition = new QuestModel.Definition(
                id,
                QuestModel.Availability.UNIQUE,
                Optional.empty(),
                "Test",
                List.of(),
                new BuiltInConditions.ExplicitSignal(id, 1),
                new RewardApi.Plan(Optional.empty(), List.of()),
                "behavior",
                "presentation");
        QuestModel.Key key = new QuestModel.Key(id, definition.behaviorHash(), new QuestModel.UniqueScope());
        return new QuestModel.Occurrence(key, definition, Instant.EPOCH, Optional.empty());
    }
}
