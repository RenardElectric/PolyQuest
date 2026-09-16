package polycube.polyquest.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import polycube.polyquest.api.PolyQuestApi;
import polycube.polyquest.commands.CommandText;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.runtime.QuestManager;

import java.util.Optional;

public class QuestGui extends SimpleGui {
    private static final Component TITLE = Component.literal("Quest GUI");

    private final ServerPlayer player;
    private QuestManager manager;
    private QuestManager.Subscription subscription;

    public QuestGui(ServerPlayer player) {
        super(MenuType.GENERIC_9x1,  player, false);
        this.player = player;
        setTitle(TITLE);

        setSlot(8, createUniqueQuestsSlot());
        open();
    }

    @Override
    public boolean open() {
        var questManager = PolyQuestApi.manager();
        if (questManager.isError()) {
            return false;
        }
        manager = questManager.getOrThrow();
        subscription = manager.addListener(this::refresh);
        refresh(); // Initial render
        return super.open();
    }

    @Override
    public void close() {
        subscription.close();
        super.close();
    }

    public void refresh() {
        int slotIndex = 0;
        for (var quest : manager.available(player.nameAndId())) {
            if (quest.definition().availability() == QuestModel.Availability.UNIQUE) continue;
            setSlot(slotIndex++, createQuestSlot(quest));
        }
    }

    private GuiElementBuilder createUniqueQuestsSlot() {
        return new GuiElementBuilder();
    }

    private GuiElementBuilder createQuestSlot(QuestModel.Occurrence quest) {
        var definition = quest.definition();
        var claimed = manager.isClaimed(player.nameAndId(), quest.key());
        var element =  new GuiElementBuilder()
                .setItem(definition.icon())
                .hideDefaultTooltip()
                .setName(Component.literal(definition.title()))
                .addLoreLine(Component.literal("Quest ID: " + definition.id()))
                .addLoreLine(Component.literal("Availability: " + definition.availability().name()))
                .addLoreLine(Component.literal("Claimed: " + claimed))
                .setCallback((_, _, _, _) -> {
                    if (claimed) return;
                    var result = manager.claim(player, quest.definition().id());
                    if (result.successful()) {
                        player.sendSystemMessage(CommandText.success("Successfully claimed quest: " + definition.title()));
                    } else {
                        player.sendSystemMessage(CommandText.error(result.message()));
                    }
                });

        if (claimed) {
            element.glow();
        }

        return element;
    }
}
