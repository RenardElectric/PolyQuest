package polycube.polyquest.runtime;

import com.google.gson.JsonObject;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import polycube.polyquest.condition.ConditionApi;
import polycube.polyquest.signal.QuestSignal;

import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/// Mutable per-player condition state and claim-time operations.
public final class ConditionRuntime {
    public record CreationContext(
            LongSupplier serverTick,
            UUID playerId,
            CriterionRegistrar criteria
    ) {
        public CreationContext(LongSupplier serverTick) {
            this(serverTick, new UUID(0L, 0L), CriterionRegistrar.NONE);
        }

        public long currentServerTick() {
            return serverTick.getAsLong();
        }

        public CriterionRegistration register(Criterion<?> criterion) {
            return criteria.register(playerId, criterion);
        }
    }

    public record EvaluationContext(MinecraftServer server, long serverTick) {}

    public record ClaimContext(MinecraftServer server, ServerPlayer player, long serverTick) {}

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

    /// Represents a mutable condition instance that can respond to signals, tick, and prepare claim operations.
    public interface Instance {
        /// Returns the immutable definition that this instance was created from.
        ConditionApi.Definition definition();

        /// Updates the instance state based on a signal and returns an update summary.
        Update onSignal(QuestSignal signal, EvaluationContext context);

        /// Updates the instance state based on a tick and returns an update summary.
        default Update tick(EvaluationContext context) {
            return Update.NONE;
        }

        /// Returns true if the condition is completed and no further progress is possible.
        boolean completed();

        /// Returns true if the condition is exhausted and no further progress is possible.
        default boolean exhausted() {
            return false;
        }

        /// Prepares claim operations for this condition, returning a ClaimPreparation that indicates readiness or blockage.
        ClaimPreparation prepareClaim(ClaimContext context);

        /// Resets the instance state to its initial state, allowing it to be reused.
        void reset();

        /// Releases external listeners owned by this runtime subtree.
        default void close() {}

        /// Returns a diagnostic JSON object representing the current state of the instance.
        default JsonObject diagnostic() {
            JsonObject result = new JsonObject();
            result.addProperty("completed", completed());
            result.addProperty("type", definition().type().id().toString());
            return result;
        }
    }

    /// Registration seam between condition trees and Minecraft advancement criteria.
    @FunctionalInterface
    public interface CriterionRegistrar {
        CriterionRegistrar NONE = (_, _) -> CriterionRegistration.NONE;

        CriterionRegistration register(UUID playerId, Criterion<?> criterion);
    }

    /// Controls one fake advancement criterion listener without exposing tracker internals.
    public interface CriterionRegistration extends AutoCloseable {
        CriterionRegistration NONE = new CriterionRegistration() {
            private static final Identifier ID = Identifier.fromNamespaceAndPath("polyquest", "runtime/unbound");

            @Override
            public Identifier id() {
                return ID;
            }

            @Override
            public void activate() {}

            @Override
            public void deactivate() {}

            @Override
            public void close() {}
        };

        Identifier id();

        void activate();

        void deactivate();

        @Override
        void close();
    }

    public record ClaimPreparation(boolean ready, List<ClaimOperation> operations, String failure) {
        public ClaimPreparation {
            operations = List.copyOf(operations);
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
        public static CommitResult success(Runnable rollback) {
            return new CommitResult(true, rollback, "");
        }

        public static CommitResult failure(String reason) {
            return new CommitResult(false, () -> {}, reason);
        }
    }

    /// Dispatches a definition through its registered runtime factory.
    @SuppressWarnings("unchecked")
    public static Instance create(ConditionApi.Definition definition, CreationContext context) {
        ConditionApi.Type<ConditionApi.Definition> type = (ConditionApi.Type<ConditionApi.Definition>) definition.type();
        return type.factory().create(definition, context);
    }

    private ConditionRuntime() {}
}
