package polycube.polyquest.integration;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import polycube.polyquest.runtime.QuestManager;
import polycube.polyquest.runtime.QuestRuntime;
import polycube.polyquest.signal.QuestSignal;

/// Converts Fabric callbacks into the small normalized signal model used by quests.
public final class FabricQuestEvents {
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(QuestRuntime::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(QuestRuntime::stop);
        ServerTickEvents.END_SERVER_TICK.register(_ -> QuestRuntime.ifPresent(QuestManager::tick));

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, victim, damageSource) -> {
            if (!(killer instanceof ServerPlayer player)) {
                return;
            }
            DamageSource source = victim.getLastDamageSource();
            if (source == null) {
                source = level.damageSources().generic();
            }
            DamageSource finalSource = source;
            QuestRuntime.ifPresent(manager -> manager.signal(new QuestSignal.Kill(
                    player, level.getServer().getTickCount(), victim, finalSource)));
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                QuestRuntime.ifPresent(manager -> manager.signal(new QuestSignal.PlayerDeath(
                        player, player.level().getServer().getTickCount(), source)));
            }
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, position, state, blockEntity) -> {
            if (world instanceof ServerLevel level && player instanceof ServerPlayer serverPlayer) {
                QuestRuntime.ifPresent(manager -> manager.signal(new QuestSignal.BlockBroken(
                        serverPlayer,
                        level.getServer().getTickCount(),
                        level,
                        position,
                        state,
                        serverPlayer.getMainHandItem())));
            }
            return true;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                QuestRuntime.ifPresent(manager -> manager.onPlayerJoin(handler.getPlayer())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                QuestRuntime.ifPresent(manager -> manager.onPlayerDisconnect(handler.getPlayer())));
    }

    private FabricQuestEvents() {
    }
}
