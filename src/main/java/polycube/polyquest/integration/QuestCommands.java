package polycube.polyquest.integration;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import polycube.polyquest.claim.QuestClaimService;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.runtime.QuestAttempt;
import polycube.polyquest.runtime.QuestManager;
import polycube.polyquest.runtime.QuestRuntime;

/// Minimal commands for in-game validation and administration.
///
/// They are intentionally a thin adapter over the domain services and can be replaced
/// by a custom UI later without moving any quest logic into the command layer.
public final class QuestCommands {
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("polyquest")
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("claim")
                        .then(Commands.argument("quest", StringArgumentType.string())
                                .executes(context -> claim(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "quest")))))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("quest", StringArgumentType.string())
                                .executes(context -> inspect(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "quest")))))
                .then(Commands.literal("signal")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.argument("id", StringArgumentType.string())
                                .executes(context -> signal(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("reroll")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.argument("difficulty", StringArgumentType.string())
                                .executes(context -> reroll(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "difficulty")))))
                .then(Commands.literal("reset")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.argument("quest", StringArgumentType.string())
                                .executes(context -> reset(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "quest")))))
                .then(Commands.literal("retry-rewards")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> retryRewards(context.getSource()))));
    }

    private static int list(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        QuestManager manager = requireManager(source);
        if (manager == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Available PolyQuest quests:"), false);
        for (QuestModel.Occurrence occurrence : manager.available(player)) {
            boolean claimed = manager.ledger().isClaimed(player.getUUID(), occurrence.key());
            QuestAttempt attempt = manager.attempt(player, occurrence);
            String state = claimed ? "CLAIMED" : attempt.status().name();
            source.sendSuccess(() -> Component.literal(
                    "- " + occurrence.definition().id() + " [" + state + "] "
                            + occurrence.definition().title()), false);
        }
        return 1;
    }

    private static int claim(CommandSourceStack source, String rawId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        QuestManager manager = requireManager(source);
        Identifier id = parseId(source, rawId);
        if (manager == null || id == null) {
            return 0;
        }
        QuestClaimService.ClaimResult result = manager.claims().claim(player, id);
        if (result.successful()) {
            source.sendSuccess(() -> Component.literal(result.message()), false);
            return 1;
        }
        if (result.state() == QuestClaimService.ClaimState.PENDING) {
            source.sendSuccess(() -> Component.literal("Reward pending: " + result.message()), false);
            return 1;
        }
        source.sendFailure(Component.literal(result.message()));
        return 0;
    }

    private static int inspect(CommandSourceStack source, String rawId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        QuestManager manager = requireManager(source);
        Identifier id = parseId(source, rawId);
        if (manager == null || id == null) {
            return 0;
        }
        QuestModel.Occurrence occurrence = manager.engine()
                .findOccurrence(player.getUUID(), id)
                .orElse(null);
        if (occurrence == null) {
            source.sendFailure(Component.literal("Quest is not currently available"));
            return 0;
        }
        QuestAttempt attempt = manager.attempt(player, occurrence);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(attempt.diagnostic());
        source.sendSuccess(() -> Component.literal(json), false);
        return 1;
    }

    private static int signal(CommandSourceStack source, String rawId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        QuestManager manager = requireManager(source);
        Identifier id = parseId(source, rawId);
        if (manager == null || id == null) {
            return 0;
        }
        manager.signal(new polycube.polyquest.signal.QuestSignal.Explicit(
                player, source.getServer().getTickCount(), id));
        source.sendSuccess(() -> Component.literal("Emitted quest signal " + id), false);
        return 1;
    }

    private static int reroll(CommandSourceStack source, String rawDifficulty) {
        QuestManager manager = requireManager(source);
        QuestModel.Difficulty difficulty = QuestModel.Difficulty.byName(rawDifficulty).orElse(null);
        if (manager == null || difficulty == null) {
            source.sendFailure(Component.literal("Expected easy, medium, or hard"));
            return 0;
        }
        manager.reroll(difficulty);
        source.sendSuccess(() -> Component.literal("Rerolled the " + rawDifficulty + " daily slot"), true);
        return 1;
    }

    private static int reset(CommandSourceStack source, String rawId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        QuestManager manager = requireManager(source);
        Identifier id = parseId(source, rawId);
        if (manager == null || id == null) {
            return 0;
        }
        QuestModel.Occurrence occurrence = manager.engine()
                .findOccurrence(player.getUUID(), id)
                .orElse(null);
        if (occurrence == null) {
            source.sendFailure(Component.literal("Quest is not currently available"));
            return 0;
        }
        manager.engine().reset(player.getUUID(), occurrence);
        source.sendSuccess(() -> Component.literal("Reset " + id + " for yourself"), true);
        return 1;
    }

    private static int retryRewards(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        QuestManager manager = requireManager(source);
        if (manager == null) {
            return 0;
        }
        var results = manager.claims().retryPending(player, true);
        source.sendSuccess(() -> Component.literal("Retried " + results.size() + " transaction(s)"), true);
        return 1;
    }

    private static QuestManager requireManager(CommandSourceStack source) {
        QuestManager manager = QuestRuntime.manager().orElse(null);
        if (manager == null) {
            source.sendFailure(Component.literal("PolyQuest runtime is not readyPrep"));
        }
        return manager;
    }

    private static Identifier parseId(CommandSourceStack source, String rawId) {
        try {
            return Identifier.parse(rawId);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("Invalid identifier: " + rawId));
            return null;
        }
    }

    private QuestCommands() {
    }
}
