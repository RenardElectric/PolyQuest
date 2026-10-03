package polycube.polyquest.commands;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.PermissionLevel;
import polycube.polycore.commands.PolyCommand;
import polycube.polyquest.gui.QuestJournalGui;

public final class GuiCommand extends PolyCommand {
    public GuiCommand() {
        super(
                "gui",
                "Opens the quest journal GUI.",
                PermissionLevel.GAMEMASTERS
        );
    }

    @Override
    protected int execute(CommandSourceStack source) throws CommandSyntaxException {
        QuestJournalGui.open(source.getPlayerOrException());
        return 1;
    }
}
