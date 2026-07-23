# Anvil.Plugin refactor & trim plan

Generated 2026-07-23 from a full read of all 30 main-source files (16,938 lines) on
`feat/plugin-consolidated`. Line numbers reference that commit's working tree.

**Headline:** ~1,700–2,000 lines removable with zero behavior change (~11%), plus a
delegation split that takes `AnvilPlugin.java` from 5,815 → ~800 lines and
`ClogTabController.java` from 3,374 → ~450, without moving any `@Subscribe` handler or
the hub-pinned `com.anvil.AnvilPlugin` entry point.

---

## 0. Real bugs / behavior issues found along the way

1. **RSN normalization drift (latent credit-miss bug).** Three divergent copies:
   - `AnvilPlugin.normalizeRsn` (4572): `replace(' ',' ').trim().toLowerCase()` — does NOT collapse inner whitespace runs.
   - `ClogTabController.normalizeRsn` (2199): `replaceAll("[\\s\\u00a0]+"," ")...` — DOES collapse.
   - `AnvilPlugin` inline (2196): nbsp-replace + `equalsIgnoreCase`, no collapse.
   PvP bounty matching and leaderboard-self-highlight can disagree on the same RSN. Unify on the collapsing version in one shared helper.
2. **Dead HTTP call every 30s per client.** `refreshSchedule()` (1579) fetches `/api/plugin/schedule` but the `schedule` field (727) is write-only — ClogTabController reads schedule off `PluginConfigResponse` (which `refreshConfig` already merges at 3877–3880). Removing kills a pointless fleet-wide request every 30s.
3. **`tryAutoEnrollWeekly` (1586) is a no-op shell** — `activeWeekly` (720) is write-only; site auto-enrolls now (the method's own comment admits it). `BingoApiClient.enrollWeekly` + `EnrollResponse` (687–715, 839–847) have zero callers.
4. **`AnvilSidebarPanel.setContent` (932)** wraps in `invokeLater` but every caller is already on the EDT — one frame of extra latency per state change.
5. **`ClanMember.accountHash` (BingoApiClient:992)** — doc claims it's set for the local player; no code ever sets it (roster build at AnvilPlugin:3740–3754 sets rsn/rank/joinedDays only).
6. **Clog orange defined 3× with two different values:** `ClogIds.TITLE_COLOR` (255,152,31 — unused), `BingoClogBannerOverlay.ORANGE` (255,152,31), `ClogTabController.COL_ORANGE` 0xff9040 = (255,144,64). Pick one.
7. **Unbounded session maps** in AnvilPlugin — only `lastSubmittedAt` is pruned (793–798); `killCounts`, `lastRareNotifyAt`, `lastAggregateNotifyAt`, `lastAllowlistNotifyAt`, `lastDiaryHandledAt`, `lastLootKillAt`, `lastTimedSubmittedAt`, `localStatProgressAt` grow for the session. Minor, but make pruning consistent.

## 1. Dead code (delete first, ~250–300 lines)

### AnvilPlugin.java
- `weeklyEnrollmentSummary` (722) — never assigned; `schedule` (727) + `refreshSchedule()` write-only (see bug #2); `activeWeekly` (720) + `tryAutoEnrollWeekly` (1586–1596); `knownMember` (694) / `isGuest` (696) write-only; `@Getter` on `apiClient` (138) and `pendingSubmissionStore` (154) unused; `lastSyncSummary` (708) should be a local.
- Orphaned duplicate javadoc blocks at 2533–2538, 4004–4008, 4581–4586, 5076–5080 (wrong/duplicated doc from moved code).
- `randomLine` (5556) unused by `buildDeathMessage` which re-inlines it (5543).
- Overly defensive: `client == null` checks at 3660/3700/3710 (injected), `executor != null && !isShutdown()` ×15 → one `runOnExecutor` helper.

### BingoApiClient.java
- `enrollWeekly` + `EnrollResponse` (687–715, 839–847) — dead endpoint (−38).
- Dead DTO fields (0 reads, grep-verified): `ClanMember.accountHash` (992), `ClanSyncResponse.renamed/.returned` (1000–1001), `ScheduledBingo.tileCount` (771), `ActivityResponse.truncated` (524).

### Other files
- `PluginConfigResponse.codeword` (10) — deliberately dropped from overlay (AnvilOverlay:60 comment), never read.
- `trackingMode` in 5 tracked-tile DTOs (PluginConfigResponse 153, 173, 201, 219, 308) — 0 reads.
- `CombatAchievementTier.points` + `getPoints()` (22, 36–39).
- `ClogIds`: `VARBIT_ACTIVE_TAB`, `VARBIT_ACTIVE_PAGE`, `ITEM_CELL_W/H`, `ITEM_STEP_X/Y`, `ITEM_COLS`, `FILTER_ROW_H`, `TITLE_COLOR` (26–27, 53–57, 59, 71) — all 0-ref.
- `ClogTaskModel`: `TaskRow.type` field (163) written never read + `typeOf` (143); 8-arg ctor (188–192) 0 callers; `earnedPoints(List)` 1-arg (771) 0 callers; stale javadocs (Kind lists 10 of 14 kinds, "Phase 4" refs).
- `ConnectionView`: board-only ctors (89–93, 96–100) — 0 callers.
- `BannerSoundService.playFile` (169–172) — 1-line pass-through.
- `BingoClogBannerOverlay.DISPLAY_SCALE = 1f` (33) — all multiplications are no-ops.
- Public filter setters on ClogTabController (257–279) have no external callers — drop to private.

## 2. Duplication → shared helpers (~1,100–1,400 lines)

### AnvilPlugin.java (~450–500)
- **Aggregate coalesce/flush/submit trio** (drops 2473–2530 / kills 2625–2734 / gains 2867–2963): identical get-or-create → reschedule shape; verbatim throttle block ×3 (2513, 2653, 2902); near-verbatim requeue-on-IOException ×2 (2695–2727, 2922–2956). → generic `CoalescingAggregate<T>` (−100–130).
- **KC push vs XP push** (4051–4106 vs 4135–4187): identical debounce/flush/merge-on-fail. → `StatPushBuffer` (−50).
- **Embed + optional-screenshot tail ×5** (4776, 4811, 4893, 4948, 4992) and manual embed build ×11 → `messageEmbed(title,desc,color)` + `postWithOptionalShot(...)` (−60–70).
- **Tracking-gate boilerplate ×8** (2139, 2271, 2295, 2550, 2583, 4445, 5108, 5163) + ad-hoc variants ×5 → `trackingActive(feature)` (−35).
- **event/team/player null-guard + id capture ×7** → `SubmissionContext.of(cfg)` (−30).
- **Capture→annotate→persist→submit ×3** (2982–3030, 3465–3527, 3409–3448) → one parameterized path (−80).
- **Synthetic-loot-from-name loop ×3** (2151, 2213, 2279) → `syntheticStacksForName` (−25).
- **Dedup-window idiom ×7** → `DedupWindow` micro-class (−25).
- Party-size gate ×3, entry-mode guard ×2, loot fan-out ×3, haul-pricing ×2 (−35 combined).
- Constant pools (taunts, quests, allowlist: lines 242–460) → `AnvilConstants` or resource file (−170 from this file).
- ~70 inline FQNs (`java.util.regex.Pattern` ×12, `com.google.gson.JsonObject` ×25, `java.awt.*` ×30) → imports.

### BingoApiClient.java (~300–360)
19 hand-rolled `try (Response …)` blocks in 5 copy-paste families; 8× url-guard + 5× `!isConfigured()` guard; 18 identical catch-log blocks. Consolidate to:
```java
private <T> T getJson(String path, boolean authed, Class<T> type)        // null-on-fail GET ×4
private <T> T getJsonCached(String path, Class<T> type, EtagCache<T> c)  // ETag GET ×3 (fetchConfig/Board/Activity)
private JsonObject postJson(String path, JsonObject payload)             // POST-or-null ×4
private void postOrThrow(String path, JsonObject payload, String ctx)    // authed POST ×4, uses submissionError
```
Plus: merge `submitStatKc`/`submitStatXp` (~40-line twins, differ only in `"kc"/"xp"` + `"stats"/"skills"`) (−35); merge `submitDrop`/`submitTimed` (same endpoint) (−25); route `fetchIsAdmin`/`syncClan` through `authedRequest` (same token: AnvilPlugin 3685–3691, 3758) (−10). Use `JsonParser.parseString` (the 4 `new JsonParser()` sites are the deprecated API).

### Clog/side UI (~500–700)
- **`ClogWidgetKit.text/clickableText/rect` factories** — the create/setText/setFontId/setTextShadowed/place/revalidate litany appears ~49× (+65× `place`, 73× `revalidate`, 15× listener triples) (−150–250). Largest single win in the repo.
- `renderEmptyBody` ×7 (−50), `finishScroll` ×11 (−20), `boardHeader` ×4 (−40–60), `renderDescription`≡`renderWrappedColored` (−16), cycle-filter scan ×3 (−25), `<col=…>` helpers for 56 tag sites + 8× dot-separator (−30–40), pre-masked `COMPLETE_RGB` ×8, `navigate(HubView)` for 11× `invokeLater(this::renderHub)` (−20).
- **AnvilSidebarPanel `SwingKit`**: `smallLabel` ×10, `thinBar` ×3, `capHeight` ×7, `column` ×3, shared `progressRow` merging `buildActiveRow`/`buildTileRow` (595–600 ≡ 862–867) (−90–130 total).
- **ClogTaskModel**: `matchesType` 14-case switch → name-parallel equality (−30); `build()` 11 near-identical kind loops, 4 literally identical → `addCountedRows` (−35).
- Cross-file: greedy word-wrap ×2 (ClogTabController.wrapText 545 vs BannerOverlay.wrap 290) + ellipsis ×3 (−50); ISO-date parsing ×3 (`epochMillis` 2323, `AnvilOverlay.isEventActive` 87, `parseTsMillis` 362) → `AnvilDates` (−40); percent formula ×3; HTML-escape ×2; SOTW/BOTW-label ternary ×3.

### Cross-file support (~110–130)
- Tolerant-JSON accessors ×3 (FederationState 248–288, ObsReplayClient 322 + inline guards, BingoApiClient inline) → `JsonSafe` (−70).
- `openFolder` daemon-thread ×2 (DebugLogExporter 180, PendingSubmissionStore 162) (−20).
- Named-daemon thread factories ×2 + raw `new Thread` ×3 → `NamedDaemonThreadFactory` (−10).
- 512KiB cap declared twice (`BingoApiClient.MAX_STATE_BYTES` 354, `FederationState.MAX_JSON_CHARS` 62) — one constant.
- Federation comment prose re-narrating FEDERATION_WIRE.md §§8–10 across 5 files: ~150–200 lines compressible (keep security rationale, cut narrative repeats). Optional.

## 3. Split plan — AnvilPlugin.java (5,815 → ~800)

Two options for extracted classes, both viable (EventBus facts verified against 1.12.30
source, see §7.4): collaborators CAN own `@Subscribe` handlers via `eventBus.register(obj)`
in startUp — cross-subscriber ordering IS deterministic (priority desc, then FQCN
alphabetical; set `@Subscribe(priority = …)` for explicit guarantees; methods must be named
`on<Event>`). Default remains thin delegators on `AnvilPlugin` for the chat mega-router
(explicit in-method ordering: KC parse must populate `killCounts` before rare-drop
embellishment in the same message), but coarse hooks (ClogTabController's widget/script
events) can self-register and drop their delegators. Prefer the RuneLite-injected shared
`ScheduledExecutorService` over a plugin-owned one (§7.1) — collaborators then take it by
plain constructor injection, no `Supplier` needed.

Extraction order (least coupled first):
1. `ObsClipManager` (~150; lines 169–189, 5615–5740) — easy.
2. `ProofImageComposer` (~150, mostly static; 3242–3391) — trivial.
3. `ProofSubmissionPipeline` (~450; capture/persist/upload/retry: 2971–3031, 3401–3640, 5759–5802, `retryBackoffMs`/`lastUploadAt`).
4. `EventConfigStore` (~380; `pluginConfig` + all 5 indexes + refreshConfig 3866–3950 + gates + checkTileCompletions). **The shared-mutable hub — every tracker reaches tiles only through this store.**
5. `InstancePartyTracker` (~90; fields 641–649, TOA/TOB varbit tables 1128–1145, party/death ticks) — the key shared-state extraction (read by drops 2380, deathless 3141, timed 3201).
6. `DropTileTracker` (~420; processLoot 2294–2465 + chat-credit paths + aggregate).
7. `KillTileTracker` (~200) + `GainTileTracker` (~300) + `ValueTileTracker` (~110) sharing `CoalescingAggregate`. Note `killCounts` is read by rare-drop notifier → expose `kcFor(String)`.
8. `TimedClearTracker` (~220; 651–682, 3043–3234).
9. `PvpKillTracker` (~250; 220–229, 1813–1838, 4394–4579).
10. `ClanNotifier` (~600; rare-drop 4581–4998, taunts/embeds 5469–5613, death/pet/champion) — optionally split `AchievementNotifier` (CA/diary/quest/milestones 5000–5467).
11. `ConnectionHealthMonitor` (~120; 1411–1535), `LmsTracker` (~110; 1223–1323, expose `isInGame()`), `ClanRosterSyncService` (~150; 3656–3815), `StatPushService` (~200; 4009–4187).

Remains in plugin: lifecycle, `@Provides`, 17 delegators, session state machine, hello/identity, clog-tab façade, `sendChatMessage`.

Coupling traps: `pluginConfig` wholesale replacement vs tracker mutation race (gain-floor
restore at 4223 exists because of it); rollback closures as `Runnable` (already the pattern);
`showBingoToast`→`locallyShownTiles`→`checkTileCompletions` triangle routes through the store.

## 4. Split plan — clog UI

### ClogTabController (3,374 → ~450) → 8 classes
1. `ClogWidgetKit` (~250, static + ItemManager) — text/wrap/place/color/sprite helpers (461–617, 1488, 1732, 3151–3373 statics).
2. `ClogTabInjector` (~280) — native tab inject/layout/scrollbar/boss-list plumbing (304–459, 619–643, 1279–1384).
3. `ClogBoardCache` (~150) — board/preview fetch + cache (815–895, 955–1012). Keep the render-triggered fetch explicit (`ensureBoardRequested`).
4. `ClogTaskListView` (~500) — accordion, filters, search, chips (255–302, 1386–1440, 1444–1874) behind a `TaskSource` interface (own-config vs previewed-board impls kills the `hubView == POINTS` branches).
5. `ClogScheduleView` (~380) — schedule home + rows + date utils (1876–2076, 2214–2360).
6. `ClogLeaderboardView` (~170) — 2078–2212 + countdown. **Hazard:** `countdownLine` widget handle refreshed from `onGameTick` — must be nulled when any other view repaints the header.
7. `ClogBoardViews` (grid/detail/race, ~600) — 2362–3149; move pure `boardTaskRows`/`boardTileKind` (2539–2615) into ClogTaskModel (testable).
8. Slim controller — lifecycle, `renderHub` routing, nav state; optionally extract `ClogLeftColumnView` (1049–1277 bundles nav + sounds + proofs + admin sync).

Iron rule for the split: one "clear + render" owner per repaint; views receive the `Widget`,
never fetch/clear the native containers themselves; all rendering stays on the client thread
behind the `clogOpen && bingoTabActive` guards.

### AnvilSidebarPanel (969 → ~350) → 3–4 classes
`FederationConnectRow` (~200; 203–347), `SidebarRows` factories (~280 post-dedup; 570–950),
`SidebarText` (~60; `plainText`/`ellipsize`/`isSafeHttpUrl` — security chokepoint, covered by
SidebarTextSafetyTest; preserve package access for tests).

## 5. Package restructure (optional, last)

All 30 files sit flat in `com.anvil`. Proposed: `com.anvil.api` (+`api.dto` — untangles
`PluginConfigResponse` ↔ `BingoApiClient.ScheduleResponse` circularity), `com.anvil.clog`,
`com.anvil.sidebar`, `com.anvil.federation`, `com.anvil.detect`, `com.anvil.io`.
**Hub constraints:** `AnvilPlugin` + `AnvilConfig` stay in `com.anvil`
(`runelite-plugin.properties` `plugins=com.anvil.AnvilPlugin`; `@ConfigGroup("osrsbingo")`
must never change or users lose settings). Tests are same-package and use package-private
seams (`TimedClearParser`, `normalizeBaseUrl`, `FederationState.withinDepthLimit`,
`isPinnedBrokerUrl`/`withUserCode`) — move tests with their classes.

## 6. Sequencing vs the plugin-hub diff budget

The hub sizes PRs on raw added+deleted lines; `feat/plugin-consolidated` is already
+4,423/−55 vs main (past the 4,000 size-l threshold). A refactor multiplies that (every moved
line counts twice). Recommended order:
1. **Now, on the consolidated branch:** only trims to code that is *new in this branch*
   (shrinks the pending hub diff) + the RSN-normalization bug fix.
2. **Ship consolidated.**
3. **Then a dedicated `refactor/slim` PR:** dead code + dedup helpers (§1–2). Net −1,700–2,000.
4. **Then split PRs, one seam at a time** (§3–4), each mechanical and reviewable: proof
   pipeline first, then config store + party tracker, then trackers, then notifiers, then clog views.
5. **Package restructure last**, as a pure `git mv` + import-fix commit.

Lombok (1.18.30) is already an annotationProcessor — use `@Value`/`@Getter` for the small
manual data classes (CombatAchievementTier, DebugLogExporter.Result, FederationConnect).
Gson + OkHttp come from the RuneLite client (injected — keep it that way, hub rule).

## 7. RuneLite-provided APIs replacing plugin code (verified vs 1.12.30 jars + source)

Every claim below was checked with `javap`/`unzip -l` against the cached
`client-1.12.30.jar` / `runelite-api-1.12.30.jar` and the `runelite-parent-1.12.30` GitHub
tag — nothing from memory. Classpath facts: client's compile deps include Guava 23.2-jre,
commons-text 1.2 (→ commons-lang3 3.7), Gson 2.8.5, OkHttp 3.14.9 — using them adds no
dependency (RuneLite core's own `Text` uses Guava + commons-text).

### 7.1 Adopt

| Ours | Replacement | Notes |
|---|---|---|
| 3 divergent RSN normalizers (AnvilPlugin 4572, inline 2196; ClogTabController 2199) | `net.runelite.client.util.Text#standardize` | Exact transform: `removeTags + nbsp→space + trim + lowercase`. Fixes the drift bug; bonus tag-stripping handles `<img=N>` ironman icons. NPEs on null — keep a null guard. NOT `toJagexName` (eats hyphens, no lowercase). |
| 11 timestamp-window/dedup maps in AnvilPlugin (9 unpruned, grow all session) | Guava `CacheBuilder.newBuilder().expireAfterWrite(…)` → `Cache<K,Boolean>` | Fixes the unbounded-growth leak. `killCounts` stays a plain map (counter, not window). |
| 3 hand-rolled `openFolder` (DebugLogExporter 180, PendingSubmissionStore 162, BannerSoundService 225) | `LinkBrowser.open(path)` | Spawns its own thread; tries `xdg-open` first on Linux (fixes broken `Desktop.open` there — real UX win). Delta: modal copy-path dialog on total failure vs our silent log. No `openLocalFile` in 1.12.30. |
| `sendChatMessage` manual `<col=>` + `invokeLater` (5804–5815) | `ChatMessageManager#queue(QueuedMessage)` + `ChatMessageBuilder` | Thread-safe queue drained on client thread — the `invokeLater` goes away. Use `append(Color,String)` overloads (plain `append` escapes `<>`; Color overload doesn't — matches current output byte-for-byte). Hub-idiomatic. |
| Plugin-owned `executor` (748) + shutdownNow (812) + ~20 `executor == null \|\| isShutdown()` guards; DiscordWebhookClient `retryScheduler` (59 — never shut down, thread leak); BannerSoundService `audioExecutor` (40) | RuneLite-injected shared `ScheduledExecutorService` (bound in `RuneLiteModule`, wrapped in `ExecutorServiceExceptionLogger`; core ScreenshotPlugin injects it the same way) | NEVER shut it down — cancel tracked `ScheduledFuture`s in shutDown instead. One-shot debounce/coalesce `schedule()` calls move over unchanged. Keep long blocking HTTP off it where possible (it's single-threaded, client-wide). |
| 30s `scheduleAtFixedRate` loop + `safely()` wrapper (767–791) | `@Schedule(period = 30, unit = ChronoUnit.SECONDS, asynchronous = true)` methods | Scheduler catches exceptions per invocation → `safely()` deletable. Trap: a bare `scheduleAtFixedRate` on the shared executor still DIES on first throw (`RunnableExceptionLogger` logs then rethrows) — `@Schedule` is what makes this safe. `asynchronous=true` required (we do HTTP). ~600ms resolution; runs from Hooks.tick even at login screen; first fire after one period (same as today). |
| Deprecated id imports: `widgets.InterfaceID.COLLECTION_LOG`, `ComponentID.COLLECTION_LOG_{TABS,ENTRY_HEADER (8 sites),ENTRY_ITEMS}`, legacy `SpriteID.SKILL_*` switch (590–614), quest-scroll raw 153/child-4 (AnvilPlugin 409–410), `COINS_ITEM_ID=995` (ClogTaskModel 476), ClogIds tab sprites 2283–2286 + 6390 | `gameval.InterfaceID.COLLECTION` / `InterfaceID.Collection.{TABS,HEADER_TEXT,ITEMS_CONTENTS}` / `SpriteID.Staticons{,2}.*` / `InterfaceID.QUESTSCROLL` + `InterfaceID.Questscroll.QUEST_TITLE` (packed, one-arg getWidget) / `ItemID.COINS` / `SpriteID.TabsTall._0.._3` + `IconActivities25x25.COLLECTIONS_LOGGED` | All values verified matching. `ADVENTURE_LOG`→`MENU` (gameval name less readable — keep a comment). **Add `Staticons2.SAILING = 228`** — exists now; our `-1` fallback is stale (bug). ToA/ToB party varbits + all CA varbits + bank/GE groups are ALREADY gameval — nothing to do there. |
| `formatGp` (1803) / sidebar `formatCount` (651) / `String.format("%,d")` ×2 | `QuantityFormatter#quantityToStackSize` / `#quantityToRSDecimalStack` / `#formatNumber` | Cosmetic output deltas (floor vs half-up, `"1.50M"` vs `"1.5M"`, `"15.9K"` vs `"15K"`) — opt in only if visible drift is OK. |
| Hand-themed thin `JProgressBar` ×3 (sidebar 639, 802, 895) | `net.runelite.client.ui.components.ThinProgressBar` (4px; `setMaximumValue`+`setValue` — no pre-scaling to 100) | Track color derived as `fg.darker().darker()` vs our explicit DARKER_GRAY — slight visual delta. |
| Lambda thread factories ×2; banner bg manual `ImageIO.read` (BingoClogBannerOverlay 102); `setSiteConnectStatus` inline HTML escape (343) | Guava `ThreadFactoryBuilder`; `ImageUtil.loadImageResource` (throws on missing — wrap try/catch to keep fail-soft); commons-text `StringEscapeUtils.escapeHtml4` (that site is always-HTML) | Do NOT swap `plainText` (729) — its conditional escaping is the security design. |

### 7.2 New bugs found during verification

1. **Banner sound at volume 0 can play at FULL volume**: `AudioPlayer.trySetGain` does NOT
   clamp (comment at BannerSoundService:176 is wrong) — it warns and plays anyway when
   −80 dB is outside the line's MASTER_GAIN range. Clamp to the line's range or skip playback
   at volume 0.
2. **Sailing skill sprite**: `SpriteID.Staticons2.SAILING = 228` exists in 1.12.30; the
   skillSpriteId `-1` fallback for sailing is stale.
3. **Blank-icon race in proof screenshots**: `itemManager.getImage()` at AnvilPlugin:3463
   returns `AsyncBufferedImage`; first-encounter icons can render blank in the annotated
   proof. Use `AsyncBufferedImage#onLoaded(Runnable)`.
4. **Silent clog-credit hole**: `VarbitID.OPTION_COLLECTION_NEW_ITEM = 11959` — users with the
   in-game "collection log — new item" chat setting off never produce the line our drop-tile
   clog credits parse. Add a nudge like `maybeNudgeLootNotifications`.
5. DiscordWebhookClient's `retryScheduler` is never shut down (leaks a thread across plugin
   restarts) — fixed by the shared-executor migration.

### 7.3 Detection upgrades available (optional, not hand-rolled duplication)

- `VarbitID.CA_TIER_STATUS_{EASY..GRANDMASTER} = 12863–12868`: direct per-tier CA completion
  status (VarbitChanged) vs our points-threshold inference for tier-clear posts.
- All 48 diary-tier completion varbits exist (`{AREA}_DIARY_{TIER}_COMPLETE`, Karamja via
  `ATJUN_*`): chat parse stays the trigger, but varbits enable login-time reconciliation of
  diaries completed while the plugin was off — capability chat can never have.
- `Notifier#notify` as an *additive* channel for tile completions (tray/flash when
  unfocused, honors user notification prefs). Not a replacement for the banner.
- `Quest`/`QuestState` (`getState` is CS2-script-based, client-thread) could cross-check the
  quest-scroll parse; no difficulty metadata — our GM/Master sets stay.

### 7.4 EventBus / framework facts the split depends on (verified from source)

- `EventBus.register(Object)` scans class + superclasses for `@Subscribe`; methods MUST be
  named `on<EventSimpleName>` (hard Preconditions check); one subscription per event class
  per object; unregister in shutDown.
- Ordering across subscribers is DETERMINISTIC: `@Subscribe(priority)` desc, ties broken
  alphabetically by FQCN (so `com.anvil.AnvilPlugin` before `com.anvil.ClogTabController` at
  default priority). Use explicit priority rather than relying on names.
- `PluginManager` registers the plugin AFTER `startUp()` returns; collaborators registered in
  startUp are on the bus first — harmless. `GameEventManager` exists for late registrants.

### 7.5 Rejected (checked — doesn't exist or wrong fit)

`Text.toJagexName` (strips hyphens, no lowercase — wrong for RSN compare) · no word-wrap
util anywhere (ours stays ×2) · `DurationFormatUtils`/`RSTimeUnit` can't express humanGap's
3-branch format · `plainText`'s conditional escape is deliberate — keep ·
`ImageCapture` is disk-save only (no in-memory PNG; our DrawManager flow already matches core
ScreenshotPlugin idiom and adds a timeout core lacks) · overlay components can't do the
banner's 5-phase animation; `AnvilOverlay` is ALREADY OverlayPanel + components ·
`MenuManager`/`WidgetMenuOption` target existing client widgets — `createChild` widgets
require the manual listener wiring we have · `WildcardMatcher` has no capture groups (all 12+
regexes are chat parsers) · NO typed events exist for clog unlocks/CA/quest/diary/pets (full
events listing checked — chat/widget/varbit detection is mandatory) · no ParamID/EnumID CA
metadata; CA task names must come from chat · `SkillIconManager`/`SpriteManager` are
Swing-layer; widget `setSpriteId` is correct · `@Schedule` is periodic-only — one-shot
debounces stay on the executor; sidebar's 15s poll correctly stays on `javax.swing.Timer`
(EDT) · `ConfigManager` RSProfile: no misuse found (token + banner clip are correctly
global) · `AnvilConfig` already uses sections/secret/Keybind properly — only `@Range`/`@Units`
polish available.
