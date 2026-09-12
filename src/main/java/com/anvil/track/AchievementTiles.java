package com.anvil.track;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.RollTable;
import com.anvil.api.dto.TrackedCombatTask;
import com.anvil.api.dto.TrackedDiary;
import com.anvil.detect.CombatAchievementTier;
import com.anvil.detect.VestigeRolls;
import com.anvil.notify.PendingCaTask;
import com.anvil.util.AnvilChat;
import com.anvil.util.TaskRunner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

/**
 * Tiles credited by things the account achieved rather than things it looted: diary tiers, combat
 * tasks, and the DT2 vestige rolls that name a drop no loot event will.
 *
 * <p>Diary selectors are "&lt;Area&gt; &lt;Tier&gt;" with "Any" as a wildcard on either side, and
 * area matching is contains-based so "Lumbridge Any" matches the "Lumbridge &amp; Draynor" area.</p>
 */
@Slf4j
@Singleton
public class AchievementTiles
{
    protected final Client client;
    protected final ClientThread clientThread;
    protected final AnvilConfig config;
    protected final BingoApiClient apiClient;
    protected final ConfigManager configManager;
    protected final ItemManager itemManager;
    protected final AnvilChat chat;
    protected final TaskRunner tasks;
    private final com.anvil.track.TrackingGate gate;
    private final com.anvil.track.ProofPipeline proofs;
    private final com.anvil.notify.AnvilEmbeds embeds;
    private final com.anvil.notify.AchievementNotifier achievements;
    private final com.anvil.notify.RareDropNotifier rareDrops;

    /** The live event config. A supplier, because the object is replaced on every poll. */
    protected Supplier<PluginConfigResponse> pluginConfig = () -> null;

    @Inject
    AchievementTiles(Client client, ClientThread clientThread, AnvilConfig config, BingoApiClient apiClient,
            ConfigManager configManager, ItemManager itemManager, AnvilChat chat, TaskRunner tasks,
            com.anvil.track.TrackingGate gate, com.anvil.track.ProofPipeline proofs, com.anvil.notify.AnvilEmbeds embeds, com.anvil.notify.AchievementNotifier achievements,
            com.anvil.notify.RareDropNotifier rareDrops) {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.apiClient = apiClient;
        this.configManager = configManager;
        this.itemManager = itemManager;
        this.chat = chat;
        this.tasks = tasks;
        this.gate = gate;
        this.proofs = proofs;
        this.embeds = embeds;
        this.achievements = achievements;
        this.rareDrops = rareDrops;
    }


    /**
     * Hand over the combat achievements parked since the last tick, and forget them.
     *
     * <p>They wait a tick because the completion LINE and the points TOTAL arrive separately, and it
     * is the total moving that tells a first completion from a recompletion.</p>
     */
    public List<PendingCaTask> drainPendingCaTasks() {
        if (pendingCaTasks.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<PendingCaTask> batch = new java.util.ArrayList<>(pendingCaTasks);
        pendingCaTasks.clear();
        return batch;
    }

    /** Park a completion for the next tick. */
    public void parkCombatTask(PendingCaTask task) {
        pendingCaTasks.add(task);
    }

    /** The next account may legitimately re-credit the same task — a teammate's alt, say. */
    public void onLogout() {
        creditedCaTaskTiles.clear();
    }

    public void bind(Supplier<PluginConfigResponse> pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    // Parsed CA completions waiting one tick so the points varbit has settled before we read them.
    // A queue (not a single slot): one kill can complete several CA tasks in the same tick — the
    // game prints a message per task and we must post every one, not just the last.
    private final List<PendingCaTask> pendingCaTasks = new ArrayList<>();

    // CA tile-credit dedup — "<tileId>|<task name>" pairs already credited this session, so
    // repeating the SAME task (repeat-completion fires on every re-meet) can't farm a
    // multi-count wildcard tile ("any 5 Master tasks" needs 5 distinct tasks, not one task
    // five times). Cleared on the login screen so account swaps start fresh.
    private final Set<String> creditedCaTaskTiles = new LinkedHashSet<>();

    // Vestige-rotation counts, per RSN ("boss=rolls:exact;…"). Per-account because the cycle is
    // account state; a shared config key would smear an alt's rolls into the main's.
    private static final String CFG_VESTIGE_ROLLS = "vestigeRolls";

    // Where this account sits in each DT2 boss's vestige rotation (VestigeRolls owns the rule).
    // Loaded lazily per RSN and written back after every counted roll.
    private VestigeRolls vestigeRolls;

    private String vestigeRollsRsn;

    /**
     * Fold a kill's loot into the vestige rotation of whichever boss dropped it, and say where the
     * player now stands. Independent of bingo: the cycle is account state, so it keeps counting with
     * no event running, and it never gates on tracking being enabled.
     */
    public void trackVestigeRolls(String source, Collection<ItemStack> items) {
        if (pluginConfig.get() == null || pluginConfig.get().rollTables == null || items == null || items.isEmpty()) {
            return;
        }
        RollTable table = null;
        for (RollTable t : pluginConfig.get().rollTables) {
            if (t != null && t.boss != null && t.boss.equalsIgnoreCase(source)) {
                table = t;
                break;
            }
        }
        if (table == null) {
            return;
        }
        String rsn = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
        String rsnKey = rsn == null ? "" : rsn.trim().toLowerCase(Locale.ROOT);
        if (vestigeRolls == null || !rsnKey.equals(vestigeRollsRsn)) {
            vestigeRolls = VestigeRolls.parse(configManager.getConfiguration("osrsbingo", CFG_VESTIGE_ROLLS + ":" + rsnKey));
            vestigeRollsRsn = rsnKey;
        }
        for (ItemStack item : items) {
            // Each unique in the loot is its own roll — a kill that somehow hands you two advances
            // the cycle twice, which is what the table did.
            VestigeRolls.Result r = vestigeRolls.record(table, item.getId());
            if (r == null) {
                continue;
            }
            configManager.setConfiguration("osrsbingo", CFG_VESTIGE_ROLLS + ":" + rsnKey, vestigeRolls.serialise());
            chat.send(table.boss + ": " + r.line);
            // Remembered for the drop post, which is built moments later off the same loot event.
            rareDrops.noteVestigeLine(r.line);
        }
    }

    /**
     * Credits diary bingo tiles whose selector list matches this completion.
     * Selectors are "&lt;Area&gt; &lt;Tier&gt;" with "Any" as a wildcard on
     * either side ("Any Elite", "Wilderness Any"); area matching is
     * contains-based so "Lumbridge Any" matches the "Lumbridge &amp; Draynor"
     * area. One completion == amount 1 through the shared proof pipeline
     * (banner + screenshot + retry store).
     */
    public void creditDiaryTiles(String area, String tier) {
        String why = gate.reason();
        if (why != null || pluginConfig.get().trackedDiaries == null) {
            if (why != null) {
                gate.logSuppressed(why);
            }
            return;
        }
        String areaLower = area.toLowerCase();
        String tierLower = tier.toLowerCase();
        for (TrackedDiary d : pluginConfig.get().trackedDiaries) {
            if (d == null || d.diaries == null || d.currentAmount >= d.requiredAmount) {
                continue;
            }
            boolean matches = false;
            for (String sel : d.diaries) {
                if (sel == null) {
                    continue;
                }
                String s = sel.trim();
                int cut = s.lastIndexOf(' ');
                if (cut <= 0) {
                    continue;
                }
                String selArea = s.substring(0, cut).trim().toLowerCase();
                String selTier = s.substring(cut + 1).trim().toLowerCase();
                boolean areaOk = selArea.equals("any") || areaLower.equals(selArea) || areaLower.contains(selArea);
                boolean tierOk = selTier.equals("any") || tierLower.equals(selTier);
                if (areaOk && tierOk) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            d.currentAmount += 1;
            final TrackedDiary fd = d;
            log.info("Tracked diary completion: {} {} → tile '{}' ({}/{})",
                    area, tier, d.label, d.currentAmount, d.requiredAmount);
            proofs.captureAndSubmitProof(d.tileId, d.label, 1, null,
                    "DIARY COMPLETE", area + " " + tier + " Diary",
                    "[Auto] " + area + " " + tier + " diary completed — detected by RuneLite plugin",
                    () -> fd.currentAmount = Math.max(0, fd.currentAmount - 1));
        }
    }

    /**
     * Credits Combat Achievement bingo tiles whose selector list matches this completion.
     * Selectors are exact task names ("Whack-a-Mole") or "Any &lt;Tier&gt;" wildcards
     * ("Any Master"), matched case-insensitively. One completion == amount 1 through the
     * shared proof pipeline (banner + screenshot + retry store). A given task credits a
     * given tile at most once per session (creditedCaTaskTiles), so re-meeting the same
     * task's conditions repeatedly can't farm a multi-count wildcard tile.
     */
    public void creditCombatTaskTiles(CombatAchievementTier tier, String task) {
        String why = gate.reason();
        if (why != null || pluginConfig.get().trackedCombatTasks == null) {
            if (why != null) {
                gate.logSuppressed(why);
            }
            return;
        }
        String taskLower = task.toLowerCase();
        String anyTier = "any " + tier.getDisplayName().toLowerCase();
        for (TrackedCombatTask t : pluginConfig.get().trackedCombatTasks) {
            if (t == null || t.tasks == null || t.currentAmount >= t.requiredAmount) {
                continue;
            }
            boolean matches = false;
            for (String sel : t.tasks) {
                if (sel == null) {
                    continue;
                }
                String s = sel.trim().toLowerCase();
                if (s.equals(taskLower) || s.equals(anyTier)) {
                    matches = true;
                    break;
                }
            }
            final String dedupKey = t.tileId + "|" + taskLower;
            if (!matches) {
                continue;
            }
            if (!creditedCaTaskTiles.add(dedupKey)) {
                log.debug("Combat task '{}' already credited tile '{}' this session — skipping repeat", task, t.label);
                continue;
            }
            t.currentAmount += 1;
            final TrackedCombatTask ft = t;
            log.info("Tracked combat task: {} '{}' → tile '{}' ({}/{})",
                    tier.getDisplayName(), task, t.label, t.currentAmount, t.requiredAmount);
            proofs.captureAndSubmitProof(t.tileId, t.label, 1, null,
                    "COMBAT TASK", tier.getDisplayName() + ": " + task,
                    "[Auto] " + tier.getDisplayName() + " combat task \"" + task + "\" completed — detected by RuneLite plugin",
                    () -> {
                        ft.currentAmount = Math.max(0, ft.currentAmount - 1);
                        // Un-remember the pair so a failed capture can credit on a later re-fire.
                        creditedCaTaskTiles.remove(dedupKey);
                    });
        }
    }
}
