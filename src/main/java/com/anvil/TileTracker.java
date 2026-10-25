package com.anvil;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.inject.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.components.*;
import java.util.function.ToLongBiFunction;
import java.time.*;

/**
 * Tiles pinned from the sidebar as in-game widgets ("Track on screen"): one movable, resizable
 * overlay per tile showing every team's progress and each team's top contributors, read from
 * {@code GET /api/plugin/event/<id>/tile/<tileId>/progress} (site capability "tile-tracker").
 *
 * <p>Fetched only while something is pinned: on the plugin's 30s config tick, and a few seconds after
 * this account progresses a pinned tile, so your own kill shows without waiting a full poll. Pins
 * belong to one event. They can go up before it starts (the widget counts down, then goes live on the
 * first poll after the whistle) and are forgotten once the site says it's over.
 *
 * <p>A pin your team finishes shows "Done" for a moment and then takes itself down. A done tile can't be
 * pinned — the sidebar offers no button for it.
 */
@Singleton
public class TileTracker
{
	static final int MAX_PINS = 3;
	private static final String CFG_PINS = "trackedTiles";
	private static final Color GOLD = new Color(255, 215, 0);
	private static final int OTHER_TEAMS_SHOWN = 4;
	/** The blank line between pins in the shared panel; one instance serves every gap. */
	private static final LineComponent GAP = LineComponent.builder().build();
	/** How long a just-finished pin keeps showing "Done" before it unpins (hidden on the next fetch after). */
	static final long DONE_LINGER_MS = 10_000;

	private final OverlayManager overlayManager;
	private final ConfigManager configManager;
	private final BingoApiClient apiClient;

	/** tileId → its widget, in pin order. Guarded by {@code this}. */
	private final Map<Integer, Widget> pins = new LinkedHashMap<>();
	private int eventId;
	/**
	 * (tileId, site count) → what to show for your team: the plugin's own newer count when it has one,
	 * so a pinned tile moves on your kill. Identity until the plugin sets it.
	 */
	private volatile ToLongBiFunction<Integer, Long> shownProgress = (id, site) -> site;
	/**
	 * One panel holding every pin (the default) or a movable widget per pin. One panel can't end up
	 * with pins on top of each other, and is one thing to place; separate ones can be put anywhere.
	 */
	private boolean onePanel = true;
	private final Combined combined = new Combined();
	/**
	 * The pins as the frame draw sees them: replaced whole on every change, read without a lock.
	 * The shared panel renders on the client thread every frame, which must never wait on a lock.
	 */
	private volatile List<Widget> drawn = new ArrayList<>();

	@Inject
	TileTracker(OverlayManager overlayManager, ConfigManager configManager, BingoApiClient apiClient)
	{
		this.overlayManager = overlayManager;
		this.configManager = configManager;
		this.apiClient = apiClient;
	}

	void setShownProgress(ToLongBiFunction<Integer, Long> shown)
	{
		shownProgress = shown;
	}

	/** Switch between one panel and a widget per pin, moving whatever is pinned across. */
	synchronized void setOnePanel(boolean one)
	{
		if (one == onePanel)
		{
			return;
		}
		hideAll();
		onePanel = one;
		showAll();
	}

	private void hideAll()
	{
		overlayManager.remove(combined);
		for (Widget w : pins.values())
		{
			overlayManager.remove(w);
		}
	}

	private void showAll()
	{
		if (pins.isEmpty())
		{
			return;
		}
		if (onePanel)
		{
			overlayManager.add(combined);
		}
		else
		{
			for (Widget w : pins.values())
			{
				overlayManager.add(w);
			}
		}
	}

	private Widget removePin(int tileId)
	{
		Widget w = pins.remove(tileId);
		drawn = new ArrayList<>(pins.values());
		return w;
	}

	/** Take one pin's widget down, and the shared panel with the last one. */
	private void hide(Widget w)
	{
		overlayManager.remove(w);
		if (pins.isEmpty())
		{
			overlayManager.remove(combined);
		}
	}

	/** Restore the pins saved for the event in config ("eventId:tileId,tileId"). */
	synchronized void startUp()
	{
		String saved = configManager.getConfiguration("osrsbingo", CFG_PINS);
		if (saved == null || !saved.contains(":"))
		{
			return;
		}
		try
		{
			String[] parts = saved.split(":", 2);
			eventId = Integer.parseInt(parts[0]);
			for (String id : parts[1].split(","))
			{
				if (!id.isEmpty() && pins.size() < MAX_PINS)
				{
					add(Integer.parseInt(id));
				}
			}
		}
		catch (NumberFormatException e)
		{
			unpinAll();
		}
	}

	synchronized void shutDown()
	{
		hideAll();
		pins.clear();
		drawn = new ArrayList<>();
	}

	synchronized boolean isPinned(int eventId, int tileId)
	{
		return this.eventId == eventId && pins.containsKey(tileId);
	}

	synchronized boolean isFull(int eventId)
	{
		return this.eventId == eventId && pins.size() >= MAX_PINS;
	}

	/**
	 * Pin ({@code on}) or unpin — an explicit state, not a flip, so a button drawn before the pin took
	 * itself down (a finished tile) can't put it back up when clicked.
	 */
	synchronized void set(int eventId, int tileId, boolean on)
	{
		if (isPinned(eventId, tileId) != on)
		{
			toggle(eventId, tileId);
		}
	}

	/** Pin or unpin; pinning a tile from another event drops the old event's pins. */
	synchronized void toggle(int eventId, int tileId)
	{
		if (isPinned(eventId, tileId))
		{
			hide(removePin(tileId));
		}
		else
		{
			if (this.eventId != eventId)
			{
				unpinAll();
				this.eventId = eventId;
			}
			if (pins.size() >= MAX_PINS)
			{
				return;
			}
			// A done tile can't be pinned (the sidebar offers no button), so a pin that turns out done
			// on its first fetch was finished in between — it comes down like any other.
			add(tileId).keepWhenDone = false;
		}
		save();
	}

	/** The 30s tick: refetch every pin. Blocking; call off the client thread. */
	void poll()
	{
		List<Integer> ids;
		synchronized (this)
		{
			ids = new ArrayList<>(pins.keySet());
		}
		for (int id : ids)
		{
			refresh(id);
		}
	}

	/** Refetch one pinned tile (no-op when it isn't pinned). Blocking. */
	void refresh(int tileId)
	{
		Widget w;
		int event;
		synchronized (this)
		{
			w = pins.get(tileId);
			event = eventId;
		}
		if (w == null)
		{
			return;
		}
		Progress p = apiClient.fetchTileProgress(event, tileId);
		if (p != null && p.phase != null && p.phase.contains("ended"))
		{
			synchronized (this)
			{
				if (eventId == event)
				{
					unpinAll();
					save();
				}
			}
		}
		else if (p != null)
		{
			w.progress = p;
			if (finishedAndShown(w, p, System.currentTimeMillis()))
			{
				unpinDone(event, tileId, w);
			}
		}
	}

	/**
	 * Note this fetch on the widget and say whether it should now come down: the viewer's team has
	 * finished the tile, the pin isn't one made on an already-done tile, and "Done" has been up for
	 * {@link #DONE_LINGER_MS}.
	 */
	static boolean finishedAndShown(Widget w, Progress p, long now)
	{
		boolean done = ownTeamDone(p);
		if (w.keepWhenDone == null)
		{
			w.keepWhenDone = done;
		}
		if (!done || w.keepWhenDone)
		{
			return false;
		}
		if (w.doneSinceMs == 0)
		{
			w.doneSinceMs = now;
			return false;
		}
		return now - w.doneSinceMs >= DONE_LINGER_MS;
	}

	private static boolean ownTeamDone(Progress p)
	{
		if (p.teams == null || "upcoming".equals(p.phase))
		{
			return false;
		}
		for (Team t : p.teams)
		{
			if (t.mine)
			{
				return t.complete;
			}
		}
		return false;
	}

	private synchronized void unpinDone(int event, int tileId, Widget w)
	{
		if (eventId == event && pins.get(tileId) == w)
		{
			hide(removePin(tileId));
			save();
		}
	}

	/** The tiles pinned for this event, in pin order. */
	synchronized List<Integer> pinned(int eventId)
	{
		return this.eventId == eventId ? new ArrayList<>(pins.keySet()) : new ArrayList<>();
	}

	/** Take every pin for this event down. */
	synchronized void clear(int eventId)
	{
		if (this.eventId == eventId && !pins.isEmpty())
		{
			unpinAll();
			save();
		}
	}

	/** Asked from the client thread on every credited kill, so read off the snapshot, lock-free. */
	boolean tracks(int tileId)
	{
		for (Widget w : drawn)
		{
			if (w.tileId == tileId)
			{
				return true;
			}
		}
		return false;
	}

	private Widget add(int tileId)
	{
		int slot = 0;
		while (slotTaken(slot))
		{
			slot++;
		}
		Widget w = new Widget(slot, tileId, (id, site) -> shownProgress.applyAsLong(id, site));
		pins.put(tileId, w);
		drawn = new ArrayList<>(pins.values());
		if (onePanel)
		{
			overlayManager.remove(combined); // re-added, so it is never in twice
			overlayManager.add(combined);
		}
		else
		{
			overlayManager.add(w);
		}
		return w;
	}

	private boolean slotTaken(int slot)
	{
		return pins.values().stream().anyMatch(w -> w.slot == slot);
	}

	private void unpinAll()
	{
		shutDown();
		eventId = 0;
	}

	private void save()
	{
		if (pins.isEmpty())
		{
			configManager.unsetConfiguration("osrsbingo", CFG_PINS);
			return;
		}
		StringBuilder sb = new StringBuilder().append(eventId).append(':');
		for (int id : pins.keySet())
		{
			sb.append(id).append(',');
		}
		configManager.setConfiguration("osrsbingo", CFG_PINS, sb.substring(0, sb.length() - 1));
	}

	/** Every pin in one movable panel, in pin order, a blank line between them — name and bar each. */
	class Combined extends OverlayPanel
	{
		Combined()
		{
			setPosition(OverlayPosition.TOP_LEFT);
			setResizable(true);
			setMovable(true);
		}

		@Override
		public String getName()
		{
			return "AnvilTileTrackerPanel";
		}

		@Override
		public Dimension render(Graphics2D graphics)
		{
			List<Widget> shown = drawn; // no lock on the frame path
			for (int i = 0; i < shown.size(); i++)
			{
				if (i > 0)
				{
					panelComponent.getChildren().add(GAP);
				}
				shown.get(i).drawInto(panelComponent, true);
			}
			return shown.isEmpty() ? null : super.render(graphics);
		}
	}

	/** The site's answer (lib/tileTracker): the viewer's team first, then the rest by progress. */
	static class Progress
	{
		/** lib/eventTime's phase: "upcoming", "active", "ended" or "force-ended". */
		String phase;
		String startsAt;
		int tileId;
		String label;
		int points;
		int goal;
		String statType;
		List<Team> teams;
	}

	static class Team
	{
		int teamId;
		String name;
		String color;
		long current;
		boolean complete;
		boolean mine;
		List<Member> members;
	}

	static class Member
	{
		String name;
		long amount;
		boolean mine;
	}

	/**
	 * One pinned tile. Alt-drag moves it and drags its edge to resize, like any overlay; each slot has
	 * its own name so RuneLite remembers where every one was put.
	 */
	static class Widget extends OverlayPanel
	{
		final int slot;
		final int tileId;
		private final ToLongBiFunction<Integer, Long> shown;
		volatile Progress progress;
		/**
		 * Stay up after the team finishes? Pins restored from config start false, so a tile finished
		 * while the client was closed still comes down; a fresh pin is decided by its first fetch.
		 */
		Boolean keepWhenDone = false;
		/** When this pin was first seen done, or 0. */
		long doneSinceMs;
		/**
		 * The lines drawn every frame, built once per fetch (or layout switch) on the client thread — the
		 * frame only moves your team's bar, your own count and the countdown, and only when they change.
		 */
		private final List<LayoutableRenderableEntity> lines = new ArrayList<>();
		private Progress builtFor;
		private boolean builtCompact;
		private Team mine;
		private ProgressBarComponent bar;
		private LineComponent you;
		private LineComponent startsIn;
		private Instant startsAt;
		private long youBase;
		private long shownAhead;
		private long shownSecs;

		Widget(int slot, int tileId, ToLongBiFunction<Integer, Long> shown)
		{
			this.slot = slot;
			this.tileId = tileId;
			this.shown = shown;
			setPosition(OverlayPosition.TOP_LEFT);
			setResizable(true);
			setMovable(true);
		}

		@Override
		public String getName()
		{
			return "AnvilTileTracker" + slot;
		}

		@Override
		public Dimension render(Graphics2D graphics)
		{
			drawInto(panelComponent, false);
			return super.render(graphics);
		}

		/**
		 * This pin's lines, into its own widget or the shared panel. {@code compact} (the shared panel)
		 * is the tile's name and your team's bar only — three pins with every member and rival team
		 * each made a panel taller than the game view.
		 */
		void drawInto(PanelComponent panelComponent, boolean compact)
		{
			Progress p = progress;
			if (p != builtFor || compact != builtCompact || lines.isEmpty())
			{
				build(p, compact);
			}
			if (startsIn != null && startsAt != null)
			{
				long s = Math.max(0, Duration.between(Instant.now(), startsAt).getSeconds());
				if (s != shownSecs)
				{
					shownSecs = s;
					startsIn.setRight(s >= 3600 ? (s / 3600) + "h " + (s % 3600 / 60) + "m" : s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s");
				}
			}
			if (bar != null && !mine.complete)
			{
				// Your own kill counts here the moment it happens; the fetch after the push confirms it.
				long current = shown.applyAsLong(tileId, mine.current);
				bar.setValue(Math.min(current, p.goal));
				long ahead = Math.max(0, current - mine.current);
				if (you != null && ahead != shownAhead)
				{
					shownAhead = ahead;
					you.setRight(Ui.count(youBase + ahead));
				}
			}
			panelComponent.getChildren().addAll(lines);
		}

		private void build(Progress p, boolean compact)
		{
			builtFor = p;
			builtCompact = compact;
			lines.clear();
			mine = null;
			bar = null;
			you = startsIn = null;
			shownAhead = shownSecs = -1;
			if (p == null || p.teams == null)
			{
				lines.add(TitleComponent.builder().text("Anvil: loading tile…").color(GOLD).build());
				return;
			}
			lines.add(TitleComponent.builder().text(p.label).color(GOLD).build());
			if ("upcoming".equals(p.phase))
			{
				// Nothing counts before the start, so there is no progress to draw yet — only when.
				lines.add(LineComponent.builder().left("Goal").right(Ui.count(p.goal)).build());
				startsIn = LineComponent.builder().left("Starts in").right("soon").rightColor(Color.YELLOW).build();
				lines.add(startsIn);
				try
				{
					startsAt = Instant.parse(p.startsAt);
				}
				catch (RuntimeException e)
				{
					startsAt = null;
				}
				return;
			}
			int others = 0;
			for (Team t : p.teams)
			{
				if (t.mine)
				{
					buildOwnTeam(p, t, compact);
				}
				else if (!compact && others++ < OTHER_TEAMS_SHOWN)
				{
					lines.add(LineComponent.builder()
						.left(t.name)
						.leftColor(Ui.teamColor(t.color, Color.WHITE))
						.right(t.complete ? "Done" : Ui.count(t.current) + "/" + Ui.count(p.goal))
						.rightColor(t.complete ? Color.GREEN : Color.WHITE)
						.build());
				}
			}
		}

		private void buildOwnTeam(Progress p, Team t, boolean compact)
		{
			mine = t;
			bar = new ProgressBarComponent();
			bar.setMaximum(Math.max(1, p.goal));
			bar.setValue(p.goal);
			bar.setForegroundColor(t.complete ? new Color(0, 160, 0) : Ui.teamColor(t.color, Color.WHITE).darker());
			bar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.FULL);
			lines.add(bar);
			if (compact || t.members == null)
			{
				return;
			}
			for (Member m : t.members)
			{
				LineComponent line = LineComponent.builder()
					.left(m.mine ? "You" : m.name)
					.leftColor(m.mine ? GOLD : Color.LIGHT_GRAY)
					.right(Ui.count(m.amount))
					.build();
				if (m.mine && !t.complete)
				{
					you = line;
					youBase = m.amount;
				}
				lines.add(line);
			}
		}
	}
}
