package polycube.polyquest.integration;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LootSignalsTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void countsOnlyStacksNewlyPlacedByContainerLootGeneration() {
        var container = new SimpleContainer(3);
        container.setItem(0, new ItemStack(new Holder.Direct<>(Items.DIAMOND, DataComponentMap.EMPTY)));

        var generated = LootSignals.newlyFilledItems(container, () ->
                container.setItem(1, new ItemStack(new Holder.Direct<>(Items.EMERALD, DataComponentMap.EMPTY))));

        assertEquals(1, generated.size());
        assertEquals(Items.EMERALD, generated.getFirst().getItem());
        assertEquals(1, generated.getFirst().getCount());
    }
}
