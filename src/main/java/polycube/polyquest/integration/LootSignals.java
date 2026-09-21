package polycube.polyquest.integration;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import polycube.polyquest.runtime.QuestRuntime;
import polycube.polyquest.signal.QuestSignal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/// Normalizes exact loot results captured at Minecraft's generation sites.
public final class LootSignals {
    private static final ThreadLocal<@Nullable Deque<EntityLootCapture>> ENTITY_LOOT_CAPTURES = new ThreadLocal<>();

    /// Opens a scoped capture around Minecraft's complete death-loot routine.
    public static void beginEntityLoot(ServerPlayer player, Entity entity) {
        var captures = ENTITY_LOOT_CAPTURES.get();
        if (captures == null) {
            captures = new ArrayDeque<>();
            ENTITY_LOOT_CAPTURES.set(captures);
        }
        captures.push(new EntityLootCapture(player, entity, new ArrayList<>()));
    }

    /// Records an item spawned by the entity currently producing death loot.
    public static void captureEntityLoot(Entity entity, ItemStack item) {
        var captures = ENTITY_LOOT_CAPTURES.get();
        if (captures == null || captures.isEmpty()) {
            return;
        }
        var capture = captures.getFirst();
        if (capture.entity() == entity && !item.isEmpty()) {
            capture.items().add(item.copy());
        }
    }

    /// Closes a death-loot capture and emits it only when Minecraft completed normally.
    public static void finishEntityLoot(Entity entity, boolean successful) {
        var captures = ENTITY_LOOT_CAPTURES.get();
        if (captures == null || captures.isEmpty()) {
            throw new IllegalStateException("Entity loot capture stack is missing");
        }
        var capture = captures.pop();
        if (captures.isEmpty()) {
            ENTITY_LOOT_CAPTURES.remove();
        }
        if (capture.entity() != entity) {
            throw new IllegalStateException("Entity loot capture stack is unbalanced");
        }
        if (successful) {
            emit(capture.player(), QuestSignal.LootOrigin.ENTITY, entity, capture.items());
        }
    }

    public static void emitFishingLoot(ServerPlayer player, FishingHook hook, Collection<ItemStack> items) {
        emit(player, QuestSignal.LootOrigin.FISHING, hook, items);
    }

    public static void emitBlockLoot(ServerPlayer player, Collection<ItemStack> items) {
        emit(player, QuestSignal.LootOrigin.BLOCK, null, items);
    }

    private static void emit(
            ServerPlayer player,
            QuestSignal.LootOrigin origin,
            @Nullable Entity sourceEntity,
            Collection<ItemStack> items
    ) {
        if (items.isEmpty()) {
            return;
        }
        QuestRuntime.ifPresent(manager -> manager.signal(new QuestSignal.LootGenerated(
                player,
                player.level().getServer().getTickCount(),
                origin,
                sourceEntity,
                List.copyOf(items))));
    }

    private record EntityLootCapture(ServerPlayer player, Entity entity, List<ItemStack> items) {}

    private LootSignals() {}
}
