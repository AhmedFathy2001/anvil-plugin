# Anvil Scenario Lab (dev client only)

This replays scripted game events into the running Anvil plugin, so you can test a tile without the grind. A 2,000-kc pet is just one chat line. It also records real events so you can build new scripts from them.

Everything lives in `src/test`, so none of it ships in the hub jar.

## Run

```
./gradlew runClient                      # AnvilPluginTest loads AnvilPlugin + AnvilScenarioPlugin
ANVIL_SITE=https://beta.anvilosrs.com ./gradlew runClient   # or set it in game: ::anvil site <url>
```

- `::` commands are always delivered to plugins, so `--developer-mode` is not needed. The shipped `::anvillog` command relies on this too.
- **Point it at beta or local.** The plugin's site is the constant `anvilosrs.com`. The lab wraps the plugin's HTTP client (via reflection, in test code only) so every anvilosrs.com request goes to the override site instead. The override is saved in `~/.runelite/anvil-scenarios/site.txt`. `-Danvil.site` and `ANVIL_SITE` take precedence over the saved file. `::anvil site off` goes back to production. While no override is set, `::anvil run` warns you that the replay will reach production.
- **Tokens are per site.** With an override set, sign in with `::anvil signin`. The side panel's Sign-in button refuses any login page that isn't on anvilosrs.com, which is a deliberate safeguard, so it can't be used for beta.
- While a scenario runs, and for 2 minutes afterwards (pushes are coalesced), every plugin request carries the header `X-Anvil-Scenario: <name>`.

## Commands

| command | does |
|---|---|
| `::anvil list` | lists scenarios with their origin (`bundled`/`local`/`site`) and default params |
| `::anvil run <name> [k=v …]` | plays a scenario, e.g. `::anvil run boss-pet boss=Vorkath kc=1999 pet=Vorki`. Values may contain spaces (`boss=Phantom Muspah`) |
| `::anvil stop` | aborts the scenario that's playing |
| `::anvil record on [varbits]` / `off` | records real events to `~/.runelite/anvil-scenarios/recorded-<stamp>.json` |
| `::anvil setvarb <id\|NAME> <value>` | writes a client-side varbit and fires `VarbitChanged` (same as DevTools) |
| `::anvil site [url\|off]` / `signin` | shows or sets the site override; device sign-in against it |
| `::anvil sync` | `GET <site>/api/plugin/lab/scenarios` → `~/.runelite/anvil-scenarios/site/` |
| `::anvil reload` | re-reads all scenario files |

Lab output is printed on the CONSOLE chat type. The plugin's credit parsers ignore that type. The two parsers that read every channel (drop and clog broadcasts) can't fire on lab output either, because the lab rewrites their trigger phrase `received a` before printing.

## Sources (a later one shadows an earlier one with the same name)

1. bundled: this directory. Every file must be listed in `index.txt`, and `ScenarioFormatTest` fails if one is missing.
2. local: `~/.runelite/anvil-scenarios/*.json`, which includes recordings. Rename a recording's `"name"` to keep it.
3. site: `~/.runelite/anvil-scenarios/site/*.json`. Each sync replaces this folder completely.

## File format (the contract the site's Tile Lab mirrors)

```json
{
  "name": "boss-pet",
  "description": "one line, shown in ::anvil list",
  "params": { "boss": "Scurrius", "kc": "250" },
  "steps": [ { "chat": { "message": "Your ${boss} kill count is: ${kc}." } }, { "wait": 1 } ]
}
```

- `name`: must match `[a-z0-9][a-z0-9-]{0,63}`, because it becomes a file name.
- `params`: string defaults, used as `${key}` in any string anywhere in the steps. Run arguments override them. `${me}` is always available and is the logged-in RSN; the run fails if a placeholder has no value.
- Any **integer field** can be a JSON number or a string. A string is what lets a placeholder stand in for a number (`"value": "${party}"`), and commas are allowed (`"1,999"`).
- `steps`: each object has **exactly one** key:

| step | body | fires |
|---|---|---|
| `wait` | integer ticks ≥ 1 | the steps after it run that many game ticks later. Steps between waits share one tick |
| `chat` | `{ "type": ChatMessageType name (default `GAMEMESSAGE`), "message": str, "name"?: str, "sender"?: str }` | `client.addChatMessage`, so the line shows in the chatbox and fires `ChatMessage`. If the client doesn't echo it, the lab posts the `ChatMessage` itself |
| `loot` | `{ "kind": "npc"\|"server"\|"generic", "name": str, "npcId"?: int, "combatLevel"?: int, "lootType"?: `NPC`\|`PLAYER`\|`EVENT`\|`PICKPOCKET`\|`UNKNOWN` (generic only, default `EVENT`), "amount"?: int (generic only, default 1), "items": [ { "id": int } or { "name": gameval `ItemID` constant }, plus "qty"?: int (default 1) ] }` | `npc` → `NpcLootReceived`, `server` → `ServerNpcLoot`, `generic` → `LootReceived` (raid chests, clues, barrows). A real NPC kill fires both `server` and `npc` |
| `varbit` | `{ "id": int }` or `{ "name": gameval `VarbitID` constant, case-insensitive }`, plus `"value": int` | `client.setVarbit`, then `VarbitChanged` |
| `stat` | `{ "skill": Skill name (any case), "xp": int, "level": int, "boostedLevel"?: int (default level) }` | `StatChanged` |
| `death` | `{ "who": "self" }` or `{ "who": "npc", "name": str, "npcId"?: int, "combatLevel"?: int }` | `ActorDeath` for the local player or a fake NPC |
| `pvpKill` | `{ "victim": str, "damage"?: int (default 20) }` | `HitsplatApplied` (dealt by us) on a fake Player, then that Player's `ActorDeath`. This is the same damage→death attribution the plugin uses to credit PKs |

Unknown step keys, two keys in one step, or a bad name are all rejected when the file loads.

## What replaying events can't do (it's not the live client)

- The plugin also reads **live client state** directly, and posting an event doesn't change it:
  - Instance detection (`getTopLevelWorldView().isInstance()`): deathless raids and the party-from-instance fallback.
  - Nearby players (`getPlayers`).
  - Player location (`getWorldLocation`): the starting-shot spot.
  - World type (`getWorldType`): seasonal, PvP worlds and artificial-stats gates.
  - Inventory and worn items (`getItemContainer`): 'gain' tiles diff these on the tick after an `ItemContainerChanged`.
  - Widgets: quest scroll, clog pages, LMS HUD.
  - Real skill levels (`getRealSkillLevel`): the baseline a `stat` 99 is compared against, so it only "levels up" a skill that is really below 99.
  - Hiscores.
- **Varbits are covered.** A `varbit` step writes the client-side value, so gates that read `getVarbitValue` see it: CoX party (`RAIDS_CLIENT_INDUNGEON`/`RAIDS_CLIENT_PARTYSIZE`), ToA level and slots, ToB party, `INSIDE_WILDERNESS`, `CA_POINTS`, LMS `BR_INGAME`. The value lasts until the server next sends that varp, so reset it at the end of the scenario as `cox-raid` does.
- Every plugin on the EventBus sees the fake events, Loot Tracker included.
- Plugin behaviour that doesn't depend on a tile still happens: a replayed pet still goes to the rare-drops pipeline and the highlight feed. The site keeps it out of real clans:
  - Any push carrying `X-Anvil-Scenario` that would reach outside a board (notify, moments, clip, clog, PBs, stats, progress, coffer) is dropped, and a submission or counter push aimed at a real board is dropped too. Replays only ever credit Tile Lab boards.
  - While your character is in a Tile Lab session (`/staff/lab` on the site), those same pushes go to the session's log, where you can read exactly what would have been posted, and the session is the plugin's active board.
  - Older sites without the Tile Lab don't know the header. Point the harness at beta or local, never production, until it ships there.
