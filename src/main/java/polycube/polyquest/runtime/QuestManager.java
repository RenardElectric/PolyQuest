package polycube.polyquest.runtime;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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

import java.util.List;
import java.util.Optional;

/// Server-scoped facade used by Fabric callbacks, commands, and future UI adapters.
public final class QuestManager {
    private final MinecraftServer server;
    private final QuestConfig config;
    private final QuestCatalogManager catalogs;
    private final QuestLedger ledger;
    private final DailyRotationService rotation;
    private final QuestEngine engine;
    private final QuestClaimService claims;
    private final QuestChangeNotifier questChanges = new QuestChangeNotifier();
    private final QuestCatalogManager.Subscription catalogSubscription;
    private long nextRewardRetryTick;

    public QuestManager(MinecraftServer server, QuestConfig config, QuestCatalogManager catalogs) {
        this.server = server;
        this.config = config;
        this.catalogs = catalogs;
        this.ledger = QuestLedger.load(server);
        this.rotation = new DailyRotationService(config);
        this.engine = new QuestEngine(server, catalogs, rotation, ledger);
        this.claims = new QuestClaimService(server, engine, ledger, catalogs, questChanges::changed);
        this.catalogSubscription = this.catalogs.addListener(this::onCatalogChanged);
        refreshRotation(false);
    }

    public MinecraftServer server() {
        return server;
    }

    /// Registers a server-thread callback for every GUI-visible quest state change.
    /// The listener is not called immediately; callers should render their initial state first.
    public Subscription addListener(Runnable listener) {
        return questChanges.addListener(listener);
    }

    /// Runs rotation checks, timed conditions, player-tick signals, and scheduled reward retries.
    public void tick() {
        long tick = server.getTickCount();
        boolean changed = false;
        if (tick % 20L == 0L) {
            changed |= refreshRotation(false);
        }
        changed |= engine.tick(tick);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            changed |= engine.onSignal(new QuestSignal.PlayerTick(player, tick));
        }
        if (changed) questChanges.changed();
        if (tick >= nextRewardRetryTick) {
            nextRewardRetryTick = tick + config.pendingRewardRetrySeconds() * 20L;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                claims.retryPending(player, false);
            }
        }
    }

    public void signal(QuestSignal signal) {
        if (engine.onSignal(signal)) questChanges.changed();
    }

    public boolean reroll(List<QuestModel.Difficulty> difficulties) {
        boolean changed = false;
        for (var difficulty : difficulties) {
            changed |= rotation.reroll(difficulty, catalogs.current(), ledger);
        }
        if (changed) {
            engine.rotationChanged();
            announceRotation();
            questChanges.changed();
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
        questChanges.clear();
    }

    /// Returns the list of quests currently available to the given player.
    public List<QuestModel.Occurrence> available(NameAndId player) {
        return engine.available(player.id());
    }

    /// Returns the current attempt state for the given player and quest occurrence.
    public QuestAttempt attempt(NameAndId player, QuestModel.Occurrence occurrence) {
        return engine.attempt(player.id(), occurrence);
    }

    public Optional<QuestModel.Occurrence> findOccurrence(NameAndId player, Identifier questId) {
        return engine.findOccurrence(player.id(), questId);
    }

    public QuestModel.DailyAssignment dailyAssignment() {
        return rotation.current();
    }

    public QuestClaimService.ClaimResult claim(ServerPlayer player, Identifier questId) {
        return claims.claim(player, questId);
    }

    public QuestClaimService.ClaimResult forceClaim(ServerPlayer player, Identifier questId) {
        return claims.forceClaim(player, questId);
    }

    public boolean reset(NameAndId player, QuestModel.Occurrence occurrence) {
        boolean changed = engine.reset(player.id(), occurrence);
        if (changed) questChanges.changed();
        return changed;
    }

    private void onCatalogChanged(QuestCatalogManager.Update update) {
        boolean rotationChanged = refreshRotation(true);
        engine.onCatalogChanged(update);
        QuestCatalogManager.Diff diff = update.diff();
        boolean catalogChanged = !diff.added().isEmpty()
                || !diff.removed().isEmpty()
                || !diff.behaviorChanged().isEmpty()
                || !diff.presentationChanged().isEmpty();
        if (catalogChanged || rotationChanged) questChanges.changed();
    }

    public boolean isClaimed(NameAndId player, QuestModel.Key key) {
        return ledger.isClaimed(player.id(), key);
    }

    /// Synchronizes changed daily occurrences into the engine and handles their configured announcement.
    private boolean refreshRotation(boolean catalogReload) {
        boolean changed = rotation.refresh(catalogs.current(), ledger);
        if (changed) {
            engine.rotationChanged();
            if (!catalogReload || config.announceRotation()) {
                announceRotation();
            }
        }
        return changed;
    }

    private void announceRotation() {
        if (!config.announceRotation() || rotation.current().slots().isEmpty()) return;
        server.getPlayerList().broadcastSystemMessage(Component.literal("New PolyQuest daily quests are available."), false);
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
