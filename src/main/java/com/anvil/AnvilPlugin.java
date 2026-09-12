package com.anvil;

import com.anvil.api.BingoApiClient;
import com.anvil.notify.AchievementNotifier;
import com.anvil.notify.MomentsService;
import com.anvil.notify.PetNotifier;
import com.anvil.track.RecapCounters;
import com.anvil.chat.ChatRouter;
import com.anvil.clan.ClanRosterService;
import com.anvil.clog.ProfileSync;
import com.anvil.session.SessionIdentity;
import com.anvil.session.SessionLifecycle;
import com.anvil.session.SettingsRouter;
import com.anvil.track.AccountProgressPush;
import com.anvil.track.AchievementTiles;
import com.anvil.track.LmsTracker;
import com.anvil.track.StatPushService;
import com.anvil.track.DropTracker;
import com.anvil.track.GainTracker;
import com.anvil.track.KillTracker;
import com.anvil.track.CombatRouter;
import com.anvil.track.LootRouter;
import com.anvil.track.PartyTracker;
import com.anvil.detect.ActivityStats;
import com.anvil.io.DebugSupportLog;
import com.anvil.io.DiscordWebhookClient;
import com.anvil.ui.AnvilOverlay;
import com.anvil.ui.AnvilUi;
import com.anvil.ui.GameTabButtons;
import com.anvil.ui.view.Standing;
import com.anvil.util.TaskRunner;
import com.google.inject.Binder;
import java.util.Collection;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.events.WorldChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;

@Slf4j
@PluginDescriptor(
        name = "Anvil",
        description = "Companion plugin for the Anvil clan-events platform — codeword overlay, auto-submits tracked bingo drops, clan Discord notifications",
        tags = {"anvil", "bingo", "overlay", "drops", "loot", "clan", "event"}
)
public class AnvilPlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private AnvilConfig config;

    @Inject
    private AnvilOverlay overlay;

    @Inject
    private BingoApiClient apiClient;

    @Inject
    private DebugSupportLog supportLog;

    /** Who is logged in, and whether the site agrees. */
    @Inject
    private SessionIdentity session;

    /** One chat line, read by everything that cares — in an order that matters. */
    @Inject
    private ChatRouter chatRouter;

    /** The collection log and personal bests, on their way to the profile page. */
    @Inject
    private ProfileSync profileSync;

    /** Kill counts, XP and varbit counters pushed as they happen. */
    @Inject
    private StatPushService statPush;

    /** Quest points, combat achievements, diary counts. */
    @Inject
    private AccountProgressPush accountProgress;

    /** Tiles credited by what the account achieved rather than what it looted. */
    @Inject
    private AchievementTiles achTiles;

    /** Last Man Standing, which is a different game inside the game. */
    @Inject
    private LmsTracker lms;

    // ── what the plugin watches for, one tracker per kind of tile ──────────────────────────
    @Inject
    private DropTracker drops;

    @Inject
    private KillTracker kills;

    @Inject
    private GainTracker gains;

    /** Who is in the instance with us, and whether anybody died. */
    @Inject
    private PartyTracker party;

    @Inject
    private PetNotifier pets;

    @Inject
    private AchievementNotifier achievements;

    /** A finished quest, read off the reward scroll the game draws to announce it. */
    @Inject
    private com.anvil.notify.QuestNotifier quests;

    @Inject
    private MomentsService moments;

    /** The cosmetic end-of-event numbers. Never scoring. */
    @Inject
    private RecapCounters counters;

    /** Our own background thread for blocking network work. See {@link TaskRunner}. */
    @Inject
    private TaskRunner tasks;

    /**
     * Who this account is to the clan's site, and the roster push that answer gates.
     *
     * <p>Was a hundred-odd lines and eleven fields here, all of it about one question the plugin
     * itself never asks — see {@link ClanRosterService}.</p>
     */
    @Inject
    private ClanRosterService roster;

    /** The two "Anvil" buttons in the game's own title bars — collection log, and clan window. */
    @Inject
    private GameTabButtons tabButtons;

    /** What the plugin's life starts and ends, and what a login starts and a logout ends. */
    @Inject
    private SessionLifecycle lifecycle;

    /** Two overlays, the sidebar, the two in-game title-bar buttons, and two hotkeys. */
    @Inject
    private AnvilUi ui;

    /** Four overlapping loot events, and the rules about which of them may count a kill. */
    @Inject
    private LootRouter loot;

    /** Who is fighting whom, and who died. */
    @Inject
    private CombatRouter combat;

    /** A plugin setting changed, and what has to happen before the next poll. */
    @Inject
    private SettingsRouter settings;

    @Override
    protected void startUp() {
        ui.mount(this);
        lifecycle.onStartUp();
    }

    @Override
    protected void shutDown() {
        ui.unmount();
        lifecycle.onShutDown();
    }

    @Subscribe
    public void onCommandExecuted(CommandExecuted event) {
        String cmd = event.getCommand();
        if (cmd != null && cmd.equalsIgnoreCase("anvillog")) {
            supportLog.export();
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        accountProgress.onStatChanged(event.getSkill(), event.getLevel(), event.getXp());
    }

    /** See {@link AnvilModule} — the bindings live there so they cannot read a this.-field. */
    @Override
    public void configure(Binder binder) {
        binder.install(new AnvilModule());
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        settings.onConfigChanged(event.getGroup(), event.getKey());
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        // The clan window. Its header is where Wise Old Man puts "Sync WOM Group", and ours goes
        // beside it — placed against whatever is already there rather than at a fixed offset.
        //
        // Deferred a tick: the group has loaded, but the header's children (including other plugins'
        // buttons, which is the whole thing we measure against) are not necessarily built yet.
        if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.CLANS_INFO) {
            tabButtons.onClanWindowLoaded();
        }
        if (event.getGroupId() == AchievementNotifier.questScrollGroup()) {
            // The scroll's text child isn't populated yet on the load event — read it next tick,
            // with a couple of retries in case the text lands late.
            quests.scheduleQuestScrollRead(3);
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event) {
        // Gain tiles: trade/bank items can land the tick their interface closes — keep those
        // inventory changes suppressed (see onItemContainerChanged).
        int g = event.getGroupId();
        if (g == InterfaceID.BANKMAIN || g == InterfaceID.BANK_DEPOSITBOX
                || g == InterfaceID.GE_OFFERS || g == InterfaceID.GE_COLLECT
                || g == InterfaceID.TRADEMAIN || g == InterfaceID.TRADECONFIRM
                || g == InterfaceID.SEED_VAULT) {
            gains.noteInterfaceClosed(client.getTickCount());
        }
    }

    /**
     * World hop — re-read whether we're on a seasonal world. Login already stamps it, but hopping
     * between a main and a league world mid-session doesn't go through login, and the direction that
     * matters most is hopping OFF: without this, main-game drops would keep posting to the Leagues
     * channel for the rest of the session.
     */
    @Subscribe
    public void onWorldChanged(WorldChanged event) {
        apiClient.setSeasonal(session.onSeasonalWorld());
    }

    // --- Whole-log collection sync ---------------------------------------------------------------
    // Opening the collection log and toggling its Search makes the SERVER transmit every entry, one
    // script fire per item — the whole log without the player clicking a single page. The technique
    // is WikiSync's (BSD-2, weirdgloop/WikiSync); RuneProfile ships the same three calls.

    /** One fire per transmitted item: args[1] = item id, args[2] = quantity. */
    private static final int COLLECTION_DELAYED_TRANSMIT = 4100;

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (event.getScriptId() == ScriptID.COLLECTION_DRAW_LIST) {
            profileSync.onClogDrawn();
            tabButtons.renderClogButton();
        } else if (event.getScriptId() == ProfileSync.setupScriptId()) {
            tabButtons.renderClogButton();
            // Opt-in until the trick has been proven on a real client: an unguarded version of this
            // recursed through the interface scripts and crashed the game. The button asks for the
            // same thing deliberately, which is the safe way to try it.
            if (config.autoFullClogSync() || profileSync.transmitRequested()) {
                profileSync.onClogSetup();
            }
        }
    }

    @Subscribe
    public void onScriptPreFired(ScriptPreFired event) {
        if (event.getScriptId() != COLLECTION_DELAYED_TRANSMIT) {
            return;
        }
        // Viewing someone else's log through a POH adventure log fires the same script with THEIR
        // items. Storing those would overwrite this account's log with a stranger's.
        if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1) {

            return;
        }
        Object[] args = event.getScriptEvent().getArguments();
        if (args == null || args.length < 3) {
            return;
        }
        try {
            profileSync.onClogItem((int) args[1], (int) args[2], System.currentTimeMillis());
        } catch (ClassCastException e) {
            // A game update changed the script's shape — stop rather than file nonsense as a log.
            log.debug("Collection transmit args weren't (id, quantity); ignoring");

        }
    }

    /**
     * Finishing a clue or a Colosseum run moves a counter the site can score a tile on, so report it
     * now rather than at the next hiscores sweep. Filtered to the handful of ids we read before
     * anything else happens — this event fires constantly, and the check is a short array scan.
     */
    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        if (ActivityStats.isTrigger(event.getVarbitId(), event.getVarpId())) {
            statPush.maybeQueueActivityPush();
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        tabButtons.onGameTick();
        profileSync.onGameTick();
        roster.onGameTick();

        // Play time for the recap. Counted from ticks rather than wall-clock so it measures time
        // actually in-game — a client left open on the login screen doesn't earn anyone an award.
        counters.recordEventTick();
        // Safety re-read of the activity counters. onVarbitChanged is what makes a finished clue
        // land in seconds; this catches anything that moved without one reaching us — most obviously
        // the counters that were already set before we logged in.
        statPush.onGameTick();
        party.onGameTick();
        achievements.onGameTick(achTiles.drainPendingCaTasks());
        // Gain tiles: diff held items (inventory + worn) once per tick, after any equip/unequip
        // has updated both containers, so a gear move never reads as a gain.
        if (gains.isDirty()) {
            gains.clearDirty();
            gains.updateHeldItemGains();
        }
        lms.trackLmsTick();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        lifecycle.onGameStateChanged(event.getGameState());
    }

    /**
     * Server-authoritative NPC loot (the in-game loot tracker's clientscript). The loot
     * signal for corpse-looted bosses — Araxxor, Maggot King's stomach loot — where the
     * client-side despawn inference behind NpcLootReceived never fires. NOT sufficient on
     * its own: loot that bypasses the in-game tracker (Maggot King's spill-out uniques)
     * only surfaces in the drop-attribution chat line — see creditDropFromChat. For
     * regular NPCs it can double-fire alongside NpcLootReceived; the per-(tile,item)
     * credit dedup and the per-item rare-drop dedup both absorb that. Kill counting stays
     * on NpcLootReceived + the Jagex KC chat line (which already covers these bosses), so
     * kills never double.
     */
    /**
     * One chat line, read by everything that cares — in an order that matters.
     *
     * <p>The kill-count branch runs before the collection-log branch, because the unlock is stamped
     * with the count the KC line just recorded; splitting them into unordered listeners would stamp
     * every unlock with the PREVIOUS kill's count. See {@link ChatRouter}.</p>
     */
    @Subscribe
    public void onChatMessage(ChatMessage event) {
        chatRouter.onChatMessage(event);
    }

    @Subscribe
    public void onServerNpcLoot(ServerNpcLoot event) {
        if (event.getComposition() == null) {
            return;
        }
        loot.onServerNpcLoot(event.getComposition().getName(), event.getItems());
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        loot.onNpcLootReceived(event.getNpc().getName(), event.getItems());
    }

    @Subscribe
    public void onLootReceived(LootReceived event) {
        loot.onLootReceived(event);
    }

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event) {
        loot.onPlayerLootReceived(event.getPlayer().getName(), event.getItems());
    }

    /**
     * Something acquired or dropped us as its target.
     *
     * <p>The only attribution the client offers for incoming damage: a hitsplat says how much and
     * what type, never who. Keeping the set of things currently on us is what lets a death name the
     * thing that killed it rather than the thing it was killing (DeathAttribution).
     */
    @Subscribe
    public void onInteractingChanged(InteractingChanged event) {
        combat.onInteractingChanged(event.getSource(), event.getTarget());
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        combat.onHitsplatApplied(event.getActor(), event.getHitsplat());
    }

    /* ----------------------------- Item-gain tiles ----------------------------- */
    /**
     * Counts tracked items appearing in the inventory (fishing catches, cooked food, jarred
     * implings) toward gain tiles. Diffs each inventory snapshot against the previous one;
     * gains while a bank/GE/deposit/trade/seed-vault interface is open (or just closed), or
     * right after a ground "Take", are recorded but never credited — those are moves, not
     * gathers. Unequipping tracked wearables still reads as a gain; the baked running total
     * on the proof screenshot is the audit trail for that (same trust model as kill tiles).
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        // Flag inventory OR worn changes; the actual diff runs once on the next onGameTick, after
        // both containers have settled. Equipping/unequipping touches both in the same tick, so
        // diffing here per-container would read the unequip's inventory bump as a phantom gain.
        int id = event.getContainerId();
        if (id == net.runelite.api.gameval.InventoryID.INV
                || id == net.runelite.api.gameval.InventoryID.WORN) {
            gains.markDirty();
        }
    }

    /**
     * Ground-pickup guards: a "Take" (or a Telekinetic Grab cast) makes a later inventory
     * change look like a fresh gain when it's really floor loot.
     */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if ("Take".equalsIgnoreCase(event.getMenuOption())) {
            gains.noteGroundTake(client.getTickCount());
        } else if ("Cast".equalsIgnoreCase(event.getMenuOption())
                && event.getMenuTarget() != null
                && event.getMenuTarget().contains("Telekinetic Grab")) {
            gains.noteTelegrab(client.getTickCount());
        }
    }

    /* -------------------------------------------------------------- */
 /* Player/account helpers                                          */
 /* -------------------------------------------------------------- */

    /* -------------------------------------------------------------- */
    /* Recap "fun stat" counters — deaths, total loot value, PvP     */
    /* kills. Cosmetic superlatives only; never touch scoring.        */
    /* -------------------------------------------------------------- */

    // ── Highlight feed ────────────────────────────────────────────────────────────────────────────
    //
    // Everything below reports; nothing below decides. Which competition week or board a moment
    // belongs to, whether an item counts as a unique, and which pets belong to which skill are all
    // the site's business (src/lib/moments.ts) — so this sends generously and expects most of it to
    // be discarded, and a clan changing any of those rules costs no plugin release.

    /* -------------------------------------------------------------- */
 /* Clan notifications — deaths, rare drops, pets (posted direct to */
 /* Discord, independent of bingo state). See DiscordWebhookClient. */
 /* -------------------------------------------------------------- */
    @Subscribe
    public void onActorDeath(ActorDeath event) {
        combat.onActorDeath(event.getActor());
    }

    // -- Profile sync: collection log + personal bests ---------------------------------------
    // Both are the player's OWN data going to the player's OWN clan site -- the pattern the hub
    // accepts (nothing here reads or reports anybody else). Everything is opt-out in config, and
    // nothing is read at all while the toggles are off.

    /**
     * The clan channel loaded (or changed) — the moment the in-game roster becomes readable.
     *
     * <p>Rosters used to drift until an admin remembered to press "Sync clan roster": someone joins,
     * the site doesn't know, and their drops land as a guest. The data is right here at login, so
     * take it. Admin-only (the site refuses anyone else's push anyway), at most once every half hour
     * per session, and silent — an automatic sync that announced itself in chat every login would be
     * worse than the drift.
     */
    @Subscribe
    public void onClanChannelChanged(ClanChannelChanged event) {
        if (event.isGuest() || !config.autoSyncClanRoster()) {
            return;
        }
        // The member list arrives just after the channel; a delay is cheaper than polling for it.
        tasks.runLater(() -> TaskRunner.safely("autoRosterSync", roster::autoSync),
                ClanRosterService.AUTO_ROSTER_DELAY_MS);
    }

}
