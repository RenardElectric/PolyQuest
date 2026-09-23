package polycube.polyquest.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import polycube.polycore.text.TextComponents;
import polycube.polyquest.commands.QuestCommandText;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.condition.CompositeConditions;
import polycube.polyquest.condition.ConditionApi;
import polycube.polyquest.presentation.QuestDisplay;
import polycube.polyquest.runtime.QuestManager;

public abstract class QuestGui extends SimpleGui {
    private static final int TIMED_REFRESH_TICKS = 20;
    private static final int ORDINARY_REFRESH_TICKS = 20 * 60;

    protected final ServerPlayer player;
    protected final QuestManager manager;
    private final Component title;
    private QuestManager.@Nullable Subscription subscription;
    private int tickCounter;
    private int interactionCooldown;
    private boolean timedQuestVisible;
    private boolean expiringQuestVisible;

    protected QuestGui(MenuType<?> menuType, ServerPlayer player, Component title, QuestManager manager) {
        super(menuType, player, false);
        this.player = player;
        this.manager = manager;
        this.title = title;
    }

    @Override
    public void onTick() {
        if ((timedQuestVisible || expiringQuestVisible) && ++tickCounter >= (timedQuestVisible ? TIMED_REFRESH_TICKS : ORDINARY_REFRESH_TICKS)) {
            refresh();
        }
        if (interactionCooldown > 0) {
            interactionCooldown--;
        }
    }

    @Override
    public boolean open() {
        if (player.hasDisconnected() || isOpen()) {
            return false;
        }
        setTitle(title);
        subscription = manager.addListener(this::refresh);
        boolean opened = false;
        try {
            refresh();
            opened = super.open();
            return opened;
        } finally {
            if (!opened) {
                closeSubscription();
            }
        }
    }

    @Override
    public void onManualClose() {
        closeSubscription();
        super.onManualClose();
    }

    @Override
    public void onPlayerClose(boolean success) {
        closeSubscription();
        super.onPlayerClose(success);
    }

    @Override
    public void close() {
        closeSubscription();
        super.close();
    }

    private void closeSubscription() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }

    /// Refreshes visible slots on state changes and tracks whether their timers need a live clock.
    public final void refresh() {
        tickCounter = 0;
        timedQuestVisible = false;
        expiringQuestVisible = false;
        render();
    }

    protected abstract void render();

    protected static GuiElementBuilder createBackgroundSlot() {
        return new GuiElementBuilder()
                .setItem(Items.STAINED_GLASS_PANE.black())
                .hideDefaultTooltip()
                .setName(Component.empty());
    }

    // Renders the player-facing quest presentation and retains the existing claim callback.
    protected GuiElementBuilder createQuestSlot(QuestModel.Occurrence quest) {
        timedQuestVisible |= hasTimeWindow(quest.definition().condition());
        expiringQuestVisible |= quest.availableUntil().isPresent();
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
                    if (interactionCooldown > 0) return;
                    var result = manager.claim(player, definition.id());
                    if (result.successful()) {
                        playSound(player, SoundEvents.VILLAGER_YES);
                        player.sendSystemMessage(QuestCommandText.questClaimed(
                                quest, QuestDisplay.format(manager, manager.server(), player.nameAndId(), quest)
                                        .hoverText()));
                    } else {
                        playSound(player, SoundEvents.VILLAGER_NO);
                        player.sendSystemMessage(TextComponents.error(result.message()));
                    }
                    interactionCooldown = 20;
                });

        if (display.claimed() || display.status() == QuestModel.AttemptStatus.READY_TO_CLAIM) {
            element.glow();
        }
        return element;
    }

    protected static void playSound(ServerPlayer player, SoundEvent sound) {
        player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(), 1.0f, 1.0f, player.getRandom().nextLong()));
    }

    private static boolean hasTimeWindow(ConditionApi.Definition condition) {
        return switch (condition) {
            case CompositeConditions.TimeWindow ignored -> true;
            case CompositeConditions.AllOf value -> value.children().stream().anyMatch(QuestGui::hasTimeWindow);
            case CompositeConditions.AnyOf value -> value.children().stream().anyMatch(QuestGui::hasTimeWindow);
            case CompositeConditions.NOfM value -> value.children().stream().anyMatch(QuestGui::hasTimeWindow);
            case CompositeConditions.Sequence value -> value.children().stream().anyMatch(QuestGui::hasTimeWindow);
            case CompositeConditions.Repeat value -> hasTimeWindow(value.child());
            case CompositeConditions.OptionalChild value -> hasTimeWindow(value.child());
            case CompositeConditions.Choice value -> value.branches().stream().anyMatch(branch -> hasTimeWindow(branch.condition()));
            default -> false;
        };
    }
}
