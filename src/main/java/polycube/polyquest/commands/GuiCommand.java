package polycube.polyquest.commands;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.PermissionLevel;
import polycube.polycore.commands.PolyCommand;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.gui.QuestGui;

import java.util.Objects;

public final class GuiCommand extends PolyCommand {
    public GuiCommand() {
        super(
                PolyQuest.MOD_ID,
                "gui",
                "",
                "",
                PermissionLevel.GAMEMASTERS
        );
    }

    @Override
    protected int execute(CommandSourceStack source) {
        new QuestGui(Objects.requireNonNull(source.getPlayer()));
        return 1;
    }
}
