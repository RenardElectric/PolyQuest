package polycube.polyquest.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.signal.QuestSignal;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/// Mutable progress for one player and one quest occurrence.
public final class QuestAttempt {
    private static final int PROGRESS_FORMAT = 1;
    private QuestModel.Occurrence occurrence;
    private final ConditionRuntime.Instance root;
    private QuestModel.AttemptStatus status = QuestModel.AttemptStatus.ACTIVE;
    private final long createdAtTick;
    private long lastUpdatedTick;
    private boolean progressed;
    private boolean restoredReady;
    private final Set<Identifier> observedAdvancements = new HashSet<>();

    public QuestAttempt(
            UUID playerId, QuestModel.Occurrence occurrence,
            MinecraftServer server, ConditionRuntime.CriterionRegistrar criteria
    ) {
        this(occurrence, ConditionRuntime.create(
                occurrence.definition().condition(),
                new ConditionRuntime.CreationContext(server::getTickCount, playerId, criteria)),
                server.getTickCount());
    }

    /// Narrow construction seam for testing a saved completion without a running Minecraft server.
    QuestAttempt(QuestModel.Occurrence occurrence, ConditionRuntime.Instance root, long createdAtTick) {
        this.occurrence = occurrence;
        this.root = root;
        this.createdAtTick = createdAtTick;
        this.lastUpdatedTick = createdAtTick;
        refreshStatus();
    }

    public QuestModel.Occurrence occurrence() {
        return occurrence;
    }

    public QuestModel.AttemptStatus status() {
        return status;
    }

    /// Rebinds current definition and timing data without replacing progress or durable identity.
    public void updateOccurrence(QuestModel.Occurrence currentOccurrence) {
        if (!occurrence.key().equals(currentOccurrence.key())) {
            throw new IllegalArgumentException("Cannot replace an attempt with another occurrence");
        }
        occurrence = currentOccurrence;
    }

    public ConditionRuntime.Update onSignal(QuestSignal signal, MinecraftServer server) {
        if (terminal()) {
            return ConditionRuntime.Update.NONE;
        }
        if (signal instanceof QuestSignal.Advancement advancement && observedAdvancements.contains(advancement.advancement().id())) {
            return ConditionRuntime.Update.NONE;
        }
        var update = root.onSignal(signal, new ConditionRuntime.EvaluationContext(server, signal.serverTick()));
        if (update.changed()) {
            if (signal instanceof QuestSignal.Advancement advancement) {
                observedAdvancements.add(advancement.advancement().id());
            }
            lastUpdatedTick = signal.serverTick();
            progressed = true;
        }
        refreshStatus();
        return update;
    }

    /// Reconciles saved vanilla progress once per advancement and attempt, avoiding repeat-count inflation on rejoin.
    ConditionRuntime.Update reconcileAdvancement(QuestSignal.Advancement signal, MinecraftServer server) {
        return observedAdvancements.contains(signal.advancement().id())
                ? ConditionRuntime.Update.NONE
                : onSignal(signal, server);
    }

    /// Rechecks unlocked advancements until ordered condition steps stop progressing.
    ConditionRuntime.Update reconcileAdvancements(List<QuestSignal.Advancement> signals, MinecraftServer server) {
        var result = ConditionRuntime.Update.NONE;
        boolean progressed;
        do {
            progressed = false;
            for (var signal : signals) {
                if (terminal()) return result;
                var update = reconcileAdvancement(signal, server);
                result = result.merge(update);
                progressed |= update.changed();
            }
        } while (progressed);
        return result;
    }

    public ConditionRuntime.Update tick(MinecraftServer server, long serverTick) {
        if (terminal()) {
            return ConditionRuntime.Update.NONE;
        }
        var update = root.tick(new ConditionRuntime.EvaluationContext(server, serverTick));
        if (update.changed()) {
            lastUpdatedTick = serverTick;
            progressed = true;
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
        if (restoredReady) {
            // A completed condition has already passed its event-driven requirements. Claim-time
            // item costs cannot be mandatory in a condition that reached READY_TO_CLAIM.
            return ConditionRuntime.ClaimPreparation.readyPrep();
        }
        var preparation = root.prepareClaim(new ConditionRuntime.ClaimContext(context.server(), context.player(), context.serverTick()));
        refreshStatus();
        return preparation;
    }

    public void markPending() {
        status = QuestModel.AttemptStatus.CLAIM_PENDING;
        // Keep registrations available for a failed claim to resume without discarding progress.
        // onSignal/tick are suppressed while pending by terminal().
    }

    public void markClaimed() {
        status = QuestModel.AttemptStatus.CLAIMED;
        root.close();
    }

    /// Re-derives readiness after claim compensation instead of blindly forcing an active state.
    public void markActiveAfterFailedClaim() {
        status = QuestModel.AttemptStatus.ACTIVE;
        refreshStatus();
    }

    /// Reprojects a saved completion without attempting to replay a one-shot game event.
    void restoreReady() {
        if (status != QuestModel.AttemptStatus.ACTIVE && status != QuestModel.AttemptStatus.READY_TO_CLAIM) return;
        restoredReady = true;
        status = QuestModel.AttemptStatus.READY_TO_CLAIM;
        root.close();
    }

    public JsonObject diagnostic() {
        JsonObject result = new JsonObject();
        result.addProperty("quest", occurrence.definition().id().toString());
        result.addProperty("occurrence", occurrence.key().persistentKey());
        result.addProperty("status", status.name());
        result.addProperty("created_at_tick", createdAtTick);
        result.addProperty("last_updated_tick", lastUpdatedTick);
        JsonObject condition = root.diagnostic();
        if (restoredReady) markCompleted(condition);
        result.add("condition", condition);
        return result;
    }

    /// Projects an unclaimed saved completion onto a fresh tree for inspection and display.
    private static void markCompleted(JsonElement diagnostic) {
        if (diagnostic instanceof JsonArray array) {
            array.forEach(QuestAttempt::markCompleted);
        } else if (diagnostic instanceof JsonObject object) {
            if (object.has("completed")) object.addProperty("completed", true);
            if (object.has("exhausted")) object.addProperty("exhausted", false);
            if (object.has("optional_child_completed")) object.addProperty("optional_child_completed", true);
            if (object.has("target")) {
                if (object.has("current")) object.add("current", object.get("target").deepCopy());
                if (object.has("iterations")) object.add("iterations", object.get("target").deepCopy());
            }
            if (object.has("current_index") && object.has("children")) {
                object.addProperty("current_index", object.getAsJsonArray("children").size());
            }
            object.entrySet().forEach(entry -> markCompleted(entry.getValue()));
        }
    }

    /// Distinguishes real progress from attempts created only to render quest state.
    public boolean hasProgress() {
        return progressed || status != QuestModel.AttemptStatus.ACTIVE;
    }

    /// Saves only mutable condition values; the definition supplies their structure on restore.
    public byte[] saveProgress(long serverTick) {
        var writer = new ProgressState.Writer(serverTick, System.currentTimeMillis());
        writer.unsignedInt(PROGRESS_FORMAT);
        root.writeProgress(writer);
        writer.unsignedInt(observedAdvancements.size());
        observedAdvancements.stream().map(Identifier::toString).sorted().forEach(writer::string);
        return writer.bytes();
    }

    /// Returns false for an outdated or damaged snapshot, leaving this attempt fresh.
    public boolean restoreProgress(byte[] bytes, long serverTick) {
        try {
            var reader = new ProgressState.Reader(bytes, serverTick, System.currentTimeMillis());
            if (reader.unsignedInt(PROGRESS_FORMAT) != PROGRESS_FORMAT) {
                throw new IllegalArgumentException("Unsupported progress format");
            }
            root.readProgress(reader);
            int count = reader.unsignedInt(1024);
            for (int index = 0; index < count; index++) {
                Identifier id = Identifier.tryParse(reader.string());
                if (id == null) throw new IllegalArgumentException("Invalid advancement identifier");
                observedAdvancements.add(id);
            }
            reader.finish();
            progressed = true;
            lastUpdatedTick = serverTick;
            refreshStatus();
            return true;
        } catch (RuntimeException error) {
            PolyQuest.LOGGER.warn("Discarding invalid saved progress for quest {}: {}", occurrence.definition().id(), error.getMessage());
            root.reset();
            observedAdvancements.clear();
            progressed = false;
            lastUpdatedTick = serverTick;
            refreshStatus();
            return false;
        }
    }

    public void close() {
        root.close();
    }

    private boolean terminal() {
        return status == QuestModel.AttemptStatus.READY_TO_CLAIM
                || status == QuestModel.AttemptStatus.CLAIMED
                || status == QuestModel.AttemptStatus.CLAIM_PENDING
                || status == QuestModel.AttemptStatus.EXHAUSTED;
    }

    /// Derives non-durable status from the root while preserving durable terminal states.
    private void refreshStatus() {
        if (status == QuestModel.AttemptStatus.CLAIMED || status == QuestModel.AttemptStatus.CLAIM_PENDING) {
            return;
        }
        if (restoredReady) {
            status = QuestModel.AttemptStatus.READY_TO_CLAIM;
            return;
        }
        if (root.exhausted()) {
            status = QuestModel.AttemptStatus.EXHAUSTED;
            root.close();
        } else if (root.completed()) {
            status = QuestModel.AttemptStatus.READY_TO_CLAIM;
            root.close();
        } else {
            status = QuestModel.AttemptStatus.ACTIVE;
        }
    }

    public record ServerPlayerContext(MinecraftServer server, net.minecraft.server.level.ServerPlayer player, long serverTick) {}
}
