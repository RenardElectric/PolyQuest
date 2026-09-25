package polycube.polyquest.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.Container;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import polycube.polyquest.integration.LootSignals;

/// Observes Minecraft's final container fill once, including nested loot-table results.
@Mixin(LootTable.class)
abstract class ContainerLootMixin {
    @WrapMethod(method = "fill(Lnet/minecraft/world/Container;Lnet/minecraft/world/level/storage/loot/LootParams;J)V")
    private void polyquest$captureFilledContainer(Container container, LootParams params, long seed, Operation<Void> original) {
        LootSignals.fillContainerLoot(container, params, () -> original.call(container, params, seed));
    }
}
