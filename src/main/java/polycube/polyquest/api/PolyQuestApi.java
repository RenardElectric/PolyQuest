package polycube.polyquest.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import polycube.polyquest.claim.QuestClaimService;
import polycube.polyquest.condition.ConditionApi;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.runtime.QuestManager;
import polycube.polyquest.runtime.QuestRuntime;
import polycube.polyquest.signal.QuestSignal;

import java.util.List;

/// Small integration API for the economy provider and other server-side mods.
public final class PolyQuestApi {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// Returns the active server's quest manager, if initialized.
    public static DataResult<QuestManager> manager() {
        return QuestRuntime.manager()
                .map(DataResult::success)
                .orElse(DataResult.error(() -> "PolyQuest runtime is not ready"));
    }

    /// Returns the currently available occurrence for a quest ID.
    public static DataResult<QuestModel.Occurrence> quest(Identifier questId) {
        return manager().flatMap(
                manager ->
                        manager.findOccurrence(questId).map(DataResult::success)
                                .orElse(DataResult.error(() -> "Quest not found: " + questId))
        );
    }

    /// Returns the globally available daily and unique quest occurrences.
    public static DataResult<List<QuestModel.Occurrence>> availableQuests() {
        return manager().map(QuestManager::available);
    }

    /// Emits a quest signal to the quest manager, if installed.
    public static DataResult<QuestManager> emit(ServerPlayer player, Identifier signalId) {
        return manager().map(manager -> {
            manager.signal(new QuestSignal.Explicit(player, manager.server().getTickCount(), signalId));
            return manager;
        });
    }

    /// Rerolls the daily quest rotation for the given difficulties, if the quest manager is installed.
    public static DataResult<Boolean> reroll(List<QuestModel.Difficulty> difficulties) {
        return manager().map(questManager -> !questManager.reroll(difficulties).isEmpty());
    }

    public static DataResult<QuestManager> reset(NameAndId player, Identifier questId) {
        return manager().flatMap(
                manager ->
                        quest(questId).map(oc -> {
                            manager.reset(player, oc);
                            return manager;
                        })
        );
    }

    /// Claims a quest or retries that quest's pending reward transaction.
    public static DataResult<QuestClaimService.ClaimResult> claim(ServerPlayer player, Identifier questId) {
        return manager().map(manager -> manager.claim(player, questId));
    }

    /// Returns both live progress and the decoded condition definition, including criterion predicates.
    public static DataResult<String> inspect(NameAndId player, Identifier questId) {
        return manager().flatMap(manager -> quest(questId).map(oc -> {
            var diagnostic = manager.attempt(player, oc).diagnostic();
            diagnostic.addProperty("quest_title", oc.definition().title());
            var encoded = ConditionApi.codec().encodeStart(
                    manager.server().registryAccess().createSerializationContext(JsonOps.INSTANCE),
                    oc.definition().condition());
            encoded.result().ifPresentOrElse(
                    definition -> diagnostic.add("condition_definition", definition),
                    () -> diagnostic.addProperty("condition_definition_error",
                            encoded.error().map(DataResult.Error::message).orElse("Could not encode condition definition")));
            return GSON.toJson(diagnostic);
        }));
    }

    private PolyQuestApi() {}
}
