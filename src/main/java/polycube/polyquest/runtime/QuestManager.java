package polycube.polyquest.runtime;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import polycube.polyquest.claim.QuestClaimService;
import polycube.polyquest.config.QuestConfig;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.persistence.QuestLedger;
import polycube.polyquest.resource.QuestCatalogManager;
import polycube.polyquest.rotation.DailyRotationService;
import polycube.polyquest.signal.QuestSignal;

/// Server-scoped facade used by Fabric callbacks, commands, and future UI adapters.
public final class QuestManager {
    private final MinecraftServer server;
    private final QuestConfig config;
    private final QuestCatalogManager catalogs;
    private final QuestLedger ledger;
    private final DailyRotationService rotation;
    private final QuestEngine engine;
    private final QuestClaimService claims;
    private final QuestCatalogManager.Subscription catalogSubscription;
    private long nextRewardRetryTick;

    public QuestManager(MinecraftServer server, QuestConfig config, QuestCatalogManager catalogs) {
        this.server = server;
        this.config = config;
        this.catalogs = catalogs;
        this.ledger = QuestLedger.load(server);
        this.rotation = new DailyRotationService(config);
        this.engine = new QuestEngine(server, catalogs, rotation, ledger);
        this.claims = new QuestClaimService(server, engine, ledger, catalogs);
        this.catalogSubscription = this.catalogs.addListener(_ -> refreshRotation(true));
        refreshRotation(false);
    }

    public MinecraftServer server() {
        return server;
    }

    public QuestEngine engine() {
        return engine;
    }

    public QuestClaimService claims() {
        return claims;
    }

    public QuestLedger ledger() {
        return ledger;
    }

    public DailyRotationService rotation() {
        return rotation;
    }

    /// Runs rotation checks, timed conditions, player-tick signals, and scheduled reward retries.
    public void tick() {
        long tick = server.getTickCount();
        if (tick % 20L == 0L) {
            refreshRotation(false);
        }
        engine.tick(tick);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            engine.onSignal(new QuestSignal.PlayerTick(player, tick));
        }
        if (tick >= nextRewardRetryTick) {
            nextRewardRetryTick = tick + config.pendingRewardRetrySeconds() * 20L;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                claims.retryPending(player, false);
            }
        }
    }

    public void signal(QuestSignal signal) {
        engine.onSignal(signal);
    }

    public boolean reroll(List<QuestModel.Difficulty> difficulties) {
        boolean changed = false;
        for (var difficulty : difficulties) {
            changed |= rotation.reroll(difficulty, catalogs.current(), ledger);
        }
        if (changed) {
            engine.rotationChanged();
            announceRotation();
        }
        return changed;
    }

    public void onPlayerJoin(ServerPlayer player) {
        claims.retryPending(player, false);
    }

    public void onPlayerDisconnect(ServerPlayer player) {
        engine.playerDisconnected(player.getUUID());
    }

    public void shutdown() {
        catalogSubscription.close();
        engine.close();
        ledger.flush();
    }

    /// Returns the list of quests currently available to the given player.
    public List<QuestModel.Occurrence> available(NameAndId player) {
        return engine.available(player.id());
    }

    /// Returns the current attempt state for the given player and quest occurrence.
    public QuestAttempt attempt(NameAndId player, QuestModel.Occurrence occurrence) {
        return engine.attempt(player.id(), occurrence);
    }

    /// Synchronizes changed daily occurrences into the engine and handles their configured announcement.
    private void refreshRotation(boolean catalogReload) {
        boolean changed = rotation.refresh(catalogs.current(), ledger);
        if (changed) {
            engine.rotationChanged();
            if (!catalogReload || config.announceRotation()) {
                announceRotation();
            }
        }
    }

    private void announceRotation() {
        if (!config.announceRotation() || rotation.current().slots().isEmpty()) return;
        server.getPlayerList().broadcastSystemMessage(Component.literal("New PolyQuest daily quests are available."), false);
    }
}
