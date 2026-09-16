package polycube.polyquest.commands;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.PermissionLevel;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.gui.QuestGui;

public class HelpCommand extends PolyQuestCommand {
    public HelpCommand() {
        super(
                "help",
                "Displays a list of available commands and their descriptions",
                "",
                PermissionLevel.ALL
        );
    }

    @Override
    protected int execute(CommandSourceStack source) {
        var helpMessage = CommandText.header("Commands")
                .append("\nClick a command to prepare it; use [Usage] for its syntax.");
        for (PolyQuestCommand command : PolyQuestCommands.getCommands()) {
            if (hasPermission(source, command.getPermissionLevel())) {
                String root = "/" + PolyQuest.MOD_ID + " " + command.getName();
                helpMessage.append("\n\n  ").append(CommandText.action(root, root + " "));
                helpMessage.append(" ").append(CommandText.action("[Usage]", root + " help"));
                if (command.getPermissionLevel() != PermissionLevel.ALL) helpMessage.append(CommandText.muted(" (Admin only)"));
                helpMessage.append("\n  " + command.getDescription());
            }
        }
        source.sendSuccess(() -> helpMessage, false);
        new QuestGui(source.getPlayer());
        return 1;
    }
}
