package polycube.polyquest.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import polycube.polyquest.integration.LootSignals;

/// Attributes vault rewards to the player who unlocked the vault, excluding its cycling display loot.
@Mixin(targets = "net.minecraft.world.level.block.entity.vault.VaultBlockEntity$Server")
abstract class VaultLootMixin {
    @Redirect(method = "resolveItemsToEject", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
    private static ObjectArrayList<ItemStack> polyquest$captureVaultRewards(
            LootTable table, LootParams params,
            ServerLevel serverLevel, VaultConfig config, BlockPos pos, Player player, ItemInstance key
    ) {
        var generated = table.getRandomItems(params);
        if (player instanceof ServerPlayer serverPlayer && !generated.isEmpty()) {
            LootSignals.emitBlockLoot(serverPlayer, serverLevel, serverLevel.getBlockState(pos), serverLevel.getBlockEntity(pos), generated);
        }
        return generated;
    }
}
