package polycube.polyquest.signal;

import java.util.List;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/// Normalized server-thread events consumed by condition instances.
public sealed interface QuestSignal
        permits QuestSignal.Kill, QuestSignal.Fishing,
        QuestSignal.BlockBroken, QuestSignal.PlayerDeath,
        QuestSignal.Advancement, QuestSignal.PlayerTick, QuestSignal.Explicit
{
    ServerPlayer player();

    long serverTick();

    record Kill(ServerPlayer player, long serverTick, Entity victim, DamageSource damageSource) implements QuestSignal {}

    record Fishing(ServerPlayer player, long serverTick, FishingHook hook, List<ItemStack> caught) implements QuestSignal {
        public Fishing {
            caught = caught.stream().map(ItemStack::copy).toList();
        }
    }

    record BlockBroken(
            ServerPlayer player, long serverTick, ServerLevel level, BlockPos position,
            BlockState state, @Nullable BlockEntity blockEntity, ItemStack tool
    ) implements QuestSignal {
        public BlockBroken {
            position = position.immutable();
            tool = tool.copy();
        }
    }

    record PlayerDeath(ServerPlayer player, long serverTick, DamageSource damageSource) implements QuestSignal {}

    record Advancement(ServerPlayer player, long serverTick, AdvancementHolder advancement) implements QuestSignal {}

    record PlayerTick(ServerPlayer player, long serverTick) implements QuestSignal {}

    record Explicit(ServerPlayer player, long serverTick, Identifier signalId) implements QuestSignal {}
}
