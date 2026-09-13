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
import java.util.List;
import java.util.Map;
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
}
