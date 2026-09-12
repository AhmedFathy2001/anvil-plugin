package com.anvil.io;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.TrackedDrop;
import com.anvil.ui.AnvilSidebarPanel;
import com.anvil.util.AnvilChat;
import com.anvil.util.TaskRunner;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.SoundEffectID;
import net.runelite.client.game.ItemManager;

/**
 * The in-game banner and the sound it can play, plus the folder buttons beside them.
 *
 * <p>Sounds are the player's own WAV files in their own RuneLite directory — nothing ships with the
 * plugin and nothing is downloaded. The folder buttons copy the path to the clipboard rather than
 * opening it, because opening a local path is a restricted API for hub releases and a button that
 * promises to open something and does not is worse than one that does not promise.</p>
 */
@Slf4j
@Singleton
public class BannerSoundActions
{
    protected final Client client;
    protected final ClientThread clientThread;
    protected final AnvilConfig config;
    protected final BingoApiClient apiClient;
    protected final ConfigManager configManager;
    protected final ItemManager itemManager;
    protected final AnvilChat chat;
    protected final TaskRunner tasks;
    private final com.anvil.ui.BingoClogBannerOverlay clogBanner;
    private final BannerSoundService bannerSound;
    private final PendingSubmissionStore pendingSubmissionStore;

    /** The live event config. A supplier, because the object is replaced on every poll. */
    protected Supplier<PluginConfigResponse> pluginConfig = () -> null;

    @Inject
    BannerSoundActions(Client client, ClientThread clientThread, AnvilConfig config, BingoApiClient apiClient,
            ConfigManager configManager, ItemManager itemManager, AnvilChat chat, TaskRunner tasks,
            com.anvil.ui.BingoClogBannerOverlay clogBanner, BannerSoundService bannerSound,
            PendingSubmissionStore pendingSubmissionStore) {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.apiClient = apiClient;
        this.configManager = configManager;
        this.itemManager = itemManager;
        this.chat = chat;
        this.tasks = tasks;
        this.clogBanner = clogBanner;
        this.bannerSound = bannerSound;
        this.pendingSubmissionStore = pendingSubmissionStore;
    }

    /** Told when a banner fired locally, so the team-completion banner does not repeat it. */
    private java.util.function.IntConsumer shownLocally = id -> { };

    public void bindShownLocally(java.util.function.IntConsumer shownLocally) {
        this.shownLocally = shownLocally;
    }

    public void bind(Supplier<PluginConfigResponse> pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    // One-shot guard so the "no banner clips yet" nudge prints at most once per session.
    private boolean bannerSoundHintShown;

    public void showBingoToast(TrackedDrop drop, int current, int required) {
        clogBanner.show(drop.label, current, required);
        playBannerSound();
        if (current >= required) {
            // This drop completed the tile locally — suppress the duplicate team-completion banner.
            shownLocally.accept(drop.tileId);
        }
    }

    /**
     * Opens the OS file picker to add banner-sound WAVs. Wired to the "Banner
     * sounds" button in the Anvil side panel (visible to all users) —
     * see AnvilSidebarPanel.buildPanelActions.
     */
    public void importBannerSounds() {
        bannerSound.importSounds(names -> {
            // New files join the cycle automatically (empty allowlist = all clips play). Users curate
            // which ones cycle by tapping them in the tab list; no need to touch config on import.
            chat.send("Added to banner sounds: " + String.join(", ", names)
                    + ". All clips cycle by default — tap one in the Anvil side panel to toggle it on/off.");
        });
    }

    /**
     * Clip filenames in the user's sounds folder — backs the in-tab manager
     * list.
     */
    public List<String> bannerSoundClips() {
        return bannerSound.listClips();
    }

    /**
     * Whether {@code name} is currently in the play cycle (for the tab's on/off
     * rendering).
     */
    public boolean bannerSoundSelected(String name) {
        return bannerSound.isSelected(name);
    }

    /**
     * Toggles a clip in/out of the play cycle from the tab. The cycle is
     * persisted as the comma-separated 'bannerSoundClip' allowlist; an empty
     * allowlist means "all clips play", so we materialise the full set before
     * removing one, and collapse back to empty when everything's on.
     */
    public void toggleBannerSound(String name) {
        List<String> all = bannerSound.listClips();
        Set<String> sel = new LinkedHashSet<>();
        String csv = config.bannerSoundClip();
        if (csv != null && !csv.trim().isEmpty()) {
            for (String part : csv.split(",")) {
                String s = part.trim();
                if (!s.isEmpty()) {
                    sel.add(s);
                }
            }
        } else {
            sel.addAll(all); // blank = everything on; materialise so we can switch one off
        }

        // Toggle by case-insensitive filename match.
        String match = null;
        for (String s : sel) {
            if (s.equalsIgnoreCase(name)) {
                match = s;
                break;
            }
        }
        if (match != null) {
            sel.remove(match);
        } else {
            sel.add(name);
        }

        // If every clip ends up selected, store blank ("all") to keep the value tidy and future-proof
        // against newly-added files (which should default to on).
        String value = (sel.size() == all.size() && all.size() > 0) ? "" : String.join(", ", sel);
        configManager.setConfiguration("osrsbingo", "bannerSoundClip", value);
    }

    /**
     * Puts the sounds folder's path on the clipboard, for deleting clips by hand.
     *
     * <p>It used to open the folder. LinkBrowser::open is restricted for plugin-hub releases, so the
     * player pastes the path instead — and is told that's what happened, because a button that
     * silently does something other than what it says is worse than one that does less.
     */
    public void copyBannerSoundsPath() {
        bannerSound.copyFolderPath();
        chat.send("Sounds folder path copied — paste it into your file manager.");
    }

    /** Same, for the folder holding proofs that haven't uploaded yet (baked PNGs + metadata). */
    public void copyPendingProofsPath() {
        pendingSubmissionStore.copyFolderPath();
        chat.send("Saved-proofs folder path copied — paste it into your file manager.");
    }

    /** Proofs still waiting to upload — drives the "Saved proofs" row in the Anvil side panel. */
    public int pendingProofCount() {
        return pendingSubmissionStore.count();
    }

    /**
     * Plays the banner sound and, the first time it fires with sound enabled
     * but no clips installed, nudges the user to the "Banner sounds" button in
     * the Anvil side panel. Fires at most once per session so it never
     * spams.
     */
    public void playBannerSound() {
        bannerSound.play();
        if (!bannerSoundHintShown && config.bannerSound() && !bannerSound.hasClips()) {
            bannerSoundHintShown = true;
            chat.send("Banner sound is on but you have no clips yet — open the Anvil side panel "
                    + "and click \"Banner sounds\" to add a .wav.");
        }
    }

    /**
     * The mission cue. A mission DROPPING is the opposite kind of news from a tile being finished, so
     * sharing the completion clip made the two indistinguishable. This is a short built-in game chime
     * instead — no clip to install, and unmistakably not the completion sound. Turning the option off
     * falls back to the banner clip, for anyone who liked it that way.
     *
     * Runs from the config-poll executor, so the actual play hops to the client thread.
     *
     * @param claimed false when a mission is announced, true when someone claims one — a slightly
     *                different chime, so "new thing to do" and "someone beat you to it" don't sound alike.
     */
    public void playMissionSound(boolean claimed) {
        if (!config.missionSound()) {
            playBannerSound();
            return;
        }
        if (!config.bannerSound()) {
            return; // the master "make noise at me" switch still wins
        }
        final int id = claimed ? SoundEffectID.GE_COLLECT_BLOOP : SoundEffectID.GE_ADD_OFFER_DINGALING;
        clientThread.invoke(() -> client.playSoundEffect(id));
    }
}
