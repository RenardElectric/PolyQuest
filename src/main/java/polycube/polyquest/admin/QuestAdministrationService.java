package polycube.polyquest.admin;

import com.google.gson.JsonObject;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import polycube.polyquest.claim.QuestClaimService;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.runtime.QuestAttempt;
import polycube.polyquest.runtime.QuestManager;

/// Domain-level administration operations independent of Brigadier or a future UI.
public final class QuestAdministrationService {
    private final QuestManager manager;

    public QuestAdministrationService(QuestManager manager) {
        this.manager = manager;
    }

    public boolean reroll(QuestModel.Difficulty difficulty) {
        return manager.reroll(difficulty);
    }

    public boolean reset(ServerPlayer player, Identifier questId) {
        Optional<QuestModel.Occurrence> occurrence = manager.engine()
                .findOccurrence(player.getUUID(), questId);
        if (occurrence.isEmpty()) {
            return false;
        }
        manager.engine().reset(player.getUUID(), occurrence.get());
        return true;
    }

    public QuestClaimService.ClaimResult forceComplete(ServerPlayer player, Identifier questId) {
        return manager.claims().forceClaim(player, questId);
    }

    public Optional<JsonObject> inspect(ServerPlayer player, Identifier questId) {
        return manager.engine().findOccurrence(player.getUUID(), questId)
                .map(occurrence -> manager.attempt(player, occurrence))
                .map(QuestAttempt::diagnostic);
    }

    public int retryPendingRewards(ServerPlayer player) {
        return manager.claims().retryPending(player, true).size();
    }
}
