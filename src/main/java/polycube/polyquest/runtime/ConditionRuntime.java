package polycube.polyquest.runtime;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import polycube.polyquest.condition.ConditionApi;
import polycube.polyquest.signal.QuestSignal;

/// Mutable per-player condition state and claim-time operations.
public final class ConditionRuntime {
    public record CreationContext(MinecraftServer server) {
    }

    public record EvaluationContext(MinecraftServer server, long serverTick) {
    }

    public record ClaimContext(MinecraftServer server, ServerPlayer player, long serverTick) {
    }

    public record Update(boolean changed, boolean progressed, boolean completedNow) {
        public static final Update NONE = new Update(false, false, false);

        public static Update changed(boolean completedNow) {
            return new Update(true, true, completedNow);
        }

        public Update merge(Update other) {
            return new Update(
                    changed || other.changed,
                    progressed || other.progressed,
                    completedNow || other.completedNow);
        }
    }

    public interface Instance {
        ConditionApi.Definition definition();

        Update onSignal(QuestSignal signal, EvaluationContext context);

        default Update tick(EvaluationContext context) {
            return Update.NONE;
        }

        boolean completed();

        default boolean exhausted() {
            return false;
        }

        ClaimPreparation prepareClaim(ClaimContext context);

        void reset();

        default JsonObject diagnostic() {
            JsonObject result = new JsonObject();
            result.addProperty("completed", completed());
            result.addProperty("type", definition().type().id().toString());
            return result;
        }
    }

    public record ClaimPreparation(boolean ready, List<ClaimOperation> operations, String failure) {
        public ClaimPreparation {
            operations = List.copyOf(operations);
            failure = failure == null ? "" : failure;
        }

        public static ClaimPreparation readyPrep(List<ClaimOperation> operations) {
            return new ClaimPreparation(true, operations, "");
        }

        public static ClaimPreparation readyPrep() {
            return readyPrep(List.of());
        }

        public static ClaimPreparation blocked(String reason) {
            return new ClaimPreparation(false, List.of(), reason);
        }
    }

    public interface ClaimOperation {
        String describe();

        boolean revalidate(ClaimContext context);

        CommitResult commit(ClaimContext context);
    }

    public record CommitResult(boolean success, Runnable rollback, String failure) {
        public CommitResult {
            rollback = rollback == null ? () -> { } : rollback;
            failure = failure == null ? "" : failure;
        }

        public static CommitResult success(Runnable rollback) {
            return new CommitResult(true, rollback, "");
        }

        public static CommitResult failure(String reason) {
            return new CommitResult(false, () -> { }, reason);
        }
    }

    public static Instance create(ConditionApi.Definition definition, CreationContext context) {
        ConditionApi.Type<ConditionApi.Definition> type =
                (ConditionApi.Type<ConditionApi.Definition>) definition.type();
        return type.factory().create(definition, context);
    }

    private ConditionRuntime() {
    }
}
