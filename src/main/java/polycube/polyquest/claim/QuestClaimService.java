package polycube.polyquest.claim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import polycube.polyquest.config.QuestConfig;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.persistence.QuestLedger;
import polycube.polyquest.reward.RewardApi;
import polycube.polyquest.runtime.ConditionRuntime;
import polycube.polyquest.runtime.QuestAttempt;
import polycube.polyquest.runtime.QuestEngine;

/// Validates a claim, commits condition costs, and grants an idempotency-keyed reward bundle.
public final class QuestClaimService {
    private final MinecraftServer server;
    private final QuestConfig config;
    private final QuestEngine engine;
    private final QuestLedger ledger;

    public QuestClaimService(
            MinecraftServer server,
            QuestConfig config,
            QuestEngine engine,
            QuestLedger ledger) {
        this.server = server;
        this.config = config;
        this.engine = engine;
        this.ledger = ledger;
    }

    public ClaimResult claim(ServerPlayer player, Identifier questId) {
        Optional<QuestModel.Occurrence> occurrenceResult =
                engine.findOccurrence(player.getUUID(), questId);
        if (occurrenceResult.isEmpty()) {
            return ClaimResult.failure("That quest is not currently available");
        }
        QuestModel.Occurrence occurrence = occurrenceResult.get();
        if (ledger.isClaimed(player.getUUID(), occurrence.key())) {
            return ClaimResult.failure("You have already claimed this quest");
        }
        if (!isAtQuestGiver(player)) {
            return ClaimResult.failure("Return to the world spawn quest giver to claim this quest");
        }

        Optional<QuestLedger.PendingTransaction> existing = ledger.pendingFor(player.getUUID()).stream()
                .filter(transaction -> transaction.occurrenceKey().equals(occurrence.key().persistentKey()))
                .findFirst();
        if (existing.isPresent()) {
            return retry(existing.get(), player);
        }

        QuestAttempt attempt = engine.attempt(player.getUUID(), occurrence);
        ConditionRuntime.ClaimPreparation preparation = attempt.prepareClaim(
                new QuestAttempt.ServerPlayerContext(server, player, server.getTickCount()));
        if (!preparation.ready()) {
            return ClaimResult.failure(preparation.failure());
        }

        List<RewardApi.Definition> rewards = resolveRewards(occurrence.definition());
        if (rewards.isEmpty()) {
            return ClaimResult.failure("This quest has no resolvable rewards");
        }

        for (ConditionRuntime.ClaimOperation operation : preparation.operations()) {
            if (!operation.revalidate(new ConditionRuntime.ClaimContext(
                    server, player, server.getTickCount()))) {
                return ClaimResult.failure("Claim requirements changed: " + operation.describe());
            }
        }

        QuestLedger.PendingTransaction transaction = ledger.beginClaim(
                player.getUUID(), occurrence, rewards);
        List<Runnable> rollbacks = new ArrayList<>();
        for (ConditionRuntime.ClaimOperation operation : preparation.operations()) {
            ConditionRuntime.CommitResult result = operation.commit(new ConditionRuntime.ClaimContext(
                    server, player, server.getTickCount()));
            if (!result.success()) {
                runRollbacks(rollbacks);
                ledger.cancel(transaction);
                attempt.markActiveAfterFailedClaim();
                return ClaimResult.failure(result.failure());
            }
            rollbacks.add(result.rollback());
        }

        ledger.markCostsCommitted(transaction);
        attempt.markPending();
        ClaimResult rewardResult = grantRemaining(transaction, player);
        if (rewardResult.state() == ClaimState.PERMANENT_FAILURE && transaction.nextReward() == 0) {
            runRollbacks(rollbacks);
            ledger.cancel(transaction);
            attempt.markActiveAfterFailedClaim();
        } else if (rewardResult.state() == ClaimState.SUCCESS) {
            attempt.markClaimed();
        }
        return rewardResult;
    }

    /// Administrator path that grants the configured rewards without objective costs
    /// or the spawn-distance requirement. The normal durable transaction path is retained.
    public ClaimResult forceClaim(ServerPlayer player, Identifier questId) {
        Optional<QuestModel.Occurrence> occurrenceResult =
                engine.findOccurrence(player.getUUID(), questId);
        if (occurrenceResult.isEmpty()) {
            return ClaimResult.failure("That quest is not currently available");
        }
        QuestModel.Occurrence occurrence = occurrenceResult.get();
        if (ledger.isClaimed(player.getUUID(), occurrence.key())) {
            return ClaimResult.failure("Quest has already been claimed");
        }
        Optional<QuestLedger.PendingTransaction> existing = ledger.pendingFor(player.getUUID()).stream()
                .filter(transaction -> transaction.occurrenceKey().equals(occurrence.key().persistentKey()))
                .findFirst();
        if (existing.isPresent()) {
            return retry(existing.get(), player);
        }
        List<RewardApi.Definition> rewards = resolveRewards(occurrence.definition());
        if (rewards.isEmpty()) {
            return ClaimResult.failure("This quest has no resolvable rewards");
        }
        QuestLedger.PendingTransaction transaction = ledger.beginClaim(
                player.getUUID(), occurrence, rewards);
        ledger.markCostsCommitted(transaction);
        QuestAttempt attempt = engine.attempt(player.getUUID(), occurrence);
        attempt.markPending();
        ClaimResult result = grantRemaining(transaction, player);
        if (result.state() == ClaimState.SUCCESS) {
            attempt.markClaimed();
        }
        return result;
    }

    public ClaimResult retry(QuestLedger.PendingTransaction transaction, ServerPlayer player) {
        if (!transaction.playerId().equals(player.getUUID())) {
            return ClaimResult.failure("Transaction belongs to another player");
        }
        if (transaction.state() == QuestLedger.TransactionState.PREPARED) {
            // No durable cost-commit marker exists. Cancel rather than risk granting a reward
            // for an operation that may never have consumed its inputs.
            ledger.cancel(transaction);
            return ClaimResult.failure("Interrupted claim was cancelled; submit the claim again");
        }
        if (transaction.state() == QuestLedger.TransactionState.FAILED) {
            return ClaimResult.failure("Reward transaction needs an administrator retry");
        }
        ClaimResult result = grantRemaining(transaction, player);
        if (result.state() == ClaimState.SUCCESS) {
            engine.findOccurrence(player.getUUID(), Identifier.parse(transaction.questId()))
                    .flatMap(occurrence -> engine.existingAttempt(player.getUUID(), occurrence.key()))
                    .ifPresent(QuestAttempt::markClaimed);
        }
        return result;
    }

    public List<ClaimResult> retryPending(ServerPlayer player, boolean includeFailed) {
        List<ClaimResult> results = new ArrayList<>();
        for (QuestLedger.PendingTransaction transaction : ledger.pendingFor(player.getUUID())) {
            if (transaction.state() == QuestLedger.TransactionState.FAILED && !includeFailed) {
                continue;
            }
            if (transaction.state() == QuestLedger.TransactionState.FAILED) {
                ledger.markRetryable(transaction, "Administrator requested retry");
            }
            results.add(retry(transaction, player));
        }
        return List.copyOf(results);
    }

    public boolean isAtQuestGiver(ServerPlayer player) {
        if (config.claimRadius <= 0.0) {
            return true;
        }
        if (player.level() != server.overworld()) {
            return false;
        }
        BlockPos spawn = server.overworld().getRespawnData().pos();
        return player.position().distanceToSqr(Vec3.atCenterOf(spawn))
                <= config.claimRadius * config.claimRadius;
    }

    private List<RewardApi.Definition> resolveRewards(QuestModel.Definition quest) {
        if (quest.rewards().profile().isPresent()) {
            RewardApi.Profile profile = polycube.polyquest.PolyQuest.catalogs()
                    .current()
                    .rewardProfiles()
                    .get(quest.rewards().profile().get());
            return profile == null ? List.of() : profile.rewards();
        }
        return quest.rewards().inlineRewards();
    }

    private ClaimResult grantRemaining(
            QuestLedger.PendingTransaction transaction,
            ServerPlayer onlinePlayer) {
        while (transaction.nextReward() < transaction.rewards().size()) {
            int rewardIndex = transaction.nextReward();
            RewardApi.Definition reward = transaction.rewards().get(rewardIndex);
            RewardApi.GrantResult grant = RewardApi.grant(
                    reward,
                    new RewardApi.Context(
                            server,
                            transaction.playerId(),
                            onlinePlayer,
                            transaction.id() + ":" + rewardIndex));
            if (grant.state() == RewardApi.GrantResult.State.RETRY_LATER) {
                ledger.markRetryable(transaction, grant.message());
                return new ClaimResult(ClaimState.PENDING, grant.message());
            }
            if (grant.state() == RewardApi.GrantResult.State.PERMANENT_FAILURE) {
                ledger.markFailed(transaction, grant.message());
                return new ClaimResult(ClaimState.PERMANENT_FAILURE, grant.message());
            }
            ledger.advanceReward(transaction);
        }
        ledger.complete(transaction);
        return new ClaimResult(ClaimState.SUCCESS, "Quest claimed successfully");
    }

    private static void runRollbacks(List<Runnable> rollbacks) {
        Collections.reverse(rollbacks);
        rollbacks.forEach(Runnable::run);
    }

    public enum ClaimState {
        SUCCESS,
        PENDING,
        PERMANENT_FAILURE,
        FAILURE
    }

    public record ClaimResult(ClaimState state, String message) {
        public static ClaimResult failure(String message) {
            return new ClaimResult(ClaimState.FAILURE, message);
        }

        public boolean successful() {
            return state == ClaimState.SUCCESS;
        }
    }
}
