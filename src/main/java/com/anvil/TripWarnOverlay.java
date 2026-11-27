package com.anvil;

import com.google.gson.JsonObject;
import java.awt.*;
import javax.inject.*;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.components.*;

/**
 * The pre-trip check's {@code warn} from the site ("?" while it is asked). Ready stays 5s, red 2 minutes at
 * most: AnvilPlugin takes it down sooner (fixed, the fight, combat, the bank). Right-click: Open the panel's card, or Dismiss. Display only.
 */
class TripWarnOverlay extends OverlayPanel {
	volatile JsonObject warn;
	volatile long until;

	@Inject
	TripWarnOverlay(AnvilPlugin plugin) {
		super(plugin);
		setPosition(OverlayPosition.TOP_CENTER);
		panelComponent.setPreferredSize(new Dimension(200, 0));
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Open", "Trip check", e -> plugin.tripOpen());
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Dismiss", "Trip check", e -> warn = null);
	}

	void show(JsonObject w) {
		var ok = UiView.str(w, "ok");
		until = System.currentTimeMillis() + ("true".equals(ok) ? 5_000 : "?".equals(ok) ? 20_000 : 120_000);
		warn = w;
	}

	boolean red() {
		return "false".equals(UiView.str(warn, "ok")) && System.currentTimeMillis() < until;
	}

	public Dimension render(Graphics2D g) {
		var w = warn;
		if (w == null || System.currentTimeMillis() > until) return null;
		var ok = UiView.str(w, "ok");
		panelComponent.getChildren().add(TitleComponent.builder().text(UiView.str(w, "title")).color("true".equals(ok) ? Color.GREEN : "?".equals(ok) ? Color.WHITE : Color.RED).build());
		var lines = UiView.arr(w, "lines");
		if (lines != null) for (var l : lines) {
			panelComponent.getChildren().add(LineComponent.builder().left(l.isJsonPrimitive() ? l.getAsString() : "").leftColor("true".equals(ok) ? Color.WHITE : StartProofOverlay.ORANGE).build());
		}
		return super.render(g);
	}
}
