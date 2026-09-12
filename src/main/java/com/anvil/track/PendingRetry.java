package com.anvil.track;

import com.anvil.AnvilConfig;
import com.anvil.api.ApiErrors;
import com.anvil.api.BingoApiClient;
import com.anvil.api.MediaUploads;
import com.anvil.api.TileSubmissions;
import com.anvil.api.dto.PermanentSubmissionException;
import com.anvil.io.PendingSubmissionStore;
import com.anvil.util.AnvilChat;
import com.anvil.util.TaskRunner;
import java.io.IOException;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Proofs that were captured but never landed, tried again later.
 *
 * <h2>Why a proof waits rather than dies</h2>
 *
 * <p>The drop really happened. A screenshot sitting on disk because the site was down, or because
 * the member has not filed their starting shot yet, is worth keeping — throwing it away because of
 * a transport problem loses a real credit that nothing else will ever re-create.</p>
 *
 * <p>So a failure is classified before it is acted on (see {@link ApiErrors}): a 4xx that will never
 * succeed drops the submission, and anything else keeps it and backs off. The backoff doubles from
 * thirty seconds so a site that is genuinely down is not hammered for the whole session, and resets
 * the moment one goes through.</p>
 */
@Slf4j
@Singleton
public class PendingRetry
{
    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final AnvilChat chat;
    private final TaskRunner tasks;
    private final PendingSubmissionStore pendingSubmissionStore;
    private final MediaUploads media;
    private final TileSubmissions tiles;

    /** A held submission is often held on the starting shot; that class owns the nag. */
    private final StartProofCapture startProof;

    /** Pull the board back once something lands, so the panel's counts catch up. */
    private final com.anvil.api.BoardRefresh boardRefresh;

    @Inject
    PendingRetry(AnvilConfig config, BingoApiClient apiClient, AnvilChat chat, TaskRunner tasks,
            PendingSubmissionStore pendingSubmissionStore, MediaUploads media,
            TileSubmissions tiles, StartProofCapture startProof,
            com.anvil.api.BoardRefresh boardRefresh) {
        this.config = config;
        this.apiClient = apiClient;
        this.chat = chat;
        this.tasks = tasks;
        this.pendingSubmissionStore = pendingSubmissionStore;
        this.media = media;
        this.tiles = tiles;
        this.startProof = startProof;
        this.boardRefresh = boardRefresh;
    }

    // Exponential backoff for pending submission retries
    private long retryBackoffMs = 30_000; // Start at 30s

    /** Never wait longer than this between attempts, however long the site has been down. */
    private static final long MAX_RETRY_BACKOFF_MS = 10 * 60_000L;

    /**
     * Try one held submission, and say whether it landed.
     *
     * <p>Public because the capture paths reach for it the moment an upload fails: a proof that just
     * failed is the one most likely to succeed on the next attempt, and waiting out the backoff to
     * find out is thirty seconds of the member wondering whether it worked.</p>
     */
    public void resetBackoff() {
        retryBackoffMs = 30_000;
    }

    public boolean processPendingSubmission(PendingSubmissionStore.PendingSubmission pending) {
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

            startProof.warnBeforeCredit();
            log.info("Uploading screenshot for tile '{}'...", pending.label);
            String imageUrl = media.uploadImage(pngBytes, filename);

            if (pending.durationSeconds != null) {
                log.info("Submitting timed clear for tile '{}'...", pending.label);
                tiles.submitTimed(
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
                tiles.submitDrop(
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
    public void run() {
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
        boardRefresh.now();
    }
}
