package com.anvil.notify;

import com.anvil.session.LocalPlayer;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.detect.CombatAchievementTier;
import com.anvil.detect.GamePools;
import com.anvil.detect.QuestAnnounceTier;
import com.anvil.util.AnvilChat;
import com.anvil.util.ChatText;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;

/**
 * The posts that are about the account rather than about a drop: combat achievements, diaries,
 * quests, ninety-nines and total-level milestones.
 *
 * <h2>Baselines, because the client shouts on login</h2>
 *
 * <p>Every one of these is "did a number go up", and every one of them is handed a full set of
 * numbers the moment the player logs in. Without a baseline seeded first, a relog announces a
 * ninety-nine earned last year and a total level crossed in March. So the first reading of a session
 * sets the mark and says nothing; only a genuine crossing after that is news.</p>
 *
 * <p>The combat-achievement path has a second reason for it: the completion line and the points
 * total arrive in different ticks, so a batch is parked and judged against the baseline on the next
 * tick — which is how a re-completion is told apart from a first.</p>
 *
 * <h2>Not on a world where the stats are not the player's</h2>
 *
 * <p>PvP Arena hands out a preset maxed account. Leagues, Deadman, Tournament, Beta, Fresh Start,
 * Quest Speedrunning and LMS all report levels that are not main-game progression. Announcing a
 * ninety-nine earned in a two-hour Leagues world is announcing something that did not happen.</p>
 */
@Slf4j
@Singleton
public class AchievementNotifier
{
    // Combat achievement task completion, e.g.
    // "Congratulations, you've completed an Elite combat task: Whack-a-Mole."
    // Package-visible for CombatTaskLineTest — CA bingo-tile crediting keys off this line.
    public static final Pattern CA_TASK_PATTERN = Pattern.compile(
            "Congratulations, you've completed an? (\\w+) combat task: (.+?)\\.?$");

    // Trailing " (5 points)" appended when the in-game recompletion setting is on.
    public static final Pattern CA_TASK_POINTS = Pattern.compile(
            "\\s*\\(\\d+ points?\\)$");

    private final Client client;
    private final ClientThread clientThread;
    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final AnvilChat chat;
    private final AnvilEmbeds embeds;
    private final MomentsService moments;
    private final com.anvil.track.RecapCounters counters;

    private final Supplier<PluginConfigResponse> pluginConfig;
    private final Supplier<String> localPlayerName;

    /** Notifications, proof screenshots and clips — the requests carrying a file. */
    private final com.anvil.api.MediaUploads media;

    @Inject
    AchievementNotifier(Client client, ClientThread clientThread, AnvilConfig config, BingoApiClient apiClient,
            AnvilChat chat, AnvilEmbeds embeds, MomentsService moments,
            com.anvil.track.RecapCounters counters,
        Supplier<PluginConfigResponse> pluginConfig, LocalPlayer localPlayer,
        com.anvil.api.MediaUploads media) {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.apiClient = apiClient;
        this.chat = chat;
        this.embeds = embeds;
        this.moments = moments;
        this.counters = counters;
        this.pluginConfig = pluginConfig;
        this.localPlayerName = localPlayer::name;
        this.media = media;
    }


    /**
     * Seed the baselines from the live client, once per login, saying nothing.
     *
     * <p>Without this a relog announces a ninety-nine earned last year: the client hands over a full
     * set of numbers the moment you log in, and every one of these posts is "did a number go up".
     * The first reading sets the mark; only a crossing after that is news.</p>
     */
    /**
     * Baseline CA points once after login (before any completion), so a first completion (points
     * rise) can be told from a recompletion (points unchanged) — then hand over whatever the chat
     * router parked last tick.
     */
    public void onGameTick(java.util.List<PendingCaTask> parked) {
        seedBaselines(() -> client.getVarbitValue(net.runelite.api.gameval.VarbitID.CA_POINTS),
                client::getTotalLevel);
        if (!parked.isEmpty()) {
            handleCombatAchievements(parked);
        }
    }

    public void seedBaselines(java.util.function.IntSupplier caPoints, java.util.function.IntSupplier totalLevel) {
        if (!caPointsInitialized) {
            int p = caPoints.getAsInt();
            if (p > 0) {
                lastCaPoints = p;
                caPointsInitialized = true;
            }
        }
        if (!totalLevelInitialized && !statsAreArtificial()) {
            int t = totalLevel.getAsInt();
            if (t > 0) {
                lastTotalLevel = t;
                totalLevelInitialized = true;
            }
        }
    }

    /** The quest-completion scroll's interface group, for the widget hook. */
    public static int questScrollGroup() {
        return QUEST_COMPLETED_GROUP_ID;
    }

    /** A skill already announced at 99 this session — the chat line and StatChanged both fire. */
    public boolean alreadyAnnounced99(String skillName) {
        return notified99.contains(skillName);
    }

    public void note99(String skillName) {
        notified99.add(skillName);
    }

    public void clear99s() {
        notified99.clear();
    }

    /** A new account is logging in: forget what the last one had already been announced for. */
    public void onLogout() {
        notifiedCaTasks.clear();
        announcedQuests.clear();
        notified99.clear();
        lastCaPoints = -1;
        caPointsInitialized = false;
        lastTotalLevel = -1;
        totalLevelInitialized = false;
    }

    // Quest-completed scroll interface — gameval InterfaceID.QUESTSCROLL (153); child 4 is
    // Questscroll.QUEST_TITLE, the "You have completed <Quest>!" line. Same signal RuneLite's
    // screenshot plugin keys off.
    private static final int QUEST_COMPLETED_GROUP_ID = 153;

    private static final int QUEST_COMPLETED_TEXT_CHILD = 4;

    // Session dedup — the scroll widget can reload (resizing, lag) without a new completion.
    private final Set<String> announcedQuests = new LinkedHashSet<>();

    // Quest-name extraction, ported from RuneLite's ScreenshotPlugin (BSD-2) — the scroll text
    // varies: "You have completed The Corsair Curse!", "'One Small Favour' completed!",
    // "Congratulations! You have defeated the Culinaromancer!" (RFD subquests), and the
    // "kind of"/"completely" phrasings of Hazeel Cult and Rag and Bone Man.
    private static final Pattern QUEST_PATTERN_1 = Pattern.compile(
            ".+?ve\\.*? (?<verb>been|rebuilt|.+?ed)? ?(?:the )?'?(?<quest>.+?)'?(?: [Qq]uest)?[!.]?$");

    private static final Pattern QUEST_PATTERN_2 = Pattern.compile(
            "'?(?<quest>.+?)'?(?: [Qq]uest)? (?<verb>[a-z]\\w+?ed)?(?: f.*?)?[!.]?$");

    // Task names already announced this session. Dedups recompletions (the in-game "repeat
    // completion" message) by NAME — which also lets multiple completions in one tick all post,
    // unlike the old points-delta guard that saw a single rise per tick and dropped the rest.
    private final Set<String> notifiedCaTasks = new LinkedHashSet<>();

    // Last-known total CA points, baselined at login; used only for tier-clear detection now.
    private int lastCaPoints = -1;

    private boolean caPointsInitialized;

    // Last-known total level, baselined at login so we only announce genuine crossings (not the
    // total we logged in with). Total only ever rises on a skill level-up, so we check it there.
    private int lastTotalLevel = -1;

    private boolean totalLevelInitialized;

    private final Set<String> notified99 = new HashSet<>();

    // NOTE on Skill.OVERALL, which four loops here used to skip by hand: it is not in
    // Skill.values() any more. The client builds $VALUES and then assigns OVERALL = null as a
    // source-compatibility tombstone, so every "skip OVERALL" guard was comparing against null and
    // never fired. They are gone; Skill.values() is already the trainable skills and nothing else.
    // High-total milestones: every step at/above the floor, e.g. 1800, 1900, … plus max total
    // (computed from the live Skill enum so it tracks future skills, e.g. Sailing → 2376). Floor is
    // ~1750 so it kicks in for high accounts without spamming every 50 levels.
    private static final int TOTAL_MILESTONE_FLOOR = 1750;

    private static final int TOTAL_MILESTONE_STEP = 100;

    /**
     * Handles a parsed combat-task completion (run a tick after the chat line
     * so the CA points varbit has settled). Posts the individual task if it
     * clears the configured min tier, and a separate tier-clear post when this
     * task pushed total points across a tier threshold.
     */
    public void handleCombatAchievements(List<PendingCaTask> batch) {
        boolean announce = embeds.notifyEnabled("combatAchievements");

        int total = client.getVarbitValue(VarbitID.CA_POINTS);
        // Points before this batch — only used to detect a tier-threshold crossing. With no baseline
        // yet, fall back to the current total so we never post a phantom tier clear.
        int before = caPointsInitialized ? lastCaPoints : total;

        // Tier clear: did the cumulative total cross any tier threshold across this batch?
        CombatAchievementTier cleared = null;
        for (CombatAchievementTier t : CombatAchievementTier.values()) {
            int threshold = client.getVarbitValue(t.getThresholdVarbitId());
            if (threshold > 0 && before < threshold && threshold <= total) {
                cleared = t; // values() ascend, so the last match is the highest tier crossed
            }
        }
        if (cleared != null && announce) {
            postCaTierClear(cleared);
        }

        // Individual tasks: post each FIRST-seen task at/above the configured floor, but only when
        // this tick's completions actually raised the CA point total. Already-owned tasks re-fire the
        // same chat line via the in-game "Repeat completion" setting — which we rely on so CA *tiles*
        // can count tasks cleared before the event — without changing points, so gating on the delta
        // keeps those recompletions out of the achievements channel. (A mixed tick containing both a
        // genuine new task and a recompletion still posts the recompletion; the aggregate varbit can't
        // attribute a per-task delta. That's rare — real spam is pure-recompletion ticks.) Dedup by
        // task NAME so every genuinely new task in a multi-task tick still posts exactly once.
        boolean pointsRose = total > before;
        for (PendingCaTask pending : batch) {
            String key = pending.task == null ? "" : pending.task.toLowerCase();
            if (key.isEmpty() || !notifiedCaTasks.add(key)) {
                continue; // unparseable, or already announced this session
            }
            if (!pointsRose) {
                continue; // a recompletion: it changed nothing, so it is not news
            }
            // The feed and the counter take every genuinely new task and let the SITE decide which
            // are worth showing — a tier floor belongs where the clan can change it without a
            // plugin release. The chat announcement keeps its own local floor.
            counters.noteCombatTaskMoment(pending.tier, pending.task);
            if (announce && pending.tier.ordinal() >= config.caMinTaskTier().ordinal()) {
                postCombatTask(pending.tier, pending.task, total);
            }
        }

        lastCaPoints = total;
    }

    /**
     * Wiki link for a specific combat task.
     *
     * The wiki has no page per task — they live as rows in the per-tier task tables — so this lands
     * on the tier's list with the task name as a fragment. Where the wiki has an anchor for it the
     * browser jumps straight to the row; where it doesn't, the reader still arrives at the list
     * containing it, which is strictly better than the Combat Achievements hub page.
     */
    private static String caTaskWikiUrl(CombatAchievementTier tier, String task) {
        String tierPath = tier.getDisplayName().replace(' ', '_');
        String base = AnvilEmbeds.caWikiUrl() + "/" + tierPath;
        if (task == null || task.isEmpty()) {
            return base;
        }
        return base + "#" + task.trim().replace(' ', '_');
    }

    /**
     * Posts one completed combat task. Carries the numbers a CA grinder actually cares about: what
     * the task was worth, where their running total sits, and how far the next tier unlock is —
     * all read from the same varbits the tier-clear check uses, so no extra bookkeeping.
     *
     * Client thread (varbit reads happen in the caller); the screenshot + send are deferred.
     */
    private void postCombatTask(CombatAchievementTier tier, String task, int totalPoints) {
        String rsn = localPlayerName.get();
        String shotName = "anvil-ca.png";
        JsonObject embed = new JsonObject();
        AnvilEmbeds.addAuthor(embed, rsn);
        // Title names the TASK, not just its tier — "Into the Den of Giants" is the news; "Easy
        // combat task" is the category. The link follows it to the tier's task list rather than the
        // Combat Achievements hub, which told a reader nothing they didn't already know.
        embed.addProperty("title", "⚔️ " + task);
        embed.addProperty("description",
                AnvilEmbeds.who(rsn) + " completed a " + tier.getDisplayName().toLowerCase()
                        + " combat task.");
        embed.addProperty("color", AnvilEmbeds.achievementColor());
        embed.addProperty("url", caTaskWikiUrl(tier, task));

        JsonArray fields = new JsonArray();
        fields.add(AnvilEmbeds.statField("Points earned", "+" + tier.getPoints()));
        if (totalPoints > 0) {
            fields.add(AnvilEmbeds.statField("Total points", String.format("%,d", totalPoints)));
            String progress = nextTierProgress(totalPoints);
            if (progress != null) {
                fields.add(AnvilEmbeds.statField("Next unlock", progress));
            }
        }
        embed.add("fields", fields);

        AnvilEmbeds.addThumbnail(embed, AnvilEmbeds.caIconUrl());

        if (config.caScreenshot()) {
            AnvilEmbeds.addAttachment(embed, shotName);
            embeds.captureFrameAsync(png -> media.postNotification("combatAchievements", null, embed, png, shotName));
        } else {
            media.postNotification("combatAchievements", null, embed, null, null);
        }
    }

    /**
     * "216/726 (29.8%)" — progress toward the next tier's reward unlock, or null once every tier is
     * unlocked. Thresholds are cumulative point totals held in per-tier varbits; the next unlock is
     * simply the lowest threshold still above the current total. Client thread (varbit reads).
     */
    private String nextTierProgress(int totalPoints) {
        int next = 0;
        for (CombatAchievementTier t : CombatAchievementTier.values()) {
            int threshold = client.getVarbitValue(t.getThresholdVarbitId());
            if (threshold > totalPoints && (next == 0 || threshold < next)) {
                next = threshold;
            }
        }
        if (next <= 0) {
            return null; // everything already unlocked — no bar left to fill
        }
        double pct = (100.0 * totalPoints) / next;
        return String.format("%,d/%,d (%.1f%%)", totalPoints, next, pct);
    }

    private void postCaTierClear(CombatAchievementTier tier) {
        String rsn = localPlayerName.get();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "🏆 Combat Achievement tier!");
        embed.addProperty("description",
                AnvilEmbeds.who(rsn) + " unlocked the **" + tier.getDisplayName()
                + "** Combat Achievements tier!");
        embed.addProperty("color", AnvilEmbeds.achievementColor());
        // Combat-achievement posts are message-only — no screenshot.
        media.postNotification("combatAchievements", null, embed, null, null);
    }

    /**
     * A skill hit level 99. Posts to the clan achievements channel (shared with
     * combat achievements), gated on that channel having a webhook configured
     * server-side.
     */
    /**
     * Posts an achievement-diary tier completion to the clan achievements
     * channel — same hook as combat achievements and 99s. Message-only.
     */
    public void maybeNotifyDiaryCompletion(String area, String tier) {
        if (!config.notifyDiaries() || !embeds.notifyEnabled("diaries")) {
            return;
        }
        String rsn = localPlayerName.get();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "📜 Diary completed!");
        embed.addProperty("description",
                AnvilEmbeds.who(rsn) + " just completed the **" + area + " " + tier
                        + "** achievement diary!");
        embed.addProperty("color", AnvilEmbeds.achievementColor());
        media.postNotification("diaries", null, embed, null, null);
    }

    /**
     * Reads the quest-completed scroll and posts the completion, gated by the
     * configured difficulty threshold (default Master &amp; up). Runs a tick
     * after the widget loads so the text child is populated; retries a couple
     * of ticks if the text lands late.
     */
    public void scheduleQuestScrollRead(int attemptsLeft) {
        clientThread.invokeLater(() -> {
            net.runelite.api.widgets.Widget text = client.getWidget(QUEST_COMPLETED_GROUP_ID, QUEST_COMPLETED_TEXT_CHILD);
            String raw = text != null ? text.getText() : null;
            if (raw == null || raw.isEmpty()) {
                if (attemptsLeft > 0) {
                    scheduleQuestScrollRead(attemptsLeft - 1);
                }
                return;
            }
            // A widget, so the angle-bracket form is what appears here — but it costs nothing to
            // take the @ codes too, and the quest scroll is styled by the same game.
            String plain = ChatText.CHAT_TAG.matcher(raw).replaceAll(" ").replaceAll("\\s+", " ").trim();
            String quest = parseQuestScroll(plain);
            if (quest == null || quest.contains("partial completion")) {
                return; // unparseable, or Hazeel Cult's "kind of completed" — not a completion
            }
            if (!announcedQuests.add(quest.toLowerCase())) {
                return; // widget re-loaded for a quest already posted this session
            }
            postQuestCompletion(quest);
        });
    }

    /**
     * Parses the quest-completed scroll text into the quest name. Ported from
     * RuneLite's ScreenshotPlugin (BSD-2) so all the scroll's text variants
     * resolve correctly — RFD subquests become "Recipe for Disaster - X",
     * "completely completed Rag and Bone Man" becomes "Rag and Bone Man II",
     * and names genuinely containing "Quest" (Legends' Quest, Doric's Quest)
     * keep the word. Returns null when nothing matches. Package-private for
     * the unit test.
     */
    static String parseQuestScroll(String text) {
        Matcher m1 = QUEST_PATTERN_1.matcher(text);
        Matcher m2 = QUEST_PATTERN_2.matcher(text);
        Matcher m = m1.matches() ? m1 : m2;
        if (!m.matches()) {
            return null;
        }
        String quest = m.group("quest");
        String verb = m.group("verb") != null ? m.group("verb") : "";
        if (verb.contains("kind of")) {
            quest += " partial completion";
        } else if (verb.contains("completely")) {
            quest += " II";
        }
        final String questAndVerb = quest + verb;
        if (GamePools.RFD_TAGS.stream().anyMatch(questAndVerb::contains)) {
            quest = "Recipe for Disaster - " + quest;
        }
        final String questName = quest;
        if (GamePools.WORD_QUEST_IN_NAME_TAGS.stream().anyMatch(questName::contains)) {
            quest += " Quest";
        }
        return quest;
    }

    /**
     * Posts a quest completion to the clan achievements channel. Tier comes
     * from the baked name sets; a quest in neither set counts as below Master,
     * so only the "All quests" setting posts it. Message-only, like CA posts.
     */
    private void postQuestCompletion(String questName) {
        QuestAnnounceTier setting = config.questAnnounce();
        if (setting == QuestAnnounceTier.OFF || !embeds.notifyEnabled("quests")) {
            return;
        }
        String key = questName.toLowerCase();
        boolean gm = GamePools.GRANDMASTER_QUESTS.contains(key);
        boolean master = GamePools.MASTER_QUESTS.contains(key);
        if (setting == QuestAnnounceTier.GRANDMASTER && !gm) {
            return;
        }
        if (setting == QuestAnnounceTier.MASTER && !gm && !master) {
            return;
        }
        String rsn = localPlayerName.get();
        String tierTag = gm ? " (Grandmaster)" : master ? " (Master)" : "";
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "🗺️ Quest complete!");
        embed.addProperty("description",
                AnvilEmbeds.who(rsn) + " just completed **" + questName + "**" + tierTag + "!");
        embed.addProperty("color", AnvilEmbeds.achievementColor());
        media.postNotification("quests", null, embed, null, null);
    }

    /**
     * True when the current world's stats aren't the player's real main-game progression, so level-up
     * and total-level milestones must be suppressed. PvP Arena hands out a preset max-stat account;
     * Leagues (SEASONAL) / Deadman / Tournament / Beta / Fresh Start / Quest Speedrunning / LMS / no-save
     * worlds are separate saves or preset loadouts. Hopping onto one otherwise spams "level 99!" for
     * stats the player never trained.
     */
    public boolean statsAreArtificial() {
        Set<WorldType> w = client.getWorldType();
        return w != null && (
               w.contains(WorldType.PVP_ARENA)
            || w.contains(WorldType.SEASONAL)
            || w.contains(WorldType.DEADMAN)
            || w.contains(WorldType.TOURNAMENT_WORLD)
            || w.contains(WorldType.BETA_WORLD)
            || w.contains(WorldType.FRESH_START_WORLD)
            || w.contains(WorldType.QUEST_SPEEDRUNNING)
            || w.contains(WorldType.LAST_MAN_STANDING)
            || w.contains(WorldType.NOSAVE_MODE));
    }

    public void handleLevelMilestone(String skill) {
        // A 99 happens once per skill per account, so when one does not reach Discord there has to be
        // something in the log saying which step dropped it. Every gate below this used to be silent,
        // and postNotification's own failure paths are log.debug — invisible at RuneLite's default
        // level — which left "it just didn't post" as the entire diagnosis.
        if (!embeds.notifyEnabled("levels")) {
            log.info("Anvil: 99 {} not announced — this clan has no channel for level posts.", skill);
            return;
        }
        if (statsAreArtificial()) {
            log.info("Anvil: 99 {} not announced — levels on this world aren't real progression.", skill);
            return;
        }
        // Post a given skill's 99 once per session — the same 99 can arrive from StatChanged and the
        // level-up chat line, and StatChanged pre-seeds skills already 99 at login.
        if (skill == null || !notified99.add(skill.toLowerCase())) {
            return; // already announced this session, or already 99 when the session started
        }
        log.info("Anvil: announcing 99 {}.", skill);
        String rsn = localPlayerName.get();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "🎉 Level 99!");
        embed.addProperty("description",
                AnvilEmbeds.who(rsn) + " just reached **level 99 " + skill + "**!");
        embed.addProperty("color", AnvilEmbeds.achievementColor());
        // How far along they are. A 99 post is about skills rather than total, so it counts those —
        // and a build not chasing max still gets it, because "12 of 23 skills at 99" is a fact about
        // what they have done rather than a distance from somebody else's goal.
        embed.add("fields", AnvilEmbeds.oneField("Progress", ninetyNineProgressLine()));
        embeds.postAchievement(embed, config.levelScreenshot());
    }

    /**
     * Called on every skill level-up. Announces a high-total milestone (every
     * {@code STEP} at or above {@code FLOOR}) or maxing, posting to the clan
     * achievements channel. Uses the baselined total so we only fire on genuine
     * crossings, and skips the round-100 post when this gain maxed.
     */
    public void handleTotalMilestone() {
        // NOT gated on the Discord channel here. The gate moved down to the post itself, because this
        // method also advances lastTotalLevel and feeds the clan's highlight feed — returning early
        // for want of a webhook froze the baseline and lost the milestone from both.
        if (statsAreArtificial()) {
            return;
        }
        int total = client.getTotalLevel();
        if (!totalLevelInitialized) {
            // Login baseline missed (e.g. levelled before the first tick settled) — seed and skip.
            lastTotalLevel = total;
            totalLevelInitialized = true;
            return;
        }
        if (total <= lastTotalLevel) {
            return;
        }
        int prev = lastTotalLevel;
        lastTotalLevel = total;

        int max = maxTotalLevel();
        if (prev < max && max <= total) {
            moments.recordLevelMoment(null, total, "max");
            postTotalMilestone(total, true);
            return; // maxing is the headline — don't also post the round-100 it passed
        }
        // Highest round-STEP value this gain reached, at/above the floor.
        int milestone = (total / TOTAL_MILESTONE_STEP) * TOTAL_MILESTONE_STEP;
        if (milestone >= TOTAL_MILESTONE_FLOOR && prev < milestone) {
            moments.recordLevelMoment(null, milestone, "total");
            postTotalMilestone(milestone, false);
        }
    }

    /**
     * Maximum possible total level, summed from the live Skill enum (adapts as
     * skills are added).
     */
    private int maxTotalLevel() {
        return Skill.values().length * 99;
    }

    /**
     * Is this account plainly not chasing 2277?
     *
     * THERE IS NO "PURE" ACCOUNT TYPE. RuneLite's AccountType knows Normal and the Ironman variants
     * and nothing else, because a pure is a BUILD rather than a flag — the game does not record the
     * intention, only the stats it produced. So this asks the one question the stats answer without
     * ambiguity: Defence 1. Nobody arrives at 99 Strength with Defence 1 by accident, and a level-3
     * skiller is the same answer for the same reason.
     *
     * It is used only to SUPPRESS a line, never to claim anything. Telling somebody who has chosen a
     * build that they are "435 levels from max" measures them against a goal they rejected, and the
     * cost of guessing wrong is a missing line rather than a wrong one.
     */
    private boolean buildIsNotChasingMax() {
        return client.getRealSkillLevel(Skill.DEFENCE) == 1;
    }

    /** "1,842 / 2,277 — 435 to go", or null when the number would not mean anything. */
    private String maxProgressLine(int total) {
        if (buildIsNotChasingMax()) {
            return null;
        }
        int max = maxTotalLevel();
        int left = max - total;
        if (left <= 0) {
            return null; // already there; the Maxed! post is the whole message
        }
        return String.format(Locale.ROOT, "%,d / %,d — %,d to go", total, max, left);
    }

    /** "12 of 23 skills at 99", the progress a 99 post is actually about. */
    private String ninetyNineProgressLine() {
        int at99 = 0;
        for (Skill sk : Skill.values()) {
            if (client.getRealSkillLevel(sk) >= 99) {
                at99++;
            }
        }
        return at99 + " of " + Skill.values().length + " skills at 99";
    }

    private void postTotalMilestone(int total, boolean maxed) {
        if (!embeds.notifyEnabled("levels")) {
            log.info("Anvil: total-level milestone {} not announced — no channel for level posts.", total);
            return;
        }
        String rsn = localPlayerName.get();
        String name = AnvilEmbeds.who(rsn);
        JsonObject embed = new JsonObject();
        embed.addProperty("title", maxed ? "🏆 Maxed!" : "📈 Total level milestone!");
        embed.addProperty("description", maxed
                ? name + " just **maxed** with a total level of **" + total + "**!"
                : name + " just reached **" + total + " total level**!");
        embed.addProperty("color", AnvilEmbeds.achievementColor());
        // Where this leaves them — from the LIVE total, not the milestone above.
        //
        // `total` is the rounded figure the post announces (1800), which is right in the sentence and
        // wrong in the progress: somebody who crossed 1800 on a level that took them to 1803 is 474
        // from max, not 477. The headline is the milestone; the progress is where they actually are.
        //
        // Omitted on a max — "0 to go" under "Maxed!" is noise — and on a build not chasing one.
        String progress = maxed ? null : maxProgressLine(client.getTotalLevel());
        if (progress != null) {
            embed.add("fields", AnvilEmbeds.oneField("Progress to max", progress));
        }
        embeds.postAchievement(embed, config.levelScreenshot());
    }
}
