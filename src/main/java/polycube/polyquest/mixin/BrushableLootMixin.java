package polycube.polyquest.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import polycube.polyquest.integration.LootSignals;

import java.util.List;

/// Captures the single item Minecraft selects from a brushed block's generated loot.
@Mixin(BrushableBlockEntity.class)
abstract class BrushableLootMixin {
    @Redirect(method = "unpackLootTable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;J)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
    private ObjectArrayList<ItemStack> polyquest$captureBrushedLoot(
            LootTable table, LootParams params, long optionalLootTableSeed,
            ServerLevel level, LivingEntity user, ItemInstance brush
    ) {
        ObjectArrayList<ItemStack> generated = table.getRandomItems(params, optionalLootTableSeed);
        if (user instanceof ServerPlayer player && !generated.isEmpty() && !generated.getFirst().isEmpty()) {
            var blockEntity = (BrushableBlockEntity) (Object) this;
            LootSignals.emitBlockLoot(player, level, blockEntity.getBlockState(), blockEntity, List.of(generated.getFirst()));
        }
        return generated;
    }
}
