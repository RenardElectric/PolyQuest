package polycube.polyquest.runtime;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.signal.QuestSignal;

/// Mutable progress for one player and one quest occurrence.
public final class QuestAttempt {
    private QuestModel.Occurrence occurrence;
    private final ConditionRuntime.Instance root;
    private QuestModel.AttemptStatus status = QuestModel.AttemptStatus.ACTIVE;
    private final long createdAtTick;
    private long lastUpdatedTick;

    public QuestAttempt(QuestModel.Occurrence occurrence, MinecraftServer server) {
        this.occurrence = occurrence;
        this.root = ConditionRuntime.create(occurrence.definition().condition(), new ConditionRuntime.CreationContext(server::getTickCount));
        this.createdAtTick = server.getTickCount();
        this.lastUpdatedTick = createdAtTick;
        refreshStatus();
    }

    public QuestModel.Occurrence occurrence() {
        return occurrence;
    }

    public QuestModel.AttemptStatus status() {
        return status;
    }

    /// Rebinds display-only data without replacing the condition tree, progress, or occurrence identity.
    public void updatePresentation(QuestModel.Occurrence currentOccurrence) {
        if (!occurrence.key().equals(currentOccurrence.key())) {
            throw new IllegalArgumentException("Cannot replace an attempt with another occurrence");
        }
        occurrence = currentOccurrence;
    }

    public ConditionRuntime.Update onSignal(QuestSignal signal, MinecraftServer server) {
        if (terminal()) {
            return ConditionRuntime.Update.NONE;
        }
        ConditionRuntime.Update update = root.onSignal(signal, new ConditionRuntime.EvaluationContext(server, signal.serverTick()));
        if (update.changed()) {
            lastUpdatedTick = signal.serverTick();
        }
        refreshStatus();
        return update;
    }

    public ConditionRuntime.Update tick(MinecraftServer server, long serverTick) {
        if (terminal()) {
            return ConditionRuntime.Update.NONE;
        }
        ConditionRuntime.Update update = root.tick(
                new ConditionRuntime.EvaluationContext(server, serverTick));
        if (update.changed()) {
            lastUpdatedTick = serverTick;
        }
        refreshStatus();
        return update;
    }

    public ConditionRuntime.ClaimPreparation prepareClaim(ServerPlayerContext context) {
        if (status == QuestModel.AttemptStatus.CLAIMED) {
            return ConditionRuntime.ClaimPreparation.blocked("Quest has already been claimed");
        }
        if (status == QuestModel.AttemptStatus.CLAIM_PENDING) {
            return ConditionRuntime.ClaimPreparation.blocked("Quest reward is already pending");
        }
        if (status == QuestModel.AttemptStatus.EXHAUSTED) {
            return ConditionRuntime.ClaimPreparation.blocked("Quest has no attempts remaining");
        }
        ConditionRuntime.ClaimPreparation preparation = root.prepareClaim(new ConditionRuntime.ClaimContext(context.server(), context.player(), context.serverTick()));
        refreshStatus();
        return preparation;
    }

    public void markPending() {
        status = QuestModel.AttemptStatus.CLAIM_PENDING;
    }

    public void markClaimed() {
        status = QuestModel.AttemptStatus.CLAIMED;
    }

    /// Re-derives readiness after claim compensation instead of blindly forcing an active state.
    public void markActiveAfterFailedClaim() {
        refreshStatus();
    }

    public JsonObject diagnostic() {
        JsonObject result = new JsonObject();
        result.addProperty("quest", occurrence.definition().id().toString());
        result.addProperty("occurrence", occurrence.key().persistentKey());
        result.addProperty("status", status.name());
        result.addProperty("created_at_tick", createdAtTick);
        result.addProperty("last_updated_tick", lastUpdatedTick);
        result.add("condition", root.diagnostic());
        return result;
    }

    private boolean terminal() {
        return status == QuestModel.AttemptStatus.CLAIMED
                || status == QuestModel.AttemptStatus.CLAIM_PENDING
                || status == QuestModel.AttemptStatus.EXHAUSTED;
    }

    /// Derives non-durable status from the root while preserving durable terminal states.
    private void refreshStatus() {
        if (status == QuestModel.AttemptStatus.CLAIMED || status == QuestModel.AttemptStatus.CLAIM_PENDING) {
            return;
        }
        if (root.exhausted()) {
            status = QuestModel.AttemptStatus.EXHAUSTED;
        } else if (root.completed()) {
            status = QuestModel.AttemptStatus.READY_TO_CLAIM;
        } else {
            status = QuestModel.AttemptStatus.ACTIVE;
        }
    }

    public record ServerPlayerContext(MinecraftServer server, net.minecraft.server.level.ServerPlayer player, long serverTick) {}
}
