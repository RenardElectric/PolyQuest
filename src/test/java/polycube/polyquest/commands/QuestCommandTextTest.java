package polycube.polyquest.commands;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import polycube.polycore.text.TextComponents;
import polycube.polyquest.condition.BuiltInConditions;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.RewardApi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuestCommandTextTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void summaryNamesAllReadyQuestsAndUsesCorrectCount() {
        var first = occurrence("first", "Find the Relic");
        var second = occurrence("second", "Explore the Cave");
        TextComponents.modName = "PolyQuest";

        assertTrue(QuestCommandText.unclaimedSummary(List.of()).isEmpty());
        assertEquals("[PolyQuest] 1 quest ready to claim: Find the Relic. Visit the Quest Giver.",
                QuestCommandText.unclaimedSummary(List.of(first)).orElseThrow().getString());
        assertEquals("[PolyQuest] 2 quests ready to claim: Find the Relic, Explore the Cave. Visit the Quest Giver.",
                QuestCommandText.unclaimedSummary(List.of(first, second)).orElseThrow().getString());
    }

    @Test
    void completionNoticeNamesTheQuestAndWhereToClaim() {
        String message = QuestCommandText.questCompleted(occurrence("first", "Find the Relic")).getString();

        assertTrue(message.contains("Quest completed: Find the Relic"));
        assertTrue(message.contains("Quest Giver"));
    }

    private static QuestModel.Occurrence occurrence(String path, String title) {
        Identifier id = Identifier.fromNamespaceAndPath("test", path);
        var definition = new QuestModel.Definition(
                id, QuestModel.Availability.UNIQUE, Optional.empty(), title, List.of(),
                Items.BOOK, new BuiltInConditions.ExplicitSignal(id, 1),
                new RewardApi.Plan(Optional.empty(), List.of()), "hash");
        return new QuestModel.Occurrence(
                new QuestModel.Key(id, new QuestModel.UniqueScope()), definition, Instant.EPOCH, Optional.empty());
    }
}
