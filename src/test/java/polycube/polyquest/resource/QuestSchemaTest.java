package polycube.polyquest.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class QuestSchemaTest {
    private static final Path SCHEMA_DIRECTORY = Path.of("schemas").toAbsolutePath().normalize();
    private static final List<String> SCHEMA_FILES = List.of(
            "polyquest.schema.json",
            "quest.schema.json",
            "quest-template.schema.json",
            "reward-profile.schema.json"
    );

    @Test
    void schemasAreJsonAndAllLocalReferencesResolve() throws IOException {
        Map<Path, JsonElement> documents = new HashMap<>();
        for (String fileName : SCHEMA_FILES) {
            Path file = SCHEMA_DIRECTORY.resolve(fileName);
            assertTrue(Files.isRegularFile(file), () -> "Missing schema " + file);
            resolveReferences(file, read(file, documents), documents);
        }
    }

    @Test
    void catalogPointsAtEveryResourceSchema() throws IOException {
        Path catalogFile = SCHEMA_DIRECTORY.resolve("catalog.json");
        JsonArray entries = read(catalogFile, new HashMap<>()).getAsJsonObject().getAsJsonArray("schemas");

        assertNotNull(entries);
        assertEquals(3, entries.size(), "The catalog must cover quests, templates, and reward profiles");
        for (JsonElement entry : entries) {
            String relativeUrl = entry.getAsJsonObject().get("url").getAsString();
            Path target = catalogFile.getParent().resolve(relativeUrl).normalize();
            assertTrue(target.startsWith(SCHEMA_DIRECTORY), () -> "Catalog URL leaves the schema directory: " + relativeUrl);
            assertTrue(Files.isRegularFile(target), () -> "Catalog URL does not exist: " + relativeUrl);
        }
    }

    @Test
    void questSchemasMatchBodyCodecFields() throws IOException {
        Map<Path, JsonElement> documents = new HashMap<>();
        JsonObject sharedDefinitions = read(SCHEMA_DIRECTORY.resolve("polyquest.schema.json"), documents)
                .getAsJsonObject().getAsJsonObject("$defs");
        JsonObject questBody = sharedDefinitions.getAsJsonObject("questBody");
        JsonObject bodyProperties = questBody.getAsJsonObject("properties");

        assertObjectFields(
                questBody,
                Set.of("availability", "difficulty", "title", "description", "icon", "condition", "rewards"),
                Set.of("availability", "title", "icon", "condition", "rewards")
        );
        assertEquals("#/$defs/identifier", bodyProperties.getAsJsonObject("icon").get("$ref").getAsString());

        JsonObject questDefinitions = read(SCHEMA_DIRECTORY.resolve("quest.schema.json"), documents)
                .getAsJsonObject().getAsJsonObject("$defs");
        JsonObject templateInvocation = questDefinitions.getAsJsonObject("templateInvocation");
        JsonObject invocationProperties = templateInvocation.getAsJsonObject("properties");

        assertObjectFields(
                templateInvocation,
                Set.of(
                        "template", "arguments", "availability", "difficulty", "title", "description",
                        "icon", "condition", "rewards"
                ),
                Set.of("template")
        );
        assertEquals(
                "polyquest.schema.json#/$defs/identifier",
                invocationProperties.getAsJsonObject("icon").get("$ref").getAsString()
        );
    }

    @Test
    void rewardSchemasMatchBuiltInCodecFields() throws IOException {
        Map<Path, JsonElement> documents = new HashMap<>();
        JsonObject definitions = read(SCHEMA_DIRECTORY.resolve("polyquest.schema.json"), documents)
                .getAsJsonObject().getAsJsonObject("$defs");

        JsonObject money = definitions.getAsJsonObject("moneyReward");
        assertObjectFields(
                money,
                Set.of("type", "amount", "formatted_amount", "currency"),
                Set.of("type", "amount", "formatted_amount", "currency")
        );
        JsonObject moneyProperties = money.getAsJsonObject("properties");
        assertEquals(
                "^[+]?[0-9]*[1-9][0-9]*$",
                moneyProperties.getAsJsonObject("amount").get("pattern").getAsString()
        );
        assertEquals("string", moneyProperties.getAsJsonObject("formatted_amount").get("type").getAsString());
        assertEquals("#/$defs/identifier", moneyProperties.getAsJsonObject("currency").get("$ref").getAsString());

        assertObjectFields(
                definitions.getAsJsonObject("itemReward"),
                Set.of("type", "stack"),
                Set.of("type", "stack")
        );

        JsonObject experience = definitions.getAsJsonObject("experienceReward");
        assertObjectFields(experience, Set.of("type", "points"), Set.of("type", "points"));
        assertEquals(Integer.MAX_VALUE, experience.getAsJsonObject("properties")
                .getAsJsonObject("points").get("maximum").getAsInt());

        JsonObject commands = definitions.getAsJsonObject("commandsReward");
        assertObjectFields(
                commands,
                Set.of("type", "title", "commands"),
                Set.of("type", "title", "commands")
        );
        assertEquals("string", commands.getAsJsonObject("properties")
                .getAsJsonObject("title").get("type").getAsString());

        JsonObject rewardProfile = read(SCHEMA_DIRECTORY.resolve("reward-profile.schema.json"), documents)
                .getAsJsonObject();
        assertObjectFields(rewardProfile, Set.of("rewards"), Set.of("rewards"));
        assertEquals(1, rewardProfile.getAsJsonObject("properties")
                .getAsJsonObject("rewards").get("minItems").getAsInt());
    }

    private static void resolveReferences(
            Path currentFile, JsonElement element, Map<Path, JsonElement> documents
    ) throws IOException {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                resolveReferences(currentFile, child, documents);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        if (object.has("$ref")) {
            resolveReference(currentFile, object.get("$ref").getAsString(), documents);
        }
        for (JsonElement child : object.asMap().values()) {
            resolveReferences(currentFile, child, documents);
        }
    }

    private static void resolveReference(
            Path currentFile, String reference, Map<Path, JsonElement> documents
    ) throws IOException {
        int fragmentStart = reference.indexOf('#');
        String documentName = fragmentStart < 0 ? reference : reference.substring(0, fragmentStart);
        String fragment = fragmentStart < 0 ? "" : reference.substring(fragmentStart + 1);
        Path referencedFile = documentName.isEmpty()
                ? currentFile
                : currentFile.getParent().resolve(documentName).normalize();

        assertTrue(referencedFile.startsWith(SCHEMA_DIRECTORY), () -> "Local $ref leaves the schema directory: " + reference);
        assertTrue(Files.isRegularFile(referencedFile), () -> "Missing $ref document: " + reference);

        JsonElement target = read(referencedFile, documents);
        if (fragment.isEmpty()) {
            return;
        }
        assertTrue(fragment.startsWith("/"), () -> "Unsupported non-pointer $ref fragment: " + reference);
        for (String encodedSegment : fragment.substring(1).split("/")) {
            String segment = encodedSegment.replace("~1", "/").replace("~0", "~");
            assertTrue(target.isJsonObject(), () -> "$ref traverses a non-object: " + reference);
            JsonObject targetObject = target.getAsJsonObject();
            assertTrue(targetObject.has(segment), () -> "Missing $ref target: " + reference);
            target = targetObject.get(segment);
        }
    }

    private static JsonElement read(Path file, Map<Path, JsonElement> documents) throws IOException {
        JsonElement existing = documents.get(file);
        if (existing != null) {
            return existing;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            documents.put(file, parsed);
            return parsed;
        }
    }

    private static Set<String> stringValues(JsonArray array) {
        Set<String> values = new HashSet<>();
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return Set.copyOf(values);
    }

    private static void assertObjectFields(
            JsonObject schema, Set<String> expectedProperties, Set<String> expectedRequired
    ) {
        assertEquals(expectedProperties, schema.getAsJsonObject("properties").keySet());
        assertEquals(expectedRequired, stringValues(schema.getAsJsonArray("required")));
    }
}
