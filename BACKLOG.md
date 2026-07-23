# Anvil.Plugin backlog — bugs & improvements

Source: full-codebase audit + RuneLite 1.12.30 API verification, 2026-07-23.
Details with exact line numbers: `REFACTOR_PLAN.md`. Sequencing note: refactor items ship
AFTER `feat/plugin-consolidated` (hub sizes PRs on raw added+deleted lines; branch is
already +4,423 > the 4,000 size-l threshold) — see REFACTOR_PLAN.md §6.

## P1 — bugs (fix regardless of refactor)

- [ ] **RSN normalization drift** — 3 divergent impls (AnvilPlugin:4572, inline :2196, ClogTabController:2199); PvP bounty matching vs leaderboard self-highlight can disagree on the same RSN. Fix: unify on `Text.standardize` (verified near-exact; keep null guard). *(safe to include in consolidated branch)*
- [ ] **Banner sound at volume 0 can play at FULL volume** — `AudioPlayer.trySetGain` doesn't clamp; lines rejecting −80 dB warn-and-play. Clamp gain to the line's range or skip playback at volume 0 (BannerSoundService:169–183; the comment at :176 is wrong).
- [ ] **Silent clog-credit hole** — users with in-game "collection log new item" chat setting off never emit the line drop-tile clog credits parse. Detect via `VarbitID.OPTION_COLLECTION_NEW_ITEM` (11959) and nudge like `maybeNudgeLootNotifications` (AnvilPlugin:5252).
- [ ] **Dead HTTP fetch every 30s per client** — `refreshSchedule()` (AnvilPlugin:1579) result is write-only; config refresh already merges schedule. Delete method + field + `fetchSchedule` call from the 30s loop.
- [ ] **Sailing skill sprite stale** — `SpriteID.Staticons2.SAILING = 228` exists in 1.12.30; skillSpriteId's −1 fallback (ClogTabController:577–617) leaves sailing iconless.

## P2 — bugs (minor / cosmetic / leaks)

- [ ] **DiscordWebhookClient retry thread leak** — `retryScheduler` (:59) never shut down across plugin restarts. Fixed by shared-executor migration (below).
- [ ] **Unbounded session maps** — 9 of 11 timestamp maps in AnvilPlugin never pruned. Fixed by Guava-cache migration (below).
- [ ] **Blank-icon race in proof screenshots** — `itemManager.getImage()` at AnvilPlugin:3463 is an `AsyncBufferedImage`; first-encounter icons can render blank in the annotated proof. Use `onLoaded(Runnable)`.
- [ ] **Clog orange defined 3× with 2 values** — ClogIds.TITLE_COLOR (255,152,31; dead), BannerOverlay.ORANGE (255,152,31), ClogTabController.COL_ORANGE (0xff9040 = 255,144,64). Pick one.
- [ ] **`AnvilSidebarPanel.setContent` redundant EDT re-dispatch** (:932) — every caller is already on the EDT; one frame of extra latency per state change.
- [ ] **`ClanMember.accountHash` never set** (BingoApiClient:992) — doc claims local player gets it; roster build doesn't. Either set it or delete field + doc.

## Hub compliance (2026 rules — verified from plugin-hub / plugin-hub-tooling / wiki, 2026-07-23)

- [ ] **Change `runeLiteVersion` pin `1.12.30` → `'latest.release'`** in build.gradle — the hub builds all plugins against its own `runelite.version` (1.12.33 today) regardless; our pin only lags local dev. No breaking changes in the 1.12.30→1.12.33 window (verified via compare API).
- [ ] **Add `warning` to the `apiUrl` config item** (AnvilConfig:70) — third-party-server disclosure is required policy ("explaining what data is being sent"); default "" already satisfies opt-in. Consider same for the user-supplied clips webhook item.
- [x] **Disallowed-API scan: CLEAN** — no `widgets.WidgetID`/`WidgetInfo`, `new Gson()`, `new OkHttpClient()`, `Client.getVar()` anywhere (grep-verified). These are CI-fatal on hub PRs since the packager's `disallowed-apis.txt`.
- [ ] Keep the crowdsourcing hard line in mind for future features: "no crowdsourcing data about other players" — rival-RSN/bounty data must keep flowing server→plugin, submissions concern only the local user's gameplay.
- Notes: AI review bot auto-reviews submissions since Apr 2026 (aligned with templateplugin/AGENTS.md → gameval migration items below double as bot-nag prevention). Java 11 bytecode is CI-enforced (no records ever — DTO compression must stay Lombok/field-style). Java 11 + junit 4.12 + lombok 1.18.30 all match the current template. The "4,000-line size-l" threshold is NOT written policy — size labels exist, mapping unpublished; phasing still wise since new-endpoint PRs get slower human review.

## Refactor phase A — dead code (net −250–300 LOC)

- [ ] AnvilPlugin: `weeklyEnrollmentSummary`, `schedule`+`refreshSchedule`, `activeWeekly`+`tryAutoEnrollWeekly`, `knownMember`/`isGuest`, unused `@Getter`s, `lastSyncSummary`→local, orphaned javadoc ×4, `randomLine`, `client == null` guards on injected fields.
- [ ] BingoApiClient: `enrollWeekly`+`EnrollResponse` (−38), dead DTO fields (`renamed`/`returned`/`tileCount`/`truncated`/`accountHash`).
- [ ] PluginConfigResponse: `codeword`, `trackingMode` ×5. CombatAchievementTier: `points`+getter.
- [ ] ClogIds: 9 dead constants. ClogTaskModel: `TaskRow.type`+`typeOf`, 8-arg ctor, 1-arg `earnedPoints`, stale javadocs. ConnectionView: 2 dead ctors. BannerSoundService.playFile. BannerOverlay DISPLAY_SCALE no-ops. ClogTabController filter setters → private.

## Refactor phase B — RuneLite API adoption (net −150–250 LOC, kills boilerplate + 3 leaks)

- [ ] `Text.standardize` for all RSN normalization (also P1 above).
- [ ] Guava `CacheBuilder.expireAfterWrite` for the 11 dedup/window maps (guava 23.2 is a client dep). `killCounts` stays a plain map.
- [ ] Shared injected `ScheduledExecutorService` — delete plugin-owned executor + shutdownNow + ~20 guards; migrate DiscordWebhookClient.retryScheduler and BannerSoundService.audioExecutor. NEVER shut the shared one down; cancel tracked futures in shutDown.
- [ ] 30s loop → `@Schedule(period=30, unit=SECONDS, asynchronous=true)`; delete `safely()` (Scheduler catches per-invocation; NOTE: raw shared executor rethrows — @Schedule is required for this to be safe).
- [ ] `sendChatMessage` → `ChatMessageManager.queue` + `ChatMessageBuilder` (use `append(Color,String)` overloads for byte-identical output).
- [ ] `LinkBrowser.open` for the 3 openFolder copies (also fixes Linux xdg-open).
- [ ] gameval renames: `InterfaceID.Collection.*` (10 sites), `InterfaceID.COLLECTION`, quest scroll → `InterfaceID.QUESTSCROLL` + `Questscroll.QUEST_TITLE`, `ItemID.COINS`, skill switch → `SpriteID.Staticons{,2}` (+ sailing case), tab sprites → `TabsTall._0.._3` + `IconActivities25x25.COLLECTIONS_LOGGED`, `ADVENTURE_LOG`→`MENU` (keep comment).
- [ ] `ThreadFactoryBuilder` ×2; `ImageUtil.loadImageResource` for banner bg (wrap try/catch); `StringEscapeUtils.escapeHtml4` for `setSiteConnectStatus` only (NOT `plainText` — conditional escape is deliberate).
- [ ] Optional (visible output changes): `QuantityFormatter` for formatGp/formatCount; `ThinProgressBar` ×3 (track color delta).

## Refactor phase C — dedup helpers (net −1,100–1,400 LOC)

- [ ] BingoApiClient: `getJson`/`getJsonCached`(EtagCache)/`postJson`/`postOrThrow`; merge submitStatKc+Xp; merge submitDrop+Timed; route fetchIsAdmin/syncClan via authedRequest; `JsonParser.parseString` (1,212 → ~850).
- [ ] AnvilPlugin: `CoalescingAggregate<T>` (drop/kill/gain trio), `StatPushBuffer` (KC+XP push), `messageEmbed`+`postWithOptionalShot` (11 embed builds, 5 screenshot tails), `trackingActive()` ×8+, `SubmissionContext` ×7, unified capture→submit path ×3, `syntheticStacksForName` ×3, party-gate/entry-mode/loot-fan-out/haul-pricing helpers, constant pools → AnvilConstants, FQN cleanup ×70.
- [ ] Clog UI: `ClogWidgetKit.text/clickableText/rect` (~49 sites — single biggest win), `renderEmptyBody` ×7, `finishScroll` ×11, `boardHeader` ×4, `<col=>` helpers (56 sites), cycle-filter generic ×3, `navigate()` ×11.
- [ ] Sidebar: `SwingKit` (smallLabel/thinBar/capHeight/column), shared `progressRow` (buildActiveRow≡buildTileRow).
- [ ] ClogTaskModel: `matchesType` → name-parallel equality, `addCountedRows` for 4 identical build() loops.
- [ ] Cross-file: `JsonSafe` (3 tolerant-JSON impls), `AnvilDates` (3 ISO parsers), unified word-wrap (2) + ellipsis (3), single 512KiB cap constant, SOTW/BOTW label ×3, HTML-escape ×2.

## Refactor phase D — file splits (maintainability; net +400–600 LOC of class scaffolding)

- [ ] AnvilPlugin (5,815 → ~800): ObsClipManager → ProofImageComposer → ProofSubmissionPipeline → EventConfigStore → InstancePartyTracker → Drop/Kill/Gain/Value trackers → TimedClearTracker → PvpKillTracker → ClanNotifier (+AchievementNotifier) → ConnectionHealthMonitor, LmsTracker, ClanRosterSyncService, StatPushService. Chat mega-router stays a single delegating handler (in-method ordering matters); coarse hooks may self-register (EventBus ordering IS deterministic: priority desc, then FQCN).
- [ ] ClogTabController (3,374 → ~450): ClogWidgetKit, ClogTabInjector, ClogBoardCache, ClogTaskListView (TaskSource interface), ClogScheduleView, ClogLeaderboardView (countdownLine hazard), ClogBoardViews, ClogLeftColumnView. One clear+render owner per repaint; `boardTaskRows`/`boardTileKind` → ClogTaskModel (testable).
- [ ] AnvilSidebarPanel (969 → ~350): FederationConnectRow, SidebarRows, SidebarText (security chokepoint — keep tests' package access).
- [ ] Package restructure LAST (pure git mv): com.anvil.{api,api.dto,clog,sidebar,federation,detect,io}; AnvilPlugin+AnvilConfig STAY in com.anvil (runelite-plugin.properties + `osrsbingo` config group). Move same-package tests with their classes.

## Detection upgrades (product improvements, optional)

- [ ] CA tier-clear via `VarbitID.CA_TIER_STATUS_{EASY..GRANDMASTER}` (12863–12868) VarbitChanged instead of points-threshold inference.
- [ ] Diary login-time reconciliation via the 48 `{AREA}_DIARY_{TIER}_COMPLETE` varbits (catches diaries completed while plugin was off; chat parse stays the live trigger).
- [ ] `Notifier.notify` as additive channel on tile completion (tray/flash for unfocused clients, honors user prefs).
- [ ] Quest scroll parse hardening: cross-check name against `Quest.getName()`/`getState() == FINISHED`.
- [ ] Timed-tile progress as InfoBox `Timer` / tile-progress `Counter` (nice-to-have UX).

## Polish

- [ ] Lombok for small data classes (`CombatAchievementTier` @Getter, `DebugLogExporter.Result` / `FederationConnect` @Value).
- [ ] `@Range`/`@Units` on numeric config items (spinner clamping; no LOC change).
- [ ] Federation comment-prose compression across 5 files (~150–200 lines; keep security rationale).
- [ ] Refresh stale ClogIds header comment (cites 1.12.24 + deprecated class names).
- [ ] persist `announcedQuests` via `setRSProfileConfiguration` if cross-restart quest dedup ever matters (not a bug today).
