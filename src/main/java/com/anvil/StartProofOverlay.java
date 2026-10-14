package com.anvil;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The starting shot, on screen from the whistle until it's filed: where to stand and the same live
 * checks the sidebar card shows, then a short "you're tracked" before it gets out of the way. Shares
 * its toggle with the arrow to the spot (config startSpotGuide).
 */
class StartProofOverlay extends OverlayPanel
{
	/** How long "you're tracked" stays up once the shot is filed. */
	private static final long DONE_SHOWN_MS = 10_000;
	private static final Color ORANGE = new Color(255, 152, 31);

	private final AnvilPlugin plugin;
	private final AnvilConfig config;

	@Inject
	StartProofOverlay(AnvilPlugin plugin, AnvilConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_CENTER);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.startSpotGuide())
		{
			return null;
		}
		if (plugin.needsStartProof())
		{
			PluginConfigResponse cfg = plugin.getPluginConfig();
			PluginConfigResponse.StartProof proof = cfg == null ? null : cfg.startProof;
			if (proof == null)
			{
				return null;
			}
			title("Starting shot needed", ORANGE);
			if (proof.location != null)
			{
				line("Go to", proof.location, Color.WHITE);
			}
			for (StartProofRules.Check c : plugin.startProofChecks(proof))
			{
				panelComponent.getChildren().add(LineComponent.builder().left(c.text).leftColor(
					c.state == StartProofRules.Check.State.OK ? Color.GREEN
						: c.state == StartProofRules.Check.State.BAD ? Color.RED : Color.LIGHT_GRAY).build());
			}
			if (plugin.startProofSending())
			{
				line("Checking tracking", "sending…", Color.YELLOW);
			}
			else
			{
				line(plugin.autoStartShotEnabled() ? "Taken automatically once all green" : "Press Take starting shot in the Anvil panel",
					"", Color.LIGHT_GRAY);
			}
			return super.render(graphics);
		}
		long filedAt = plugin.startProofFiledAt();
		if (filedAt > 0 && System.currentTimeMillis() - filedAt < DONE_SHOWN_MS)
		{
			title("You're tracked", Color.GREEN);
			line("Starting shot filed — drops and tiles count now", "", Color.WHITE);
			return super.render(graphics);
		}
		return null;
	}

	private void title(String text, Color color)
	{
		panelComponent.getChildren().add(TitleComponent.builder().text(text).color(color).build());
	}

	private void line(String left, String right, Color rightColor)
	{
		panelComponent.getChildren().add(LineComponent.builder().left(left).right(right).rightColor(rightColor).build());
	}
}
