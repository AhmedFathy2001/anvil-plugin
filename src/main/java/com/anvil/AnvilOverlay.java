package com.anvil;

import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.components.*;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

@Slf4j
public class AnvilOverlay extends OverlayPanel {

	static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

	final AnvilPlugin plugin;
	final AnvilConfig config;

	@Inject
	AnvilOverlay(AnvilPlugin plugin, AnvilConfig config) {
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(OverlayPriority.LOW);
	}

	/** The config the lines below were built from; rebuilt only when a poll swaps it. Client thread only. */
	PluginConfigResponse builtFor;
	/** The event's [start, end], or null when it can't be live (force-ended, an unparseable date). */
	Instant[] window;
	LineComponent team;
	final LineComponent date = LineComponent.builder().left("Date:").build();
	long dateMinute = -1;

	static final TitleComponent TITLE = TitleComponent.builder().text("Anvil").color(new Color(255, 215, 0)).build();

	public Dimension render(Graphics2D graphics) {
		if (!config.showOverlay() || AnvilPlugin.capturing) return null;

		var pluginConfig = plugin.getPluginConfig();
		if (pluginConfig == null || pluginConfig.event == null) return null;
		if (pluginConfig != builtFor) build(pluginConfig);

		// Don't show overlay if event isn't active
		var now = System.currentTimeMillis();
		if (!within(window, Instant.ofEpochMilli(now))) return null;

		var c = panelComponent.getChildren();
		c.add(TITLE);
		if (team != null) c.add(team);
		// Date — UTC for unambiguous time-of-drop on the proof image; it only reads to the minute.
		if (now / 60_000 != dateMinute) {
			dateMinute = now / 60_000;
			date.setRight(ZonedDateTime.now(ZoneOffset.UTC).format(DATE_FMT));
		}
		c.add(date);


		return super.render(graphics);
	}

	void build(PluginConfigResponse cfg) {
		builtFor = cfg;
		window = window(cfg.event);
		// Team — the only proof we need on a screenshot. Team name + UTC date are
		// uniquely identifying for a submission. The previous codeword was redundant
		// since we never validated it server-side, and the aggregate "Drops: 1/3"
		// line was misleading when multiple tiles are progressing in parallel.
		team = cfg.team == null || cfg.team.name == null || cfg.team.name.isEmpty() ? null
				: LineComponent.builder().left("Team:").right(cfg.team.name)
						.rightColor(Ui.teamColor(cfg.team.color, Color.WHITE)).build();
	}

	/** The last event asked about and its window: asked every frame, parsed (and a bad date logged) once. */
	static volatile Object[] active = {};

	static boolean isEventActive(PluginConfigResponse.EventInfo event) {
		var a = active;
		if (a.length == 0 || a[0] != event) active = a = new Object[]{event, window(event)};
		return within((Instant[]) a[1], Instant.now());
	}

	static boolean within(Instant[] window, Instant now) {
		return window != null && !now.isBefore(window[0]) && !now.isAfter(window[1]);
	}

	/**
	 * When the event runs, open-ended where a date is missing — or null when it never does: missing,
	 * force-ended by admin, or a date that won't parse (treated as inactive).
	 */
	static Instant[] window(PluginConfigResponse.EventInfo event) {
		if (event == null || event.forceEndedAt != null) return null;
		try {
			return new Instant[] {
				event.startDate == null ? Instant.MIN : Instant.parse(event.startDate),
				event.endDate == null ? Instant.MAX : Instant.parse(event.endDate)};
		} catch (Exception e) {
			log.warn("Bad event dates: {}", e.getMessage());
			return null;
		}
	}
}
