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
 * Tiles that count GP rather than items: "loot 50m from anything", "loot 10m from Vorkath".
 *
 * <p>Priced from the higher of GE value and high alch, per item, at the moment it lands — a board
 * that runs through a price crash should score what the drop was worth when it dropped.</p>
 */
@Slf4j
@Singleton
public class ValueTracker implements Tracker
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
    ValueTracker(AnvilConfig config, BingoApiClient apiClient, Client client, ClientThread clientThread,
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

    /** One kill arrives as two loot events; a haul counted twice completes a tile on half of it. */
    private final com.anvil.util.DedupWindow<String> lastSubmittedAt =
            new com.anvil.util.DedupWindow<>(3_000);

    @Override
    public void bind(Supplier<PluginConfigResponse> pluginConfig, Runnable refreshConfig,
            Supplier<String> localPlayerName) {
        this.pluginConfig = pluginConfig;
        this.refreshConfig = refreshConfig;
        this.localPlayerName = localPlayerName;
    }

    /**
     * Loot-value tiles ("loot worth ≥ X gp"): price the WHOLE haul (GE value of every item) and, when
     * a single haul from a matching source meets the threshold, submit it with a baked screenshot —
     * the value-tile equivalent of the drop pipeline. The server decides completion (single-haul: a
     * submission ≥ threshold). Source filter mirrors the site: "PvP" = a player kill, "Loot Chest" =
     * an opened loot key, otherwise an NPC/chest name; empty = any.
     */
    public void processValueTiles(String source, Collection<ItemStack> items, String sourceKind) {
        String why = gate.reason();
        if (why != null || !config.autoSubmit() || pluginConfig.get() == null
                || pluginConfig.get().trackedValues == null || pluginConfig.get().trackedValues.isEmpty()
                || items == null || items.isEmpty()) {
            return;
        }
        long haulGp = 0;
        for (ItemStack it : items) {
            if (it == null || it.getId() <= 0) {
                continue;
            }
            int price = itemManager.getItemPrice(it.getId());
            if (price > 0) {
                haulGp += (long) price * Math.max(1, it.getQuantity());
            }
        }
        if (haulGp <= 0) {
            return;
        }
        for (TrackedValue v : pluginConfig.get().trackedValues) {
            if (v == null || v.completed) {
                continue;
            }
            boolean total = "total".equalsIgnoreCase(v.mode);
            // Single haul: THIS haul must meet the threshold. Total: every qualifying haul counts
            // toward the target (server sums the submitted amounts), so there's no per-haul threshold.
            if (!total && haulGp < v.thresholdGp) {
                continue;
            }
            if (!valueSourceMatches(v.sources, source, sourceKind)) {
                continue;
            }
            // Dedup: the same loot can fire NpcLootReceived + LootReceived back-to-back.
            if (!lastSubmittedAt.claim("value:" + v.tileId)) {
                continue;
            }
            final int amount = (int) Math.min(haulGp, Integer.MAX_VALUE);
            final String gp = Gp.format(haulGp);
            if (total) {
                // Accumulate toward the target: capture a proof screenshot per qualifying haul (same
                // pipeline as single-haul value tiles) so every contribution to the aggregate is
                // verifiable — and removable — on the site. The server sums the submitted amounts and
                // completes at the target, so we don't optimistically mark the tile done (and need no
                // rollback).
                log.info("Value tile credited (total): '{}' +{} gp", v.label, haulGp);
                proofs.captureAndSubmitProof(v.tileId, v.label, amount, null, "BINGO VALUE", v.label + "  " + gp,
                        "[Auto] loot worth " + gp + " (" + v.label + ") counted by RuneLite plugin", null);
            } else {
                // Single-haul completion: optimistically mark done so a follow-up haul in the same
                // stint doesn't double-submit; capture a proof screenshot (rollback reverts on failure).
                v.completed = true;
                final TrackedValue tile = v;
                log.info("Value tile credited (single): '{}' haul {} gp (threshold {})", v.label, haulGp, v.thresholdGp);
                proofs.captureAndSubmitProof(v.tileId, v.label, amount, null, "BINGO VALUE", v.label + "  " + gp,
                        "[Auto] loot worth " + gp + " (" + v.label + ") detected by RuneLite plugin",
                        () -> tile.completed = false);
            }
        }
    }

    /** Does a value tile's source filter accept this loot? "PvP" matches a player kill; other entries
     *  match the loot source name (case-insensitive). Empty/null = any source. */
    private boolean valueSourceMatches(List<String> sources, String source, String sourceKind) {
        if (sources == null || sources.isEmpty()) {
            return true;
        }
        for (String s : sources) {
            if (s == null) {
                continue;
            }
            if (s.equalsIgnoreCase("PvP")) {
                if ("pvp".equals(sourceKind)) {
                    return true;
                }
            } else if (source != null && s.equalsIgnoreCase(source)) {
                return true;
            }
        }
        return false;
    }
}
