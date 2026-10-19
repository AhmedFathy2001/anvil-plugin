package com.anvil;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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

	@Override
	public Dimension render(Graphics2D graphics) {
		if (!config.showOverlay()) {
			return null;
		}

		PluginConfigResponse pluginConfig = plugin.getPluginConfig();
		if (pluginConfig == null || pluginConfig.event == null) {
			return null;
		}

		// Don't show overlay if event isn't active
		if (!isEventActive(pluginConfig.event)) {
			return null;
		}

		// Title
		panelComponent.getChildren().add(TitleComponent.builder()
				.text("Anvil")
				.color(new Color(255, 215, 0)) // Gold
				.build());

		// Team — the only proof we need on a screenshot. Team name + UTC date are
		// uniquely identifying for a submission. The previous codeword was redundant
		// since we never validated it server-side, and the aggregate "Drops: 1/3"
		// line was misleading when multiple tiles are progressing in parallel.
		if (pluginConfig.team != null && pluginConfig.team.name != null && !pluginConfig.team.name.isEmpty()) {
			Color teamColor = Color.WHITE;
			try {
				if (pluginConfig.team.color != null && pluginConfig.team.color.startsWith("#")) {
					teamColor = Color.decode(pluginConfig.team.color);
				}
			} catch (NumberFormatException ignored) {
				/* fall back to white */ }
			panelComponent.getChildren().add(LineComponent.builder()
					.left("Team:")
					.right(pluginConfig.team.name)
					.rightColor(teamColor)
					.build());
		}

		// Date — UTC for unambiguous time-of-drop on the proof image.
		panelComponent.getChildren().add(LineComponent.builder()
				.left("Date:")
				.right(ZonedDateTime.now(ZoneOffset.UTC).format(DATE_FMT))
				.build());

		return super.render(graphics);
	}

	static boolean isEventActive(PluginConfigResponse.EventInfo event) {
		// Missing, or force-ended by admin
		if (event == null || event.forceEndedAt != null) {
			return false;
		}

		// Not started yet, already ended, or an unparseable date (treated as inactive).
		Instant now = Instant.now();
		return !outside(event.startDate, "startDate", now, true) && !outside(event.endDate, "endDate", now, false);
	}

	/** True when {@code iso} is set and {@code now} is before it ({@code start}) or after it — or it won't parse. */
	private static boolean outside(String iso, String field, Instant now, boolean start) {
		if (iso == null) {
			return false;
		}
		try {
			Instant at = Instant.parse(iso);
			return start ? now.isBefore(at) : now.isAfter(at);
		} catch (Exception e) {
			log.warn("Failed to parse event {} '{}': {}", field, iso, e.getMessage());
			return true;
		}
	}
}
