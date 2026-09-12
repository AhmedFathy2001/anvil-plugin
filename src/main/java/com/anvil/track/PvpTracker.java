package com.anvil.track;

import com.anvil.session.LocalPlayer;
import com.anvil.api.BoardRefresh;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.Claim;
import com.anvil.api.dto.CompletedTile;
import com.anvil.api.dto.RosterEntry;
import com.anvil.api.dto.TrackedPvp;
import com.anvil.io.PendingSubmissionStore;
import com.anvil.ui.AnvilOverlay;
import com.anvil.util.AnvilChat;
import com.anvil.util.DedupWindow;
import com.anvil.util.Rsn;
import com.anvil.util.TaskRunner;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.ui.DrawManager;

/**
 * PvP kills, which the client never attributes for you.
 *
 * <p>A player dying near you is not your kill. The only evidence available is that you landed a hit
 * on them recently — so hits are parked, and a death inside the window claims the park. The park is
 * consumed, so one death credits once even when two attackers both damaged the victim.</p>
 *
 * <p>Tiles with a minimum-loot requirement park a second time and wait for the loot event, because
 * the kill and its loot are separate messages and the value is only in the second.</p>
 */
@Slf4j
@Singleton
public class PvpTracker
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
    private final Supplier<PluginConfigResponse> pluginConfig;
    /** Pull the board back after a credit, so the tile's new total is what the panel shows. */
    private final Runnable refreshConfig;
    /** Who is playing. Read at capture time, not at draw time. */
    private final Supplier<String> localPlayerName;

    /** Notifications, proof screenshots and clips — the requests carrying a file. */
    private final com.anvil.api.MediaUploads media;

    @Inject
    PvpTracker(AnvilConfig config, BingoApiClient apiClient, Client client, ClientThread clientThread,
            ItemManager itemManager, DrawManager drawManager, ConfigManager configManager,
            PendingSubmissionStore pendingSubmissionStore, AnvilChat chat, TaskRunner tasks,
            TrackingGate gate, LocalProgress progress, PartyTracker party, Coalescer coalescer,
            com.anvil.notify.AnvilEmbeds embeds, com.anvil.notify.LootSourceMemory lootSource,
            com.anvil.notify.MomentsService moments, com.anvil.notify.RareDropNotifier rareDrops,
            RecapCounters counters, ProofPipeline proofs,
        Supplier<PluginConfigResponse> pluginConfig, BoardRefresh boardRefresh, LocalPlayer localPlayer,
        com.anvil.api.MediaUploads media) {
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
        this.pluginConfig = pluginConfig;
        this.refreshConfig = boardRefresh::now;
        this.localPlayerName = localPlayer::name;
        this.media = media;
    }

    /** Normalised RSN -> teamId for every enrolled player, so 'team:other' can classify. */
    public java.util.Map<String, Integer> roster() {
        return pvpRosterIndex;
    }


    /** We landed a hit on this player. A death inside the window claims it as our kill. */
    public void noteDamagedPlayer(String name) {
        lastDamagedPlayerAt.record(name);
    }

    /**
     * Claim this death as ours, once.
     *
     * <p>Consumed rather than read, so two attackers who both damaged the victim do not both credit
     * it on the strength of the same park.</p>
     */
    public boolean claimKill(String name) {
        return lastDamagedPlayerAt.consume(name);
    }

    private boolean isTileCompleted(int tileId) {
        PluginConfigResponse cfg = pluginConfig.get();
        if (cfg == null || cfg.completedTiles == null) {
            return false;
        }
        for (CompletedTile c : cfg.completedTiles) {
            if (c != null && c.tileId == tileId) {
                return true;
            }
        }
        return false;
    }


    // PvP-kill attribution — when a hitsplat we dealt lands on a player, remember it. If that
    // player then dies within the window, we count it as our kill (avoids screenshotting random
    // nearby deaths). Keyed by lowercased player name. Pruned on each kill check.
    private static final long PVP_KILL_ATTRIBUTION_MS = 6_000;

    public final DedupWindow<String> lastDamagedPlayerAt = new DedupWindow<>(PVP_KILL_ATTRIBUTION_MS);

    // PvP min-loot tiles credit off the LOOT (priced at PlayerLootReceived), not the death — so a
    // kill on a matching victim is parked here at death and consumed when its loot arrives and prices
    // at/above the tile's floor. Keyed by lowercased victim RSN. Loot-key / no-loot kills never fire
    // PlayerLootReceived, so their entry just expires and the min-loot tile isn't credited (intended).
    private static final long PVP_MINLOOT_LOOT_WINDOW_MS = 20_000;

    private final DedupWindow<String> pendingMinLootKillAt = new DedupWindow<>(PVP_MINLOOT_LOOT_WINDOW_MS);

    // ---- PvP-kill tiles --------------------------------------------------------------------
    // Normalised RSN -> teamId for every enrolled event player, so 'team:other' selectors can
    // classify a victim. Rebuilt on each config refresh; empty unless the event has a pvp tile.
    private volatile Map<String, Integer> pvpRosterIndex = Collections.emptyMap();

    /**
     * Rebuild the normalised-RSN → teamId roster index used by PvP-kill tiles'
     * "team:other" selectors; refreshed together with the drop index. Empty
     * unless the event has a pvp tile (the server only ships the roster then).
     */
    public void rebuildPvpRosterIndex() {
        Map<String, Integer> index = new HashMap<>();
        if (pluginConfig.get() != null && pluginConfig.get().pvpRoster != null) {
            for (RosterEntry entry : pluginConfig.get().pvpRoster) {
                if (entry != null && entry.name != null && !entry.name.isEmpty()) {
                    index.put(Rsn.normalize(entry.name), entry.teamId);
                }
            }
        }
        pvpRosterIndex = index;
    }

    /**
     * True when the recap PvP-kill counter alone wants damage→death attribution: an active event
     * with auto-tracking on. Kept to cheap reference checks — this runs per hitsplat; the full
     * tracking gate applies later inside ensureCounterEvent().
     */
    /** True when the current event config carries any PvP-kill tiles. */
    public boolean boardHasPvpTiles() {
        PluginConfigResponse cfg = pluginConfig.get();
        return cfg != null && cfg.trackedPvp != null && !cfg.trackedPvp.isEmpty();
    }

    public boolean pvpCounterActive() {
        PluginConfigResponse cfg = pluginConfig.get();
        return config.autoSubmit() && cfg != null && cfg.event != null && AnvilOverlay.isEventActive(cfg.event);
    }

    /** Dangerous PvP only — the Wilderness or a PvP world. Safe minigames (LMS, Soul Wars,
     *  Castle Wars, PvP Arena) and DMM never count as PKs. Client thread (varbit read). */
    public boolean inDangerousPvp() {
        return client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1
                || client.getWorldType().contains(WorldType.PVP);
    }

    /**
     * Posts a PvP kill to the kills channel. Called from onActorDeath once the kill is already
     * attributed to us (damage within the window), so this just applies the channel toggle and
     * posts. Runs on the client thread; screenshot + network send are deferred.
     */
    public void notifyPvpKill(String name) {
        if (!embeds.notifyEnabled("pvpKills")) {
            return;
        }
        String message = rareDrops.buildKillMessage(localPlayerName.get(), name);
        embeds.captureFrameAsync(png -> media.postNotification("pvpKills", message, null, png, "anvil-pvp-kill.png"));
    }

    /**
     * Credits PvP-kill bingo tiles for a kill attributed to us — called from onActorDeath when a
     * player we damaged (within the attribution window) dies. Using the death (not a chat line)
     * makes it work for loot-key kills, which produce only a random taunt message and no ground
     * loot. Only dangerous PvP counts — the Wilderness or a PvP world — so safe minigames (LMS,
     * Soul Wars, Castle Wars, PvP Arena) and DMM can't farm the tile. Selector semantics:
     * "team:other" matches any event participant on a different team (via the pvpRoster index —
     * so the victim must be enrolled on a team with a matching RSN); "rsn:&lt;name&gt;" matches
     * that exact player, enrolled or not. Amount 1 per kill through the shared proof pipeline (the
     * death fires on the kill tick — the frame still shows the fight).
     */
    public void creditPvpKillTiles(String victimName) {
        String why = gate.reason();
        if (why != null || pluginConfig.get().trackedPvp == null || pluginConfig.get().trackedPvp.isEmpty()) {
            if (why != null) {
                gate.logSuppressed(why);
            }
            return;
        }
        if (!inDangerousPvp()) {
            gate.logSuppressed("PvP kill outside dangerous PvP (Wilderness / PvP world) — not counted");
            return;
        }
        String victim = Rsn.normalize(victimName);
        Integer myTeam = pluginConfig.get().team != null ? pluginConfig.get().team.id : null;
        boolean anyDeferred = false;
        for (TrackedPvp tile : pluginConfig.get().trackedPvp) {
            if (tile == null || tile.targets == null || tile.currentAmount >= tile.requiredAmount
                    || isTileCompleted(tile.tileId) || !pvpVictimMatchesTile(tile, victim, myTeam)) {
                continue;
            }
            // A min-loot floor is checked against the kill's LOOT, which only arrives in a later
            // PlayerLootReceived — park the kill and let that event credit it. Every other PvP tile
            // credits off the death now (still works for loot-key kills, which drop no ground loot).
            if (tile.minLootValue > 0) {
                anyDeferred = true;
                continue;
            }
            creditOnePvpTile(tile, victimName);
        }
        if (anyDeferred) {
            pendingMinLootKillAt.record(victim);
        }
    }

    /** Selector match for a PvP tile against a normalised victim RSN ('any' / 'team:other' / 'rsn:&lt;name&gt;'). */
    private boolean pvpVictimMatchesTile(TrackedPvp tile, String victimNorm, Integer myTeam) {
        if (tile.targets == null) {
            return false;
        }
        Integer victimTeam = pvpRosterIndex.get(victimNorm);
        for (String sel : tile.targets) {
            if (sel == null) {
                continue;
            }
            String s = sel.trim();
            if (s.equalsIgnoreCase("any")) {
                // Any player kill counts — no team/bounty restriction (the caller already gated on
                // dangerous-PvP, so safe minigames don't reach here).
                return true;
            } else if (s.equalsIgnoreCase("team:other")) {
                if (victimTeam != null && myTeam != null && !victimTeam.equals(myTeam)) {
                    return true;
                }
            } else if (s.regionMatches(true, 0, "rsn:", 0, 4)) {
                if (Rsn.normalize(s.substring(4)).equals(victimNorm)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Optimistically bump a PvP tile and submit a baked kill screenshot (rollback reverts on failure). */
    private void creditOnePvpTile(TrackedPvp tile, String victimName) {
        tile.currentAmount += 1;
        final TrackedPvp ft = tile;
        log.info("Tracked PvP kill: {} → tile '{}' ({}/{})",
                victimName, tile.label, tile.currentAmount, tile.requiredAmount);
        String detail = "Killed " + victimName + "  (" + tile.currentAmount + "/" + tile.requiredAmount + ")";
        proofs.captureAndSubmitProof(tile.tileId, tile.label, 1, null,
                "BINGO PVP KILL", detail,
                "[Auto] PvP kill on " + victimName + " — detected by RuneLite plugin",
                () -> ft.currentAmount = Math.max(0, ft.currentAmount - 1));
    }

    /**
     * Credits PvP min-loot tiles from a kill's loot — called from onPlayerLootReceived. If we parked a
     * matching kill on this victim at death (pendingMinLootKillAt) and the loot prices at/above a
     * tile's floor, credit it. The loot is priced once and every qualifying min-loot tile for this
     * victim is credited; the parked entry is consumed so one kill credits at most once per tile.
     */
    public void creditPvpMinLootKillTiles(String victimName, Collection<ItemStack> items) {
        if (pluginConfig.get() == null || pluginConfig.get().trackedPvp == null || pluginConfig.get().trackedPvp.isEmpty()
                || items == null || items.isEmpty() || victimName == null) {
            return;
        }
        String victim = Rsn.normalize(victimName);
        // One credit per parked kill: consume() both reads and removes it.
        if (!pendingMinLootKillAt.consume(victim)) {
            return;
        }
        if (gate.reason() != null) {
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
        Integer myTeam = pluginConfig.get().team != null ? pluginConfig.get().team.id : null;
        for (TrackedPvp tile : pluginConfig.get().trackedPvp) {
            if (tile == null || tile.minLootValue <= 0 || tile.currentAmount >= tile.requiredAmount
                    || isTileCompleted(tile.tileId) || !pvpVictimMatchesTile(tile, victim, myTeam)) {
                continue;
            }
            if (haulGp < tile.minLootValue) {
                log.info("PvP kill on {} worth {} gp is below tile '{}' floor {} gp — not counted",
                        victimName, haulGp, tile.label, tile.minLootValue);
                continue;
            }
            creditOnePvpTile(tile, victimName);
        }
    }
}
