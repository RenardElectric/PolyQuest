package polycube.polyquest.integration;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import polycube.polyquest.runtime.QuestManager;
import polycube.polyquest.runtime.QuestRuntime;

/// Converts Fabric callbacks into the small normalized signal model used by quests.
public final class FabricQuestEvents {
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(QuestRuntime::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(QuestRuntime::stop);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) {
                QuestRuntime.ifPresent(QuestManager::onDataPackReload);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(_ -> QuestRuntime.ifPresent(QuestManager::tick));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                QuestRuntime.ifPresent(manager -> manager.onPlayerJoin(handler.getPlayer())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                QuestRuntime.ifPresent(manager -> manager.onPlayerDisconnect(handler.getPlayer())));
    }

    private FabricQuestEvents() {}
}
