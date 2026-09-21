package polycube.polyquest.claim;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.persistence.QuestLedger;
import polycube.polyquest.resource.QuestCatalogManager;
import polycube.polyquest.reward.RewardApi;
import polycube.polyquest.runtime.ConditionRuntime;
import polycube.polyquest.runtime.QuestAttempt;
import polycube.polyquest.runtime.QuestEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Validates a claim, commits condition costs, and grants a durably tracked reward bundle.
public final class QuestClaimService {
    private final MinecraftServer server;
    private final QuestEngine engine;
    private final QuestLedger ledger;
    private final QuestCatalogManager catalogs;
    private final Runnable questChanged;

    public QuestClaimService(
            MinecraftServer server, QuestEngine engine,
            QuestLedger ledger, QuestCatalogManager catalogs, Runnable questChanged
    ) {
        this.server = server;
        this.engine = engine;
        this.ledger = ledger;
        this.catalogs = catalogs;
        this.questChanged = questChanged;
    }

    /// Runs the normal claim transaction and checkpoints each stage for safe reward retries.
    public ClaimResult claim(ServerPlayer player, Identifier questId) {
        Optional<QuestModel.Occurrence> occurrenceResult = engine.findOccurrence(questId);
        if (occurrenceResult.isEmpty()) return ClaimResult.failure("That quest is not currently available");

        QuestModel.Occurrence occurrence = occurrenceResult.get();
        if (ledger.isClaimed(player.getUUID(), occurrence.key())) return ClaimResult.failure("You have already claimed this quest");

        Optional<QuestLedger.PendingTransaction> existing = ledger.pendingFor(player.getUUID()).stream()
                .filter(transaction -> transaction.occurrenceKey().equals(occurrence.key().persistentKey()))
                .findFirst();
        if (existing.isPresent()) return retry(existing.get(), player);

        QuestAttempt attempt = engine.attempt(player.getUUID(), occurrence);
        ConditionRuntime.ClaimPreparation preparation = attempt.prepareClaim(new QuestAttempt.ServerPlayerContext(server, player, server.getTickCount()));
        if (!preparation.ready()) return ClaimResult.failure(preparation.failure());

        List<RewardApi.Definition> rewards = resolveRewards(occurrence.definition());
        if (rewards.isEmpty()) return ClaimResult.failure("This quest has no resolvable rewards");

        for (ConditionRuntime.ClaimOperation operation : preparation.operations()) {
            if (!operation.revalidate(new ConditionRuntime.ClaimContext(server, player, server.getTickCount()))) {
                return ClaimResult.failure("Claim requirements changed: " + operation.describe());
            }
        }

        QuestLedger.PendingTransaction transaction = ledger.beginClaim(player.getUUID(), occurrence, rewards);
        List<Runnable> rollbacks = new ArrayList<>();
        for (ConditionRuntime.ClaimOperation operation : preparation.operations()) {
            ConditionRuntime.CommitResult result = operation.commit(new ConditionRuntime.ClaimContext(server, player, server.getTickCount()));
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
        boolean reverted = rewardResult.state() == ClaimState.PERMANENT_FAILURE && transaction.nextReward() == 0;
        if (reverted) {
            runRollbacks(rollbacks);
            ledger.cancel(transaction);
            attempt.markActiveAfterFailedClaim();
        } else if (rewardResult.state() == ClaimState.SUCCESS) {
            attempt.markClaimed();
        }
        if (!reverted) questChanged.run();
        return rewardResult;
    }

    /// Administrator path that grants configured rewards without objective costs.
    /// The normal durable transaction path is retained.
    public ClaimResult forceClaim(ServerPlayer player, Identifier questId) {
        Optional<QuestModel.Occurrence> occurrenceResult = engine.findOccurrence(questId);
        if (occurrenceResult.isEmpty()) return ClaimResult.failure("That quest is not currently available");

        QuestModel.Occurrence occurrence = occurrenceResult.get();
        if (ledger.isClaimed(player.getUUID(), occurrence.key())) return ClaimResult.failure("Quest has already been claimed");

        Optional<QuestLedger.PendingTransaction> existing = ledger.pendingFor(player.getUUID()).stream()
                .filter(transaction -> transaction.occurrenceKey().equals(occurrence.key().persistentKey()))
                .findFirst();
        if (existing.isPresent()) return retry(existing.get(), player);

        List<RewardApi.Definition> rewards = resolveRewards(occurrence.definition());
        if (rewards.isEmpty()) return ClaimResult.failure("This quest has no resolvable rewards");

        QuestLedger.PendingTransaction transaction = ledger.beginClaim(player.getUUID(), occurrence, rewards);
        ledger.markCostsCommitted(transaction);
        QuestAttempt attempt = engine.attempt(player.getUUID(), occurrence);
        attempt.markPending();
        ClaimResult result = grantRemaining(transaction, player);
        if (result.state() == ClaimState.SUCCESS) {
            attempt.markClaimed();
        }
        questChanged.run();
        return result;
    }

    /// Resumes a cost-committed reward transaction and cancels ambiguous prepared transactions.
    public ClaimResult retry(QuestLedger.PendingTransaction transaction, ServerPlayer player) {
        if (!transaction.playerId().equals(player.getUUID())) {
            return ClaimResult.failure("Transaction belongs to another player");
        }
        if (transaction.state() == QuestLedger.TransactionState.PREPARED) {
            // No durable cost-commit marker exists. Cancel rather than risk granting a reward
            // for an operation that may never have consumed its inputs.
            ledger.cancel(transaction);
            engine.findOccurrence(transaction.questId())
                    .flatMap(occurrence -> engine.existingAttempt(player.getUUID(), occurrence.key()))
                    .ifPresent(QuestAttempt::markActiveAfterFailedClaim);
            questChanged.run();
            return ClaimResult.failure("Interrupted claim was cancelled; submit the claim again");
        }
        if (transaction.state() == QuestLedger.TransactionState.FAILED) {
            return ClaimResult.failure("Reward transaction needs an administrator retry");
        }
        ClaimResult result = grantRemaining(transaction, player);
        if (result.state() == ClaimState.SUCCESS) {
            engine.findOccurrence(transaction.questId())
                    .flatMap(occurrence -> engine.existingAttempt(player.getUUID(), occurrence.key()))
                    .ifPresent(QuestAttempt::markClaimed);
        }
        questChanged.run();
        return result;
    }

    /// Retries a player's pending rewards, optionally reopening administrator-reviewed failures.
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

    private List<RewardApi.Definition> resolveRewards(QuestModel.Definition quest) {
        if (quest.rewards().profile().isPresent()) {
            return Optional.ofNullable(catalogs.current()
                            .rewardProfiles()
                            .get(quest.rewards().profile().get()))
                    .map(RewardApi.Profile::rewards)
                    .orElse(List.of());
        }
        return quest.rewards().inlineRewards();
    }

    /// Grants rewards from the durable cursor and checkpoints after every successful grant.
    private ClaimResult grantRemaining(
            QuestLedger.PendingTransaction transaction,
            ServerPlayer player
    ) {
        while (transaction.nextReward() < transaction.rewards().size()) {
            int rewardIndex = transaction.nextReward();
            RewardApi.Definition reward = transaction.rewards().get(rewardIndex);
            RewardApi.GrantResult grant;
            try {
                grant = RewardApi.grant(
                        reward,
                        new RewardApi.Context(
                                server, transaction.playerId(), player,
                                transaction.id() + ":" + rewardIndex
                        )
                );
            } catch (RuntimeException exception) {
                String message = "Reward threw an exception; transaction requires administrator review";
                PolyQuest.LOGGER.error("Reward {} failed unexpectedly in quest transaction {}", reward.type().id(), transaction.id(), exception);
                ledger.markFailed(transaction, message);
                return new ClaimResult(ClaimState.FAILURE, message);
            }
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

    /// Runs claim-cost compensations in reverse commit order without hiding later rollbacks.
    private static void runRollbacks(List<Runnable> rollbacks) {
        for (int index = rollbacks.size() - 1; index >= 0; index--) {
            try {
                rollbacks.get(index).run();
            } catch (RuntimeException exception) {
                PolyQuest.LOGGER.error("Quest claim rollback failed", exception);
            }
        }
    }

    /// Durable state of a claim transaction, including the reward grant result.
    public enum ClaimState {
        SUCCESS,
        PENDING,
        PERMANENT_FAILURE,
        FAILURE
    }

    /// Result of a claim attempt, including the durable state of the transaction.
    public record ClaimResult(ClaimState state, String message) {
        public static ClaimResult failure(String message) {
            return new ClaimResult(ClaimState.FAILURE, message);
        }

        public boolean successful() {
            return state == ClaimState.SUCCESS;
        }
    }
}
