# PolyQuest

PolyQuest is a fully server-side Fabric quest engine for global daily quests and permanent unique quests. Quest definitions, condition trees, templates, and reward profiles are server-data JSON resources. Vanilla clients do not need the mod.

The implementation targets the Minecraft, Fabric Loader, Fabric API, mappings, and Java versions already declared by this project.

## What is implemented

- Transactional datapack loading during Fabric's server-data reload cycle.
- Registry-aware Mojang codecs for vanilla item, entity, block, location, and damage-source predicates.
- Reusable quest templates with typed whole-value substitution and text interpolation.
- Immutable catalogs, canonical behavior/presentation hashes, and reload diffs.
- Global deterministic easy, medium, and hard calendar-day rotations.
- Per-player daily attempts and always-visible unique attempts.
- Event conditions for fishing, entity kills, successful advancement completion, block breaking, death, explicit integration signals, locations, and uninterrupted falling.
- Composite `all_of`, `any_of`, `repeat`, `sequence`, `time_window`, `n_of_m`, `optional`, and locking `choice` conditions.
- Claim-time item delivery and consumption.
- Extensible money, item, experience, and command rewards.
- Durable claimed-occurrence and pending-reward ledger stored with the world.
- Idempotency keys for the external economy provider.
- In-game list, claim, inspection, explicit-signal, reroll, reset, and reward-retry commands.

## First in-game checkpoint

The mod bundles four small data-driven test quests. They use experience rewards so the economy bridge is not needed for the first test.

1. Start a dedicated development server and join as an operator.
2. Run `/polyquest list`.
3. For the easy quest, run `/polyquest signal polyquest:test_easy`.
4. Return to the center of the overworld shared spawn.
5. Run `/polyquest claim polyquest:daily/test_easy`.
6. Run `/reload`, then `/polyquest list`; claimed state and unchanged in-memory attempts should remain.
7. Restart the server. Daily in-progress attempts reset, while a claimed unique quest stays claimed.

The medium and hard bundled quests display the exact explicit signals needed to test sequence and time-window behavior.

## Economy provider integration

Register the server's Common Economy API adapter during mod initialization:

```java
PolyQuestApi.setEconomyGateway((playerId, amount, transactionId) -> {
    // Resolve the account through your Common Economy provider.
    // The provider must remember transactionId and make duplicate calls no-ops.
    // Return success, retryLater, or permanentFailure.
});
```

Money is represented by `BigDecimal`, encoded as a JSON string. The transaction key is stable for one reward in one claim transaction.

## Server configuration

The first launch creates `config/polyquest.json`:

```json
{
  "dailySeed": 5786933479488603475,
  "timeZone": "Europe/Paris",
  "claimRadius": 12.0,
  "announceRotation": true,
  "pendingRewardRetrySeconds": 30
}
```

`claimRadius: 0` disables the shared-spawn proximity check. Use an explicit IANA time-zone ID so moving the world to another machine does not move the reset boundary.

## Resource folders

```text
data/<namespace>/quests/**/*.json
data/<namespace>/quest_templates/**/*.json
data/<namespace>/reward_profiles/**/*.json
```

Resource-path IDs are stable. For example, `data/example/quests/daily/logs.json` is `example:daily/logs`.

See [`docs/DATAPACK_FORMAT.md`](docs/DATAPACK_FORMAT.md), [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), and [`docs/MILESTONES.md`](docs/MILESTONES.md).

## Commands

```text
/polyquest list
/polyquest claim <quest-id>
/polyquest inspect <quest-id>
/polyquest signal <signal-id>       # permission level 2
/polyquest reroll <difficulty>      # permission level 2
/polyquest reset <quest-id>         # permission level 2; self for test adapter
/polyquest retry-rewards            # permission level 2; self for test adapter
```

The commands are intentionally thin testing/admin adapters. A UI can call `QuestManager`, `QuestClaimService`, and `QuestAdministrationService` without duplicating quest logic.

## Crash semantics

- Attempt progress is memory-only by design and resets on process restart.
- Claimed daily occurrences and unique completions are durable.
- The economy bridge receives idempotency keys, allowing safe retry after an uncertain deposit.
- Pending reward bundles are stored as registry-aware codec JSON.
- Vanilla item/experience/command rewards cannot be made exactly-once across every process-crash instruction boundary without an idempotent external sink. Money rewards through an idempotent provider are the strongest path.
- A crash in the tiny interval between consuming a claim cost and writing the `COSTS_COMMITTED` marker can require administrator intervention. See `docs/CLAIM_TRANSACTIONS.md`.
