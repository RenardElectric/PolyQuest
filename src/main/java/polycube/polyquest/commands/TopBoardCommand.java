package polycube.polyquest.commands;

import net.minecraft.server.permissions.PermissionLevel;

public class TopBoardCommand extends PolyQuestCommand {
    public TopBoardCommand() {
        super(
                "topboard",
                "Displays the top board for a given economy provider and currency.",
                "[currencyId] <limit>",
                PermissionLevel.GAMEMASTERS
        );
    }
}
