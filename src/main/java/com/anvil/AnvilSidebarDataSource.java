package com.anvil;

import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import java.util.function.Consumer;

/**
 * What the side panel reads from the plugin itself: the polled config (the clan picker, capabilities),
 * the logged-in character, pins, the starting shot, syncs and banner sounds. What it shows of the
 * clan and its boards is the site's (UiView); this is only what the client alone can know or do.
 */
public class AnvilSidebarDataSource
{
	private final Supplier<PluginConfigResponse> configSupplier;

	private final BingoApiClient apiClient;

	public AnvilSidebarDataSource(Supplier<PluginConfigResponse> configSupplier, BingoApiClient apiClient)
	{
		this.configSupplier = configSupplier;
		this.apiClient = apiClient;
	}

	/**
	 * The starting-shot action, bound by the plugin after construction (Guice builds this source
	 * before the plugin's own fields exist, so it can't be a constructor argument). Null in tests and
	 * anywhere the panel is driven without a live plugin — the sidebar then simply shows no button.
	 */
	private volatile Runnable startProofCapture;

	/** Bind the capture action. Idempotent; passing null unbinds. */
	public void setStartProofCapture(Runnable capture)
	{
		this.startProofCapture = capture;
	}

	/** The plugin behind the panel's buttons, bound after construction like the capture above. */
	private volatile AnvilPlugin plugin;

	public void setPlugin(AnvilPlugin plugin)
	{
		this.plugin = plugin;
	}

	/** Run {@code f} on the bound plugin; a no-op when none is bound (tests, a panel driven by hand). */
	private void withPlugin(Consumer<AnvilPlugin> f)
	{
		AnvilPlugin p = plugin;
		if (p != null)
		{
			f.accept(p);
		}
	}

	/** {@code f} of the bound plugin, or {@code none} when there isn't one. */
	private <T> T fromPlugin(java.util.function.Function<AnvilPlugin, T> f, T none)
	{
		AnvilPlugin p = plugin;
		return p == null ? none : f.apply(p);
	}

	/** Actions available for the clan on screen. Never null. */
	public PanelActions actionsFor()
	{
		AnvilPlugin p = plugin;
		if (p == null)
		{
			return PanelActions.NONE;
		}
		boolean profile = p.canSyncProfileFromPanel();
		if (!p.isAdmin())
		{
			return new PanelActions(false, profile, null);
		}
		// Admin — but the roster comes from the clan channel, so it has to be readable. The CACHED
		// answer: this runs while the panel paints, on the EDT, where asking the client directly is a
		// thread violation.
		boolean scrape = p.isClanRosterReadable();
		return new PanelActions(scrape, profile, scrape ? null : "Join your clan channel to sync the roster");
	}

	/** The logged-in character's row, or {@code null} to hide it (logged out, not signed in). */
	public AccountRow accountRow()
	{
		return fromPlugin(AnvilPlugin::accountRow, null);
	}

	/** Run a row action; {@code done} gets a one-line outcome, off the EDT. */
	public void accountAction(AccountRow.Action action, Consumer<String> done)
	{
		withPlugin(p -> p.accountAction(action, done));
	}

	/** Push the in-game clan roster to the home site. */
	public void syncRoster()
	{
		withPlugin(AnvilPlugin::syncClanRosterFromPanel);
	}

	/** Send this account's collection log + bests to the home site. */
	public void syncProfile()
	{
		withPlugin(AnvilPlugin::syncProfileNow);
	}

	/** Is this clip in the play cycle? */
	public boolean bannerSoundOn(String clip)
	{
		return fromPlugin(p -> p.bannerSoundSelected(clip), false);
	}

	/** Flip one clip in or out of the cycle. */
	public void toggleBannerSound(String clip)
	{
		withPlugin(p -> p.toggleBannerSound(clip));
	}

	/** Copy the sounds folder's path — hub releases may not open a file manager (see Clipboards). */
	public void copyBannerSoundsPath()
	{
		withPlugin(AnvilPlugin::copyBannerSoundsPath);
	}

	/** Import a .wav into {@code kind}'s clips folder. */
	public void importSounds(BannerSoundService.Kind kind)
	{
		withPlugin(p -> p.importSounds(kind));
	}

	/** {@code kind}'s clips; for missions, the folder that decides between a clip and the built-in chime. */
	public List<String> sounds(BannerSoundService.Kind kind)
	{
		return fromPlugin(p -> p.soundClips(kind), Collections.emptyList());
	}

	/**
	 * The STARTING SHOT this account still owes on the addressed clan's live event (site lib/startProof),
	 * or {@code null} when nothing is owed — which is the case on every event that doesn't ask for
	 * one, on sites that predate the feature, and the moment one is filed.
	 */
	public PluginConfigResponse.StartProof startProof()
	{
		PluginConfigResponse cfg = configSupplier.get();
		if (cfg == null || cfg.startProof == null || !cfg.startProof.required
			|| !cfg.startProof.drawn || !cfg.startProof.needsUpload)
		{
			return null;
		}
		// Only while the event is actually live — the same gate the in-game overlay uses, so the
		// panel can't ask for a shot of an event that has ended.
		return AnvilOverlay.isEventActive(cfg.event) ? cfg.startProof : null;
	}

	/**
	 * The starting shot's live requirements (session age, position) for the card, in display order.
	 * Empty where nothing is checked. Cheap and non-blocking: the panel polls it every second.
	 */
	public List<StartProofRules.Check> startProofChecks(PluginConfigResponse.StartProof proof)
	{
		return fromPlugin(p -> p.startProofChecks(proof), Collections.emptyList());
	}

	/** Will the plugin take the starting shot by itself once the checks pass? (config autoStartShot) */
	public boolean autoStartShot()
	{
		return fromPlugin(AnvilPlugin::autoStartShotEnabled, false);
	}

	/** Ask Shortest Path for a route to the starting shot's spot. */
	public void routeToStartSpot()
	{
		withPlugin(AnvilPlugin::routeToStartSpot);
	}

	/**
	 * Whether this event's tiles can be pinned as in-game widgets: the site serves them (capability
	 * "tile-tracker") and it is the event you're playing, or one still waiting to start (pinned early,
	 * it counts down and goes live at the whistle).
	 */
	public boolean canTrackTiles(int eventId, boolean preStart)
	{
		PluginConfigResponse cfg = configSupplier.get();
		return eventId > 0 && (preStart || cfg != null && cfg.event != null && cfg.event.id == eventId)
			&& fromPlugin(AnvilPlugin::supportsTileTracker, false)
			&& fromPlugin(AnvilPlugin::inGame, false);
	}

	/** The live board's event id, or 0 with none. */
	public int activeEventId()
	{
		PluginConfigResponse cfg = configSupplier.get();
		return cfg != null && cfg.event != null ? cfg.event.id : 0;
	}

	/** This client's newest count for a tile, or -1 (AnvilPlugin.tileCount). */
	public long tileCount(int tileId)
	{
		return fromPlugin(p -> p.tileCount(tileId), -1L);
	}

		/** The tiles pinned over the game on {@code eventId}. */
	public List<Integer> pinnedTiles(int eventId)
	{
		return eventId > 0 ? fromPlugin(p -> p.tileTracker().pinned(eventId), Collections.<Integer>emptyList())
			: Collections.<Integer>emptyList();
	}

	/** Signed in, and the first config answer hasn't come back (or failed) yet. */
	public boolean awaitingFirstConfig()
	{
		return fromPlugin(AnvilPlugin::awaitingFirstConfig, false);
	}

	/** Take every pin on {@code eventId} off the screen. */
	public void unpinAll(int eventId)
	{
		withPlugin(p -> p.tileTracker().clear(eventId));
	}

	public void setTileTracked(int eventId, int tileId, boolean on)
	{
		withPlugin(p -> p.setTrackedTile(eventId, tileId, on));
	}

	/** Take + file the starting shot. No-op where {@link #startProof()} is null. Never blocks the EDT. */
	public void captureStartProof()
	{
		Runnable capture = startProofCapture;
		if (capture != null)
		{
			capture.run();
		}
	}

	/** Whether the site builds the Lookup and You tabs ({@link UiView}). False ⇒ no tabs, and a lookup opens the browser. */
	public boolean supportsPluginUi()
	{
		return siteSupports("plugin-ui");
	}

	/**
	 * The config's capability list when there is a config; /api/version's when the person is in no
	 * clan (a 404 config carries none). The You and Lookup tabs are exactly what someone without a
	 * clan should still get.
	 */
	private boolean siteSupports(String capability)
	{
		PluginConfigResponse cfg = configSupplier.get();
		if (cfg != null)
		{
			return cfg.serverSupports(capability);
		}
		return apiClient.signedInWithoutClan() && apiClient.siteAdvertises(capability);
	}

	/** The game's name lists for Lookup's suggestions; {@code then} runs on the client thread. */
	public void lookupNames(Consumer<LookupSuggestions.Names> then)
	{
		AnvilPlugin p = plugin;
		if (p == null)
		{
			then.accept(LookupSuggestions.Names.EMPTY);
			return;
		}
		p.lookupNames(then);
	}

	/** The character logged in right now, or null at the login screen. */
	public String myRsn()
	{
		return apiClient.getCurrentRsn();
	}

	// ── The clan switcher's data ────────────────────────────────────────────────────────────────
	//
	// One Anvil serves every clan, so the Site URL names none of them and a person may hold seats in
	// several. Straight off the config this source already reads, so offering the dropdown costs no
	// request.

	/**
	 * Re-read the config if it's stale (always, on a clicked Refresh — often someone was just made a
	 * clan admin, so ask about that now too). Blocking; the panel calls it off the EDT.
	 */
	public void refreshConfig(boolean force)
	{
		withPlugin(p ->
		{
			p.refreshConfigForSidebar(force);
			if (force)
			{
				p.reprobeAdminNow();
			}
		});
	}

	/** Every clan the signed-in person holds a seat in. Empty on an older site, or before the first poll. */
	public List<PluginConfigResponse.ClanRef> clans()
	{
		PluginConfigResponse cfg = configSupplier.get();
		return cfg == null ? Collections.emptyList() : cfg.switchableClans();
	}

	/** The member's explicit pick, or "" when they are on Auto and the site is deciding. */
	public String chosenClan()
	{
		return fromPlugin(AnvilPlugin::getChosenClan, "");
	}

	/**
	 * The clan slug this plugin is actually addressing — a pick if there is one, else whatever the
	 * site resolved. "" before the first answer.
	 */
	public String activeClan()
	{
		return apiClient.getActiveClan();
	}

	/** The member picked a clan (or "" for Auto): persist it and refetch, without waiting. EDT-safe. */
	public void chooseClan(String slug)
	{
		withPlugin(p -> p.setChosenClan(slug));
	}

	// ---- Weekly competitions (SOTW/BOTW) as sidebar events ----------------------------------------

	// ── The logged-in character: whose it's tracked as, and the way out ──────────────────────────
	//
	// The Account Token names whoever set this RuneLite up, not whoever is playing. Every character
	// links to it by default (the one-click path); siblings and visitors use this row to say "not
	// mine", and to claim back a character that's linked to somebody else.

	/** One line about the logged-in character. */
	@RequiredArgsConstructor
	public static final class AccountRow
	{
		public enum State
		{
			/** Linked to (or linking to) the signed-in Anvil account. Offers Not my account. */
			TRACKING,
			/** Marked Not my account on this computer; nothing is sent. Offers Track it again. */
			PAUSED,
			/** Claimed by another Anvil account; nothing tracks for this token. Offers It's mine. */
			OTHER,
		}

		public enum Action
		{
			NOT_MINE,
			TRACK_AGAIN,
			DISPUTE,
		}

		public final String rsn;
		public final State state;

	}

	/**
	 * What the panel may DO for the addressed clan.
	 *
	 * <p>A roster sync scrapes the clan channel you are standing in, so it needs an admin with that
	 * channel readable. A profile sync goes to the site the plugin is authenticated against.
	 */
	public static final class PanelActions
	{
		public static final PanelActions NONE = new PanelActions(false, false, null);

		/** Show "Sync clan roster": admin, at home, with a readable clan channel. */
		public final boolean canSyncRoster;
		/** Show "Sync profile": the site takes profile data and it's the one we're signed in to. */
		public final boolean canSyncProfile;
		/** Why the roster button is absent, when the reason is worth saying. Null when it isn't. */
		public final String rosterNote;

		public PanelActions(boolean canSyncRoster, boolean canSyncProfile, String rosterNote)
		{
			this.canSyncRoster = canSyncRoster;
			this.canSyncProfile = canSyncProfile;
			this.rosterNote = rosterNote;
		}
	}
}
