package polycube.polyquest.mixin;

import java.util.Collection;
import java.util.List;

import net.minecraft.advancements.triggers.FishingRodHookedTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import polycube.polyquest.runtime.QuestRuntime;
import polycube.polyquest.signal.QuestSignal;

/// Reuses the point where vanilla evaluates `fishing_rod_hooked`, preserving the exact
/// caught loot collection instead of trying to infer fishing from later item pickups.
@Mixin(FishingRodHookedTrigger.class)
abstract class FishingRodHookedTriggerMixin {
    @Inject(method = "trigger", at = @At("HEAD"))
    private void polyquest$onFishingResult(ServerPlayer player, ItemStack rod, FishingHook hook, Collection<ItemStack> items, CallbackInfo callback) {
        QuestRuntime.ifPresent(manager -> manager.signal(new QuestSignal.Fishing(player, player.level().getServer().getTickCount(), hook, List.copyOf(items))));
    }
}
