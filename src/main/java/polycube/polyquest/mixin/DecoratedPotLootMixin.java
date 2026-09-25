package polycube.polyquest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import polycube.polyquest.integration.LootSignals;

import java.util.List;

/// Decorated pots unpack without a player in vanilla, so attribute only the first player-caused unpack.
@Mixin(DecoratedPotBlock.class)
abstract class DecoratedPotLootMixin {
    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/DecoratedPotBlockEntity;getTheItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack polyquest$capturePotLoot(
            DecoratedPotBlockEntity pot, Operation<ItemStack> original,
            ItemStack itemStack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit
    ) {
        var pendingLoot = pot.getLootTable() != null;
        var item = original.call(pot);
        if (pendingLoot && player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel && !item.isEmpty()) {
            LootSignals.emitBlockLoot(serverPlayer, serverLevel, state, pot, List.of(item));
        }
        return item;
    }
}
