# PolyQuest JSON schemas

These Draft 2020-12 schemas follow the codecs and semantic validation in the current PolyQuest source.

| Datapack resource                            | Schema                       |
|----------------------------------------------|------------------------------|
| `data/<namespace>/quests/**/*.json`          | `quest.schema.json`          |
| `data/<namespace>/quest_templates/**/*.json` | `quest-template.schema.json` |
| `data/<namespace>/reward_profiles/**/*.json` | `reward-profile.schema.json` |

`polyquest.schema.json` contains the shared definitions for every built-in condition and reward, plus the Mojang predicate and item-stack shapes that PolyQuest delegates to Minecraft. `catalog.json` provides all three file-pattern mappings for editors and other tooling that consume JSON Schema catalogs.

Do not add a `$schema` field to datapack resources solely for editor support. PolyQuest's quest behavior hash includes unknown fields, so configure the mappings in the editor instead.

JSON Schema cannot express every catalog-wide rule. PolyQuest still validates referenced template parameters and reward profiles, unique choice-branch names, `n_of_m.required` against the child count, and the signal/claim-cost restrictions of nested conditions during datapack reload.
