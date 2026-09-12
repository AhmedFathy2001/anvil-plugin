package com.anvil.notify;

import com.google.gson.JsonArray;
import java.util.Set;
import java.util.LinkedHashSet;
import net.runelite.api.gameval.VarbitID;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.MediaUploads;
import com.anvil.api.PluginConfigResponse;
import com.anvil.session.LocalPlayer;
import com.anvil.util.AnvilChat;
import com.anvil.util.TaskRunner;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import com.anvil.detect.CombatAchievementTier;
import com.anvil.notify.PendingCaTask;

/**
 * Combat achievements: one completion, and the tier it may have finished.
 *
 * <h2>The part that is not obvious</h2>
 *
 * <p>The completion LINE and the points TOTAL arrive separately, and it is the total MOVING that
 * tells a first completion from a recompletion. A task announced without that check would post
 * every time somebody re-ran a boss they had already ticked off — which, for a clan feed, is the
 * difference between a highlight and a spam channel.</p>
 *
 * <p>So completions are parked for a tick, drained together, and only then compared against the
 * baseline the tick handler seeded at login.</p>
 */
@Slf4j
@Singleton
public class CombatTaskNotifier
{
    /** CA points as of the last completion we saw — the thing that tells a first from a repeat. */
    private int lastCaPoints = -1;

    private boolean caPointsInitialized;

    // Task names already announced this session. Dedups recompletions (the in-game "repeat
    // completion" message) by NAME — which also lets multiple completions in one tick all post,
    // unlike the old points-delta guard that saw a single rise per tick and dropped the rest.
    private final Set<String> notifiedCaTasks = new LinkedHashSet<>();

    /**
     * Baseline the points ONCE after login, before any completion.
     *
     * <p>Zero means the varbit has not populated yet, so it is not a baseline — it is "ask again
     * next tick". Baselining on zero would make the first completion of the session look like the
     * player had gone from nothing to everything.</p>
     */
    public void seedPointsBaseline(java.util.function.IntSupplier caPoints) {
        if (!caPointsInitialized) {
            int p = caPoints.getAsInt();
            if (p > 0) {
                lastCaPoints = p;
                caPointsInitialized = true;
            }
        }
    }

    /** The next account has its own tasks, and its own points. */
    public void onLogout() {
        notifiedCaTasks.clear();
        lastCaPoints = -1;
        caPointsInitialized = false;
    }

    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final TaskRunner tasks;
    private final AnvilChat chat;
    private final Supplier<PluginConfigResponse> pluginConfig;
    private final LocalPlayer localPlayer;
    private final AnvilEmbeds embeds;
    private final MediaUploads media;
    private final MomentsService moments;
    private final net.runelite.api.Client client;
    private final AchievementNotifier achievements;
    private final com.anvil.track.RecapCounters counters;

    @Inject
    CombatTaskNotifier(AnvilConfig config, BingoApiClient apiClient, TaskRunner tasks, AnvilChat chat,
            Supplier<PluginConfigResponse> pluginConfig, LocalPlayer localPlayer,
            AnvilEmbeds embeds, MediaUploads media, MomentsService moments,
            net.runelite.api.Client client, AchievementNotifier achievements,
            com.anvil.track.RecapCounters counters) {
        this.config = config;
        this.apiClient = apiClient;
        this.tasks = tasks;
        this.chat = chat;
        this.pluginConfig = pluginConfig;
        this.localPlayer = localPlayer;
        this.embeds = embeds;
        this.media = media;
        this.moments = moments;
        this.client = client;
        this.achievements = achievements;
        this.counters = counters;
    }

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
        String rsn = localPlayer.name();
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
        String rsn = localPlayer.name();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "🏆 Combat Achievement tier!");
        embed.addProperty("description",
                AnvilEmbeds.who(rsn) + " unlocked the **" + tier.getDisplayName()
                + "** Combat Achievements tier!");
        embed.addProperty("color", AnvilEmbeds.achievementColor());
        // Combat-achievement posts are message-only — no screenshot.
        media.postNotification("combatAchievements", null, embed, null, null);
    }
}
