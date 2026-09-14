package polycube.polyquest.commands;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.jspecify.annotations.Nullable;
import polycube.polyquest.PolyQuest;

import java.util.List;
import java.util.Objects;

public final class PolyQuestCommands {
    private static @Nullable List<PolyQuestCommand> commands;

    private PolyQuestCommands() {}

    /// Registers PolyQuest's complete built-in command set.
    public static void registerCommands() {
        registerCommandList(builtInCommands());
    }

    private static void registerCommandList(List<PolyQuestCommand> commands) {
        List<PolyQuestCommand> registeredCommands = List.copyOf(commands);
        PolyQuestCommands.commands = registeredCommands;
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, _) -> {
            var baseCommand = Commands.literal(PolyQuest.MOD_ID);
            baseCommand.executes(context -> printModInfo(context.getSource()));
            for (PolyQuestCommand command : registeredCommands) {
                for (var commandAlias : command.getCommands(buildContext)) {
                    baseCommand.then(commandAlias);
                    if (command.hasQuickAlias()) dispatcher.register(commandAlias);
                }
            }
            dispatcher.register(baseCommand);
            PolyQuest.LOGGER.debug("Registered {} PolyQuest subcommand(s)", registeredCommands.size());
        });
    }

    static List<PolyQuestCommand> builtInCommands() {
        return List.of(
                new HelpCommand(),
                new ListCommand(),
                new ClaimCommand(),
                new InspectCommand(),
                new SignalCommand(),
                new RerollCommand(),
                new ResetCommand()
        );
    }

    public static int printModInfo(CommandSourceStack cst) {
        var optionalModData = FabricLoader.getInstance()
                .getModContainer(PolyQuest.MOD_ID)
                .map(ModContainer::getMetadata);

        if (optionalModData.isEmpty()) {
            PolyQuest.LOGGER.warn("Could not find PolyQuest metadata while handling the base command");
            cst.sendFailure(CommandText.error("Could not fetch mod information."));
            return 0;
        }
        var modData = optionalModData.get();
        var authors = modData.getAuthors().stream()
                .map(Person::getName)
                .reduce((a, b) -> a + " and " + b)
                .orElse("Unknown authors");
        var modInfo = CommandText.header(modData.getName())
                .append(CommandText.muted(" v" + modData.getVersion().getFriendlyString()))
                .append(CommandText.field("Made by", CommandText.value(authors)))
                .append("\n" + modData.getDescription())
                .append("\n").append(CommandText.action("[View commands]", "/" + PolyQuest.MOD_ID + " help"));
        cst.sendSuccess(() -> modInfo, false);
        return 1;
    }

    public static List<PolyQuestCommand> getCommands() {
        return Objects.requireNonNull(commands, "PolyQuest commands are unavailable before registration");
    }
}
