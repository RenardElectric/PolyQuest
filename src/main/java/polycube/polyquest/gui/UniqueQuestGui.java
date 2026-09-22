package polycube.polyquest.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import polycube.polycore.text.TextComponents;
import polycube.polyquest.api.PolyQuestApi;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.runtime.QuestManager;

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

    private UniqueQuestGui(ServerPlayer player, QuestManager manager) {
        super(MenuType.GENERIC_9x6, player, TITLE, manager);
    }

    public static void open(ServerPlayer player) {
        PolyQuestApi.manager().result().ifPresent(manager -> new UniqueQuestGui(player, manager).open());
    }

    @Override
    protected void render() {
        for (int slot = 0; slot < MENU_SIZE; slot++) {
            clearSlot(slot);
        }

        renderBackground();

        var uniqueQuests = manager.available().stream()
                .filter(quest -> quest.definition().availability() == QuestModel.Availability.UNIQUE)
                .sorted(Comparator
                        .comparingInt((QuestModel.Occurrence quest) -> statusOrder(
                                manager.attempt(player.nameAndId(), quest).status()))
                        .thenComparing((QuestModel.Occurrence quest) -> quest.definition().id().toString()))
                .toList();
        index = Math.clamp((uniqueQuests.size() - 1) / QUESTS_PER_PAGE, 0, index);
        int visibleQuestsStart = Math.min(index * QUESTS_PER_PAGE, uniqueQuests.size());
        int visibleQuestsEnd = Math.min(uniqueQuests.size(), visibleQuestsStart + QUESTS_PER_PAGE);

        for (int i = visibleQuestsStart; i < visibleQuestsEnd; i++) {
            setSlot(i - visibleQuestsStart, createQuestSlot(uniqueQuests.get(i)));
        }

        if (index > 0) {
            setSlot(BACKWARD_SLOT, createBackwardSlot());
        }
        if (visibleQuestsEnd < uniqueQuests.size()) {
            setSlot(FORWARD_SLOT, createForwardSlot());
        }
        setSlot(BACK_SLOT, createBackSlot());
    }

    private static int statusOrder(QuestModel.AttemptStatus status) {
        return switch (status) {
            case READY_TO_CLAIM -> 0;
            case CLAIM_PENDING -> 1;
            case ACTIVE -> 2;
            case EXHAUSTED -> 3;
            case CLAIMED -> 4;
        };
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
