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
import polycube.polycore.text.TextComponents;
import polycube.polyquest.api.PolyQuestApi;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.presentation.QuestDisplay;
import polycube.polyquest.runtime.QuestManager;

public abstract class QuestGui extends SimpleGui {
    private static final int REFRESH_INTERVAL_TICKS = 20;

    protected final ServerPlayer player;
    @SuppressWarnings("NotNullFieldNotInitialized")
    protected QuestManager manager;
    @SuppressWarnings("NotNullFieldNotInitialized")
    private QuestManager.Subscription subscription;
    private int tickCounter;
    private int interactionCooldown;

    @SuppressWarnings("this-escape")
    public QuestGui(MenuType<?> menuType, ServerPlayer player, Component title) {
        super(menuType, player, false);
        this.player = player;
        this.setTitle(title);
        open();
    }

    @Override
    public void onTick() {
        if (++tickCounter >= REFRESH_INTERVAL_TICKS) {
            refresh();
            tickCounter = 0;
        }
        if (interactionCooldown > 0) {
            interactionCooldown--;
        }
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
    public void onManualClose() {
        subscription.close();
        super.onManualClose();
    }

    @Override
    public void onPlayerClose(boolean success) {
        subscription.close();
        super.onPlayerClose(success);
    }

    @Override
    public void close() {
        subscription.close();
        super.close();
    }

    public abstract void refresh();

    protected static GuiElementBuilder createBackgroundSlot() {
        return new GuiElementBuilder()
                .setItem(Items.STAINED_GLASS_PANE.black())
                .hideDefaultTooltip()
                .setName(Component.empty());
    }

    // Renders the player-facing quest presentation and retains the existing claim callback.
    protected GuiElementBuilder createQuestSlot(QuestModel.Occurrence quest) {
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
                        player.sendSystemMessage(TextComponents.success("Successfully claimed quest: " + definition.title()));
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
}
