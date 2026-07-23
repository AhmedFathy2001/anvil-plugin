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

Constraint honored: all 17 `@Subscribe` methods stay on `AnvilPlugin` as one-line delegators
(preserves auto-registration AND intra-batch handler ordering, which matters — KC parse must
populate `killCounts` before rare-drop embellishment in the same message batch). Extracted
classes are plain `@Singleton` collaborators; no `eventBus.register`. `executor` is created in
startUp — pass `Supplier<ScheduledExecutorService>` or give collaborators start/stop.

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
