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
3. Open RuneLite → Configuration → Anvil and fill in:
   - **Site URL** — the URL of your Anvil site (e.g. `https://your-anvil.vercel.app`)
   - **Player Token** — the UUID from step 2
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
| Site URL | *(empty)* | Base URL of your Anvil site |
| Player Token | *(empty)* | Per-player UUID from the Anvil site dashboard |
| Auto Submit Drops | `true` | Auto-screenshot and submit on tracked drops |
| Show Overlay | `true` | Render the codeword/date verification overlay |
| Auto-enroll weekly comp | `true` | On login, auto-enroll in the active weekly SotW/BotW if one is running |

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
