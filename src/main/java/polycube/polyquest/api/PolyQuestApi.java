package polycube.polyquest.api;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.RewardApi;
import polycube.polyquest.runtime.QuestManager;
import polycube.polyquest.runtime.QuestRuntime;
import polycube.polyquest.signal.QuestSignal;

/// Small integration API for the economy provider and other server-side mods.
public final class PolyQuestApi {
    /// Installs the server's Common Economy API adapter.
    ///
    /// The transaction ID must be treated idempotently by the adapter: depositing twice
    /// with the same ID must not credit the account twice.
    public static void setEconomyGateway(RewardApi.EconomyGateway gateway) {
        RewardApi.setEconomyGateway(gateway);
    }

    public static Optional<QuestManager> manager() {
        return QuestRuntime.manager();
    }

    public static List<QuestModel.Occurrence> availableQuests(ServerPlayer player) {
        return manager().map(value -> value.available(player)).orElse(List.of());
    }

    public static void emit(ServerPlayer player, Identifier signalId) {
        QuestRuntime.ifPresent(manager -> manager.signal(new QuestSignal.Explicit(
                player,
                manager.server().getTickCount(),
                signalId)));
    }

    private PolyQuestApi() {
    }
}
