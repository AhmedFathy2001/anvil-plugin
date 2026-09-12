package com.anvil.track;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.detect.AccountProgress;
import com.anvil.util.AnvilChat;
import com.anvil.util.TaskRunner;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * Quest points, combat achievements and diary counts — the account state the hiscores never publish.
 *
 * <p>Sampled on the client thread, diffed against what was last sent, and pushed only when something
 * moved. For most logins that is nothing at all, so the steady state costs a few varbit reads and no
 * request.</p>
 *
 * <p>The quest LIST rides along only when the number of finished quests changes, because that is the
 * only thing that can change what the list says — hashing two hundred names every half minute to
 * learn the same thing would be work for nothing. Combat tasks follow the same rule against their
 * point total.</p>
 */
@Slf4j
@Singleton
public class AccountProgressPush
{
    protected final Client client;
    protected final ClientThread clientThread;
    protected final AnvilConfig config;
    protected final BingoApiClient apiClient;
    protected final ConfigManager configManager;
    protected final ItemManager itemManager;
    protected final AnvilChat chat;
    protected final TaskRunner tasks;
    private final com.anvil.notify.AchievementNotifier achievements;
    private final com.anvil.notify.MomentsService moments;
    private final com.anvil.track.StatPushService statPush;

    /** The live event config. A supplier, because the object is replaced on every poll. */
    protected Supplier<PluginConfigResponse> pluginConfig = () -> null;

    @Inject
    AccountProgressPush(Client client, ClientThread clientThread, AnvilConfig config, BingoApiClient apiClient,
            ConfigManager configManager, ItemManager itemManager, AnvilChat chat, TaskRunner tasks,
            com.anvil.notify.AchievementNotifier achievements, com.anvil.notify.MomentsService moments, com.anvil.track.StatPushService statPush) {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.apiClient = apiClient;
        this.configManager = configManager;
        this.itemManager = itemManager;
        this.chat = chat;
        this.tasks = tasks;
        this.achievements = achievements;
        this.moments = moments;
        this.statPush = statPush;
    }


    /**
     * A skill's XP moved. Returns true when this is a REAL gain rather than the login/resync burst.
     *
     * <p>The client hands over a StatChanged for every skill on login. Only a rise against what we
     * already had is a gain — the first sighting of a skill just sets the baseline.</p>
     */
    public boolean noteXp(Skill skill, int xp) {
        Integer prev = lastSkillXp.put(skill, xp);
        return prev != null && xp > prev;
    }

    /** A skill's level as we last saw it, or null if this is the first sighting this session. */
    public Integer noteLevel(Skill skill, int level) {
        return lastSkillLevel.put(skill, level);
    }

    /**
     * A skill's level or XP moved.
     *
     * <p>Preset / alt-save worlds (PvP Arena, Leagues, Deadman, LMS, …) report levels and XP that
     * are not the player's real progression — never notify off them, never overwrite the real-level
     * baseline, and never push their XP.</p>
     *
     * <p>The XP push runs regardless of the level-up notifier toggle, so skill-XP tiles move without
     * waiting on the hourly hiscores cron (mirroring the boss-KC push). Hiscores stays the source of
     * truth: the server keeps max(hiscores, pushed) and reconciles. A real gain (XP rose) is told
     * apart from the login/resync baseline burst, because only the former marks the tile "You".</p>
     */
    public void onStatChanged(Skill skill, int level, int xp) {
        if (skill == null || achievements.statsAreArtificial()) {
            return;
        }
        boolean realGain = noteXp(skill, xp);
        statPush.maybeQueueSkillXpPush(skill.getName(), xp, realGain);

        if (!config.notifyLevelUps()) {
            return;
        }
        Integer prev = noteLevel(skill, level);
        if (prev == null) {
            // First sighting this session = baseline; remember pre-existing 99s so they never announce.
            if (level >= 99) {
                achievements.note99(skill.getName().toLowerCase());
            }
            return;
        }
        if (level <= prev) {
            return; // XP within a level, or no gain — nothing to announce
        }
        if (level >= 99 && prev < 99) {
            moments.recordLevelMoment(skill.getName(), 99, "skill");
            achievements.handleLevelMilestone(skill.getName());
        }
        achievements.handleTotalMilestone();
    }

    /** Progress is per ACCOUNT: the next login may be an alt, whose quest points are not this one's. */
    public void onLogout() {
        lastSentProgress.clear();
        lastSentQuestCount = null;
        lastSentCaPoints = null;
        lastSkillLevel.clear();
        lastSkillXp.clear();
    }

    public void bind(Supplier<PluginConfigResponse> pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    /** Account progress (quest points, CAs, diaries) as the site last accepted it — see AccountProgress. */
    private final Map<String, Integer> lastSentProgress = new LinkedHashMap<>();

    /** Finished-quest count behind the list the site last accepted — the list is re-sent when it moves. */
    private volatile Integer lastSentQuestCount;

    /** CA points behind the task list the site last accepted — the list is re-read when it moves. */
    private volatile Integer lastSentCaPoints;

    // Per-skill real level, so a 99 is detected off the stat event — independent of the in-game
    // "Level-up interface" setting, which decides whether the chat line even carries the level number.
    // notified99 dedups a 99 arriving from both StatChanged and the chat line.
    //
    // Seeded from the live stat table (seedSkillLevels), NOT from whichever XP drop happens to arrive
    // first. See that method for what the lazy version cost.
    private final Map<Skill, Integer> lastSkillLevel = new EnumMap<>(Skill.class);

    // Last real-world XP seen per skill, so the "Active now" self-signal fires only on an actual gain —
    // NOT on the burst of StatChanged RuneLite emits for every skill on login/resync (which otherwise
    // mislabels every tracked skill tile as "You"). The first sighting per skill just seeds the baseline.
    private final Map<Skill, Integer> lastSkillXp = new EnumMap<>(Skill.class);

    /**
     * Quest points, combat-achievement points/tier and diary counts → the site (AccountProgress).
     *
     * <p>Sampled on the client thread, diffed against what we last sent, and pushed only when
     * something moved — which for most logins is nothing at all, so the steady state costs one
     * varbit read and no request. The sample itself refuses to report an account that reads as all
     * zeroes, which is what the client looks like for the first few ticks after login.
     *
     * <p>Cleared on logout with the rest of the per-account state: the next login may be an alt, and
     * its progress is not this one's.
     */
    public void pushAccountProgress() {
        if (!apiClient.isConfigured() || client.getGameState() != GameState.LOGGED_IN) {
            return;
        }
        // A site that predates the endpoint would answer 404 on a loop; hide rather than error.
        PluginConfigResponse cfg = pluginConfig.get();
        if (cfg == null || !cfg.serverSupports("progress")) {
            return;
        }
        clientThread.invoke(() -> {
            Map<String, Integer> sampled = AccountProgress.sample(client);
            if (sampled.isEmpty()) {
                return;
            }
            Map<String, Integer> changed = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : sampled.entrySet()) {
                Integer sent = lastSentProgress.get(e.getKey());
                if (sent == null || !sent.equals(e.getValue())) {
                    changed.put(e.getKey(), e.getValue());
                }
            }

            // The quest LIST rides along whenever the number of finished quests moves — that's the
            // only thing that can change what the list says, and hashing 200 names every half minute
            // to learn the same thing would be work for nothing.
            Integer questsNow = sampled.get("questsCompleted");
            boolean questsMoved = questsNow != null && !questsNow.equals(lastSentQuestCount);
            final List<AccountProgress.Item> quests = questsMoved ? AccountProgress.quests(client) : null;

            // Combat tasks, same rule: the points total is the only thing that can change which
            // tasks are done, so the bits are re-read when it moves. We send the raw varps and the
            // total; the site decodes them against its catalogue and discards the lot if they don't
            // reconcile, so a layout change can't produce a wrong list here or there.
            Integer caNow = sampled.get("caPoints");
            boolean caMoved = caNow != null && caNow > 0 && !caNow.equals(lastSentCaPoints);
            final Map<Integer, Integer> caVarps = caMoved && cfg.caVarps != null
                    ? AccountProgress.combatVarps(client, cfg.caVarps)
                    : null;
            // Say which half is quiet when nothing lands: a site that never asked for the varps and a
            // client that read none of them look identical from the profile page.
            if (caMoved && cfg.caVarps == null) {
                log.info("Anvil: combat achievements — this site didn't send a varp list, so none were read");
            } else if (caVarps != null) {
                log.info("Anvil: combat achievements — read {} varps at {} points", caVarps.size(), caNow);
            }
            final int caPointsNow = caNow == null ? 0 : caNow;

            if (changed.isEmpty() && quests == null && (caVarps == null || caVarps.isEmpty())) {
                return;
            }
            if (!tasks.isLive()) {
                return;
            }
            tasks.run(() -> {
                try {
                    // One request each: the endpoint takes a category at a time, and these two move
                    // independently.
                    apiClient.submitProgress(changed, quests != null ? "quest" : null, quests);
                    lastSentProgress.putAll(changed);
                    if (quests != null) {
                        lastSentQuestCount = questsNow;
                    }
                    if (caVarps != null && !caVarps.isEmpty()) {
                        apiClient.submitProgress(Collections.emptyMap(), null, null, caVarps, caPointsNow);
                        lastSentCaPoints = caNow;
                    }
                } catch (IOException e) {
                    log.debug("Account progress push failed, retrying later: {}", e.getMessage());
                }
            });
        });
    }

    /**
     * Skill 99s + total-level milestones, detected off StatChanged so they fire regardless of the
     * in-game "Level-up interface" setting. (We also parse the level-up chat line, but that only
     * carries the level number when the popup is disabled — with it on, the default, nothing matched,
     * so 99s/totals silently never posted.) Each skill is baselined on its first event of the session
     * (login), so pre-existing 99s and the starting total don't announce; handleTotalMilestone's own
     * baseline guards the total the same way.
     */
    /**
     * Record every skill's real level as the session's starting point.
     *
     * <p>WHAT THIS FIXES. The baseline used to be taken lazily: the first StatChanged for a skill was
     * treated as "where they started" and discarded. That is right at login, when a burst of events
     * arrives for skills nobody just trained — and wrong every other time the map is empty, because
     * then the first XP drop is a real one. Start the plugin mid-session and the next level a player
     * gains is read as their starting level and thrown away.</p>
     *
     * <p>Which is exactly what a 99 is: one XP drop, once. Reload the plugin at 98, cook one more
     * fish, and the announcement is gone — silently, and with the skill added to notified99, so the
     * chat line can't rescue it either. A once-per-account moment, lost to a plugin restart.</p>
     *
     * <p>Reading the table up front removes the guess. Skills already at 99 are marked announced (they
     * were 99 before we were watching); anything that rises from here is a real level-up.</p>
     */
    public void seedSkillLevels() {
        clientThread.invokeLater(() -> {
            if (client.getGameState() != GameState.LOGGED_IN) {
                return;
            }
            lastSkillLevel.clear();
            achievements.clear99s();
            for (Skill skill : Skill.values()) {
                int level = client.getRealSkillLevel(skill);
                if (level <= 0) {
                    continue; // not populated yet — the next StatChanged baselines it the old way
                }
                lastSkillLevel.put(skill, level);
                if (level >= 99) {
                    achievements.note99(skill.getName().toLowerCase());
                }
            }
        });
    }
}
