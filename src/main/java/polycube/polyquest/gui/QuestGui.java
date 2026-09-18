package polycube.polyquest.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import polycube.polycore.text.TextComponents;
import polycube.polyquest.api.PolyQuestApi;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.presentation.QuestDisplay;
import polycube.polyquest.runtime.QuestManager;

public final class QuestGui extends SimpleGui {
    private static final Component TITLE = Component.literal("Quest GUI");

    private final ServerPlayer player;
    @SuppressWarnings("NotNullFieldNotInitialized")
    private QuestManager manager;
    @SuppressWarnings("NotNullFieldNotInitialized")
    private QuestManager.Subscription subscription;

    public QuestGui(ServerPlayer player) {
        super(MenuType.GENERIC_9x1, player, false);
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
        refresh();
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
                .glow();
    }

    // Renders the player-facing quest presentation and retains the existing claim callback.
    private GuiElementBuilder createQuestSlot(QuestModel.Occurrence quest) {
        var display = QuestDisplay.format(manager, player.level().getServer(), player.nameAndId(), quest);
        var definition = quest.definition();
        var element = new GuiElementBuilder()
                .setItem(definition.icon())
                .hideDefaultTooltip()
                .setName(display.title());

        for (Component line : display.lines()) {
            element.addLoreLine(line);
        }

        element.addLoreLine(Component.empty())
                .addLoreLine(display.actionHint())
                .setCallback((_, _, _, _) -> {
                    if (display.claimed()) return;
                    var result = manager.claim(player, definition.id());
                    if (result.successful()) {
                        player.sendSystemMessage(TextComponents.success("Successfully claimed quest: " + definition.title()));
                    } else {
                        player.sendSystemMessage(TextComponents.error(result.message()));
                    }
                });

        if (display.claimed() || display.status() == QuestModel.AttemptStatus.READY_TO_CLAIM) {
            element.glow();
        }
        return element;
    }
}
