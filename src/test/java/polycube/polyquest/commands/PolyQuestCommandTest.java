package polycube.polyquest.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.PermissionLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PolyQuestCommandTest {
    private static final List<PolyQuestCommand> COMMANDS = PolyQuestCommands.builtInCommands();

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void exposesTheCompletePortedCommandSet() {
        assertEquals(
                List.of("help", "list", "claim", "inspect", "signal", "reroll", "reset"),
                COMMANDS.stream().map(PolyQuestCommand::getName).toList());
        assertEquals(
                List.of(
                        PermissionLevel.ALL,
                        PermissionLevel.GAMEMASTERS,
                        PermissionLevel.GAMEMASTERS,
                        PermissionLevel.GAMEMASTERS,
                        PermissionLevel.GAMEMASTERS,
                        PermissionLevel.GAMEMASTERS,
                        PermissionLevel.GAMEMASTERS
                ),
                COMMANDS.stream().map(PolyQuestCommand::getPermissionLevel).toList());
    }

    @Test
    void argumentBranchesArePresent() {
        assertNotNull(new ListCommand().getCommand("list").build().getChild("player"));
        assertNotNull(new ClaimCommand().getCommand("claim").build().getChild("quest"));
        assertNotNull(new InspectCommand().getCommand("inspect").build().getChild("quest"));
        assertNotNull(new SignalCommand().getCommand("signal").build().getChild("id"));
        assertNotNull(new RerollCommand().getCommand("reroll").build().getChild("difficulty"));
        assertNotNull(new ResetCommand().getCommand("reset").build().getChild("quest"));
    }

    @Test
    void listUsageDoesNotRepeatItsCommandName() {
        String help = new ListCommand().getFullDescription().getString();
        assertTrue(help.contains("/polyquest list [player]"));
        assertFalse(help.contains("/polyquest list list [player]"));
    }
}
