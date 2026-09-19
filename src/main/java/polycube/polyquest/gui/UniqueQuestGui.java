package polycube.polyquest.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import polycube.polycore.text.TextComponents;
import polycube.polyquest.model.QuestModel;

import java.util.Comparator;

public final class UniqueQuestGui  extends QuestGui {
    private static final Component TITLE = TextComponents.styled("Unique Quests", ChatFormatting.LIGHT_PURPLE, true).withStyle(s -> s.withShadowColor(0xFF000000));
    private static final int MENU_SIZE = 9 * 2;
    private static final int BACKGROUND_START_SLOT = 9;
    private static final int BACK_SLOT = 9;
    private static final Comparator<QuestModel.Occurrence> DAILY_QUEST_ORDER = Comparator
            .comparingInt((QuestModel.Occurrence quest) -> quest.definition().difficulty()
                    .map(Enum::ordinal)
                    .orElse(Integer.MAX_VALUE))
            .thenComparing(quest -> quest.definition().id().toString());

    private UniqueQuestGui(ServerPlayer player) {
        super(MenuType.GENERIC_9x2, player, TITLE);
    }

    public static void open(ServerPlayer player) {
        new UniqueQuestGui(player);
    }

    @Override
    public void refresh() {
        renderBackground();

        var dailyQuests = manager.available(player.nameAndId()).stream()
                .filter(quest -> quest.definition().availability() == QuestModel.Availability.UNIQUE)
                .sorted(DAILY_QUEST_ORDER)
                .toList();
        int visibleQuests = Math.min(dailyQuests.size(), MENU_SIZE);
        for (int index = 0; index < visibleQuests; index++) {
            setSlot(index, createQuestSlot(dailyQuests.get(index)));
        }

        setSlot(BACK_SLOT, createBackSlot());
    }

    private void renderBackground() {
        for (int slot = BACKGROUND_START_SLOT; slot < MENU_SIZE; slot++) {
            setSlot(slot, createBackgroundSlot());
        }
    }

    @Override
    public void close() {
        super.close();
        QuestJournalGui.open(player);
    }

    private GuiElementBuilder createBackSlot() {
        return new GuiElementBuilder()
                .setItem(Items.STRUCTURE_VOID)
                .hideDefaultTooltip()
                .setName(TextComponents.styled("Back", ChatFormatting.GRAY, true))
                .addLoreLine(TextComponents.styled("Return to the quest journal.", ChatFormatting.GRAY))
                .setCallback((_, _, _, _) -> {
                    playSound(player, SoundEvents.UI_BUTTON_CLICK.value());
                    close();
                });
    }
}
