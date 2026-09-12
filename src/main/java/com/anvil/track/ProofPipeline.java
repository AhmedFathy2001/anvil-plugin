package com.anvil.track;

import com.anvil.session.LocalPlayer;
import com.anvil.api.BoardRefresh;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.TrackedDrop;
import com.anvil.io.PendingSubmissionStore;
import com.anvil.ui.ProofBanner;
import com.anvil.util.AnvilChat;
import com.anvil.util.TaskRunner;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.DrawManager;

/**
 * Turning a moment into a PNG the clan can trust, and getting it to the site.
 *
 * <h2>Everything the proof claims is in the pixels</h2>
 *
 * <p>It is looked at later, by somebody who was not there. So the item, the count, the account, the
 * team, the event and a UTC stamp are burned into the image rather than left to an overlay that
 * happened to be rendering — see {@link com.anvil.ui.ProofBanner}.</p>
 *
 * <h2>Disk first, then the network</h2>
 *
 * <p>The PNG is written to the pending store before any upload is attempted, so a crash, a closed
 * client or a site that is down costs nothing. The retry loop backs off to five minutes, and a
 * failure the server calls permanent is dropped rather than retried forever.</p>
 *
 * <h2>The starting shot</h2>
 *
 * <p>A separate thing that uses the same machinery: a screenshot taken at the start of an event, so
 * a member cannot stockpile drops beforehand and submit them on day one.</p>
 */
@Slf4j
@Singleton
public class ProofPipeline
{
    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final Client client;
    private final ClientThread clientThread;
    private final ItemManager itemManager;
    private final DrawManager drawManager;
    private final ConfigManager configManager;
    private final PendingSubmissionStore pendingSubmissionStore;

    /**
     * The starting shot: its own rule, its own deadline, and the one proof we nag about.
     *
     * <p>A Provider because it reaches back here for the proof banner's context.</p>
     */
    private final javax.inject.Provider<StartProofCapture> startProofRef;

    /** Proofs that were captured but never landed. A Provider: it nags via the start proof. */
    private final javax.inject.Provider<PendingRetry> retryRef;

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

    /** The live event config. A supplier, because the object is replaced on every poll. */
    private final Supplier<PluginConfigResponse> pluginConfig;
    /** Pull the board back after a credit, so the tile's new total is what the panel shows. */
    private final Runnable refreshConfig;
    /** Who is playing. Read at capture time, not at draw time. */
    private final Supplier<String> localPlayerName;

    /** A drop, a timed clear, or the starting shot — each with a screenshot behind it. */
    private final com.anvil.api.TileSubmissions tiles;

    /** Notifications, proof screenshots and clips — the requests carrying a file. */
    private final com.anvil.api.MediaUploads media;

    @Inject
    ProofPipeline(AnvilConfig config, BingoApiClient apiClient, Client client, ClientThread clientThread,
            ItemManager itemManager, DrawManager drawManager, ConfigManager configManager,
            PendingSubmissionStore pendingSubmissionStore, AnvilChat chat, TaskRunner tasks,
            TrackingGate gate, LocalProgress progress, PartyTracker party, Coalescer coalescer,
            com.anvil.notify.AnvilEmbeds embeds, com.anvil.notify.LootSourceMemory lootSource,
            com.anvil.notify.MomentsService moments, com.anvil.notify.RareDropNotifier rareDrops,
            RecapCounters counters,
        Supplier<PluginConfigResponse> pluginConfig, BoardRefresh boardRefresh, LocalPlayer localPlayer,
        com.anvil.api.TileSubmissions tiles,
        com.anvil.api.MediaUploads media,
            javax.inject.Provider<StartProofCapture> startProofRef,
            javax.inject.Provider<PendingRetry> retryRef) {
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
        this.pluginConfig = pluginConfig;
        this.refreshConfig = boardRefresh::now;
        this.localPlayerName = localPlayer::name;
        this.tiles = tiles;
        this.media = media;
        this.startProofRef = startProofRef;
        this.retryRef = retryRef;
    }


    /** A new account may owe its own starting shot, and has not been nudged about it. */
    public void onLogout() {
        startProofRef.get().onLogout();
    }






    // Server-upload throttle. Submissions go through a tiny gap so we never burst the
    // upload + submit endpoints if multiple aggregates flush close together.
    private static final long UPLOAD_THROTTLE_MS = 600;

    private volatile long lastUploadAt = 0;

    private static final long MAX_RETRY_BACKOFF_MS = 300_000; // Cap at 5 minutes

    // Hello/membership flow state
    @Getter
    private volatile Boolean knownMember; // null = unknown, true = in clanMembers
    @Getter
    private volatile boolean isGuest;

    /**
     * Shared capture → bake → persist → upload → submit path for kill and timed
     * tiles. Mirrors captureAndSubmit (drops) but takes primitives plus an
     * optional durationSeconds (non-null = timed) and a rollback to run if the
     * screenshot capture fails.
     */
    public void captureAndSubmitProof(int tileId, String label, int amount, Integer durationSeconds,
            String bannerTitle, String bannerDetail, String note, Runnable rollback) {
        if (pluginConfig.get() == null || pluginConfig.get().event == null || pluginConfig.get().team == null || pluginConfig.get().player == null) {
            return;
        }
        progress.noteTile(tileId); // "Active now": this account credited this tile (kill/timed/diary/CA/...)
        final int eventId = pluginConfig.get().event.id;
        final int teamId = pluginConfig.get().team.id;
        final int playerId = pluginConfig.get().player.id;
        final String capturedRsn = localPlayerName.get();

        drawManager.requestNextFrameListener(image -> {
            if (!tasks.isLive()) {
                return;
            }
            tasks.run(() -> {
                try {
                    BufferedImage buffered = (BufferedImage) image;
                    ProofBanner.draw(buffered, bannerTitle, bannerDetail, proofContext(capturedRsn), null);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(buffered, "png", baos);
                    byte[] pngBytes = baos.toByteArray();

                    PendingSubmissionStore.PendingSubmission pending = new PendingSubmissionStore.PendingSubmission();
                    pending.eventId = eventId;
                    pending.tileId = tileId;
                    pending.teamId = teamId;
                    pending.playerId = playerId;
                    pending.amount = amount;
                    pending.label = label;
                    pending.note = note;
                    pending.timestamp = System.currentTimeMillis();
                    pending.itemId = null;
                    pending.durationSeconds = durationSeconds;
                    pending.capturedRsn = capturedRsn;

                    String savedId = pendingSubmissionStore.save(pending, pngBytes);
                    if (savedId == null) {
                        log.error("Failed to persist submission '{}' to disk", label);
                        return;
                    }

                    chat.send("Uploading proof: " + label + "...");
                    boolean success = retryRef.get().processPendingSubmission(pending);
                    if (success) {
                        chat.send("Submitted: " + label);
                        retryRef.get().resetBackoff();
                    } else {
                        notifyUploadFailed(label);
                    }
                    refreshConfig.run();
                } catch (IOException e) {
                    log.error("Failed to capture screenshot for '{}': {}", label, e.getMessage());
                    chat.send("Screenshot failed for " + label + ": " + e.getMessage());
                    if (rollback != null) {
                        rollback.run();
                    }
                }
            });
        });
    }

    /**
     * Stacks the at-drop frame above the flush frame (thin gold divider, corner
     * time tags) so the proof shows both the moment of the drop and the floor
     * loot once it settled. Returns the flush frame untouched when there is no
     * trigger frame (toggle off, or the frame never arrived).
     */
    /**
     * One chat line when a proof can't be submitted right now. The PNG (banner
     * already baked) is safe on disk in the pending store and auto-retried with
     * backoff — this just makes the failure visible and points at the file.
     */
    private void notifyUploadFailed(String label) {
        chat.send("Couldn't submit \"" + label + "\" — proof saved locally, will keep retrying. "
                + "Find it in the Anvil side panel → \"Saved proofs\".");
    }

    /**
     * Who and where a proof was taken, as {@link ProofBanner} wants it.
     *
     * <p>Read at capture time, not at draw time: the config is replaced wholesale on every poll, and
     * a proof that spends two seconds in the encoder should still say which event it belonged to.</p>
     */
    ProofBanner.Context proofContext(String rsn) {
        PluginConfigResponse cfg = pluginConfig.get();
        return new ProofBanner.Context(rsn,
                cfg != null && cfg.team != null ? cfg.team.name : null,
                cfg != null && cfg.event != null ? cfg.event.name : null);
    }

    /**
     * Capture + save a MANUAL proof for a collectible the plugin can't auto-credit to a tile — a pet
     * drop, or a duplicate Champion's scroll (the "would have received" line names no item and fires
     * no loot event). We grab the next frame, burn the standard proof banner onto it, and stash it in
     * the pending store flagged {@code manual} (so the retry loop never tries to upload it). It surfaces
     * in the Anvil side panel under "Saved proofs" for the player to attach when they submit by
     * hand on the site.
     */
    public void captureManualProof(String label, String note) {
        if (drawManager == null || !tasks.isLive()) {
            return;
        }
        final int eventId = pluginConfig.get() != null && pluginConfig.get().event != null ? pluginConfig.get().event.id : 0;
        final int teamId = pluginConfig.get() != null && pluginConfig.get().team != null ? pluginConfig.get().team.id : 0;
        final int playerId = pluginConfig.get() != null && pluginConfig.get().player != null ? pluginConfig.get().player.id : 0;
        final String capturedRsn = localPlayerName.get();
        drawManager.requestNextFrameListener(image -> {
            if (!tasks.isLive()) {
                return;
            }
            tasks.run(() -> {
                try {
                    // Copy the shared frame before annotating so we don't mutate the draw manager's buffer.
                    BufferedImage src = (BufferedImage) image;
                    BufferedImage buffered = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = buffered.createGraphics();
                    g.drawImage(src, 0, 0, null);
                    g.dispose();
                    ProofBanner.draw(buffered, "BINGO", label, proofContext(capturedRsn), null);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(buffered, "png", baos);

                    PendingSubmissionStore.PendingSubmission pending = new PendingSubmissionStore.PendingSubmission();
                    pending.eventId = eventId;
                    pending.tileId = -1; // no tile — manual proof
                    pending.teamId = teamId;
                    pending.playerId = playerId;
                    pending.amount = 1;
                    pending.label = label;
                    pending.note = note;
                    pending.timestamp = System.currentTimeMillis();
                    pending.capturedRsn = capturedRsn;
                    pending.manual = true;

                    String savedId = pendingSubmissionStore.save(pending, baos.toByteArray());
                    if (savedId != null) {
                        chat.send(label + " — proof saved. Submit it on the Anvil site "
                                + "(Anvil side panel → \"Saved proofs\").");
                    } else {
                        log.error("Failed to persist manual proof '{}'", label);
                    }
                } catch (IOException e) {
                    log.error("Failed to capture manual proof '{}': {}", label, e.getMessage());
                }
            });
        });
    }

    public void captureAndSubmit(TrackedDrop drop, int amount, int snapshotCurrent, int snapshotRequired, Integer trackingItemId,
            BufferedImage triggerFrame) {
        progress.noteTile(drop.tileId); // "Active now": this account credited this drop tile
        // Capture IDs now (before async) since pluginConfig could change
        final int eventId = pluginConfig.get().event.id;
        final int teamId = pluginConfig.get().team.id;
        final int playerId = pluginConfig.get().player.id;
        // The character this drop was obtained on (read on the client thread). The submission is only
        // ever sent while logged into this same account, so a drop caught on a non-enrolled alt can't
        // be credited to the enrolled account later.
        final String capturedRsn = localPlayerName.get();
        // Item icon, fetched on the client thread so it's baked into the proof even with chat off.
        final BufferedImage capturedIcon = trackingItemId != null ? itemManager.getImage(trackingItemId) : null;

        drawManager.requestNextFrameListener(image
                -> {
            if (!tasks.isLive()) {
                return;
            }
            tasks.run(()
                    -> {
                try {
                    // Two-frame proof: the at-drop frame (stashed when the burst started) stacked
                    // above this flush frame, taken COALESCE_FLUSH_MS later once floor loot has
                    // settled. Falls back to the single flush frame when the toggle is off or the
                    // trigger frame never arrived.
                    BufferedImage buffered = ProofBanner.stack(triggerFrame, (BufferedImage) image);
                    // Annotate the screenshot directly with a high-contrast banner so the
                    // drop is unambiguous even when the in-game loot popup has already
                    // faded or never rendered (5-stack pickups can fade quickly). Drawing
                    // on the image guarantees it ends up in the saved PNG regardless of
                    // overlay timing.
                    ProofBanner.drawDrop(buffered, drop.label, amount, snapshotCurrent, snapshotRequired,
                            proofContext(capturedRsn), capturedIcon);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(buffered, "png", baos);
                    byte[] pngBytes = baos.toByteArray();

                    // Persist to disk first so it survives a crash/close
                    PendingSubmissionStore.PendingSubmission pending = new PendingSubmissionStore.PendingSubmission();
                    pending.eventId = eventId;
                    pending.tileId = drop.tileId;
                    pending.teamId = teamId;
                    pending.playerId = playerId;
                    pending.amount = amount;
                    pending.label = drop.label;
                    pending.note = "[Auto] " + drop.label + " detected by RuneLite plugin";
                    pending.timestamp = System.currentTimeMillis();
                    pending.itemId = trackingItemId;
                    pending.capturedRsn = capturedRsn;

                    String savedId = pendingSubmissionStore.save(pending, pngBytes);
                    if (savedId == null) {
                        log.error("Failed to persist drop '{}' to disk", drop.label);
                        return;
                    }

                    // Now upload and submit
                    chat.send("Uploading proof: " + drop.label + "...");
                    boolean success = retryRef.get().processPendingSubmission(pending);

                    if (success) {
                        chat.send("Drop submitted: " + drop.label + " (" + snapshotCurrent + "/" + snapshotRequired + ")");
                        // Reset backoff on success
                        retryRef.get().resetBackoff();
                    } else {
                        notifyUploadFailed(drop.label);
                    }

                    // Refresh config from server to sync all counts
                    refreshConfig.run();
                } catch (IOException e) {
                    log.error("Failed to capture screenshot for '{}': {}", drop.label, e.getMessage());
                    chat.send("Screenshot failed for " + drop.label + ": " + e.getMessage());
                    drop.currentAmount = Math.max(0, drop.currentAmount - amount);
                }
            });
        });
    }

}
