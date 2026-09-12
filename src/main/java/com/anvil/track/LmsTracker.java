package com.anvil.track;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.TrackedLms;
import com.anvil.ui.AnvilOverlay;
import com.anvil.ui.view.Standing;
import com.anvil.util.AnvilChat;
import com.anvil.util.TaskRunner;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * Last Man Standing, which is a different game inside the game.
 *
 * <p>Its loot is not real — a kill "drops" the victim's throwaway loadout — so none of it may reach
 * a drop, value or rare-drop path. What IS real is the placement, and the only way to know it is to
 * watch the survivor count fall and catch where it stopped.</p>
 */
@Slf4j
@Singleton
public class LmsTracker
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

    /** The live event config. A supplier, because the object is replaced on every poll. */
    protected Supplier<PluginConfigResponse> pluginConfig = () -> null;

    @Inject
    LmsTracker(Client client, ClientThread clientThread, AnvilConfig config, BingoApiClient apiClient,
            ConfigManager configManager, ItemManager itemManager, AnvilChat chat, TaskRunner tasks,
            com.anvil.track.TrackingGate gate, com.anvil.track.ProofPipeline proofs) {
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
    }


    /** Nobody is in a minigame while logged out. */
    public void onLogout() {
        lmsInGame = false;
        lmsSurvivors = 0;
        lmsKills = 0;
        lmsPlacementRecorded = false;
    }

    /**
     * We died: if the BR HUD still reads N survivors, N is where we placed. No-op outside a game,
     * and once per game — the last-two case is recorded by the HUD watcher instead.
     */
    public void recordDeathPlacement() {
        if (lmsInGame && !lmsPlacementRecorded) {
            recordLmsPlacement(Math.max(lmsSurvivors, 2));
        }
    }

    public boolean inGame() {
        return lmsInGame;
    }

    public void bind(Supplier<PluginConfigResponse> pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    /* ------------------------- LMS placement tracking ------------------------- */
    // Last Man Standing is "BR" (battle royale) in the cache. While the BR_INGAME varbit is up
    // we sample the HUD's survivor counter every tick; dying while it reads N means we placed
    // Nth (we were one of the N still standing). Winning never fires our death — the game just
    // ends — so a session that closes with the last reading at "1 survivor" is a win.
    private volatile boolean lmsInGame;

    private volatile int lmsSurvivors;

    private volatile int lmsKills;

    private volatile boolean lmsPlacementRecorded; // one placement per game

    public void trackLmsTick() {
        boolean inGame = client.getVarbitValue(VarbitID.BR_INGAME) == 1;
        if (inGame) {
            if (!lmsInGame) {
                lmsInGame = true;
                lmsPlacementRecorded = false;
                lmsSurvivors = 0;
                lmsKills = 0;
            }
            Widget survivors = client.getWidget(InterfaceID.BrOverlay.SURVIVOR_COUNT);
            if (survivors != null && !survivors.isHidden()) {
                int n = parseLeadingInt(survivors.getText());
                if (n > 0) {
                    lmsSurvivors = n;
                }
            }
            lmsKills = client.getVarbitValue(VarbitID.BR_KILLCOUNT);
        } else if (lmsInGame) {
            lmsInGame = false;
            // Game ended without us dying (a death records placement via onActorDeath). A win fires no
            // death — the game just ends. The last survivor reading is usually 1, but landing the final
            // kill can end the game before a tick samples "1", leaving a stale 2. So treat "ended in the
            // final duel (1 or 2 left) without ever dying" as a win; 3+ still standing means an x-log /
            // spectate exit and records nothing. (0 = never got a reading — ambiguous, skip.)
            if (!lmsPlacementRecorded && lmsSurvivors >= 1 && lmsSurvivors <= 2) {
                recordLmsPlacement(1);
            }
        }
    }

    /**
     * Submits a qualifying LMS finish to every LMS tile whose placement cap covers it,
     * with a baked "Placed Nth — K kills" proof screenshot. Runs at most once per game.
     */
    public void recordLmsPlacement(int placement) {
        lmsPlacementRecorded = true;
        if (!config.autoSubmit() || pluginConfig.get() == null || pluginConfig.get().trackedLms == null
                || pluginConfig.get().trackedLms.isEmpty()) {
            return;
        }
        if (!AnvilOverlay.isEventActive(pluginConfig.get().event)) {
            return;
        }
        final int kills = lmsKills;
        final String place = ordinal(placement);
        for (TrackedLms tile : pluginConfig.get().trackedLms) {
            if (tile.completed) {
                continue;
            }
            int cap = Math.max(1, tile.placementCap);
            if (placement > cap) {
                continue;
            }
            log.info("Tracked LMS placement: {} ({} kills) for '{}' (cap top {})", place, kills, tile.label, cap);
            chat.send("Tracked LMS placement: " + place + " — " + tile.label);
            String detail = "Placed " + place + " — " + kills + (kills == 1 ? " kill" : " kills")
                    + "  (needs top " + cap + ")";
            proofs.captureAndSubmitProof(tile.tileId, tile.label, 1, null, "BINGO LMS", detail,
                    "[Auto] LMS " + place + " place, " + kills + (kills == 1 ? " kill" : " kills")
                            + " — detected by RuneLite plugin", null);
        }
    }

    private static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) {
            return n + "th";
        }
        switch (n % 10) {
            case 1: return n + "st";
            case 2: return n + "nd";
            case 3: return n + "rd";
            default: return n + "th";
        }
    }

    /** Digits of a widget text like "5" or "Survivors: 5" (tags stripped); -1 when unparseable. */
    private static int parseLeadingInt(String text) {
        if (text == null) {
            return -1;
        }
        String digits = text.replaceAll("<[^>]*>", "").replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 3) {
            return -1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
