package polycube.polyquest.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.serialization.DataResult;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import polycube.polyquest.claim.QuestClaimService;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.RewardApi;
import polycube.polyquest.runtime.QuestManager;
import polycube.polyquest.runtime.QuestRuntime;
import polycube.polyquest.signal.QuestSignal;

import java.util.List;

/// Small integration API for the economy provider and other server-side mods.
public final class PolyQuestApi {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// Returns the server's Common Economy API adapter, if installed.
    public static DataResult<QuestManager> manager() {
        return QuestRuntime.manager()
                .map(DataResult::success)
                .orElse(DataResult.error(() -> "PolyQuest runtime is not ready"));
    }

    /// Returns the quest occurrence for the given player and quest ID, if the quest manager is installed.
    public static DataResult<QuestModel.Occurrence> quest(Identifier questId) {
        return manager().flatMap(
                manager ->
                        manager.findOccurrence(questId).map(DataResult::success)
                                .orElse(DataResult.error(() -> "Quest not found: " + questId))
        );
    }

    /// Returns the list of quests available to the given player, if the quest manager is installed.
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
        return manager().map(questManager -> questManager.reroll(difficulties));
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

    /// Retries any pending reward transactions for the given player, if the quest manager is installed.
    public static DataResult<QuestClaimService.ClaimResult> claim(ServerPlayer player, Identifier questId) {
        return manager().map(manager -> manager.claim(player, questId));
    }

    /// Returns a JSON string representing the diagnostic state of the given player's quest attempt, if the quest manager is installed.
    public static DataResult<String> inspect(NameAndId player, Identifier questId) {
        return manager().flatMap(
                manager ->
                        quest(questId).map(oc -> {
                            var attempt = manager.attempt(player, oc);
                            return GSON.toJson(attempt.diagnostic());
                        })
        );
    }

    private PolyQuestApi() {}
}
