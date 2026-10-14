package com.anvil;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.ProgressBarComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

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
 * <p>A pin your team finishes shows "Done" for a moment and then takes itself down. A tile pinned when
 * it was already done stays up: that member is watching the other teams on it.
 */
@Singleton
public class TileTracker
{
	static final int MAX_PINS = 3;
	private static final String CFG_PINS = "trackedTiles";
	private static final Color GOLD = new Color(255, 215, 0);
	private static final int OTHER_TEAMS_SHOWN = 4;
	/** How long a just-finished pin keeps showing "Done" before it unpins (hidden on the next fetch after). */
	private static final long DONE_LINGER_MS = 10_000;

	private final OverlayManager overlayManager;
	private final ConfigManager configManager;
	private final BingoApiClient apiClient;

	/** tileId → its widget, in pin order. Guarded by {@code this}. */
	private final Map<Integer, Widget> pins = new LinkedHashMap<>();
	private int eventId;

	@Inject
	TileTracker(OverlayManager overlayManager, ConfigManager configManager, BingoApiClient apiClient)
	{
		this.overlayManager = overlayManager;
		this.configManager = configManager;
		this.apiClient = apiClient;
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
		for (Widget w : pins.values())
		{
			overlayManager.remove(w);
		}
		pins.clear();
	}

	synchronized boolean isPinned(int eventId, int tileId)
	{
		return this.eventId == eventId && pins.containsKey(tileId);
	}

	synchronized boolean isFull(int eventId)
	{
		return this.eventId == eventId && pins.size() >= MAX_PINS;
	}

	/** Pin or unpin; pinning a tile from another event drops the old event's pins. */
	synchronized void toggle(int eventId, int tileId)
	{
		if (isPinned(eventId, tileId))
		{
			overlayManager.remove(pins.remove(tileId));
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
			add(tileId).keepWhenDone = null; // decided by the first fetch
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
			overlayManager.remove(pins.remove(tileId));
			save();
		}
	}

	synchronized boolean tracks(int tileId)
	{
		return pins.containsKey(tileId);
	}

	private Widget add(int tileId)
	{
		int slot = 0;
		while (slotTaken(slot))
		{
			slot++;
		}
		Widget w = new Widget(slot);
		pins.put(tileId, w);
		overlayManager.add(w);
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
		volatile Progress progress;
		/**
		 * Stay up after the team finishes? Pins restored from config start false, so a tile finished
		 * while the client was closed still comes down; a fresh pin is decided by its first fetch.
		 */
		Boolean keepWhenDone = false;
		/** When this pin was first seen done, or 0. */
		long doneSinceMs;

		Widget(int slot)
		{
			this.slot = slot;
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
			Progress p = progress;
			if (p == null || p.teams == null)
			{
				panelComponent.getChildren().add(TitleComponent.builder().text("Anvil: loading tile…").color(GOLD).build());
				return super.render(graphics);
			}
			panelComponent.getChildren().add(TitleComponent.builder().text(p.label).color(GOLD).build());
			if ("upcoming".equals(p.phase))
			{
				// Nothing counts before the start, so there is no progress to draw yet — only when.
				panelComponent.getChildren().add(LineComponent.builder().left("Goal").right(fmt(p.goal)).build());
				panelComponent.getChildren().add(LineComponent.builder()
					.left("Starts in").right(countdown(p.startsAt)).rightColor(Color.YELLOW).build());
				return super.render(graphics);
			}
			int others = 0;
			for (Team t : p.teams)
			{
				if (t.mine)
				{
					renderOwnTeam(p, t);
				}
				else if (others++ < OTHER_TEAMS_SHOWN)
				{
					panelComponent.getChildren().add(LineComponent.builder()
						.left(t.name)
						.leftColor(teamColor(t.color))
						.right(t.complete ? "Done" : fmt(t.current) + "/" + fmt(p.goal))
						.rightColor(t.complete ? Color.GREEN : Color.WHITE)
						.build());
				}
			}
			return super.render(graphics);
		}

		private void renderOwnTeam(Progress p, Team t)
		{
			ProgressBarComponent bar = new ProgressBarComponent();
			bar.setMaximum(Math.max(1, p.goal));
			bar.setValue(t.complete ? p.goal : Math.min(t.current, p.goal));
			bar.setForegroundColor(t.complete ? new Color(0, 160, 0) : teamColor(t.color).darker());
			bar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.FULL);
			panelComponent.getChildren().add(bar);
			if (t.members == null)
			{
				return;
			}
			for (Member m : t.members)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(m.mine ? "You" : m.name)
					.leftColor(m.mine ? GOLD : Color.LIGHT_GRAY)
					.right(fmt(m.amount))
					.build());
			}
		}

		private static Color teamColor(String hex)
		{
			try
			{
				return hex != null && hex.startsWith("#") ? Color.decode(hex) : Color.WHITE;
			}
			catch (NumberFormatException e)
			{
				return Color.WHITE;
			}
		}

		private static String countdown(String iso)
		{
			try
			{
				long s = Math.max(0, java.time.Duration.between(java.time.Instant.now(), java.time.Instant.parse(iso)).getSeconds());
				return s >= 3600 ? (s / 3600) + "h " + (s % 3600 / 60) + "m" : s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
			}
			catch (RuntimeException e)
			{
				return "soon";
			}
		}

		private static String fmt(long n)
		{
			return n >= 10_000_000 ? (n / 1_000_000) + "M" : n >= 100_000 ? (n / 1_000) + "K" : String.format("%,d", n);
		}
	}
}
