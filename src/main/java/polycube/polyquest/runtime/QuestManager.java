package polycube.polyquest.runtime;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;
import polycube.polyquest.claim.QuestClaimService;
import polycube.polyquest.commands.QuestCommandText;
import polycube.polyquest.config.QuestConfig;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.persistence.QuestLedger;
import polycube.polyquest.resource.QuestCatalogManager;
import polycube.polyquest.reward.RewardApi;
import polycube.polyquest.rotation.DailyRotationService;
import polycube.polyquest.signal.QuestSignal;

import java.util.*;

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
    private final Set<UUID> pendingResetNotifications = new HashSet<>();
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
        refreshRotationAndEngine();
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
            changed |= refreshRotationAndEngine();
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
        boolean changed = refreshRotation().assignmentChanged();
        for (var difficulty : difficulties) {
            changed |= rotation.reroll(difficulty, catalogs.current(), ledger);
        }
        if (changed) {
            engine.rotationChanged();
            questChanges.changed();
        }
        return changed;
    }

    public void onPlayerJoin(ServerPlayer player) {
        claims.retryPending(player, false);
        notifyDailyRotation(player);
        if (pendingResetNotifications.remove(player.getUUID())) {
            sendResetNotification(player);
        }
    }

    public void shutdown() {
        catalogSubscription.close();
        engine.close();
        questChanges.clear();
        pendingResetNotifications.clear();
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

    /// Returns a reward profile from the currently active datapack catalog.
    public Optional<RewardApi.Profile> rewardProfile(Identifier profileId) {
        return Optional.ofNullable(catalogs.current().rewardProfiles().get(profileId));
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
        DailyRotationService.RefreshResult rotationResult = refreshRotation();
        for (var playerId : engine.onCatalogChanged(update)) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                pendingResetNotifications.add(playerId);
            } else {
                sendResetNotification(player);
            }
        }
        if (update.diff().hasChanges() || rotationResult.assignmentChanged()) questChanges.changed();
    }

    public boolean isClaimed(NameAndId player, QuestModel.Key key) {
        return ledger.isClaimed(player.id(), key);
    }

    /// Refreshes daily state and records exactly which players received the new-day notice.
    private DailyRotationService.RefreshResult refreshRotation() {
        DailyRotationService.RefreshResult result = rotation.refresh(catalogs.current(), ledger);
        if (result.dateChanged()) {
            server.getPlayerList().getPlayers().forEach(this::notifyDailyRotation);
        }
        return result;
    }

    private boolean refreshRotationAndEngine() {
        var result = refreshRotation();
        if (result.assignmentChanged()) {
            engine.rotationChanged();
            pendingResetNotifications.clear();
        }
        return result.assignmentChanged();
    }

    private void notifyDailyRotation(ServerPlayer player) {
        if (!rotation.current().slots().isEmpty() && ledger.markRotationNotified(player.getUUID())) {
            player.sendSystemMessage(QuestCommandText.dailyRotation());
        }
    }

    private static void sendResetNotification(ServerPlayer player) {
        player.sendSystemMessage(QuestCommandText.progressReset());
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
