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
| `::anvil stop` | aborts the scenario that's playing, and puts back any varbit/varp it changed |
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
| `varbit` | `{ "id": int }` or `{ "name": gameval `VarbitID` constant, case-insensitive }`, plus either `"value": int` or `"add": int` (on top of the current value, or of the `"base"` varbit's: id or name) | `client.setVarbit`, then `VarbitChanged`. `add` is what lets a counter tile see "+1" without knowing your real count; `base` sets one var from another (`CA_POINTS` to `CA_THRESHOLD_<tier>`) |
| `varp` | same as `varbit`, but `name` is a gameval `VarPlayerID` constant and `base` a varp | `client.setVarpValue`, then `VarbitChanged` with the varp id (Doom delves, Giants' Foundry, Mortimer tasks) |
| `restore` | `{}` | writes every varbit/varp this run changed back to its value from before the run. `::anvil stop` does the same |
| `stat` | `{ "skill": Skill name (any case), "xp": int, "level": int, "boostedLevel"?: int (default level) }`, or `{ "skill", "addXp": int }` (on top of your real XP; the level follows from the XP unless `level` is given) | `StatChanged` |
| `death` | `{ "who": "self" }`, `{ "who": "npc", "name": str, "npcId"?: int, "combatLevel"?: int }` or `{ "who": "player", "name": str }` | `ActorDeath` for the local player, a fake NPC, or a fake Player we didn't hit (a party member dying, which fails a deathless run when you're in an instance) |
| `pvpKill` | `{ "victim": str, "damage"?: int (default 20) }` | `HitsplatApplied` (dealt by us) on a fake Player, then that Player's `ActorDeath`. This is the same damage→death attribution the plugin uses to credit PKs |
| `playerLoot` | `{ "victim": str, "items": [ as in `loot` ] }` | `PlayerLootReceived` from a fake Player: the loot pile a PK leaves, which PvP min-loot tiles price |
| `attacked` | `{ "by": str, "npc"?: bool (default false, a player), "npcId"?: int, "combatLevel"?: int, "damage"?: int (default 5) }` | `InteractingChanged` (the attacker targets us), then a hitsplat on us that isn't ours. What a death is attributed to: the killer, and the pile size when several players had us |
| `playerSpawn` | `{ "name": str }` | `PlayerSpawned` for a fake Player. Inside an instance it joins the raid roster that party and team-only gates read |

Unknown step keys, two keys in one step, or a bad name are all rejected when the file loads.

## What replaying events can't do (it's not the live client)

- The plugin also reads **live client state** directly, and posting an event doesn't change it:
  - Instance detection (`getTopLevelWorldView().isInstance()`): a party member's death and the party-from-instance fallback only count while you are in an instance. Stand in any instance (your POH works) and `death who=player` / `playerSpawn` do count; see `raid-party-death`. A clean deathless clear needs no instance: the completion lines credit it with zero deaths.
  - Nearby players (`getPlayers`).
  - Player location (`getWorldLocation`): the starting-shot spot, and the GotR region gate on the 'You found some loot' search line (Wintertodt and Tempoross print the same line).
  - World type (`getWorldType`): seasonal, PvP worlds and artificial-stats gates, so game-mode (Leagues / DMM) announcement lines aren't replayable off a normal world.
  - Inventory and worn items (`getItemContainer`): 'gain' tiles diff these on the tick after an `ItemContainerChanged`. The containers are read-only to plugins, so no step can add an item. Test a gain tile for real with something cheap: buy one from a shop, or fill a vial at a fountain (bank, GE, trade, ground "Take" and storage moves are deliberately not gains).
  - Widgets: the quest-complete scroll (quest posts read its title, there's no chat line), clog pages (page proofs), the LMS survivor counter (placing Nth by dying; a win is covered by its chat line, see `lms-win`), the clan coffer.
  - Real skill levels (`getRealSkillLevel`) and total level (`getTotalLevel`): the baseline a `stat` level is compared against, so it only "levels up" a skill that is really below that level, and total-level milestones can't be faked.
  - Hiscores.
- **Varbits are covered.** A `varbit` step writes the client-side value, so gates that read `getVarbitValue` see it: CoX party (`RAIDS_CLIENT_INDUNGEON`/`RAIDS_CLIENT_PARTYSIZE`), ToA level and slots, ToB party, `INSIDE_WILDERNESS`, `CA_POINTS`, LMS `BR_INGAME`, and the game-counter vars (slayer streaks, Wintertodt pool, Tithe points, Doom delves). The value lasts until the server next sends that varp, so end the scenario with a `restore` step (or reset it, as `cox-raid` does). Restoring a counter lowers it, which an increase counter ignores; it also leaves the plugin's own baselines (CA points, last XP) one step off until the next real change.
- Every plugin on the EventBus sees the fake events, Loot Tracker included.
- Plugin behaviour that doesn't depend on a tile still happens: a replayed pet still goes to the rare-drops pipeline and the highlight feed. The site keeps it out of real clans:
  - Any push carrying `X-Anvil-Scenario` that would reach outside a board (notify, moments, clip, clog, PBs, stats, progress, coffer) is dropped, and a submission or counter push aimed at a real board is dropped too. Replays only ever credit Tile Lab boards.
  - While your character is in a Tile Lab session (`/staff/lab` on the site), those same pushes go to the session's log, where you can read exactly what would have been posted, and the session is the plugin's active board.
  - Older sites without the Tile Lab don't know the header. Point the harness at beta or local, never production, until it ships there.

## Bundled scenarios

Every handled game event that can be replayed has one. Run `::anvil list` for the defaults.

| scenario | covers | example |
|---|---|---|
| `boss-kc` | kill tile off the KC line alone (corpse-loot bosses) | `::anvil run boss-kc boss=Araxxor kc=10` |
| `boss-pb` | timed clear: KC + fight duration | `::anvil run boss-pb boss=Vorkath time=1:36` |
| `boss-pet` | new pet: KC, pet line, clog line | `::anvil run boss-pet boss=Vorkath kc=1999 pet=Vorki` |
| `boss-pet-duplicate` | pet already owned ("would have been followed") | `::anvil run boss-pet-duplicate` |
| `pet-backpack` | pet into the backpack + clog line | `::anvil run pet-backpack pet=Heron` |
| `pet-clan-broadcast` | pet named only by the clan broadcast | `::anvil run pet-clan-broadcast boss=Vorkath pet=Vorki` |
| `zulrah-unique` | boss drop (server + client loot) | `::anvil run zulrah-unique item=12922` |
| `npc-drop` | any NPC drop, incl. a valuable one with no KC | `::anvil run npc-drop npc=Lizardman shaman item=13576` |
| `nightmare-kill` | Nightmare KC, team size + duration, drop | `::anvil run nightmare-kill boss=Phosani's Nightmare npc=Phosani's Nightmare` |
| `spill-drop` | personal drop line with no loot event | `::anvil run spill-drop item=Elder venator fang` |
| `drop-clan-broadcast` | clan drop broadcast naming you | `::anvil run drop-clan-broadcast item=Elder venator fang` |
| `champion-scroll-duplicate` | "would have received a Champion's scroll" | `::anvil run champion-scroll-duplicate npc=Imp` |
| `clog-unlock` | personal clog line (no clan broadcast) | `::anvil run clog-unlock item=Fighter torso` |
| `clog-clan-broadcast` | clan clog broadcast naming you | `::anvil run clog-clan-broadcast item=Fighter torso` |
| `cox-raid` | CoX: party varbits, time, KC, chest | `::anvil run cox-raid party=3 time=15:39` |
| `tob-raid` | ToB: party varbits, KC, total time, chest | `::anvil run tob-raid p2=1 p3=1 time=1:02:30` |
| `toa-raid` | ToA: raid level, party slots, challenge + total time, KC, chest | `::anvil run toa-raid level=500 p1=1 p2=1` |
| `raid-party-death` | failed deathless run (stand in your POH) | `::anvil run raid-party-death teammate=Raid buddy` |
| `gauntlet-clear` | (Corrupted) Gauntlet duration + count | `::anvil run gauntlet-clear gauntlet=Gauntlet label=Challenge` |
| `inferno-cape` | Inferno duration + Zuk KC (repeat cape) | `::anvil run inferno-cape time=59:00` |
| `fight-caves-cape` | Fight Caves duration + Jad KC (repeat cape) | `::anvil run fight-caves-cape` |
| `colosseum-clear` | Colosseum duration, Sol KC, reward chest | `::anvil run colosseum-clear time=24:11` |
| `sepulchre-run` | Sepulchre floors 1-5 + coffin | `::anvil run sepulchre-run` |
| `barracuda-trial` | Sailing trial count, rank, time | `::anvil run barracuda-trial course=Gwenith Glide rank=Marlin` |
| `doom-delve` | Doom delve line + delve varp | `::anvil run doom-delve level=5` |
| `clue-casket` | clue casket loot per tier | `::anvil run clue-casket tier=medium item=2577` |
| `barrows-chest` | Barrows chest count + loot | `::anvil run barrows-chest item=4718` |
| `loot-key` | Wilderness loot key (pvp loot) | `::anvil run loot-key item=11832 coins=250000` |
| `larran-chest` | Larran's chest via its chat lines | `::anvil run larran-chest size=big item=Dagon'hai robe top` |
| `pickpocket` | pickpocket loot, not a kill | `::anvil run pickpocket` |
| `agility-lap` | lap count line | `::anvil run agility-lap kc=1235` |
| `wintertodt-game` | Wintertodt KC, rewards earned + claimed, cart loot | `::anvil run wintertodt-game rewards=3` |
| `tempoross-game` | Tempoross KC + reward pool | `::anvil run tempoross-game` |
| `gotr-game` | GotR energy counters + rewards search | `::anvil run gotr-game elemental=300 catalytic=250` |
| `hunter-rumour` | Hunter Guild rumour | `::anvil run hunter-rumour` |
| `egg-offering` | Woodcutting Guild egg offering | `::anvil run egg-offering` |
| `vale-offerings` | Vale Totems offerings | `::anvil run vale-offerings` |
| `sunstone-golem` | Sunstone golem | `::anvil run sunstone-golem` |
| `slayer-task` | slayer task streak counter | `::anvil run slayer-task counter=SLAYER_WILDERNESS_TASKS_COMPLETED` |
| `tithe-farm` | Tithe Farm points counter | `::anvil run tithe-farm points=30` |
| `giants-foundry` | Giants' Foundry reputation counter (varp) | `::anvil run giants-foundry rep=150` |
| `level-99` | a 99: XP + level line | `::anvil run level-99 skill=Cooking` |
| `level-up` | a non-99 level-up | `::anvil run level-up skill=Agility level=70 xp=737627` |
| `xp-drop` | skill XP gain on top of your real XP | `::anvil run xp-drop skill=Fishing xp=5000` |
| `ca-task` | combat task line | `::anvil run ca-task tier=Elite task=Whack-a-Mole` |
| `ca-tier-clear` | combat task that clears a tier | `::anvil run ca-tier-clear tier=Master` |
| `diary-tier` | diary tier completion | `::anvil run diary-tier tier=hard area=Ardougne` |
| `pvp-kill` | PK by damage→death | `::anvil run pvp-kill victim=Some Pker` |
| `pvp-loot` | PK + its loot pile (min-loot tiles) | `::anvil run pvp-loot victim=Some Pker item=13652` |
| `lms-win` | LMS win line | `::anvil run lms-win kills=4` |
| `self-death` | your death | `::anvil run self-death` |
| `death-to-boss` | death attributed to a boss | `::anvil run death-to-boss boss=Vorkath` |
| `gang-death` | death to several players (pile size) | `::anvil run gang-death p1=A p2=B p3=C` |
