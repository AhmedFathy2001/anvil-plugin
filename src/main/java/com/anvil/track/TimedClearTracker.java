package com.anvil.track;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.CoopFingerprint;
import com.anvil.api.dto.CompletedTile;
import com.anvil.api.dto.ItemRequirement;
import com.anvil.api.dto.TrackedDeathless;
import com.anvil.api.dto.TrackedDrop;
import com.anvil.api.dto.TrackedGain;
import com.anvil.api.dto.TrackedKill;
import com.anvil.api.dto.TrackedLms;
import com.anvil.api.dto.TrackedPvp;
import com.anvil.api.dto.TrackedTimed;
import com.anvil.api.dto.TrackedValue;
import com.anvil.clog.ClogTaskModel;
import com.anvil.detect.DropSource;
import com.anvil.detect.GamePools;
import com.anvil.detect.StartProofRules;
import com.anvil.detect.TimedClearParser;
import com.anvil.io.PendingSubmissionStore;
import com.anvil.notify.AnvilEmbeds;
import com.anvil.notify.LootSourceMemory;
import com.anvil.notify.MomentsService;
import com.anvil.notify.RareDropNotifier;
import com.anvil.ui.AnvilOverlay;
import com.anvil.ui.ProofBanner;
import com.anvil.util.AnvilChat;
import com.anvil.util.DedupWindow;
import com.anvil.util.Gp;
import com.anvil.util.Rsn;
import com.anvil.util.TaskRunner;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.ui.DrawManager;

/**
 * Timed clears and deathless runs, both of which the game reports across two chat lines.
 *
 * <h2>The duration and the name arrive separately, in either order</h2>
 *
 * <p>Inferno prints "Duration:" first; most content prints the completion count first. So recent
 * lines are buffered alongside a pending duration and either order resolves — rather than a
 * per-boss table of which content says what in which order.</p>
 *
 * <h2>And the boss that just died names the activity</h2>
 *
 * <p>Which means no table is needed for that either: a timed tile configured with a boss's name
 * matches automatically when that boss is the last thing that died.</p>
 */
@Slf4j
@Singleton
public class TimedClearTracker implements Tracker
{
    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final Client client;
    private final ClientThread clientThread;
    private final ItemManager itemManager;
    private final DrawManager drawManager;
    private final ConfigManager configManager;
    private final PendingSubmissionStore pendingSubmissionStore;
    private final AnvilChat chat;
    private final TaskRunner tasks;
    private final TrackingGate gate;
    private final LocalProgress progress;
    private final PartyTracker party;
    private final Coalescer coalescer;
    private final com.anvil.notify.AnvilEmbeds embeds;
    private final com.anvil.notify.LootSourceMemory lootSource;
    private final com.anvil.notify.MomentsService moments;
    private final com.anvil.notify.RareDropNotifier rareDrops;
    private final RecapCounters counters;
    private final ProofPipeline proofs;

    /** The live event config. A supplier, because the object is replaced on every poll. */
    private Supplier<PluginConfigResponse> pluginConfig = () -> null;
    /** Pull the board back after a credit, so the tile's new total is what the panel shows. */
    private Runnable refreshConfig = () -> { };
    /** Who is playing. Read at capture time, not at draw time. */
    private Supplier<String> localPlayerName = () -> null;

    @Inject
    TimedClearTracker(AnvilConfig config, BingoApiClient apiClient, Client client, ClientThread clientThread,
            ItemManager itemManager, DrawManager drawManager, ConfigManager configManager,
            PendingSubmissionStore pendingSubmissionStore, AnvilChat chat, TaskRunner tasks,
            TrackingGate gate, LocalProgress progress, PartyTracker party, Coalescer coalescer,
            com.anvil.notify.AnvilEmbeds embeds, com.anvil.notify.LootSourceMemory lootSource,
            com.anvil.notify.MomentsService moments, com.anvil.notify.RareDropNotifier rareDrops,
            RecapCounters counters, ProofPipeline proofs) {
        this.config = config;
        this.apiClient = apiClient;
        this.client = client;
        this.clientThread = clientThread;
        this.itemManager = itemManager;
        this.drawManager = drawManager;
        this.configManager = configManager;
        this.pendingSubmissionStore = pendingSubmissionStore;
        this.chat = chat;
        this.tasks = tasks;
        this.gate = gate;
        this.progress = progress;
        this.party = party;
        this.coalescer = coalescer;
        this.embeds = embeds;
        this.lootSource = lootSource;
        this.moments = moments;
        this.rareDrops = rareDrops;
        this.counters = counters;
        this.proofs = proofs;
    }


    /**
     * The boss that just died names the activity a "Duration:" line belongs to — which is why no
     * per-boss table is needed to match a timed tile.
     */
    public void noteNpcDeath(String npcName) {
        lastNpcDeathName = npcName;
        lastNpcDeathAt = System.currentTimeMillis();
    }

    public void clearNpcDeath() {
        lastNpcDeathName = null;
    }


    /** A logout clears the correlation buffer: the next account's lines are not this one's. */
    public void reset() {
        recentTimedMessages.clear();
        pendingTimedSeconds = null;
        lastNpcDeathName = null;
    }

    @Override
    public void bind(Supplier<PluginConfigResponse> pluginConfig, Runnable refreshConfig,
            Supplier<String> localPlayerName) {
        this.pluginConfig = pluginConfig;
        this.refreshConfig = refreshConfig;
        this.localPlayerName = localPlayerName;
    }

    // ---- Timed-clear tiles ---------------------------------------------------------------
    // Per-tile dedup so one clear isn't submitted twice (the duration + identity lines correlate,
    // and some content repeats either line). Parsing/matching lives in TimedClearParser (tested).
    private static final long TIMED_DEDUP_WINDOW_MS = 20_000;

    private final DedupWindow<Integer> lastTimedSubmittedAt = new DedupWindow<>(TIMED_DEDUP_WINDOW_MS);

    // The duration line and the activity-identifying line are separate, adjacent chat messages,
    // and the order varies (Inferno prints "Duration:" first; most others print the kill/completion
    // count first). We buffer recent lines + a pending duration so either order resolves.
    private static final long TIMED_CORRELATION_MS = 8_000;

    private static class TimedMsg {

        final String lower;
        final long ts;

        TimedMsg(String lower, long ts) {
            this.lower = lower;
            this.ts = ts;
        }
    }

    public final ArrayDeque<TimedMsg> recentTimedMessages = new ArrayDeque<>();

    public Integer pendingTimedSeconds = null;

    private long pendingTimedAt = 0;

    // Table-free attribution: the most recent NPC the player killed. When a "Duration:" line lands,
    // the boss that just died names the activity, so a timed tile configured with that boss's name
    // matches automatically — no per-boss string table needed (raids/friendly names also match via
    // the activity name appearing in chat, plus the small optional alias set in TimedClearParser).
    public volatile String lastNpcDeathName = null;

    public volatile long lastNpcDeathAt = 0;

    /* ----------------------------- Timed-clear tiles ----------------------------- */
    /**
     * Correlates a clear time (from a "Duration:/completion time:" line) with
     * the adjacent line that names the activity (the boss kill/completion-count
     * line). The two are separate chat messages and their order varies, so we
     * keep a short ring buffer of recent lines plus a pending duration and
     * resolve whichever arrives second. Parsing/matching is delegated to the
     * unit-tested {@link TimedClearParser}. Runs on the client thread (from
     * onChatMessage).
     */
    public void handleTimedChat(String plain) {
        if (!config.autoSubmit() || pluginConfig.get() == null) {
            return;
        }
        // Deathless tiles piggyback on the same correlation: a raid's completion is announced
        // by the very duration + identity lines the timed machinery already pairs up.
        boolean hasTimed = pluginConfig.get().trackedTimed != null && !pluginConfig.get().trackedTimed.isEmpty();
        boolean hasDeathless = pluginConfig.get().trackedDeathless != null && !pluginConfig.get().trackedDeathless.isEmpty();
        if (!hasTimed && !hasDeathless) {
            return;
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.get().event)) {
            return;
        }
        final long now = System.currentTimeMillis();
        final String lower = plain.toLowerCase();

        // Maintain the recent-line buffer (prune by age, cap size).
        recentTimedMessages.addLast(new TimedMsg(lower, now));
        while (!recentTimedMessages.isEmpty() && now - recentTimedMessages.peekFirst().ts > TIMED_CORRELATION_MS) {
            recentTimedMessages.removeFirst();
        }
        while (recentTimedMessages.size() > 12) {
            recentTimedMessages.removeFirst();
        }

        Integer seconds = TimedClearParser.parseDurationSeconds(lower);
        if (seconds != null) {
            // Duration line. The identifying line may already be in the buffer (count-first
            // content) or may still be coming (Inferno prints the duration first).
            boolean submitted = false;
            for (TimedMsg m : recentTimedMessages) {
                if (now - m.ts <= TIMED_CORRELATION_MS) {
                    if (submitTimedForMessage(m.lower, seconds, now)) {
                        submitted = true;
                    }
                    if (submitDeathlessForMessage(m.lower, now)) {
                        submitted = true;
                    }
                }
            }
            // Table-free fallback: attribute to the boss we just killed.
            if (lastNpcDeathName != null && (now - lastNpcDeathAt) <= TIMED_CORRELATION_MS
                    && submitTimedForMessage(lastNpcDeathName.toLowerCase(), seconds, now)) {
                submitted = true;
            }
            if (submitted) {
                pendingTimedSeconds = null;
            } else {
                pendingTimedSeconds = seconds;
                pendingTimedAt = now;
            }
        } else if (pendingTimedSeconds != null && (now - pendingTimedAt) <= TIMED_CORRELATION_MS) {
            // No duration here, but a duration is waiting — does THIS line identify the activity?
            boolean submitted = submitTimedForMessage(lower, pendingTimedSeconds, now);
            if (submitDeathlessForMessage(lower, now)) {
                submitted = true;
            }
            if (submitted) {
                pendingTimedSeconds = null;
            }
        }
    }

    /**
     * Credits deathless tiles this line identifies. Reaching here means a raid completion is
     * being announced (a duration line is part of the correlation), so the run counts when no
     * player died in the instance since we entered — and, when the tile pins a party size,
     * when exactly that many distinct players were seen inside.
     */
    private boolean submitDeathlessForMessage(String lowerMessage, long now) {
        if (pluginConfig.get() == null || pluginConfig.get().trackedDeathless == null) {
            return false;
        }
        boolean any = false;
        for (TrackedDeathless tile : pluginConfig.get().trackedDeathless) {
            if (tile == null || tile.completed || tile.activity == null
                    || tile.currentAmount >= Math.max(1, tile.requiredAmount)) {
                continue;
            }
            if (!TimedClearParser.messageMatchesActivity(lowerMessage, tile.activity)) {
                continue;
            }
            // An Entry Mode clear must never credit a base-raid tile ("Theatre of Blood" is a
            // substring of its Entry line). Harder modes crediting a base tile is fine.
            if (lowerMessage.contains("entry mode")
                    && !tile.activity.toLowerCase(Locale.ROOT).contains("entry mode")) {
                continue;
            }
            // Claims attempts too — several nearby identity lines would otherwise repeat the
            // verdict (or double-submit) for the same run.
            if (!lastTimedSubmittedAt.claim(tile.tileId)) {
                continue;
            }
            if (party.deathsThisInstance() > 0) {
                chat.send("Not deathless: " + tile.label + " — " + party.deathsThisInstance()
                        + (party.deathsThisInstance() == 1 ? " death" : " deaths") + " this run.");
                continue;
            }
            int partySeen = party.observedSize();
            if (tile.partySize > 0 && partySeen != tile.partySize) {
                chat.send("Deathless run not counted for " + tile.label + ": party of "
                        + partySeen + ", tile requires " + tile.partySize + ".");
                continue;
            }
            tile.currentAmount++;
            int goal = Math.max(1, tile.requiredAmount);
            log.info("Tracked deathless run: {} party={} → tile '{}' ({}/{})",
                    tile.activity, partySeen, tile.label, tile.currentAmount, goal);
            chat.send("Tracked deathless run: " + tile.label + " (" + tile.currentAmount + "/" + goal + ")");
            String detail = tile.activity + "  deathless"
                    + (tile.partySize > 0 ? "  party " + partySeen : "")
                    + "  (" + tile.currentAmount + "/" + goal + ")";
            final TrackedDeathless credited = tile;
            proofs.captureAndSubmitProof(tile.tileId, tile.label, 1, null, "BINGO DEATHLESS", detail,
                    "[Auto] " + tile.activity + " deathless run detected by RuneLite plugin",
                    () -> credited.currentAmount = Math.max(0, credited.currentAmount - 1));
            any = true;
        }
        return any;
    }

    /**
     * Submits {@code seconds} to every timed tile this line identifies, gated
     * by the tile's cap, completion state, and a per-tile dedup window. Returns
     * true if at least one tile submitted.
     */
    private boolean submitTimedForMessage(String lowerMessage, int seconds, long now) {
        boolean any = false;
        for (TrackedTimed tile : pluginConfig.get().trackedTimed) {
            if (tile.completed || tile.activity == null) {
                continue;
            }
            // Barracuda Trials rank tiles ("Gwenith Glide — Marlin") gate on the EXACT course + rank
            // the game reports, NOT a time cap or party size — each rank is a separate PB, so a Shark
            // run must never credit a Marlin tile. Match those and skip the cap/party/entry-mode gates.
            String[] trialTarget = TimedClearParser.trialTileTarget(tile.activity);
            if (trialTarget != null) {
                String[] got = TimedClearParser.parseTrialCompletion(lowerMessage);
                if (got == null || !got[0].equals(trialTarget[0]) || !got[1].equals(trialTarget[1])) {
                    continue;
                }
            } else {
                if (!TimedClearParser.messageMatchesActivity(lowerMessage, tile.activity)) {
                    continue;
                }
                // An Entry Mode clear must never credit a base-raid tile ("Tombs of Amascut" is a
                // substring of its Entry line) — same guard as the deathless path. Harder modes
                // (CM / Hard / Expert) crediting a base tile is intended.
                if (lowerMessage.contains("entry mode")
                        && !tile.activity.toLowerCase(Locale.ROOT).contains("entry mode")) {
                    continue;
                }
                // Optional exact-party gate (raid tiles) — same signal as the deathless path.
                if (tile.partySize > 0) {
                    int partySeen = party.observedSize();
                    if (partySeen != tile.partySize) {
                        log.info("Timed '{}' clear with party of {} — tile requires {}, not submitting.",
                                tile.label, partySeen, tile.partySize);
                        continue;
                    }
                }
                if (seconds > tile.thresholdSeconds) {
                    log.info("Timed '{}' clear {} over cap {} — not submitting.", tile.label,
                            TimedClearParser.formatClock(seconds), TimedClearParser.formatClock(tile.thresholdSeconds));
                    continue;
                }
            }
            if (!lastTimedSubmittedAt.claim(tile.tileId)) {
                continue;
            }
            log.info("Tracked timed clear: {} in {} (cap {})", tile.label,
                    TimedClearParser.formatClock(seconds), TimedClearParser.formatClock(tile.thresholdSeconds));
            chat.send("Tracked timed clear: " + tile.label + " in " + TimedClearParser.formatClock(seconds));
            String detail = trialTarget != null
                    ? tile.activity + "  " + TimedClearParser.formatClock(seconds)
                    : tile.activity + "  " + TimedClearParser.formatClock(seconds)
                            + "  (cap " + TimedClearParser.formatClock(tile.thresholdSeconds) + ")";
            proofs.captureAndSubmitProof(tile.tileId, tile.label, 1, seconds, "BINGO TIMED", detail,
                    "[Auto] " + tile.activity + " cleared in " + TimedClearParser.formatClock(seconds) + " by RuneLite plugin", null);
            any = true;
        }
        return any;
    }
}
