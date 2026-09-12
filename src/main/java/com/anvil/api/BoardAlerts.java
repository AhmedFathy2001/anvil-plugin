package com.anvil.api;

import com.anvil.detect.LadderMissions;
import com.anvil.AnvilConfig;
import com.anvil.api.dto.Claim;
import com.anvil.api.dto.CompletedTile;
import com.anvil.api.dto.Mission;
import com.anvil.io.BannerSoundActions;
import com.anvil.ui.AnvilSidebarPanel;
import com.anvil.ui.BingoClogBannerOverlay;
import com.anvil.util.AnvilChat;
import com.anvil.util.ClipMoments;
import com.anvil.util.Rsn;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * What CHANGED on the board since the last poll, and which of it is worth interrupting for.
 *
 * <h2>The seeding rule, which is the whole difficulty</h2>
 *
 * <p>A tile the team finished raises a banner once. But the first poll of an event arrives with
 * weeks of completions already on it, and popping a banner for each would be a wall of them for
 * things that happened while the member was asleep. So the first poll of each event seeds SILENTLY —
 * everything already done is absorbed — and only what completes afterwards announces itself.</p>
 *
 * <p>A mission works the same way, with the same reason: one announced mid-event is news; the eight
 * that ran last week are not.</p>
 */
@Slf4j
@Singleton
public class BoardAlerts
{
    // Team-level tile completions (drops, stats, manual — any tile type, completed by any member).
    // Fire a banner once per newly-completed tile. Seeded silently on the first refresh per event so
    // tiles completed before this session (or a relog) don't re-pop.
    private final Set<Integer> notifiedCompletedTiles = new HashSet<>();

// Ladder missions board: mission tiles we've already alerted "new mission" for, and claim tiles
    // we've already announced. Seeded on the first poll of an event (no backlog dump), cleared on change.
    private final Set<Integer> notifiedMissionTiles = new HashSet<>();

    private final Set<Integer> notifiedClaimTiles = new HashSet<>();

    private final AnvilConfig config;
    private final AnvilChat chat;
    private final Supplier<String> localPlayerName;
    private final AnvilSidebarPanel sidebarPanel;
    private final BingoClogBannerOverlay clogBanner;
    private final BannerSoundActions sounds;
    private final ClipMoments clipMoments;

    @Inject
    BoardAlerts(AnvilConfig config, AnvilChat chat, com.anvil.session.LocalPlayer localPlayer,
            AnvilSidebarPanel sidebarPanel, BingoClogBannerOverlay clogBanner,
            BannerSoundActions sounds, ClipMoments clipMoments) {
        this.config = config;
        this.chat = chat;
        this.localPlayerName = localPlayer::name;
        this.sidebarPanel = sidebarPanel;
        this.clogBanner = clogBanner;
        this.sounds = sounds;
        this.clipMoments = clipMoments;
    }

    /** Banners are per event: seeded silently on the first poll so a relog re-pops nothing. */
    public void onShutDown() {
        notifiedCompletedTiles.clear();
        completionBaselineEventId = null;
        ladderBaselineEventId = null;
    }

    private Integer completionBaselineEventId;

    private Integer ladderBaselineEventId;

    public void checkTileCompletions(PluginConfigResponse cfg) {
        if (cfg == null || cfg.event == null || cfg.completedTiles == null) {
            return;
        }
        boolean seeding = completionBaselineEventId == null || completionBaselineEventId != cfg.event.id;
        if (seeding) {
            notifiedCompletedTiles.clear();
                completionBaselineEventId = cfg.event.id;
        }
        // Collect this poll's newly-completed tiles. add() still marks every tile seen even when the
        // popup is toggled off, so flipping it on later won't dump a backlog.
        List<CompletedTile> newlyDone = new ArrayList<>();
        for (CompletedTile t : cfg.completedTiles) {
            if (notifiedCompletedTiles.add(t.tileId) && !seeding) {
                newlyDone.add(t);
            }
        }
        if (newlyDone.isEmpty() || !config.teamCompletionBanner()) {
            return;
        }
        // Banner only the hardest (most points) tile this poll to avoid a burst of banners.
        CompletedTile hardest = newlyDone.get(0);
        for (CompletedTile t : newlyDone) {
            if (t.points > hardest.points) {
                hardest = t;
            }
        }
        clogBanner.show("Anvil Bingo", "Tile complete!", hardest.label);
        sounds.playBannerSound();
        // A persistent chat line for EVERY newly-completed tile (including the bannered one) — the
        // banner is easy to miss, so leave a record naming who finished it. Stat/manual completions
        // carry no crediting player, so those just say "Tile complete: <label>!".
        for (CompletedTile t : newlyDone) {
            String by = (t.completedBy != null && !t.completedBy.trim().isEmpty())
                    ? " — by " + t.completedBy.trim() : "";
            clipMoments.record("✅ Tile complete: " + t.label);
            chat.send("Tile complete: " + t.label + by + "!");
        }
    }

    /**
     * Missions board alerts, diffed across config polls like {@link #checkTileCompletions}: a banner +
     * chat when a NEW mission drops, and when ANOTHER player claims a lock-out one (own claims skipped).
     * Both pulse the sidebar card. Seeded on the first poll so opening the board doesn't dump the
     * backlog. Fires for a ladder OR a classic bingo carrying missions — NOT for a reveal-policy board
     * (showdown/rotating/bounty), whose reveals keep their existing sidebar-note behaviour.
     */
    public void checkMissionAlerts(PluginConfigResponse cfg) {
        if (cfg == null || cfg.event == null) {
            return;
        }
        boolean revealBoard = cfg.event.revealPolicy != null && !cfg.event.revealPolicy.isEmpty();
        boolean surface = LadderMissions.isLadder(cfg.event.format)
            || (!revealBoard && cfg.serverSupports("bingo-missions"));
        if (!surface) {
            return;
        }
        String tag = LadderMissions.isLadder(cfg.event.format) ? "Anvil Ladder" : "Anvil";
        boolean seeding = ladderBaselineEventId == null || ladderBaselineEventId != cfg.event.id;
        if (seeding) {
            notifiedMissionTiles.clear();
            notifiedClaimTiles.clear();
            ladderBaselineEventId = cfg.event.id;
        }

        // --- new missions (revealed + open) ---
        List<Mission> fresh = new ArrayList<>();
        if (cfg.event.missions != null) {
            for (Mission m : cfg.event.missions) {
                if (m != null && notifiedMissionTiles.add(m.tileId) && !seeding) {
                    fresh.add(m);
                }
            }
        }
        if (!fresh.isEmpty()) {
            Mission top = fresh.get(0);
            for (Mission m : fresh) {
                if (m.points > top.points) {
                    top = m;
                }
            }
            clogBanner.show(tag, "New mission!", top.label);
            sounds.playMissionSound(false);
            for (Mission m : fresh) {
                clipMoments.record("⚡ New mission: " + m.label);
                chat.send("New mission: " + m.label + " - " + m.points + " pts!");
            }
            if (sidebarPanel != null) {
                sidebarPanel.flashLadder();
            }
        }

        // --- lock-out claims by OTHER players ---
        String me = Rsn.normalize(localPlayerName.get());
        List<Claim> claims = new ArrayList<>();
        if (cfg.event.recentClaims != null) {
            for (Claim c : cfg.event.recentClaims) {
                if (c == null || !notifiedClaimTiles.add(c.tileId) || seeding) {
                    continue;
                }
                boolean mine = c.rsn != null && !me.isEmpty() && me.equals(Rsn.normalize(c.rsn));
                if (!mine) {
                    claims.add(c);
                }
            }
        }
        if (!claims.isEmpty()) {
            Claim latest = claims.get(0);
            String who = latest.rsn != null && !latest.rsn.trim().isEmpty() ? latest.rsn.trim() : "Someone";
            clogBanner.show(tag, "Mission claimed", who + ": " + latest.label);
            sounds.playMissionSound(true);
            for (Claim c : claims) {
                String by = c.rsn != null && !c.rsn.trim().isEmpty() ? c.rsn.trim() : "Someone";
                chat.send(by + " claimed " + c.label + " - " + c.points + " pts!");
            }
            if (sidebarPanel != null) {
                sidebarPanel.flashLadder();
            }
        }
    }

    /** Set once we've mentioned the canonical URL, so a 30-second poll does not become a 30-second nag. */
    private volatile boolean urlMigrationSuggested = false;
}
