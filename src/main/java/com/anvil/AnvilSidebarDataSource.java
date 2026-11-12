package com.anvil;

import lombok.*;
import java.util.*;
import java.util.function.*;

/**
 * What the side panel reads from the plugin itself: the polled config (the clan picker, capabilities),
 * the logged-in character, pins, the starting shot, syncs and banner sounds. What it shows of the
 * clan and its boards is the site's (UiView); this is only what the client alone can know or do.
 */
@RequiredArgsConstructor
public class AnvilSidebarDataSource {
	final Supplier<PluginConfigResponse> configSupplier;

	final BingoApiClient apiClient;

	/**
	 * The starting-shot action, bound by the plugin after construction (Guice builds this source
	 * before the plugin's own fields exist, so it can't be a constructor argument). Null in tests and
	 * anywhere the panel is driven without a live plugin — the sidebar then simply shows no button.
	 */
	@Setter volatile Runnable startProofCapture;

	/** The plugin behind the panel's buttons, bound after construction like the capture above. */
	@Setter volatile AnvilPlugin plugin;

	/** On a seasonal world the site takes mode activity from: the panel shows the sharing switch. */
	boolean seasonalSharingOffered() {
		return fromPlugin(AnvilPlugin::seasonalSharingOffered, false);
	}

	boolean shareSeasonal() {
		return fromPlugin(AnvilPlugin::shareSeasonal, false);
	}

	void setShareSeasonal(boolean on) {
		withPlugin(p -> p.setShareSeasonal(on));
	}

	/** Hand the keyboard back to the game canvas. */
	void gameFocus() {
		withPlugin(AnvilPlugin::gameFocus);
	}

	/** The Feed tab came on screen, or went (AnvilPlugin.feedOnScreen). */
	void feedOnScreen(boolean on) {
		withPlugin(p -> p.feedOnScreen(on));
	}

	/** Run {@code f} on the bound plugin; a no-op when none is bound (tests, a panel driven by hand). */
	void withPlugin(Consumer<AnvilPlugin> f) {
		var p = plugin;
		if (p != null) f.accept(p);
	}

	/** {@code f} of the bound plugin, or {@code none} when there isn't one. */
	<T> T fromPlugin(Function<AnvilPlugin, T> f, T none) {
		var p = plugin;
		return p == null ? none : f.apply(p);
	}

	/** Actions available for the clan on screen. Never null. */
	PanelActions actionsFor() {
		var p = plugin;
		if (p == null) return PanelActions.NONE;
		var profile = p.canSyncProfileFromPanel();
		if (!p.isAdmin()) return new PanelActions(false, profile, null);
		// Admin — but the roster comes from the clan channel, so it has to be readable. The CACHED
		// answer: this runs while the panel paints, on the EDT, where asking the client directly is a
		// thread violation.
		var scrape = p.isClanRosterReadable();
		return new PanelActions(scrape, profile, scrape ? null : "Join your clan channel to sync the roster");
	}

	/** The logged-in character's row, or {@code null} to hide it (logged out, not signed in). */
	AccountRow accountRow() {
		return fromPlugin(AnvilPlugin::accountRow, null);
	}

	/** A character's Supporter look from the plugin's own list (ChatFlair), or null. */
	ChatFlair.Entry flairOf(String rsn) {
		return fromPlugin(p -> p.flairOf(rsn), null);
	}

	/** Run a row action; {@code done} gets a one-line outcome, off the EDT. */
	void accountAction(AccountRow.Action action, Consumer<String> done) {
		withPlugin(p -> p.accountAction(action, done));
	}

	/** Push the in-game clan roster to the home site. */
	void syncRoster() {
		withPlugin(AnvilPlugin::syncClanRosterFromPanel);
	}

	/** Send this account's collection log + bests to the home site. */
	void syncProfile() {
		withPlugin(AnvilPlugin::syncProfileNow);
	}

	/** Is this clip in the play cycle? */
	boolean bannerSoundOn(String clip) {
		return fromPlugin(p -> p.bannerSoundSelected(clip), false);
	}

	/** Flip one clip in or out of the cycle. */
	void toggleBannerSound(String clip) {
		withPlugin(p -> p.toggleBannerSound(clip));
	}

	/** Copy the sounds folder's path — hub releases may not open a file manager (see Clipboards). */
	void copyBannerSoundsPath() {
		withPlugin(AnvilPlugin::copyBannerSoundsPath);
	}

	/** Import a .wav into {@code kind}'s clips folder. */
	void importSounds(BannerSoundService.Kind kind) {
		withPlugin(p -> p.importSounds(kind));
	}

	/** {@code kind}'s clips; for missions, the folder that decides between a clip and the built-in chime. */
	List<String> sounds(BannerSoundService.Kind kind) {
		return fromPlugin(p -> p.soundClips(kind), Collections.emptyList());
	}

	/**
	 * The STARTING SHOT this account still owes on the addressed clan's live event (site lib/startProof),
	 * or {@code null} when nothing is owed — which is the case on every event that doesn't ask for
	 * one, on sites that predate the feature, and the moment one is filed.
	 */
	PluginConfigResponse.StartProof startProof() {
		var cfg = configSupplier.get();
		if (cfg == null || cfg.startProof == null || !cfg.startProof.required
			|| !cfg.startProof.drawn || !cfg.startProof.needsUpload) {
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
	List<StartProofRules.Check> startProofChecks(PluginConfigResponse.StartProof proof) {
		return fromPlugin(p -> p.startProofChecks(proof), Collections.emptyList());
	}

	/** Will the plugin take the starting shot by itself once the checks pass? (config autoStartShot) */
	boolean autoStartShot() {
		return fromPlugin(AnvilPlugin::autoStartShotEnabled, false);
	}

	/** Ask Shortest Path for a route to the starting shot's spot. */
	void routeToStartSpot() {
		withPlugin(AnvilPlugin::routeToStartSpot);
	}

	/**
	 * Whether this event's tiles can be pinned as in-game widgets: it is one of the live boards you're
	 * playing, or one still waiting to start (pinned early, it counts down and goes live at the whistle).
	 */
	boolean canTrackTiles(int eventId, boolean preStart) {
		var cfg = configSupplier.get();
		return eventId > 0 && (preStart || cfg != null && cfg.live(eventId))
			&& fromPlugin(p -> p.supportsTileTracker() && p.inGame(), false);
	}

	/** The live board's event id, or 0 with none. */
	int activeEventId() {
		var cfg = configSupplier.get();
		return cfg != null && cfg.event != null ? cfg.event.id : 0;
	}

	/** This client's newest count for a tile, or -1 (AnvilPlugin.tileCount). */
	long tileCount(int tileId) {
		return fromPlugin(p -> p.tileCount(tileId), -1L);
	}

	/** The tiles pinned over the game on {@code eventId}. */
	List<Integer> pinnedTiles(int eventId) {
		List<Integer> none = Collections.emptyList();
		return eventId > 0 ? fromPlugin(p -> p.tileTracker().pinned(eventId), none) : none;
	}

	/** Signed in, and the first config answer hasn't come back (or failed) yet. */
	boolean awaitingFirstConfig() {
		return fromPlugin(AnvilPlugin::awaitingFirstConfig, false);
	}

	/** Take every pin on {@code eventId} off the screen. */
	void unpinAll(int eventId) {
		withPlugin(p -> p.tileTracker().clear(eventId));
	}

	void setTileTracked(int eventId, int tileId, boolean on) {
		withPlugin(p -> p.setTrackedTile(eventId, tileId, on));
	}

	/** Take + file the starting shot. No-op where {@link #startProof()} is null. Never blocks the EDT. */
	void captureStartProof() {
		var capture = startProofCapture;
		if (capture != null) capture.run();
	}

	/**
	 * Whether the site's tabs ({@link UiView}) can be built: a config answered, or the person is in no
	 * clan (a 404 config) — the You and Lookup tabs are exactly what someone without a clan should
	 * still get. False ⇒ no tabs, and a lookup opens the browser.
	 */
	boolean supportsPluginUi() {
		return configSupplier.get() != null || apiClient.signedInWithoutClan();
	}

	/** The game's name lists for Lookup's suggestions; {@code then} runs on the client thread. */
	void lookupNames(Consumer<LookupSuggestions.Names> then) {
		var p = plugin;
		if (p == null) then.accept(LookupSuggestions.Names.EMPTY);
		else p.lookupNames(then);
	}

	/** The character logged in right now, or null at the login screen. */
	String myRsn() {
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
	void refreshConfig(boolean force) {
		withPlugin(p -> {
			p.refreshConfigForSidebar(force);
			if (force) {
				p.reprobeAdminNow();
				p.refreshLook();
				p.refreshChatFlairNow();
			}
		});
	}

	/**
	 * Is the place the site answered for a creator space (a streamer's events, no in-game clan)? The
	 * site sends {@code activeClan.kind = "creator"} for one; older sites send nothing, which reads as a clan.
	 */
	boolean activeIsSpace() {
		var cfg = configSupplier.get();
		return cfg != null && cfg.activeClan != null && "creator".equals(cfg.activeClan.kind);
	}

	/** Every clan the signed-in person holds a seat in. Empty on an older site, or before the first poll. */
	List<PluginConfigResponse.ClanRef> clans() {
		var cfg = configSupplier.get();
		return cfg == null ? Collections.emptyList() : cfg.switchableClans();
	}

	/** The member's explicit pick, or "" when they are on Auto and the site is deciding. */
	String chosenClan() {
		return fromPlugin(AnvilPlugin::getChosenClan, "");
	}

	/**
	 * The clan slug this plugin is actually addressing — a pick if there is one, else whatever the
	 * site resolved. "" before the first answer.
	 */
	String activeClan() {
		return apiClient.getActiveClan();
	}

	/** The member picked a clan (or "" for Auto): persist it and refetch, without waiting. EDT-safe. */
	void chooseClan(String slug) {
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
	static final class AccountRow {
		enum State {
			/** Linked to (or linking to) the signed-in Anvil account. Offers Not my account. */
			TRACKING,
			/** Marked Not my account on this computer; nothing is sent. Offers Track it again. */
			PAUSED,
			/** Claimed by another Anvil account; nothing tracks for this token. Offers It's mine. */
			OTHER,
		}

		enum Action {
			NOT_MINE,
			TRACK_AGAIN,
			DISPUTE,
		}

		final String rsn;
		final State state;

	}

	/**
	 * What the panel may DO for the addressed clan.
	 *
	 * <p>A roster sync scrapes the clan channel you are standing in, so it needs an admin with that
	 * channel readable. A profile sync goes to the site the plugin is authenticated against.
	 */
	@RequiredArgsConstructor
	static final class PanelActions {
		static final PanelActions NONE = new PanelActions(false, false, null);

		/** Show "Sync clan roster": admin, at home, with a readable clan channel. */
		final boolean canSyncRoster;
		/** Show "Sync profile": the site takes profile data and it's the one we're signed in to. */
		final boolean canSyncProfile;
		/** Why the roster button is absent, when the reason is worth saying. Null when it isn't. */
		final String rosterNote;
	}
}
