# Anvil

The companion RuneLite plugin for **Anvil** — a clan-operations platform for Old
School RuneScape that runs bingo events, weekly SotW/BotW competitions, and keeps
a live clan roster synced from in-game.

The plugin lets participants:

- **See a verification overlay** — a site-generated daily codeword and UTC
  timestamp burned into every screenshot so evidence is tamper-evident.
- **Auto-submit tracked drops** — when a drop matching a bingo tile hits your
  loot, the plugin takes a screenshot, uploads it, and files a submission to your
  team's board with zero clicks.
- **Manually submit** — a side-panel fallback for drops the auto-detect misses
  (pet drops, off-loot collection-log drops, etc.).
- **Auto-enroll in the active weekly** — on login the plugin asks the site
  whether a SotW/BotW is running and locks your baseline with one round-trip.

If you're a **clan admin**, it also lets you:

- **Link your account to the plugin** — paste a one-time 6-char code from the
  Anvil admin panel; the plugin gets a long-lived admin token bound to your RSN.
- **Sync the in-game clan roster** — one click pushes the full clan member list
  to the site, keeping ranks and guest/member status accurate.

## Setup

1. Install **Anvil** from the RuneLite Plugin Hub.
2. In your Anvil site's player dashboard, copy your **Player Token**.
3. Open RuneLite → Configuration → Anvil and paste your **Player Token** (the
   UUID from step 2). That's the only required field — **Site URL** defaults to
   the official site, so leave it unless you self-host.
4. A side panel appears in RuneLite's sidebar. When connected, it shows your
   event, team, codeword, tracked drop progress, and any upcoming events.

## How it works

- The plugin periodically calls `GET /api/plugin/config` on your Anvil site
  (authenticated with your player token) to pull event, team, and tracked-drop
  metadata plus the rotating codeword.
- When any of `NpcLootReceived`, `LootReceived` (chests/raids/clue caskets), or
  `PlayerLootReceived` fires with an item ID that matches a tracked drop tile,
  the plugin:
  1. Captures the current frame (with the codeword overlay visible).
  2. Uploads the PNG to the Anvil site's image host.
  3. Posts a submission to `/api/events/{id}/submissions`, crediting your player.
- Failed submissions are persisted to disk in `~/.runelite/osrs-bingo-pending/`
  and retried with exponential backoff (plus age-based cleanup after 7 days), so
  a flaky connection or server restart won't lose a drop.

## Settings

| Setting | Default | Description |
|---------|---------|-------------|
| Site URL | *(official site)* | Base URL of your Anvil site — only change if you self-host |
| Player Token | *(empty)* | Per-player UUID from the Anvil site dashboard |
| Auto Submit Drops | `true` | Auto-screenshot and submit on tracked drops |
| Show Overlay | `true` | Render the codeword/date verification overlay |
| Auto-enroll weekly comp | `true` | On login, auto-enroll in the active weekly SotW/BotW if one is running |

**Notifications section** — posts deaths and rare drops to clan Discord channels.
The channels themselves are configured on the site (Admin → Integrations); these
settings choose what *you* share:

| Setting | Default | Description |
|---------|---------|-------------|
| Notify on death | `true` | Post to the clan deaths channel when you die |
| Death message | `{name} just died!` | Your death line (`{name}` → RSN); small chance of a random clan one-liner |
| Screenshot deaths | `true` | Attach a screenshot of the moment you died |
| Notify on rare drops | `true` | Post valuable drops to the clan rare-drops channel |
| Min drop value | `5,000,000` | Post a single drop worth at least this (higher of GE / high-alch). `0` disables value posts |
| Screenshot rare drops | `true` | Attach a screenshot to rare-drop posts |
| Notify on pets | `true` | Post when you receive a pet |
| Min drop rarity (1 in N) | `1000` | Also post very rare NPC/pickpocket drops regardless of value (catches cheap-but-rare uniques). `0` disables |
| Loot key value | `1,000,000` | Post a loot key as **one** notification when its contents total at least this. Loot keys only. `0` disables |
| Funny lines | `true` | Add a cheeky one-liner to death/rare-drop posts (e.g. "Sit.", "SPOONED.") |

How rare-drop posting works:

- **Regular drops** (NPC / raid chest / clue / pickpocket / PvP floor loot) post
  standout items — over *Min drop value*, or rarer than *Min drop rarity* — bundled
  into one post per kill (no per-item spam).
- **Loot keys** are reported as a single post gated only by *Loot key value*, and
  the loot key item itself is never posted on receipt — only its contents on open.
- **Prestige items** (Infernal cape, Dizana's quiver, raid ornament kits, etc.)
  always post regardless of value/rarity. The list is baked in and can be extended
  on the site (Admin → Integrations → *Always-notify drops*) with no plugin update.
  Awarded items that don't drop are caught via the collection-log unlock message,
  so enable RuneLite's *Collection log → New addition notification*.

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
