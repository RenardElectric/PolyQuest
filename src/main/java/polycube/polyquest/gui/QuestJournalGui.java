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

public final class QuestJournalGui extends QuestGui {
    private static final Component TITLE = TextComponents.styled("Quest Journal", ChatFormatting.GOLD, true).withStyle(s -> s.withShadowColor(0xFF000000));
    private static final int MENU_SIZE = 9 * 3;
    private static final int UNIQUE_QUEST_SLOT = 26;
    private static final int[] DAILY_QUEST_SLOTS = {11, 13, 15};
    private static final Comparator<QuestModel.Occurrence> DAILY_QUEST_ORDER = Comparator
            .comparingInt((QuestModel.Occurrence quest) -> quest.definition().difficulty()
                    .map(Enum::ordinal)
                    .orElse(Integer.MAX_VALUE))
            .thenComparing(quest -> quest.definition().id().toString());

    private QuestJournalGui(ServerPlayer player) {
        super(MenuType.GENERIC_9x3, player, TITLE);
    }

    public static void open(ServerPlayer player) {
        new QuestJournalGui(player);
    }

    @Override
    public void refresh() {
        renderBackground();

        var dailyQuests = manager.available().stream()
                .filter(quest -> quest.definition().availability() == QuestModel.Availability.DAILY)
                .sorted(DAILY_QUEST_ORDER)
                .toList();
        int visibleQuests = Math.min(dailyQuests.size(), DAILY_QUEST_SLOTS.length);
        for (int index = 0; index < visibleQuests; index++) {
            setSlot(DAILY_QUEST_SLOTS[index], createQuestSlot(dailyQuests.get(index)));
        }

        setSlot(UNIQUE_QUEST_SLOT, createUniqueQuestsSlot());
    }

    private void renderBackground() {
        for (int slot = 0; slot < MENU_SIZE; slot++) {
            setSlot(slot, createBackgroundSlot());
        }
    }

    // Renders the unique quest collection in the last slot of the main quest GUI.
    private GuiElementBuilder createUniqueQuestsSlot() {
        return new GuiElementBuilder()
                .setItem(Items.BOOK)
                .hideDefaultTooltip()
                .setName(TextComponents.styled("Unique Quests", ChatFormatting.LIGHT_PURPLE, true))
                .addLoreLine(TextComponents.styled("Permanent quests for every player.", ChatFormatting.GRAY))
                .addLoreLine(Component.empty())
                .addLoreLine(TextComponents.section("UNIQUE QUESTS"))
                .addLoreLine(TextComponents.detail("Type", "One-time objectives", ChatFormatting.LIGHT_PURPLE))
                .addLoreLine(TextComponents.detail("Rotation", "Never expires", ChatFormatting.GREEN))
                .addLoreLine(TextComponents.styled("Each reward can only be claimed once.", ChatFormatting.DARK_GRAY))
                .glow()
                .setCallback((_, _, _, _) -> {
                    playSound(player, SoundEvents.UI_BUTTON_CLICK.value());
                    UniqueQuestGui.open(player);
                });
    }
}
