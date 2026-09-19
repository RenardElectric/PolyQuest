package polycube.polyquest.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Either;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import polycube.polycore.NpcCreator;
import polycube.polycore.commands.PolyCommand;
import polycube.polycore.text.TextComponents;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.gui.QuestJournalGui;

import java.util.Optional;
import java.util.UUID;

public final class PnjCommand extends PolyCommand {
    private static final Identifier QUEST_GIVER_TYPE = PolyQuest.id("quest_giver");
    private static final NpcCreator.NpcFactory QUEST_GIVER_FACTORY = _ -> new QuestGiverCallback();

    public PnjCommand() {
        super(
                PolyQuest.MOD_ID,
                "pnj",
                "Spawns an immovable mannequin that opens the quest journal",
                " | <pos> <yaw> <pitch>",
                PermissionLevel.GAMEMASTERS
        );
        NpcCreator.registerNpcType(QUEST_GIVER_TYPE, QUEST_GIVER_FACTORY);
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getCommand(String name) {
        return super.getCommand(name)
                .then(Commands.argument("pos", Vec3Argument.vec3())
                                .then(Commands.argument("rotation", RotationArgument.rotation())
                                            .executes(context -> spawn(
                                                    context.getSource(),
                                                    Vec3Argument.getVec3(context, "pos"),
                                                    RotationArgument.getRotation(context, "rotation").getRotation(context.getSource())))));
    }

    @Override
    protected int execute(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        return spawn(source, player.position(), Vec2.ZERO);
    }

    private static int spawn(CommandSourceStack source, Vec3 pos, Vec2 rotation) {

        // skin from: https://www.minecraftskins.com/skin/24256690/fundy-explorer/
        var skinPatch = PlayerSkin.Patch.create(
                Optional.of(new ClientAsset.ResourceTexture.ResourceTexture(PolyQuest.id("quest_giver_skin"))),
                Optional.empty(), Optional.empty(),
                Optional.of(PlayerModelType.WIDE)
        );
        var patch = new ResolvableProfile.Static(Either.left(new GameProfile(UUID.randomUUID(), "quest_giver")), skinPatch);

        var result = NpcCreator.summonNpc(
                source.getLevel(), QUEST_GIVER_TYPE,
                patch, pos, rotation, Pose.STANDING
        );

        if (result.error().isPresent()) {
            source.sendFailure(TextComponents.error("Failed to create the quest giver: " + result.error().get()));
            return 0;
        }

        source.sendSuccess(
                () -> TextComponents.success("Spawned quest giver at")
                        .append(TextComponents.value(" %.2f %.2f %.2f".formatted(pos.x, pos.y, pos.z)))
                        .append(TextComponents.muted(" (yaw: %.2f, pitch: %.2f)".formatted(Mth.wrapDegrees(rotation.x), Mth.wrapDegrees(rotation.y)))),
                true
        );
        return 1;
    }

    private static final class QuestGiverCallback implements NpcCreator.NpcCallback {
        @Override
        public void update(Display.TextDisplay textDisplay, Mannequin mannequin) {
            textDisplay.setBillboardConstraints(Display.BillboardConstraints.VERTICAL);
            textDisplay.setBackgroundColor(0x00000000);
            textDisplay.setFlags(Display.TextDisplay.FLAG_SHADOW);
            textDisplay.setBrightnessOverride(Brightness.FULL_BRIGHT);
            textDisplay.setText(Component.empty()
                    .append(Component.literal("✦ Quest Giver ✦").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                    .append(Component.literal("\nInteract to browse quests").withStyle(ChatFormatting.YELLOW)));
        }

        @Override
        public void onInteract(ServerPlayer player) {
            QuestJournalGui.open(player);
        }

        @Override
        public void onAttack(ServerPlayer player) {
            QuestJournalGui.open(player);
        }
    }
}
