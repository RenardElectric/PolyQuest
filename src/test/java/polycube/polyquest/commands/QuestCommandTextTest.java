package polycube.polyquest.commands;

import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.TextColor;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuestCommandTextTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        TextComponents.modName = "PolyQuest";
    }

    @Test
    void summaryNamesReadyQuestsAndUsesCorrectCount() {
        var first = occurrence("first", "Find the Relic");
        var second = occurrence("second", "Explore the Cave");
        assertTrue(QuestCommandText.unclaimedSummary(List.of(), QuestCommandTextTest::details).isEmpty());
        assertEquals("[PolyQuest] 1 quest ready to claim: [Find the Relic]. Visit the Quest Giver.",
                QuestCommandText.unclaimedSummary(List.of(first), QuestCommandTextTest::details)
                        .orElseThrow().getString());
        assertEquals("[PolyQuest] 2 quests ready to claim: [Find the Relic], [Explore the Cave]. Visit the Quest Giver.",
                QuestCommandText.unclaimedSummary(List.of(first, second), QuestCommandTextTest::details)
                        .orElseThrow().getString());
    }

    @Test
    void summaryCutsOffLongListsWithoutLosingTheTotal() {
        var ready = List.of(
                occurrence("first", "First"), occurrence("second", "Second"),
                occurrence("third", "Third"), occurrence("fourth", "Fourth"),
                occurrence("fifth", "Fifth"), occurrence("sixth", "Sixth"));
        var message = QuestCommandText.unclaimedSummary(ready, QuestCommandTextTest::details).orElseThrow();

        assertEquals("[PolyQuest] 6 quests ready to claim: [First], [Second], [Third], [Fourth], ... and 2 more. Visit the Quest Giver.",
                message.getString());
        assertFalse(message.getString().contains("Fifth"));
        assertEquals(4, message.getSiblings().stream()
                .filter(part -> part.getStyle().getHoverEvent() instanceof HoverEvent.ShowText)
                .count());
    }

    @Test
    void questNamesUseQuestGiverColorsAndHoverDetails() {
        var easy = dailyOccurrence("easy", "Easy Quest", QuestModel.Difficulty.EASY);
        var medium = dailyOccurrence("medium", "Medium Quest", QuestModel.Difficulty.MEDIUM);
        var hard = dailyOccurrence("hard", "Hard Quest", QuestModel.Difficulty.HARD);
        var unique = occurrence("unique", "Unique Quest");

        assertQuestName(easy, ChatFormatting.GREEN);
        assertQuestName(medium, ChatFormatting.GOLD);
        assertQuestName(hard, ChatFormatting.RED);
        assertQuestName(unique, ChatFormatting.LIGHT_PURPLE);
    }

    @Test
    void completionAndClaimNoticesShowHoverableBracketedQuestNames() {
        var quest = occurrence("first", "Find the Relic");
        var completed = QuestCommandText.questCompleted(quest, details(quest));
        var claimed = QuestCommandText.questClaimed(quest, details(quest));

        assertTrue(completed.getString().contains("Quest completed: [Find the Relic]"));
        assertTrue(completed.getString().contains("Quest Giver"));
        assertTrue(claimed.getString().contains("Successfully claimed quest: [Find the Relic]"));
        assertTrue(completed.getSiblings().stream().anyMatch(part ->
                part.getString().equals("[Find the Relic]")
                        && part.getStyle().getHoverEvent() instanceof HoverEvent.ShowText));
        assertTrue(claimed.getSiblings().stream().anyMatch(part ->
                part.getString().equals("[Find the Relic]")
                        && part.getStyle().getHoverEvent() instanceof HoverEvent.ShowText));
    }

    private static void assertQuestName(QuestModel.Occurrence occurrence, ChatFormatting color) {
        var label = QuestCommandText.questName(occurrence, details(occurrence));
        assertEquals("[" + occurrence.definition().title() + "]", label.getString());
        assertEquals(TextColor.fromLegacyFormat(color), label.getStyle().getColor());
        assertInstanceOf(HoverEvent.ShowText.class, label.getStyle().getHoverEvent());
    }

    private static Component details(QuestModel.Occurrence occurrence) {
        return Component.literal("Details for " + occurrence.definition().title());
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

    private static QuestModel.Occurrence dailyOccurrence(String path, String title, QuestModel.Difficulty difficulty) {
        Identifier id = Identifier.fromNamespaceAndPath("test", path);
        var definition = new QuestModel.Definition(
                id, QuestModel.Availability.DAILY, Optional.of(difficulty), title, List.of(),
                Items.BOOK, new BuiltInConditions.ExplicitSignal(id, 1),
                new RewardApi.Plan(Optional.empty(), List.of()), "hash");
        return new QuestModel.Occurrence(
                new QuestModel.Key(id, new QuestModel.DailyScope(difficulty, Instant.parse("2026-09-24T00:00:00Z"))),
                definition, Instant.EPOCH, Optional.empty());
    }
}
