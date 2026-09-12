package com.anvil.notify;

import com.anvil.AnvilConfig;
import com.anvil.api.PluginConfigResponse;
import com.anvil.api.dto.TrackedCombatTask;
import com.anvil.ui.AnvilOverlay;
import com.anvil.util.AnvilChat;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.VarbitID;

/**
 * The settings that quietly stop the plugin working, said once.
 *
 * <p>Every one of these is a switch somewhere OTHER than the plugin's own config — an in-game chat
 * option, an account-wide toggle — that the plugin cannot change and cannot work around, and whose
 * effect is silence. A member with loot notifications off sees no drops credited and no error, which
 * reads as the plugin being broken.</p>
 *
 * <p><b>Once per session, and only when it matters.</b> A nudge fires when the board actually needs
 * the thing that is switched off: telling somebody to enable drop lines when their board has no drop
 * tiles is noise, and noise is what makes the real one get scrolled past.</p>
 */
@Slf4j
@Singleton
public class NudgeService
{
    private final Client client;
    private final AnvilConfig config;
    private final AnvilChat chat;
    private final net.runelite.client.callback.ClientThread clientThread;
    private final AnvilEmbeds embeds;

    private final Supplier<PluginConfigResponse> pluginConfig;

    @Inject
    NudgeService(Client client, AnvilConfig config, AnvilChat chat,
            net.runelite.client.callback.ClientThread clientThread, AnvilEmbeds embeds,
        Supplier<PluginConfigResponse> pluginConfig) {
        this.client = client;
        this.config = config;
        this.chat = chat;
        this.clientThread = clientThread;
        this.embeds = embeds;
        this.pluginConfig = pluginConfig;
    }


    /** A new login gets its nudges back — it may be a different account with different settings. */
    public void onLogout() {
        autoSubmitNudgeSent = false;
        caRepeatNudgeSent = false;
        lootNotifyNudgeSent = false;
    }

    // One nudge per session about the in-game "Repeat completion" CA setting.
    /** One per login: the event is live and auto-submit is off, so none of it is being counted. */
    private boolean autoSubmitNudgeSent;

    private boolean caRepeatNudgeSent;

    // One nudge per session about the in-game loot drop notifications (rare-drop post dependency).
    private boolean lootNotifyNudgeSent;

    /**
     * Is a live event being tracked into a void because auto-submit is off?
     *
     * <p>THE SWITCH THAT TURNS EVERYTHING OFF. "Auto Submit Drops" reads like it governs drops, and
     * it governs the lot: drop, value, kill, timed, LMS, gain, deathless, diary and combat-task
     * tiles all check it, and so does {@link #statPushAllowed} — so a member who flicked it off
     * months ago, or who never looked at the config because the plugin was set up for them, plays a
     * whole bingo contributing nothing. Nothing on the board looks broken from their side: tiles
     * simply never move, which is indistinguishable from not having got the drop.</p>
     *
     * <p>Only while an event is actually RUNNING. Outside one the toggle costs nothing, and a plugin
     * that lectures about settings for something that is not happening is noise.</p>
     */
    public static boolean autoSubmitBlocksEvent(PluginConfigResponse cfg, boolean autoSubmit) {
        return !autoSubmit && cfg != null && AnvilOverlay.isEventActive(cfg.event);
    }

    /**
     * Does this board have tiles that can only credit off the in-game drop-notification line?
     *
     * <p>Drop and value tiles both do, for the corpse-looted bosses whose loot bypasses every loot
     * event the client raises (see {@link #creditDropFromChat}). The rest of the board does not care,
     * but the plugin cannot tell in advance which boss a tile's drop will come from, and the cost of
     * asking is one chat line against a silent 50m fang.</p>
     */
    public static boolean eventNeedsDropLines(PluginConfigResponse cfg) {
        if (cfg == null || !AnvilOverlay.isEventActive(cfg.event)) {
            return false;
        }
        return (cfg.trackedDrops != null && !cfg.trackedDrops.isEmpty())
                || (cfg.trackedValues != null && !cfg.trackedValues.isEmpty());
    }

    /** One chat nudge per login when a live event is being played with auto-submit switched off. */
    public void maybeNudgeAutoSubmit() {
        if (autoSubmitNudgeSent || !autoSubmitBlocksEvent(pluginConfig.get(), config.autoSubmit())) {
            return;
        }
        autoSubmitNudgeSent = true;
        String event = pluginConfig.get().event != null && pluginConfig.get().event.name != null
                ? pluginConfig.get().event.name : "this event";
        chat.send("\"Auto Submit Drops\" is off in the Anvil plugin config — nothing you do in \""
                + event + "\" is being counted until you turn it back on.");
    }

    /**
     * One-time (per session) reminder to enable the in-game "Repeat completion" Combat
     * Achievement setting when the active event has incomplete CA tiles — without it, tasks
     * the player already owns never re-fire the completion line, so those tiles can never
     * track for them. Reads the setting's varbit, so the check runs on the client thread.
     */
    public void maybeNudgeCaRepeatSetting() {
        if (caRepeatNudgeSent || pluginConfig.get() == null || pluginConfig.get().trackedCombatTasks == null
                || pluginConfig.get().trackedCombatTasks.isEmpty() || !AnvilOverlay.isEventActive(pluginConfig.get().event)) {
            return;
        }
        boolean anyIncomplete = false;
        for (TrackedCombatTask t : pluginConfig.get().trackedCombatTasks) {
            if (t != null && t.currentAmount < t.requiredAmount) {
                anyIncomplete = true;
                break;
            }
        }
        if (!anyIncomplete) {
            return;
        }
        clientThread.invokeLater(() -> {
            if (client.getGameState() != GameState.LOGGED_IN) {
                return;
            }
            if (client.getVarbitValue(VarbitID.CA_TASK_RECOMPLETION_NOTIFICATIONS) == 1) {
                return; // setting already on — nothing to remind about
            }
            caRepeatNudgeSent = true;
            chat.send("This event has Combat Achievement tiles — enable Settings > Combat Achievements"
                    + " > \"Repeat completion\" so tasks you've already done can still count.");
        });
    }

    /**
     * One-time (per session) reminder to enable the in-game loot drop notifications when clan
     * rare-drop posts are on. The "&lt;player&gt; received a drop: …" chat line those posts key
     * off for corpse-boss spill loot (Maggot King uniques — see creditDropFromChat) is Jagex's
     * opt-in loot notification: with the setting off, or its value threshold above the drop's
     * price, the line never prints and the plugin has nothing to parse — a 50m fang can pass
     * completely silently. Varbit read requires the client thread.
     */
    public void maybeNudgeLootNotifications() {
        if (lootNotifyNudgeSent) {
            return;
        }
        // TWO REASONS TO CARE, and only one of them used to be asked about. Clan rare-drop posts
        // need the line, and so does a BOARD with drop or value tiles on it: the same corpse-boss
        // loot that never posts also never credits (creditDropFromChat is what feeds both). Gating
        // the reminder on the clan-post toggle meant a member who had turned posts off — or whose
        // clan doesn't run them — played a bingo whose spill-loot tiles could not fire, and was
        // never told why.
        boolean forPosts = config.notifyRareDrops() && embeds.notifyEnabled("rareDrops");
        boolean forTiles = eventNeedsDropLines(pluginConfig.get());
        if (!forPosts && !forTiles) {
            return;
        }
        clientThread.invokeLater(() -> {
            if (client.getGameState() != GameState.LOGGED_IN) {
                return;
            }
            boolean settingOn = client.getVarbitValue(VarbitID.OPTION_LOOTNOTIFICATION_ON) == 1;
            // The plugin's own posting floor (enforced minimum 1m) — an in-game threshold above
            // it would swallow lines for drops the clan channel wants to see.
            long plugFloor = Math.max(1_000_000, Math.max(0, config.rareDropMinValue()));
            long gameThreshold = client.getVarbitValue(VarbitID.OPTION_LOOTNOTIFICATION_VALUE);
            // The threshold half is a CLAN-POST concern only: it is measured against the posting
            // floor, and there is no equivalent number for a tile — a tile wants whatever the drop
            // happens to be worth. So a board-driven reminder ends at "the setting is off".
            if (settingOn && (!forPosts || gameThreshold <= plugFloor)) {
                return; // configured fine — the attribution line will fire for qualifying drops
            }
            lootNotifyNudgeSent = true;
            if (!settingOn) {
                chat.send(forTiles
                        ? "Enable Settings > Chat > \"Loot drop notifications\" — this board has drop tiles, and"
                        + " bosses that spill their loot (Maggot King, Araxxor) only announce it on that line."
                        : "Enable Settings > Chat > \"Loot drop notifications\" — clan rare-drop posts for corpse-boss"
                        + " loot (Maggot King uniques) rely on that chat line.");
            } else {
                chat.send("Your in-game loot notification threshold is above the clan rare-drop floor — lower it"
                        + " (Settings > Chat > Loot drop notifications) or drops like Maggot King uniques won't post.");
            }
        });
    }
}
