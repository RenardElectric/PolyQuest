package polycube.polyquest.runtime;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
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
    private final AdvancementCriterionTracker advancementCriteria;
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
        this.advancementCriteria = new AdvancementCriterionTracker(server);
        this.engine = new QuestEngine(server, catalogs, rotation, ledger, advancementCriteria);
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

    /// Runs rotation checks, timed conditions, and scheduled reward retries.
    public void tick() {
        long tick = server.getTickCount();
        if (tick % 20L == 0L) {
            refreshRotationAndEngine();
        }
        publishProgress(engine.tick(tick));
        if (tick >= nextRewardRetryTick) {
            nextRewardRetryTick = tick + config.pendingRewardRetrySeconds() * 20L;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                claims.retryPending(player, false);
            }
        }
    }

    public void signal(QuestSignal signal) {
        publishProgress(engine.onSignal(signal));
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
        refreshRotationAndEngine();
        engine.playerJoined(player);
        // Fabric JOIN runs before PlayerList indexes the player, so bind newly created criteria directly.
        advancementCriteria.rebind(player);
        claims.retryPending(player, false);
        notifyDailyRotation(player);
        if (pendingResetNotifications.remove(player.getUUID())) {
            sendResetNotification(player);
        }
        sendUnclaimedSummary(player);
    }

    public void onPlayerDisconnect(ServerPlayer player) {
        advancementCriteria.disconnect(player);
    }

    /// Reattaches fake criteria after Minecraft rebuilds every player's advancement state.
    public void onDataPackReload() {
        server.getPlayerList().getPlayers().forEach(advancementCriteria::rebind);
    }

    public void shutdown() {
        catalogSubscription.close();
        engine.close();
        advancementCriteria.close();
        questChanges.clear();
        pendingResetNotifications.clear();
    }

    /// Returns the list of quests currently available to the given player.
    public List<QuestModel.Occurrence> available() {
        return engine.available();
    }

    /// Returns the current attempt state for the given player and quest occurrence.
    public QuestAttempt attempt(NameAndId player, QuestModel.Occurrence occurrence) {
        return engine.attempt(player.id(), occurrence);
    }

    public Optional<QuestModel.Occurrence> findOccurrence(Identifier questId) {
        return engine.findOccurrence(questId);
    }

    /// Returns a reward profile from the currently active datapack catalog.
    public Optional<RewardApi.Profile> rewardProfile(Identifier profileId) {
        return Optional.ofNullable(catalogs.current().rewardProfiles().get(profileId));
    }

    public QuestModel.DailyAssignment dailyAssignment() {
        return rotation.current();
    }

    public QuestClaimService.ClaimResult claim(ServerPlayer player, Identifier questId) {
        refreshRotationAndEngine();
        return claims.claim(player, questId);
    }

    public QuestClaimService.ClaimResult forceClaim(ServerPlayer player, Identifier questId) {
        refreshRotationAndEngine();
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

    /// Converts a fake vanilla advancement award into the corresponding quest signal.
    public boolean interceptAdvancementCriterion(
            ServerPlayer player,
            AdvancementHolder holder,
            String criterionName
    ) {
        var award = advancementCriteria.intercept(player, holder, criterionName);
        award.registrationId().ifPresent(id -> signal(new QuestSignal.CriteriaMatched(player, server.getTickCount(), Set.of(id))));
        return award.intercepted();
    }

    public void beginAdvancementCriterionBatch(ServerPlayer player) {
        advancementCriteria.beginMatchBatch(player);
    }

    public void endAdvancementCriterionBatch(ServerPlayer player) {
        var matches = advancementCriteria.endMatchBatch(player);
        if (!matches.isEmpty()) {
            signal(new QuestSignal.CriteriaMatched(player, server.getTickCount(), matches));
        }
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

    private void refreshRotationAndEngine() {
        var result = refreshRotation();
        if (result.assignmentChanged()) {
            engine.rotationChanged();
            questChanges.changed();
        }
    }

    private void notifyDailyRotation(ServerPlayer player) {
        if (!rotation.current().slots().isEmpty() && ledger.markRotationNotified(player.getUUID())) {
            player.sendSystemMessage(QuestCommandText.dailyRotation());
        }
    }

    private static void sendResetNotification(ServerPlayer player) {
        player.sendSystemMessage(QuestCommandText.progressReset());
    }

    /// Announces only fresh ready transitions; reconnects get a current summary instead.
    private void publishProgress(QuestEngine.ProgressResult progress) {
        if (progress.changed()) questChanges.changed();
        var notifiedPlayers = new HashSet<UUID>();
        for (var completed : progress.completed()) {
            var player = server.getPlayerList().getPlayer(completed.playerId());
            if (player == null) continue;
            player.sendSystemMessage(QuestCommandText.questCompleted(completed.occurrence()));
            notifiedPlayers.add(completed.playerId());
        }
        for (var playerId : notifiedPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) sendUnclaimedSummary(player);
        }
    }

    private void sendUnclaimedSummary(ServerPlayer player) {
        var ready = engine.available().stream()
                .filter(occurrence -> engine.existingAttempt(player.getUUID(), occurrence.key())
                        .map(attempt -> attempt.status() == QuestModel.AttemptStatus.READY_TO_CLAIM)
                        .orElse(false))
                .toList();
        QuestCommandText.unclaimedSummary(ready).ifPresent(player::sendSystemMessage);
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
