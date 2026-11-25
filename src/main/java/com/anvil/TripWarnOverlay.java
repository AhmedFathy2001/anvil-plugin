package com.anvil;

import com.google.gson.JsonObject;
import java.awt.*;
import javax.inject.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.components.*;

/** The pre-trip check's {@code warn} from the site: up for 12s (5s when all's well) or until the bank opens. Display only. */
class TripWarnOverlay extends OverlayPanel {
	volatile JsonObject warn;
	volatile long until;

	@Inject
	TripWarnOverlay(AnvilPlugin plugin) {
		super(plugin);
		setPosition(OverlayPosition.TOP_CENTER);
		panelComponent.setPreferredSize(new Dimension(200, 0));
	}

	void show(JsonObject w) {
		boolean ok = w != null && "true".equals(UiView.str(w, "ok"));
		until = System.currentTimeMillis() + (ok ? 5_000 : 12_000);
		warn = w;
	}

	public Dimension render(Graphics2D g) {
		var w = warn;
		if (w == null || System.currentTimeMillis() > until) return null;
		boolean ok = "true".equals(UiView.str(w, "ok"));
		panelComponent.getChildren().add(TitleComponent.builder().text(UiView.str(w, "title")).color(ok ? Color.GREEN : Color.RED).build());
		var lines = UiView.arr(w, "lines");
		if (lines != null) for (var l : lines) {
			panelComponent.getChildren().add(LineComponent.builder().left(l.isJsonPrimitive() ? l.getAsString() : "").leftColor(ok ? Color.WHITE : StartProofOverlay.ORANGE).build());
		}
		return super.render(g);
	}
}
