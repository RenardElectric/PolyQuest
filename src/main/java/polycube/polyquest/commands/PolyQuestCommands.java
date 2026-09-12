package polycube.polyquest.commands;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.jspecify.annotations.Nullable;
import polycube.polyquest.PolyQuest;

import java.util.Objects;

public final class PolyQuestCommands {
    private static PolyQuestCommand @Nullable [] commands;

    private PolyQuestCommands() {}

    public static void registerCommands(PolyQuestCommand... commands) {
        PolyQuestCommands.commands = commands;
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, _) -> {
            var baseCommand = Commands.literal(PolyQuest.MOD_ID);
            baseCommand.executes(context -> printModInfo(context.getSource()));
            for (PolyQuestCommand command : commands) {
                for (var commandAlias : command.getCommands(buildContext)) {
                    baseCommand.then(commandAlias);
                    if (command.hasQuickAlias()) dispatcher.register(commandAlias);
                }
            }
            dispatcher.register(baseCommand);
            PolyQuest.LOGGER.debug("Registered {} PolyCoin subcommand(s)", commands.length);
        });
    }

    public static int printModInfo(CommandSourceStack cst) {
        var optionalModData = FabricLoader.getInstance()
                .getModContainer(PolyQuest.MOD_ID)
                .map(ModContainer::getMetadata);

        if (optionalModData.isEmpty()) {
            PolyQuest.LOGGER.warn("Could not find PolyCoin metadata while handling the base command");
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

    public static PolyQuestCommand[] getCommands() {
        return Objects.requireNonNull(commands, "PolyCoin commands are unavailable before registration");
    }
}
