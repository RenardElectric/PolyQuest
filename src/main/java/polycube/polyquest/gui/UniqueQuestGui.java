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

public final class UniqueQuestGui extends QuestGui {
    private static final Component TITLE = TextComponents.styled("Unique Quests", ChatFormatting.LIGHT_PURPLE, true).withStyle(s -> s.withShadowColor(0xFF000000));
    private static final int MENU_SIZE = 9 * 6;
    private static final int BACKGROUND_START_SLOT = MENU_SIZE - 9;
    private static final int QUESTS_PER_PAGE = BACKGROUND_START_SLOT;
    private static final int BACK_SLOT = MENU_SIZE - 5;
    private static final int FORWARD_SLOT = MENU_SIZE - 3;
    private static final int BACKWARD_SLOT = MENU_SIZE - 7;

    private int index;

    private UniqueQuestGui(ServerPlayer player) {
        super(MenuType.GENERIC_9x6, player, TITLE);
        index = 0;
    }

    public static void open(ServerPlayer player) {
        new UniqueQuestGui(player);
    }

    @Override
    public void refresh() {
        for (int slot = 0; slot < MENU_SIZE; slot++) {
            clearSlot(slot);
        }

        renderBackground();

        var attemptsCount = QuestModel.AttemptStatus.values().length;
        var dailyQuests = manager.available(player.nameAndId()).stream()
                .filter(quest -> quest.definition().availability() == QuestModel.Availability.UNIQUE)
                .sorted(Comparator
                        .comparingInt((QuestModel.Occurrence quest) -> attemptsCount - manager.attempt(player.nameAndId(), quest).status().ordinal())
                        .thenComparing((QuestModel.Occurrence quest) -> quest.definition().id().toString()))
                .toList();
        int visibleQuestsStart = Math.min(index * QUESTS_PER_PAGE, dailyQuests.size());
        int visibleQuestsEnd = Math.min(dailyQuests.size(), visibleQuestsStart + QUESTS_PER_PAGE);

        for (int i = visibleQuestsStart; i < visibleQuestsEnd; i++) {
            setSlot(i - visibleQuestsStart, createQuestSlot(dailyQuests.get(i)));
        }

        if (index > 0) {
            setSlot(BACKWARD_SLOT, createBackwardSlot());
        }
        if (visibleQuestsEnd < dailyQuests.size()) {
            setSlot(FORWARD_SLOT, createForwardSlot());
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

    private GuiElementBuilder createForwardSlot() {
        return new GuiElementBuilder()
                .setItem(Items.ARROW)
                .hideDefaultTooltip()
                .setName(TextComponents.styled("Next Page", ChatFormatting.GRAY, true))
                .addLoreLine(TextComponents.styled("Go to the next page of unique quests.", ChatFormatting.GRAY))
                .setCallback((_, _, _, _) -> {
                    playSound(player, SoundEvents.UI_BUTTON_CLICK.value());
                    index++;
                    refresh();
                });
    }

    private GuiElementBuilder createBackwardSlot() {
        return new GuiElementBuilder()
                .setItem(Items.ARROW)
                .hideDefaultTooltip()
                .setName(TextComponents.styled("Previous Page", ChatFormatting.GRAY, true))
                .addLoreLine(TextComponents.styled("Go to the previous page of unique quests.", ChatFormatting.GRAY))
                .setCallback((_, _, _, _) -> {
                    playSound(player, SoundEvents.UI_BUTTON_CLICK.value());
                    index = Math.max(0, index - 1);
                    refresh();
                });
    }
}
