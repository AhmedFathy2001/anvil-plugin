package com.anvil.notify;

import com.anvil.session.LocalPlayer;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.util.AnvilChat;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
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

    /** Combat achievements, which need the baseline this class seeds. A Provider: it asks back. */
    private final javax.inject.Provider<CombatTaskNotifier> combatTasks;

    /** Quests, which dedup against this class's own session state. A Provider: it asks back. */
    private final javax.inject.Provider<QuestNotifier> questsRef;
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
        com.anvil.api.MediaUploads media,
            javax.inject.Provider<CombatTaskNotifier> combatTasks,
            javax.inject.Provider<QuestNotifier> questsRef) {
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
        this.combatTasks = combatTasks;
        this.questsRef = questsRef;
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
            combatTasks.get().handleCombatAchievements(parked);
        }
    }

    public void seedBaselines(java.util.function.IntSupplier caPoints, java.util.function.IntSupplier totalLevel) {
        combatTasks.get().seedPointsBaseline(caPoints);
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
        return QuestNotifier.questScrollGroup();
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
        combatTasks.get().onLogout();
        questsRef.get().onLogout();
        notified99.clear();


        lastTotalLevel = -1;
        totalLevelInitialized = false;
    }









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
