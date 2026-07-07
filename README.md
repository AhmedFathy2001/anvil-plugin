# Anvil

The companion RuneLite plugin for **Anvil** — a clan-operations platform for Old
School RuneScape that runs bingo events, weekly SotW/BotW competitions, and keeps
a live clan roster synced from in-game.

The plugin lets participants:

- **See a verification overlay** — a site-generated daily codeword and UTC
  timestamp burned into every screenshot so evidence is tamper-evident.
- **Auto-submit tracked tiles** — drops, kill counts, item gains
  (catch/cook/gather), timed clears, deathless raids, LMS placements,
  achievement-diary tiers, and Combat Achievement tasks all credit automatically:
  the plugin takes a screenshot, uploads it, and files a submission to your
  team's board with zero clicks. Combat Achievement tiles even work for tasks
  you completed years ago — enable the in-game *Settings → Combat Achievements →
  Repeat completion* and re-meeting the task's conditions counts.
- **Browse the board in-game** — a Bingo tab inside the collection log lists
  every tile with live progress, filters (status/type/category/tier), and the
  same tile order as the site's board: in-progress first, then not started,
  then completed.
- **Manually submit** — a side-panel fallback for anything auto-detect misses
  (pet drops, off-loot collection-log items, etc.).
- **Weeklies tracked automatically** — SotW/BotW enrollment is handled
  server-side for clan members; the plugin greets you with what's live on login.

If you're a **clan admin**, it also lets you:

- **Link your account to the plugin** — paste a one-time 6-char code from the
  Anvil admin panel; the plugin gets a long-lived admin token bound to your RSN.
- **Sync the in-game clan roster** — one click pushes the full clan member list
  to the site, keeping ranks and guest/member status accurate.

## Setup

1. Install **Anvil** from the RuneLite Plugin Hub.
2. On your Anvil site, go to **Profile → Plugin** and copy your **Account Token**
   (one token works across every event you're signed up for).
3. Open RuneLite → Configuration → Anvil and paste your **Account Token**. That's
   the only required field — **Site URL** defaults to the official site, so leave
   it unless you self-host.
4. A side panel appears in RuneLite's sidebar. When connected, it shows your
   event, team, codeword, tracked tile progress, and any upcoming events.

## How it works

- The plugin periodically calls `GET /api/plugin/config` on your Anvil site
  (authenticated with your account token) to pull event, team, and tracked-tile
  metadata plus the rotating codeword.
- When any of `NpcLootReceived`, `ServerNpcLoot` (corpse-interaction bosses like
  Araxxor and the Maggot King), `LootReceived` (chests/raids/clue caskets), or
  `PlayerLootReceived` fires with an item ID that matches a tracked drop tile —
  or a collection-log unlock message names one (shop/gamble rewards that never
  fire loot), or the "received a drop" attribution line names one (Maggot King's
  spill-out uniques — enable the in-game *Loot drop notifications* chat setting,
  which this line comes from), or a kill-count message signals a guaranteed
  completion award (Infernal cape, Fire cape — credited on every completion, not
  just the first) — the plugin:
  1. Captures proof (with the codeword overlay visible). Drop tiles bake **two
     frames** into one image by default — one the moment the drop lands, one a
     couple of seconds later once floor loot has settled (toggle: *Two-frame
     drop proof*).
  2. Uploads the PNG to the Anvil site's image host (an "Uploading proof…"
     chat line shows it's in flight).
  3. Posts a submission to `/api/events/{id}/submissions`, crediting your player.
- Failed submissions are persisted to disk in `~/.runelite/osrs-bingo-pending/`
  and retried with exponential backoff (plus age-based cleanup after 7 days), so
  a flaky connection or server restart won't lose a drop. A failure is announced
  in chat, and while anything is stuck a **Saved proofs** row appears in the
  collection-log Bingo tab that opens the folder holding the baked screenshots.

## Settings

| Setting | Default | Description |
|---------|---------|-------------|
| Site URL | *(official site)* | Base URL of your Anvil site — only change if you self-host |
| Account Token | *(empty)* | From the site's Profile → Plugin page; one token covers all your events |
| Auto Submit Drops | `true` | Auto-screenshot and submit on tracked tiles |
| Show Overlay | `true` | Render the codeword/date verification overlay |
| Team completion popups | `true` | Banner (and sound) when a teammate completes a tile |
| Bingo tab in Collection Log | `true` | The in-game board/task list inside the collection log |
| Two-frame drop proof | `true` | Drop proofs bake two frames: at the drop, and once floor loot settles |

**Notifications section** — posts deaths and rare drops to clan Discord channels.
The channels themselves are configured on the site (Admin → Integrations); these
settings choose what *you* share:

| Setting | Default | Description |
|---------|---------|-------------|
| Notify on death | `true` | Post to the clan deaths channel when you die |
| Death message | `{name} just died!` | Your death line (`{name}` → RSN); small chance of a random clan one-liner |
| Notify on PvP kill | `false` | Post to the clan PvP channel when you kill a player |
| Notify on rare drops | `true` | Post valuable drops to the clan rare-drops channel |
| Min drop value | `5,000,000` | Post a single drop worth at least this (higher of GE / high-alch). `0` disables value posts |
| Min drop rarity (1 in N) | `1000` | Also post very rare NPC/pickpocket drops regardless of value (catches cheap-but-rare uniques). `0` disables |
| Screenshot rare drops | `true` | Attach a screenshot to rare-drop posts |
| Loot key value | `1,000,000` | Post a loot key as **one** notification when its contents total at least this. Loot keys only. `0` disables |
| Notify on pets | `true` | Post when you receive a pet |
| Screenshot pets | `true` | Attach a screenshot to pet posts |
| Notify on combat achievements | `true` | Post CA task completions and tier unlocks to the clan achievements channel |
| CA task min tier | `Master` | Lowest CA tier worth announcing |
| Notify on 99s & high totals | `true` | Post level-99s and high total-level milestones |
| Notify on diary completions | `true` | Post achievement-diary tier clears |
| Announce quest completions | `Master & up` | Post quest completions at or above this difficulty |

There's also a **Clips** section (OBS replay-buffer integration — auto-clip deaths
and rare drops to a Discord webhook you supply) — see the in-plugin descriptions.

How rare-drop posting works:

- **Regular drops** (NPC / raid chest / clue / pickpocket / PvP floor loot) post
  standout items — over *Min drop value*, or rarer than *Min drop rarity* — bundled
  into one post per kill (no per-item spam).
- **Loot keys** are reported as a single post gated only by *Loot key value*, and
  the loot key item itself is never posted on receipt — only its contents on open.
- **Diary & quest completions** post to the clan achievements channel — diary
  tier clears (*Notify on diary completions*) and quest completions at or above
  a difficulty threshold (*Announce quest completions*, default Master & up).
- **Prestige items** (Infernal cape, Dizana's quiver, raid ornament kits, etc.)
  always post regardless of value/rarity. The list is baked in and can be extended
  on the site (Admin → Integrations → *Always-notify drops*) with no plugin update.
  Awarded items that don't drop are caught via the collection-log unlock message,
  so enable RuneLite's *Collection log → New addition notification*.

**In-game settings the plugin relies on** (it reminds you in chat when one is off
and matters):

- *Loot drop notifications* (Settings → Chat) — the only signal for corpse-boss
  spill loot (Maggot King uniques); keep its value threshold at or below your
  *Min drop value*.
- *Combat Achievements → Repeat completion* — lets CA bingo tiles count tasks
  you already completed before the event.
- *Collection log → New addition notification* — credits shop/gamble/awarded
  collection-log items that never fire a loot event.

**Admin-only section (collapsed by default):**

| Setting | Description |
|---------|-------------|
| Admin link code | Paste the 6-char code from the site's `/admin/clan` page, then click **Link as admin** in the side panel |
| Admin plugin token | Managed automatically after linking; don't edit manually (marked `secret`) |
| Linked RSN | The RSN that was linked when the admin token was issued (display only) |

## Admin flows

If you're an Anvil admin or moderator, link your plugin once:

1. On the site, go to `/admin/clan` → **Generate Link Code**. You'll get a 6-char
   code valid for 10 minutes.
2. Paste the code into the plugin's **Admin link code** setting (under the
   collapsed *Admin link* section).
3. Open the Anvil side panel in RuneLite and press **Link as admin**.

Once linked, the side panel shows a **Sync clan** button. Open the clan tab
in-game (so RuneLite loads the roster), then press **Sync clan** to push the
current in-game clan roster to the site. The site rejects the sync if the
reported clan name doesn't match the one configured on `/admin/clan`.

## Privacy

- The plugin only talks to the Site URL you configure — no third-party servers.
- Screenshots are uploaded only when a tracked drop is detected (or you hit
  *Manual submit*).
- The player token and admin plugin token are stored locally in your RuneLite
  config (both marked as secret).
- On each login, the plugin sends a small `{ rsn }` payload to
  `/api/plugin/hello` so the site can auto-register you as a guest clan member.
  You can remove yourself from the roster from the site if you don't want to be
  tracked.

## Building locally

```bash
./gradlew build
./gradlew runClient   # launches RuneLite with the plugin loaded for dev
```

Requires JDK 11+.

## License

BSD-2-Clause — see [LICENSE](LICENSE).
