package polycube.polyquest.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.StrictJsonParser;

/// Discovers raw PolyQuest JSON files in the effective server datapack stack.
///
/// Decoding is deliberately deferred until after template expansion. Every file is
/// inspected in one pass, and any error rejects the whole candidate resource set.
public final class QuestResourceLoader {
    private static final FileToIdConverter QUEST_FILES = FileToIdConverter.json("quests");
    private static final FileToIdConverter TEMPLATE_FILES = FileToIdConverter.json("quest_templates");
    private static final FileToIdConverter REWARD_PROFILE_FILES = FileToIdConverter.json("reward_profiles");

    public DataResult<ResourceSet> load(ResourceManager resources) {
        List<String> errors = new ArrayList<>();
        Map<Identifier, JsonElement> quests = loadDirectory(resources, QUEST_FILES, "quest", errors);
        Map<Identifier, JsonElement> templates = loadDirectory(resources, TEMPLATE_FILES, "quest template", errors);
        Map<Identifier, JsonElement> rewardProfiles = loadDirectory(resources, REWARD_PROFILE_FILES, "reward profile", errors);

        if (!errors.isEmpty()) {
            return DataResult.error(() -> "Failed to load PolyQuest resources:\n - " + String.join("\n - ", errors));
        }
        return DataResult.success(new ResourceSet(quests, templates, rewardProfiles));
    }

    private static Map<Identifier, JsonElement> loadDirectory(ResourceManager resources, FileToIdConverter files, String kind, List<String> errors) {
        Map<Identifier, JsonElement> output = new TreeMap<>();
        files.listMatchingResources(resources).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> readResource(files, kind, entry.getKey(), entry.getValue(), output, errors));
        return output;
    }

    private static void readResource(
            FileToIdConverter files, String kind, Identifier fileId,
            Resource resource, Map<Identifier, JsonElement> output, List<String> errors
    ) {
        Identifier id = files.fileToId(fileId);
        String source = kind + " '" + id + "' from '" + fileId + "' in pack '" + resource.sourcePackId() + "'";

        try (Reader reader = resource.openAsReader()) {
            JsonElement json = StrictJsonParser.parse(reader);
            if (!json.isJsonObject()) {
                errors.add(source + " must have a JSON object as its root");
                return;
            }
            if (output.putIfAbsent(id, json) != null) {
                errors.add("Duplicate " + source);
            }
        } catch (JsonParseException | IllegalArgumentException | IOException exception) {
            errors.add("Could not read " + source + ": " + exception.getMessage());
        }
    }

    public record ResourceSet(
            Map<Identifier, JsonElement> quests,
            Map<Identifier, JsonElement> templates,
            Map<Identifier, JsonElement> rewardProfiles) {
        public ResourceSet {
            quests = Map.copyOf(new LinkedHashMap<>(quests));
            templates = Map.copyOf(new LinkedHashMap<>(templates));
            rewardProfiles = Map.copyOf(new LinkedHashMap<>(rewardProfiles));
        }
    }
}
