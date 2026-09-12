package com.anvil.track;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.CompletedTile;
import com.anvil.api.dto.CoopFingerprint;
import com.anvil.api.dto.ItemRequirement;
import com.anvil.api.dto.PermanentSubmissionException;
import com.anvil.api.dto.StartProof;
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
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
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
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
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
public class ProofPipeline implements Tracker
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

    /** The live event config. A supplier, because the object is replaced on every poll. */
    private Supplier<PluginConfigResponse> pluginConfig = () -> null;
    /** Pull the board back after a credit, so the tile's new total is what the panel shows. */
    private Runnable refreshConfig = () -> { };
    /** Who is playing. Read at capture time, not at draw time. */
    private Supplier<String> localPlayerName = () -> null;

    @Inject
    ProofPipeline(AnvilConfig config, BingoApiClient apiClient, Client client, ClientThread clientThread,
            ItemManager itemManager, DrawManager drawManager, ConfigManager configManager,
            PendingSubmissionStore pendingSubmissionStore, AnvilChat chat, TaskRunner tasks,
            TrackingGate gate, LocalProgress progress, PartyTracker party, Coalescer coalescer,
            com.anvil.notify.AnvilEmbeds embeds, com.anvil.notify.LootSourceMemory lootSource,
            com.anvil.notify.MomentsService moments, com.anvil.notify.RareDropNotifier rareDrops,
            RecapCounters counters) {
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
    }


    /** A new account may owe its own starting shot, and has not been nudged about it. */
    public void onLogout() {
        startProofFiled = false;
        startProofNudged = false;
        startProofCreditWarned = false;
    }

    /** When this session logged in, which is what the starting-shot rule is measured against. */
    private java.util.function.LongSupplier sessionLoginAt = () -> StartProofRules.UNKNOWN_LOGIN;

    public void bindSessionClock(java.util.function.LongSupplier sessionLoginAt) {
        this.sessionLoginAt = sessionLoginAt;
    }

    @Override
    public void bind(Supplier<PluginConfigResponse> pluginConfig, Runnable refreshConfig,
            Supplier<String> localPlayerName) {
        this.pluginConfig = pluginConfig;
        this.refreshConfig = refreshConfig;
        this.localPlayerName = localPlayerName;
    }

    // STARTING SHOT (site lib/startProof). `startProofFiled` latches the moment one is accepted by
    // the server so the button/nudge go away immediately instead of waiting on the next config poll;
    // `startProofInFlight` keeps an impatient double-click from filing two. Both reset on logout,
    // since the next login may be a different account with a different obligation.
    public volatile boolean startProofFiled;

    private volatile boolean startProofInFlight;

    /** One nudge per login — a reminder that repeats every poll is just noise. */
    public volatile boolean startProofNudged;

    /** One "this credit is being held" line per login — see {@link #warnStartProofBeforeCredit()}. */
    public volatile boolean startProofCreditWarned;

    // Server-upload throttle. Submissions go through a tiny gap so we never burst the
    // upload + submit endpoints if multiple aggregates flush close together.
    private static final long UPLOAD_THROTTLE_MS = 600;

    private volatile long lastUploadAt = 0;

    // Exponential backoff for pending submission retries
    private long retryBackoffMs = 30_000; // Start at 30s
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
                    boolean success = processPendingSubmission(pending);
                    if (success) {
                        chat.send("Submitted: " + label);
                        retryBackoffMs = 30_000;
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
    private ProofBanner.Context proofContext(String rsn) {
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

    /**
     * Is a STARTING SHOT outstanding for this account right now? Drives the sidebar button and the
     * login nudge. False on every site/event that doesn't ask for one, and the moment one is filed.
     */
    public boolean needsStartProof() {
        PluginConfigResponse cfg = pluginConfig.get();
        return cfg != null
                && cfg.startProof != null
                && cfg.startProof.required
                && cfg.startProof.drawn
                && cfg.startProof.needsUpload
                && !startProofFiled
                && cfg.event != null
                && AnvilOverlay.isEventActive(cfg.event);
    }

    /**
     * Take the STARTING SHOT (site lib/startProof): grab the next frame, burn the standard proof
     * banner onto it (RSN / team / event / UTC) with the drawn location and this player's keyword,
     * upload it and file it. The keyword is derived server-side from a stamp that didn't exist before
     * the event went live, so a shot carrying it could not have been staged in advance.
     *
     * Filed exactly once — {@link #startProofFiled} latches on success and the button disappears the
     * moment the next config poll agrees. A failure says so in chat and leaves the button up, since
     * the whole action is one keypress to repeat.
     */
    public void captureStartProof() {
        PluginConfigResponse cfg = pluginConfig.get();
        if (cfg == null || cfg.startProof == null || cfg.event == null || !cfg.startProof.drawn) {
            chat.send("No starting shot is being asked for right now.");
            return;
        }
        if (drawManager == null || !tasks.isLive()) {
            return;
        }
        if (startProofInFlight) {
            return;
        }

        // Where this account is standing, for the drawn spot's position check. Read before anything
        // async: by the time the frame arrives the player may have taken a step.
        final Integer worldX = localWorldX();
        final Integer worldY = localWorldY();
        final long loginAtMs = sessionLoginAt.getAsLong();

        // Refuse rather than file something staff will only have to chase: standing in the wrong
        // place or on a session too old to have flushed the hiscores are both fixable in-game, in
        // seconds, and the message says how.
        String blocked = StartProofRules.blockReason(
                cfg.startProof, loginAtMs, System.currentTimeMillis(), worldX, worldY);
        if (blocked != null) {
            chat.send(blocked);
            return;
        }

        startProofInFlight = true;

        final int eventId = cfg.event.id;
        final String location = cfg.startProof.location;
        final String keyword = cfg.startProof.keyword;
        final String capturedRsn = localPlayerName.get();
        final String capturedAt = Instant.now().toString();
        final String loginAt = loginAtMs == StartProofRules.UNKNOWN_LOGIN
                ? null
                : Instant.ofEpochMilli(loginAtMs).toString();

        drawManager.requestNextFrameListener(image -> {
            if (!tasks.isLive()) {
                startProofInFlight = false;
                return;
            }
            tasks.run(() -> {
                try {
                    // Copy the shared frame before annotating — never mutate the draw manager's buffer.
                    BufferedImage src = (BufferedImage) image;
                    BufferedImage buffered = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = buffered.createGraphics();
                    g.drawImage(src, 0, 0, null);
                    g.dispose();

                    String detail = keyword != null ? keyword : "";
                    if (location != null && !location.isEmpty()) {
                        detail = detail.isEmpty() ? location : detail + "  @  " + location;
                    }
                    ProofBanner.draw(buffered, "STARTING SHOT", detail, proofContext(capturedRsn), null);

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(buffered, "png", baos);

                    String imageUrl = apiClient.uploadImage(baos.toByteArray(), "start-proof-" + eventId + ".png");
                    apiClient.submitStartProof(eventId, imageUrl, keyword, capturedAt, worldX, worldY, loginAt);
                    startProofFiled = true;
                    chat.send("Starting shot sent. You're clear to play.");
                    refreshConfig.run();
                } catch (IOException e) {
                    log.error("Failed to file starting shot: {}", e.getMessage());
                    chat.send("Starting shot failed: " + e.getMessage() + " — try again.");
                } finally {
                    startProofInFlight = false;
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
                    boolean success = processPendingSubmission(pending);

                    if (success) {
                        chat.send("Drop submitted: " + drop.label + " (" + snapshotCurrent + "/" + snapshotRequired + ")");
                        // Reset backoff on success
                        retryBackoffMs = 30_000;
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

    /**
     * Uploads screenshot and submits a pending drop. Removes from disk on
     * success. Returns true on success, false on failure.
     */
    private boolean processPendingSubmission(PendingSubmissionStore.PendingSubmission pending) {
        // Only submit while logged into the account that obtained the drop. Guards the multi-account
        // case: a drop caught on a non-enrolled alt is never credited to the enrolled account, even
        // if it was queued during the brief window after switching characters.
        if (pending.capturedRsn != null && !pending.capturedRsn.isEmpty()) {
            String current = apiClient.getCurrentRsn();
            if (current == null || !pending.capturedRsn.equalsIgnoreCase(current)) {
                log.debug("Holding pending '{}' — captured on '{}', currently '{}'", pending.label, pending.capturedRsn, current);
                return false;
            }
        }
        byte[] pngBytes = pendingSubmissionStore.readScreenshot(pending);
        if (pngBytes == null) {
            log.error("No screenshot found for pending submission (tile '{}')", pending.label);
            pendingSubmissionStore.remove(pending);
            return false;
        }

        try {
            String filename = "anvil-sub-" + pending.tileId + "-" + pending.timestamp + ".png";

            warnStartProofBeforeCredit();
            log.info("Uploading screenshot for tile '{}'...", pending.label);
            String imageUrl = apiClient.uploadImage(pngBytes, filename);

            if (pending.durationSeconds != null) {
                log.info("Submitting timed clear for tile '{}'...", pending.label);
                apiClient.submitTimed(
                        pending.eventId,
                        pending.tileId,
                        pending.teamId,
                        pending.durationSeconds,
                        imageUrl,
                        pending.note,
                        pending.playerId
                );
            } else {
                log.info("Submitting drop for tile '{}'...", pending.label);
                apiClient.submitDrop(
                        pending.eventId,
                        pending.tileId,
                        pending.teamId,
                        pending.amount,
                        imageUrl,
                        pending.note,
                        pending.playerId,
                        pending.itemId
                );
            }

            log.info("Submission '{}' sent successfully!", pending.label);
            pendingSubmissionStore.remove(pending);
            return true;
        } catch (PermanentSubmissionException e) {
            // The server rejected this for good (tile already complete, event ended, invalid) — retrying
            // will never work, so drop it instead of looping forever. Treat as handled, not a failure.
            log.info("Dropping pending '{}' — server rejected permanently: {}", pending.label, e.getMessage());
            pendingSubmissionStore.remove(pending);
            return true;
        } catch (IOException e) {
            log.error("Failed to submit pending drop '{}': {} (will retry with backoff)", pending.label, e.getMessage());
            return false;
        }
    }

    /**
     * Retries any pending submissions with exponential backoff.
     */
    public void retryPendingSubmissions() {
        if (!apiClient.isConfigured()) {
            return;
        }

        List<PendingSubmissionStore.PendingSubmission> pending = pendingSubmissionStore.loadAll();
        if (pending.isEmpty()) {
            return;
        }

        log.info("Found {} pending submission(s), retrying...", pending.size());
        boolean anyFailed = false;
        for (PendingSubmissionStore.PendingSubmission sub : pending) {
            // Manual proofs (pet / duplicate Champion's scroll) have no tile to auto-submit to — they
            // just sit in "Saved proofs" for the player to attach by hand on the site. Never upload.
            if (sub.manual) {
                continue;
            }
            boolean success = processPendingSubmission(sub);
            if (!success) {
                anyFailed = true;
            } else {
                // A previously-failed proof finally made it — say so, since the original
                // "submitted" message never fired.
                chat.send("Queued proof submitted: " + sub.label);
            }
        }

        if (anyFailed) {
            // Increase backoff (capped)
            retryBackoffMs = Math.min(retryBackoffMs * 2, MAX_RETRY_BACKOFF_MS);
            log.info("Some pending submissions failed, next retry backoff: {}s", retryBackoffMs / 1000);
        } else {
            // Reset backoff on full success
            retryBackoffMs = 30_000;
        }

        // Refresh config to get updated counts from server
        refreshConfig.run();
    }

    /**
     * This account's world position, for the starting shot's position check (StartProofRules).
     * Null while logged out — which simply means the check doesn't run.
     */
    private Integer localWorldX() {
        if (client == null || client.getLocalPlayer() == null || client.getLocalPlayer().getWorldLocation() == null) {
            return null;
        }
        return client.getLocalPlayer().getWorldLocation().getX();
    }

    private Integer localWorldY() {
        if (client == null || client.getLocalPlayer() == null || client.getLocalPlayer().getWorldLocation() == null) {
            return null;
        }
        return client.getLocalPlayer().getWorldLocation().getY();
    }

    /**
     * One chat nudge per login when this account still owes a STARTING SHOT — the event is live, the
     * location is drawn, and nothing has been filed. Says where to stand and that the panel button
     * does the rest; repeating it every 30s refresh would just be noise, so it latches.
     */
    public void maybeNudgeStartProof() {
        if (startProofNudged || !needsStartProof()) {
            return;
        }
        StartProof sp = pluginConfig.get().startProof;
        startProofNudged = true;
        String left = StartProofRules.describeWindow(sp, System.currentTimeMillis());
        chat.send("Starting shot needed before you play"
                + (sp.location != null && !sp.location.isEmpty() ? " — go to " + sp.location : "")
                + ". Open the Anvil side panel and press \"Take starting shot\"."
                + (sp.maxSessionMinutes > 0
                        ? " Take it within " + sp.maxSessionMinutes + " min of logging in — hiscores only save"
                        + " on logout, so that's what sets your starting totals."
                        : "")
                // The consequence, which the nudge never spelled out: a player told only that
                // something is "needed" has no reason to do it before their next drop.
                + " Until it's filed your drops are held for review"
                + (left != null ? ", and it's only asked for another " + left : "")
                + ".");
    }

    /**
     * Say it once, at the moment it starts costing them something: a credit is going up while this
     * account still owes a STARTING SHOT, so the site will hold it for review.
     *
     * The login nudge fires before anyone has done anything, which is the easiest message in the
     * world to scroll past. This one lands on the drop itself. Once per login — the point is to be
     * noticed, and a line per kill is how a plugin gets turned off.
     */
    public void warnStartProofBeforeCredit() {
        if (startProofCreditWarned || !needsStartProof()) {
            return;
        }
        startProofCreditWarned = true;
        chat.send("That's recorded, but your starting shot is still missing — it stays held for"
                + " review until you take it. Anvil side panel → \"Take starting shot\".");
    }
}
