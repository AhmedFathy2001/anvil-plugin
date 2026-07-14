# Activity feed + "working on" — architecture notes

This branch (`feat/activity-panel`) adds the **live layer** the always-on sidebar was missing: a
player-attributed team **activity feed** and a **"you're working on"** spotlight. It started as a
backend + pure-models spec handed to the sidebar branch (`feat/federation-sidebar`); that branch was
then rebased in here and the two were **fully integrated** — the panel now renders both sections,
driven by a real single-home data source. These notes document the shape for the multi-home track.

## What landed

**Anvil.Site** (`feat/activity-panel`)
- `src/lib/pluginActivity.ts` — `buildActivity()` shapes the feed from `submissions` + `completions`.
- `src/app/api/plugin/activity/route.ts` — `GET` (receive, ETag/304) + `POST` (send+receive stub).

**Anvil.Plugin** (`feat/activity-panel`)

*Pure, RuneLite-free, unit-tested models (new):*
- `ActivityEntry.java` — one feed row (mirrors the endpoint JSON) + `summary()` line text + `Kind.fromWire`.
- `AnvilActivityLog.java` — bounded (50), deduped, newest-first ring + cursor. Ingests server batches.
- `WorkingOnTracker.java` — "auto: last progressed" focus: feed it `TaskRow`s, get the spotlight tile.

*Integration (wired into the sidebar):*
- `AnvilSidebarDataSource.java` (new) — the **real** single-home `SidebarDataSource`: reads the config
  the plugin already polls (`AnvilPlugin::getPluginConfig`) for the board summary + nearest tiles + focus,
  and one conditional GET to `/api/plugin/activity` for the feed.
- `BingoApiClient.java` — `fetchActivity(since)` (+ `ActivityResponse`/`ActivityItem` DTOs), own ETag.
- `ConnectionView.java` — now carries `recentActivity` + `focus` (extra constructors; old ones still work).
- `AnvilSidebarPanel.java` — renders a "Working on" spotlight + a "Team activity" feed.
- `MockSidebarDataSource.java` — populates the new sections with live-moving fake data (offline demo).
- `AnvilPlugin.java` — `@Provides` now binds `AnvilSidebarDataSource` (swap for `mock` for offline UI work).

*Tests — `./gradlew test` green:* `AnvilActivityLogTest`, `WorkingOnTrackerTest`, `AnvilSidebarDataSourceTest`.

## Endpoint contract

```
GET /api/plugin/activity?since=<cursor>          Authorization: Bearer <accountToken>
                                                 If-None-Match: <last ETag>   → 304 when idle
→ 200 {
    cursor:   "s<subId>_c<compId>",   // send back as ?since= next poll
    activity: [ { id, ts, player, tileId, tileLabel, kind:"progress"|"complete", amount, isSelf } ],
    progress: { "<tileId>": <teamSubmissionSum> },  // only tiles that changed this batch
    truncated: false                  // true → you may have missed events; refetch the board
  }
```

- **Cursor** is opaque (`s<n>_c<n>`). First call (no/blank `since`) backfills the last ~15 as history;
  subsequent calls return only what's after the cursor, capped at 50/table.
- **Idle is free**: unchanged team → same payload → same weak ETag → **304, no body** (same
  `jsonWithEtag` the config/board routes use). Poll it with `If-None-Match`.
- **No-event** (valid token, not enrolled) → `{ activity:[], noActiveEvent:true }`, still 200/304 —
  don't treat as a failure.
- `isSelf` marks the caller's own events (submissions by `creditPlayerId`, completions by RSN).
- **`POST`** exists for the future send+receive tick (light `outbound` KC/XP pushes consolidating
  `/api/plugin/stats`). v1 does the receive half only and flags `outboundAccepted: 0` — keep pushing
  stats to `/api/plugin/stats` for now.

## Federation note

`/api/plugin/activity` is a normal plugin-surface route (like `/config`, `/board`), **not** part of
`FEDERATION_WIRE.md`. For the multi-home data source, add it to the per-instance fold exactly like
`/board`; a federation-token (`board:read`) variant can mirror it later, same as `/board` has both forms.

## Wiring (done for single-home; the pattern the multi-home track generalises)

`ConnectionView` now carries two fields (per instance — the feed and spotlight are per active event):

```java
public final List<ActivityEntry> recentActivity;   // newest-first; == log.snapshot()
public final ClogTaskModel.TaskRow focus;           // "you're working on", or null
```

In the **real** `SidebarDataSource.fetchConnections()`, for each connected instance, after the
`/board` read:

```java
// Keep per-instance state across polls (multi-home): a log + tracker + cursor + etag, keyed by instanceId.
InstanceFeed f = feeds.computeIfAbsent(instanceId, k -> new InstanceFeed());
if (activeEventChanged) { f.log.reset(); f.tracker.reset(); f.etag = null; }

ActivityResponse r = apiClient.fetchActivity(baseUrl, token, f.log.getCursor(), f.etag); // conditional GET
if (r.notModified) { /* 304 — reuse f.log as-is */ }
else {
    f.etag = r.etag;
    f.log.ingest(r.cursor, r.activity);   // bounded + deduped for you
}
ClogTaskModel.TaskRow focus = f.tracker.update(boardTaskRows);   // TaskRows you already build for /board

connections.add(new ConnectionView(instanceId, clanName, eventName, tilesComplete, tilesTotal,
    nearestTiles, f.log.snapshot(), focus));
```

`fetchActivity` is a thin client method (mirror `fetchBoard`): GET with `If-None-Match`, return
`{ notModified, etag, cursor, activity, progress, truncated }`. **Don't let Gson populate
`ActivityEntry` directly** — it bypasses the constructor's normalization and won't match the wire's
`isSelf`/lowercase-`kind`. Deserialize each element into a raw DTO (or `JsonObject`) and build through
the constructor: `new ActivityEntry(id, ts, player, tileId, tileLabel, ActivityEntry.Kind.fromWire(kind),
amount, isSelf)`. `Kind.fromWire` handles the enum casing for you.

`MockSidebarDataSource` can populate `recentActivity` from a few `ActivityEntry`s and set `focus` to
one of its nearest tiles, so the panel's new sections are reviewable without a server.

## Rendering (panel-side, your call on styling)

- **Spotlight** (if `focus != null`): item/boss icon + label + a prominent progress bar
  `focus.current/focus.goal`. This is the one that should feel instant — drive a ~1s Swing timer off
  the local config so your own drops tick it up between polls (stop the timer in `shutDown`).
- **Feed**: iterate `recentActivity`, one row each = `entry.summary()`. Style `entry.self` distinctly
  ("You …"), tint `entry.isCompletion()` green. Cap what you render (the log is already ≤50).
- **Mem-safety** (already handled in the models, keep it that way): log is bounded; call `reset()` on
  event change; don't retain old snapshots; the only timer is the spotlight ticker → stop on teardown.

## Verified

- Site: `npx tsc --noEmit` + `eslint` clean.
- Plugin: `./gradlew test` green — `AnvilActivityLogTest`, `WorkingOnTrackerTest`, and
  `AnvilSidebarDataSourceTest` (drives the real source headlessly: summary, nearest-ordering,
  focus-on-advance, event reset) + the full pre-existing suite.
- **Still to do — live/visual:** run it in RuneLite against a real instance (`./gradlew runClient`
  with a Site URL + token) to eyeball the panel and confirm the feed/spotlight move on real drops. The
  environment here is headless, so the Swing render wasn't visually verified.

## Follow-ups (not blocking)

- **Instant spotlight**: the panel polls every 15s; a ~1s local Swing timer reading the live-mutated
  config would make your own drops tick the spotlight sub-second (stop it in `shutDown`).
- **Icons**: spotlight/feed are text-only (matches the existing icon-less tile rows). `ItemManager` /
  `SpriteManager` could add tile icons later.
- **`POST /activity` outbound**: fold KC/XP pushes off `/api/plugin/stats` into the sync tick.
- **Multi-home**: generalise `AnvilSidebarDataSource` to iterate `{baseUrl, token}` homes — one
  `ConnectionView` (with its own per-instance `AnvilActivityLog` + `WorkingOnTracker`) per home.
