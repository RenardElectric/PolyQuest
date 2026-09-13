package polycube.polyquest.integration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SimpleCommandExceptionType RUNTIME_UNAVAILABLE = new SimpleCommandExceptionType(
            Component.literal("PolyQuest runtime is not ready"));

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("polyquest")
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("claim")
                        .then(Commands.argument("quest", IdentifierArgument.id())
                                .executes(context -> claim(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "quest")))))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("quest", IdentifierArgument.id())
                                .executes(context -> inspect(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "quest")))))
                .then(Commands.literal("signal")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.argument("id", IdentifierArgument.id())
                                .executes(context -> signal(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "id")))))
                .then(Commands.literal("reroll")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.argument("difficulty", StringArgumentType.word())
                                .executes(context -> reroll(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "difficulty")))))
                .then(Commands.literal("reset")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.argument("quest", IdentifierArgument.id())
                                .executes(context -> reset(
                                        context.getSource(),
                                        IdentifierArgument.getId(context, "quest")))))
                .then(Commands.literal("retry-rewards")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> retryRewards(context.getSource()))));
    }

    private static int list(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        QuestManager manager = requireManager(source);
        source.sendSuccess(() -> Component.literal("Available PolyQuest quests:"), false);
        for (QuestModel.Occurrence occurrence : manager.available(player)) {
            boolean claimed = manager.ledger().isClaimed(player.getUUID(), occurrence.key());
            QuestAttempt attempt = manager.attempt(player, occurrence);
            String state = claimed ? "CLAIMED" : attempt.status().name();
            source.sendSuccess(() -> Component.literal("- " + occurrence.definition().id() + " [" + state + "] " + occurrence.definition().title()), false);
        }
        return 1;
    }

    private static int claim(CommandSourceStack source, Identifier id) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        QuestManager manager = requireManager(source);
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

    private static int inspect(CommandSourceStack source, Identifier id) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        QuestManager manager = requireManager(source);
        var occurrence = manager.engine().findOccurrence(player.getUUID(), id);
        if (occurrence.isEmpty()) {
            source.sendFailure(Component.literal("Quest is not currently available"));
            return 0;
        }
        QuestAttempt attempt = manager.attempt(player, occurrence.get());
        String json = GSON.toJson(attempt.diagnostic());
        source.sendSuccess(() -> Component.literal(json), false);
        return 1;
    }

    private static int signal(CommandSourceStack source, Identifier id) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        QuestManager manager = requireManager(source);
        manager.signal(new polycube.polyquest.signal.QuestSignal.Explicit(
                player, source.getServer().getTickCount(), id));
        source.sendSuccess(() -> Component.literal("Emitted quest signal " + id), false);
        return 1;
    }

    private static int reroll(CommandSourceStack source, String rawDifficulty) throws CommandSyntaxException {
        QuestManager manager = requireManager(source);
        var difficulty = QuestModel.Difficulty.byName(rawDifficulty);
        if (difficulty.isEmpty()) {
            source.sendFailure(Component.literal("Expected easy, medium, or hard"));
            return 0;
        }
        if (!manager.reroll(difficulty.get())) {
            source.sendFailure(Component.literal("No daily quest is available for that difficulty"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Rerolled the " + rawDifficulty + " daily slot"), true);
        return 1;
    }

    private static int reset(CommandSourceStack source, Identifier id) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        QuestManager manager = requireManager(source);
        var occurrence = manager.engine().findOccurrence(player.getUUID(), id);
        if (occurrence.isEmpty()) {
            source.sendFailure(Component.literal("Quest is not currently available"));
            return 0;
        }
        manager.engine().reset(player.getUUID(), occurrence.get());
        source.sendSuccess(() -> Component.literal("Reset " + id + " for yourself"), true);
        return 1;
    }

    private static int retryRewards(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        QuestManager manager = requireManager(source);
        var results = manager.claims().retryPending(player, true);
        source.sendSuccess(() -> Component.literal("Retried " + results.size() + " transaction(s)"), true);
        return 1;
    }

    private static QuestManager requireManager(CommandSourceStack source) throws CommandSyntaxException {
        return QuestRuntime.manager().orElseThrow(RUNTIME_UNAVAILABLE::create);
    }

    private QuestCommands() {
    }
}
