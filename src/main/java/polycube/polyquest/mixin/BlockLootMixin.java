package polycube.polyquest.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import polycube.polyquest.integration.LootSignals;

import java.util.List;

/// Captures the same block-drop list Minecraft subsequently spawns for a player break.
@Mixin(Block.class)
abstract class BlockLootMixin {
    @Redirect(method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;getDrops(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemInstance;)Ljava/util/List;"))
    private static List<ItemStack> polyquest$captureBlockLoot(
            BlockState state, ServerLevel level, BlockPos pos,
            @Nullable BlockEntity blockEntity, @Nullable Entity breaker, ItemInstance tool
    ) {
        List<ItemStack> generated = Block.getDrops(state, level, pos, blockEntity, breaker, tool);
        if (breaker instanceof ServerPlayer player) {
            LootSignals.emitBlockLoot(player, generated);
        }
        return generated;
    }
}
