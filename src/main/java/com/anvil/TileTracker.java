package com.anvil;

import lombok.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.inject.*;
import net.runelite.client.config.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.components.*;
import java.util.function.*;
import java.time.*;

/**
 * Tiles pinned from the sidebar ("Track on screen"), all in one movable, resizable panel: each tile's
 * name and your team's progress, read from
 * {@code GET /api/plugin/event/<id>/tile/<tileId>/progress}.
 *
 * <p>Fetched only while something is pinned: on the plugin's 30s config tick, and a few seconds after
 * this account progresses a pinned tile, so your own kill shows without waiting a full poll. Each pin
 * keeps its board: pins from several live boards sit side by side (tile ids are unique across boards).
 * They can go up before it starts (the pin counts down, then goes live on the first poll after the
 * whistle) and a board's pins are forgotten once the site says it's over.
 *
 * <p>A pin your team finishes shows "Done" for a moment and then takes itself down. A done tile can't be
 * pinned — the sidebar offers no button for it.
 */

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TileTracker {
	static final int MAX_PINS = 3;
	static final String CFG_PINS = "trackedTiles", G = "osrsbingo";
	static final Color GOLD = new Color(255, 215, 0);
	/** The blank line between pins in the shared panel; one instance serves every gap. */
	static final LineComponent GAP = LineComponent.builder().build();
	/** How long a just-finished pin keeps showing "Done" before it unpins (hidden on the next fetch after). */
	static final long DONE_LINGER_MS = 10_000;
	/** Failed fetches of a pin that never loaded before it is let go (30s apart). */
	static final int MAX_FAILS = 6;

	final OverlayManager overlayManager;
	final ConfigManager configManager;
	final BingoApiClient apiClient;

	/** tileId → its pin, in pin order. Guarded by {@code this}. */
	Map<Integer, Widget> pins = new LinkedHashMap<>();
	/** The logged-in account's hash, or -1 at the login screen: pins are saved per account. */
	long account = -1;
	/**
	 * (tileId, site count) → what to show for your team: the plugin's own newer count when it has one,
	 * so a pinned tile moves on your kill. Identity until the plugin sets it.
	 */
	@Setter volatile ToLongBiFunction<Integer, Long> shownProgress = (id, site) -> site;
	/**
	 * tileId → the plugin's extra lines for it ("3/5 runs", a combo's parts, TileNotes), read from
	 * the polled config each frame. None until the plugin sets it.
	 */
	@Setter volatile IntFunction<List<String>> notes = id -> List.of();
	/** The live boards' ids, to find the board of a pin saved without one. */
	volatile Supplier<List<Integer>> boards = Collections::emptyList;
	/** Says why a pin came down (a chat line). */
	volatile Consumer<String> told = m -> { };
	Combined combined = new Combined();
	/**
	 * The pins as the frame draw sees them: replaced whole on every change, read without a lock.
	 * The shared panel renders on the client thread every frame, which must never wait on a lock.
	 */
	volatile List<Widget> drawn = new ArrayList<>();


	/**
	 * {@code doneSeen}: a pin just started showing "Done" (tileId) — the plugin asks again after the
	 * linger, so it comes down on time instead of on the next 30s poll. {@code unpinned}: a finished pin
	 * came down, so whatever lists the pins (the panel's "Tracked on screen") asks again.
	 */
	void setDoneHooks(IntConsumer seen, Runnable down) {
		doneSeen = seen;
		unpinned = down;
	}

	volatile IntConsumer doneSeen = id -> { };
	volatile Runnable unpinned = () -> { };

	void setBoards(Supplier<List<Integer>> live, Consumer<String> tell) {
		boards = live;
		told = tell;
	}

	/** Unpin one, and take the panel down with the last pin. */
	void unpin(int tileId) {
		pins.remove(tileId);
		redraw();
		if (pins.isEmpty()) overlayManager.remove(combined);
	}

	void redraw() {
		drawn = new ArrayList<>(pins.values());
	}

	/** Pins saved before they were kept per account, handed to the first account that logs in. */
	String legacyPins;

	/**
	 * A fresh start: the next login restores its pins even if it's the account that was logged in when
	 * the plugin stopped. Pins saved before they were kept per account go to the first account seen —
	 * almost always the one that pinned them — rather than being lost on the upgrade.
	 */
	synchronized void startUp() {
		account = -1;
		var old = configManager.getConfiguration(G, CFG_PINS);
		if (old != null) {
			legacyPins = old;
			configManager.unsetConfiguration(G, CFG_PINS);
		}
	}

	/**
	 * The account now logged in ({@code -1} at the login screen): take the last one's pins down and
	 * restore this one's ("eventId:tileId,tileId;eventId:tileId"). One client plays several accounts,
	 * and an alt that isn't on the event has no board to unpin them from.
	 */
	synchronized void forAccount(long hash) {
		if (hash == account) return;
		shutDown();
		account = hash;
		var saved = hash == -1 ? null : configManager.getConfiguration(G, key());
		if (saved == null && hash != -1 && legacyPins != null) {
			saved = legacyPins;
			configManager.setConfiguration(G, key(), saved);
		}
		if (hash != -1) legacyPins = null;
		if (saved == null || saved.isEmpty()) return;
		try {
			parse(saved).forEach((tile, event) -> {
				if (pins.size() < MAX_PINS) add(event, tile);
			});
		} catch (RuntimeException e) {
			shutDown();
			save();
		}
	}

	synchronized void shutDown() {
		overlayManager.remove(combined);
		pins.clear();
		redraw();
	}

	synchronized boolean isPinned(int eventId, int tileId) {
		var w = pins.get(tileId);
		return w != null && w.event == eventId;
	}

	/**
	 * Pin ({@code on}) or unpin — an explicit state, not a flip, so a button drawn before the pin took
	 * itself down (a finished tile) can't put it back up when clicked.
	 */
	synchronized void set(int eventId, int tileId, boolean on) {
		if (isPinned(eventId, tileId) != on) toggle(eventId, tileId);
	}

	/** Pin or unpin. */
	synchronized void toggle(int eventId, int tileId) {
		if (!TileNotes.pinnable(tileId)) return; // a cap counter's virtual entry is never pinned
		if (isPinned(eventId, tileId)) unpin(tileId);
		else {
			if (pins.size() >= MAX_PINS) {
				told.accept("You can pin 3 tiles. Unpin one first.");
				return;
			}
			// A done tile can't be pinned (the sidebar offers no button), so a pin that turns out done
			// on its first fetch was finished in between — it comes down like any other.
			add(eventId, tileId).keepWhenDone = false;
		}
		save();
	}

	/** The 30s tick: refetch every pin. Blocking; call off the client thread. */
	void poll() {
		List<Integer> ids;
		synchronized (this) {
			ids = new ArrayList<>(pins.keySet());
		}
		for (int id : ids) refresh(id);
	}

	synchronized Widget pin(int tileId) {
		return pins.get(tileId);
	}

	/** Refetch one pinned tile (no-op when it isn't pinned). Blocking. */
	void refresh(int tileId) {
		var w = pin(tileId);
		if (w == null) return;
		var p = fetch(w);
		if (letGo(w, p)) {
			drop(w, p == Progress.LOCKED ? "not open for you" : p == Progress.GONE ? "tile removed" : "couldn't load");
		} else if (p != null && p != Progress.GONE) {
			if (p.phase != null && p.phase.contains("ended")) clear(w.event);
			else show(w.event, tileId, w, p);
		}
	}

	/**
	 * Note one fetch on the pin and say whether to take it down: the site says the tile is gone, or it has failed {@link #MAX_FAILS} times without ever loading.
	 */
	static boolean letGo(Widget w, Progress p) {
		if (p == Progress.GONE || p == Progress.LOCKED) return true; // deleted (a reseed) or off your boards: final, no retry
		if (p == null) return w.progress == null && ++w.fails >= MAX_FAILS;
		w.fails = 0;
		return false;
	}

	/** The board's fetch; a pin with no board tries each live one. */
	Progress fetch(Widget w) {
		if (w.event > 0) return apiClient.fetchTileProgress(w.event, w.tileId);
		var last = Progress.GONE;
		for (int b : boards.get()) {
			var p = apiClient.fetchTileProgress(b, w.tileId);
			if (p != null && p != Progress.GONE) {
				w.event = b;
				save();
				return p;
			}
			if (p == null) last = null;
		}
		return last;
	}

	synchronized void drop(Widget w, String why) {
		if (pins.get(w.tileId) != w) return;
		unpin(w.tileId);
		save();
		told.accept("Stopped tracking " + (w.label != null ? w.label : "a tile") + " (" + why + ")");
	}

	/**
	 * A tile's numbers from the live push: the whole board's view, so ours is marked here (team first,
	 * as the route ranks it for a caller), then shown like a fetch.
	 */
	void pushed(int event, int tileId, Progress p, int myTeam) {
		var w = pin(tileId);
		if (w == null || w.event != event || p == null || p.teams == null || p.phase == null || p.phase.contains("ended")) return;
		for (var t : p.teams) t.mine = t.teamId == myTeam;
		p.teams.sort((a, b) -> Boolean.compare(b.mine, a.mine));
		show(event, tileId, w, p);
	}

	void show(int event, int tileId, Widget w, Progress p) {
		w.progress = p;
		w.label = p.label;
		int step = afterFetch(w, p, System.currentTimeMillis());
		if (step == UNPIN) synchronized (this) {
			if (pins.get(tileId) == w) {
				unpin(tileId);
				save();
				unpinned.run();
			}
		} else if (step == DONE_SHOWN) doneSeen.accept(tileId);
	}

	static final int KEEP = 0, DONE_SHOWN = 1, UNPIN = 2;

	/** What a fetch means for the pin: nothing, "Done" just went up (ask again after the linger), or take it down. */
	static int afterFetch(Widget w, Progress p, long now) {
		var was = w.doneSinceMs;
		if (finishedAndShown(w, p, now)) return UNPIN;
		return was == 0 && w.doneSinceMs != 0 ? DONE_SHOWN : KEEP;
	}

	/**
	 * Note this fetch on the widget and say whether it should now come down: the viewer's team has
	 * finished the tile, the pin isn't one made on an already-done tile, and "Done" has been up for
	 * {@link #DONE_LINGER_MS}.
	 */
	static boolean finishedAndShown(Widget w, Progress p, long now) {
		var done = false;
		if (p.teams != null && !"upcoming".equals(p.phase)) for (var t : p.teams) {
			if (t.mine) {
				done = t.complete;
				break;
			}
		}
		if (w.keepWhenDone == null) w.keepWhenDone = done;
		if (!done || w.keepWhenDone) return false;
		if (w.doneSinceMs == 0) {
			w.doneSinceMs = now;
			return false;
		}
		return now - w.doneSinceMs >= DONE_LINGER_MS;
	}

	/** The tiles pinned for this event, in pin order. */
	synchronized List<Integer> pinned(int eventId) {
		var out = new ArrayList<Integer>();
		pins.values().forEach(w -> {
			if (w.event == eventId) out.add(w.tileId);
		});
		return out;
	}

	/** The panel's right-click "Unpin all". */
	synchronized boolean unpinFrom(Overlay overlay, String option) {
		if (overlay != combined || option == null || !option.startsWith("Unpin")) return false;
		shutDown();
		save();
		return true;
	}

	/** Take every pin for this event down. */
	synchronized void clear(int eventId) {
		for (int id : pinned(eventId)) unpin(id);
		save();
	}

	/** Asked from the client thread on every credited kill, so read off the snapshot, lock-free. */
	boolean tracks(int tileId) {
		for (var w : drawn) {
			if (w.tileId == tileId) return true;
		}
		return false;
	}

	Widget add(int eventId, int tileId) {
		var w = new Widget(tileId, (id, site) -> shownProgress.applyAsLong(id, site));
		w.event = eventId;
		w.notes = id -> notes.apply(id);
		pins.put(tileId, w);
		redraw();
		overlayManager.remove(combined); // re-added, so it is never in twice
		overlayManager.add(combined);
		return w;
	}

	String key() {
		return CFG_PINS + "." + account;
	}

	void save() {
		if (account == -1) return;
		if (pins.isEmpty()) configManager.unsetConfiguration(G, key());
		else configManager.setConfiguration(G, key(), format(pins.values()));
	}

	/** Saved pins, tile → its board: "event:tile,tile;event:tile" (one board's "event:tile,tile" too). */
	static Map<Integer, Integer> parse(String saved) {
		var out = new LinkedHashMap<Integer, Integer>();
		for (var board : saved.split(";")) {
			// "event:tile,tile"; a bare "tile,tile" (saved before pins kept a board) gets board 0, found at fetch.
			var parts = board.contains(":") ? board.split(":", 2) : new String[]{"0", board};
			int event;
			try {
				event = Integer.parseInt(parts[0].trim());
			} catch (NumberFormatException e) {
				continue;
			}
			for (var id : parts[1].split(",")) {
				try {
					out.put(Integer.valueOf(id.trim()), event);
				} catch (NumberFormatException e) {
					// skip a bad id, keep the rest
				}
			}
		}
		return out;
	}

	static String format(Collection<Widget> pins) {
		var sj = new StringJoiner(";");
		pins.forEach(w -> sj.add(w.event + ":" + w.tileId));
		return sj.toString();
	}

	/** Every pin in one movable panel, in pin order, a blank line between them — name and bar each. */
	class Combined extends OverlayPanel {
		Combined() {
			getMenuEntries().add(new OverlayMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Unpin all", "Anvil tile"));
			setPosition(OverlayPosition.TOP_LEFT);
			setResizable(true);
			setMovable(true);
		}

		public String getName() {
			return "AnvilTileTrackerPanel";
		}

		public Dimension render(Graphics2D graphics) {
			if (AnvilPlugin.capturing) return null;
			var shown = drawn; // no lock on the frame path
			for (int i = 0; i < shown.size(); i++) {
				if (i > 0) panelComponent.getChildren().add(GAP);
				shown.get(i).drawInto(panelComponent);
			}
			return shown.isEmpty() ? null : super.render(graphics);
		}
	}

	/** The site's answer (lib/tileTracker): the viewer's team first, then the rest by progress. */
	static class Progress {
		/** lib/eventTime's phase: "upcoming", "active", "ended" or "force-ended". */
		String phase;
		String startsAt;
		int tileId;
		String label;
		int points;
		int goal;
		List<Team> teams;
		/** Fetch result for "the site says this tile is not on your boards". */
		static final Progress GONE = new Progress();
		/** Fetch result for "exists, but not open for you" (a race step ahead, a closed tile). */
		static final Progress LOCKED = new Progress();
	}

	static class Team {
		int teamId;
		String color;
		long current;
		boolean complete;
		boolean mine;
	}

	/** One pinned tile: its name, any notes, and your team's bar (or the countdown before the start). */
	@RequiredArgsConstructor
	static class Widget {
		final int tileId;
		/** The board it's on. */
		int event;
		final ToLongBiFunction<Integer, Long> shown;
		/** This tile's extra lines (TileTracker.notes); none by default. */
		volatile IntFunction<List<String>> notes = id -> List.of();
		List<String> builtNotes = List.of();
		volatile Progress progress;
		/** The tile's name once seen; fetches failed with nothing shown. */
		String label;
		int fails;
		/**
		 * Stay up after the team finishes? Pins restored from config start false, so a tile finished
		 * while the client was closed still comes down; a fresh pin is decided by its first fetch.
		 */
		Boolean keepWhenDone = false;
		/** When this pin was first seen done, or 0. */
		long doneSinceMs;
		/**
		 * The lines drawn every frame, built once per fetch on the client thread — the frame only moves
		 * your team's bar and the countdown, and only when they change.
		 */
		List<LayoutableRenderableEntity> lines = new ArrayList<>();
		Progress builtFor;
		Team mine;
		ProgressBarComponent bar;
		LineComponent startsIn;
		Instant startsAt;
		long shownSecs;

		void drawInto(PanelComponent panelComponent) {
			var p = progress;
			var n = notes.apply(tileId);
			if (n == null) n = List.of();

			if (p != builtFor || lines.isEmpty() || !n.equals(builtNotes)) {
				builtNotes = n;
				build(p);
			}
			if (startsIn != null && startsAt != null) {
				var s = Math.max(0, Duration.between(Instant.now(), startsAt).getSeconds());
				if (s != shownSecs) {
					shownSecs = s;
					startsIn.setRight(s >= 3600 ? (s / 3600) + "h " + (s % 3600 / 60) + "m" : s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s");
				}
			}
			// Your own kill counts here the moment it happens; the fetch after the push confirms it.
			if (bar != null && !mine.complete) bar.setValue(Math.min(shown.applyAsLong(tileId, mine.current), p.goal));
			panelComponent.getChildren().addAll(lines);
		}

		void build(Progress p) {
			builtFor = p;
			lines.clear();
			mine = null;
			bar = null;
			startsIn = null;
			shownSecs = -1;
			if (p == null || (p.teams == null && p.label == null)) {
				lines.add(TitleComponent.builder().text("Anvil: loading tile…").color(GOLD).build());
				return;
			}
			lines.add(TitleComponent.builder().text(p.label).color(GOLD).build());
			for (var note : builtNotes) lines.add(LineComponent.builder().left(note).leftColor(Color.LIGHT_GRAY).build());
			if ("upcoming".equals(p.phase)) {
				// Nothing counts before the start, so there is no progress to draw yet — only when.
				lines.add(LineComponent.builder().left("Goal").right(Ui.count(p.goal)).build());
				startsIn = LineComponent.builder().left("Starts in").right("soon").rightColor(Color.YELLOW).build();
				lines.add(startsIn);
				try {
					startsAt = Instant.parse(p.startsAt);
				} catch (RuntimeException e) {
					startsAt = null;
				}
				return;
			}
			if (p.teams == null) return; // named, nothing to rank yet
			for (var t : p.teams) {
				if (!t.mine) continue;
				mine = t;
				bar = new ProgressBarComponent();
				bar.setMaximum(Math.max(1, p.goal));
				bar.setValue(p.goal);
				bar.setForegroundColor(t.complete ? new Color(0, 160, 0) : Ui.teamColor(t.color, Color.WHITE).darker());
				bar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.FULL);
				lines.add(bar);
			}
		}
	}
}
