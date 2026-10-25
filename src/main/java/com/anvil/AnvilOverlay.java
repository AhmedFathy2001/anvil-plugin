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

	private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

	private final AnvilPlugin plugin;
	private final AnvilConfig config;

	@Inject
	public AnvilOverlay(AnvilPlugin plugin, AnvilConfig config) {
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(OverlayPriority.LOW);
	}

	/** The config the lines below were built from; rebuilt only when a poll swaps it. Client thread only. */
	private PluginConfigResponse builtFor;
	/** The event's [start, end], or null when it can't be live (force-ended, an unparseable date). */
	private Instant[] window;
	private LineComponent team;
	private final LineComponent date = LineComponent.builder().left("Date:").build();
	private long dateMinute = -1;

	private static final TitleComponent TITLE = TitleComponent.builder().text("Anvil").color(new Color(255, 215, 0)).build();

	@Override
	public Dimension render(Graphics2D graphics) {
		if (!config.showOverlay()) {
			return null;
		}

		PluginConfigResponse pluginConfig = plugin.getPluginConfig();
		if (pluginConfig == null || pluginConfig.event == null) {
			return null;
		}
		if (pluginConfig != builtFor) {
			build(pluginConfig);
		}

		// Don't show overlay if event isn't active
		long now = System.currentTimeMillis();
		if (!within(window, Instant.ofEpochMilli(now))) {
			return null;
		}

		panelComponent.getChildren().add(TITLE);
		if (team != null) {
			panelComponent.getChildren().add(team);
		}
		// Date — UTC for unambiguous time-of-drop on the proof image; it only reads to the minute.
		if (now / 60_000 != dateMinute) {
			dateMinute = now / 60_000;
			date.setRight(ZonedDateTime.now(ZoneOffset.UTC).format(DATE_FMT));
		}
		panelComponent.getChildren().add(date);

		return super.render(graphics);
	}

	private void build(PluginConfigResponse cfg) {
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

	static boolean isEventActive(PluginConfigResponse.EventInfo event) {
		return within(window(event), Instant.now());
	}

	private static boolean within(Instant[] window, Instant now) {
		return window != null && !now.isBefore(window[0]) && !now.isAfter(window[1]);
	}

	/**
	 * When the event runs, open-ended where a date is missing — or null when it never does: missing,
	 * force-ended by admin, or a date that won't parse (treated as inactive).
	 */
	private static Instant[] window(PluginConfigResponse.EventInfo event) {
		if (event == null || event.forceEndedAt != null) {
			return null;
		}
		try {
			return new Instant[] {
				event.startDate == null ? Instant.MIN : Instant.parse(event.startDate),
				event.endDate == null ? Instant.MAX : Instant.parse(event.endDate)};
		} catch (Exception e) {
			log.warn("Failed to parse event dates '{}' / '{}': {}", event.startDate, event.endDate, e.getMessage());
			return null;
		}
	}
}
