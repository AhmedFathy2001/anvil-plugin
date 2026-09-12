package com.anvil.track;

import javax.imageio.ImageIO;
import java.time.Instant;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.TileSubmissions;
import com.anvil.api.dto.StartProof;
import com.anvil.detect.StartProofRules;
import com.anvil.io.PendingSubmissionStore;
import com.anvil.notify.AnvilEmbeds;
import com.anvil.session.LocalPlayer;
import com.anvil.ui.ProofBanner;
import com.anvil.util.AnvilChat;
import com.anvil.util.TaskRunner;
import java.io.IOException;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

/**
 * The starting shot: one screenshot, filed before the event, proving nothing was stockpiled.
 *
 * <h2>Why it is a separate thing from every other proof</h2>
 *
 * <p>Every other proof records something that happened. This one records something that did NOT —
 * that the account's totals at the start were what it says they were. It is therefore the only
 * capture with a deadline, the only one the server can refuse a later submission for
 * ({@code start_proof_required}), and the only one the plugin nags about, because a member who
 * misses the window cannot go back and take it.</p>
 *
 * <p>Two client-side checks ride along with the image — where the account was standing, and when
 * this session logged in. The server re-measures both rather than trusting the verdict, and a shot
 * that fails one lands as {@code pending} instead of accepted.</p>
 */
@Slf4j
@Singleton
public class StartProofCapture
{
    private final Client client;
    private final ClientThread clientThread;
    private final AnvilConfig config;
    private final BingoApiClient apiClient;
    private final AnvilChat chat;
    private final TaskRunner tasks;
    private final Supplier<PluginConfigResponse> pluginConfig;
    private final LocalPlayer localPlayer;
    private final AnvilEmbeds embeds;
    private final TileSubmissions tiles;
    private final PendingSubmissionStore pendingSubmissionStore;
    private final com.anvil.api.MediaUploads media;
    private final net.runelite.client.ui.DrawManager drawManager;

    /** Pull the board back once a shot is filed: the panel's prompt disappears on the next poll. */
    private final com.anvil.api.BoardRefresh boardRefresh;

    /** For the proof banner's context. A Provider: ProofPipeline injects this class. */
    private final javax.inject.Provider<ProofPipeline> proofs;

    @Inject
    StartProofCapture(Client client, ClientThread clientThread, AnvilConfig config,
            BingoApiClient apiClient, AnvilChat chat, TaskRunner tasks,
            Supplier<PluginConfigResponse> pluginConfig, LocalPlayer localPlayer,
            AnvilEmbeds embeds, TileSubmissions tiles,
            PendingSubmissionStore pendingSubmissionStore, com.anvil.api.MediaUploads media,
            net.runelite.client.ui.DrawManager drawManager,
            com.anvil.api.BoardRefresh boardRefresh,
            javax.inject.Provider<ProofPipeline> proofs) {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.apiClient = apiClient;
        this.chat = chat;
        this.tasks = tasks;
        this.pluginConfig = pluginConfig;
        this.localPlayer = localPlayer;
        this.embeds = embeds;
        this.tiles = tiles;
        this.pendingSubmissionStore = pendingSubmissionStore;
        this.media = media;
        this.drawManager = drawManager;
        this.boardRefresh = boardRefresh;
        this.proofs = proofs;
    }

    /** When this session logged in, which is what the starting-shot rule is measured against. */
    private java.util.function.LongSupplier sessionLoginAt = () -> StartProofRules.UNKNOWN_LOGIN;

    public void bindSessionClock(java.util.function.LongSupplier sessionLoginAt) {
        this.sessionLoginAt = sessionLoginAt;
    }

    // STARTING SHOT (site lib/startProof). `startProofFiled` latches the moment one is accepted by
    // the server so the button/nudge go away immediately instead of waiting on the next config poll;
    // `startProofInFlight` keeps an impatient double-click from filing two. Both reset on logout,
    // since the next login may be a different account with a different obligation.
    private volatile boolean startProofFiled;

    /** One nudge per login — a reminder that repeats every poll is just noise. */
    private volatile boolean startProofNudged;

    /** One "this credit is being held" line per login — see {@link #warnBeforeCredit()}. */
    private volatile boolean startProofCreditWarned;

    /**
     * Is a STARTING SHOT outstanding for this account right now? Drives the sidebar button and the
     * login nudge. False on every site/event that doesn't ask for one, and the moment one is filed.
     */
    /**
     * A new account owes its own shot, and has not been told about it yet.
     *
     * <p>Per ACCOUNT, so all three reset on logout: the next login may be an alt that still owes
     * one, and the server's config is the real answer either way.</p>
     */
    public void onLogout() {
        startProofFiled = false;
        startProofNudged = false;
        startProofCreditWarned = false;
    }

    private volatile boolean startProofInFlight;

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

    public boolean owed() {
        PluginConfigResponse cfg = pluginConfig.get();
        return cfg != null
                && cfg.startProof != null
                && cfg.startProof.required
                && cfg.startProof.drawn
                && cfg.startProof.needsUpload
                && !startProofFiled
                && cfg.event != null
                && com.anvil.ui.AnvilOverlay.isEventActive(cfg.event);
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
    public void capture() {
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
        final String capturedRsn = localPlayer.name();
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
                    ProofBanner.draw(buffered, "STARTING SHOT", detail, proofs.get().proofContext(capturedRsn), null);

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(buffered, "png", baos);

                    String imageUrl = media.uploadImage(baos.toByteArray(), "start-proof-" + eventId + ".png");
                    tiles.submitStartProof(eventId, imageUrl, keyword, capturedAt, worldX, worldY, loginAt);
                    startProofFiled = true;
                    chat.send("Starting shot sent. You're clear to play.");
                    boardRefresh.now();
                } catch (IOException e) {
                    log.error("Failed to file starting shot: {}", e.getMessage());
                    chat.send("Starting shot failed: " + e.getMessage() + " — try again.");
                } finally {
                    startProofInFlight = false;
                }
            });
        });
    }

    /**
     * One chat nudge per login when this account still owes a STARTING SHOT — the event is live, the
     * location is drawn, and nothing has been filed. Says where to stand and that the panel button
     * does the rest; repeating it every 30s refresh would just be noise, so it latches.
     */
    public void maybeNudge() {
        if (startProofNudged || !owed()) {
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
    public void warnBeforeCredit() {
        if (startProofCreditWarned || !owed()) {
            return;
        }
        startProofCreditWarned = true;
        chat.send("That's recorded, but your starting shot is still missing — it stays held for"
                + " review until you take it. Anvil side panel → \"Take starting shot\".");
    }
}
