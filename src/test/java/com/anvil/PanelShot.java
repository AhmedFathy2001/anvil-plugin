package com.anvil;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.junit.Assume;
import org.junit.Test;

/**
 * Dev only: draws site panel views to PNGs without the game, to check spacing and wrapping.
 * {@code ./gradlew test --tests com.anvil.PanelShot } with env PANELSHOT=<dir> renders every {@code <dir>/*.json}
 * (a node, or a {@code {ui: node}} view) at the side panel's content width into {@code <dir>/<name>.png}.
 */
public class PanelShot
{
	@Test
	public void shoot() throws Exception
	{
		var dir = System.getenv("PANELSHOT");
		Assume.assumeTrue(dir != null && !dir.isEmpty());
		var files = new File(dir).listFiles((d, n) -> n.endsWith(".json"));
		for (var f : files)
		{
			var node = new JsonParser().parse(new String(Files.readAllBytes(f.toPath()))).getAsJsonObject();
			var view = new JsonObject();
			view.add("ui", node.has("ui") ? node.get("ui") : node);
			var png = new File(dir, f.getName().replace(".json", ".png"));
			SwingUtilities.invokeAndWait(() -> {
				try
				{
					var v = new UiView(new UiView.Host()
					{
						public void load(String view, Map<String, String> args, Consumer<JsonObject> done)
						{
						}

						public void post(String act, Map<String, String> args, Map<String, String> fields, Consumer<JsonObject> done)
						{
						}

						public void site(String path)
						{
						}

						public void lookup(String rsn)
						{
						}

						public void clan(String slug)
						{
						}

						public void pin(int event, int tile, boolean on)
						{
						}

						public void switchClan(String slug)
						{
						}

						public JComponent nativePart(String name)
						{
							return null;
						}
					}, null, null, null);
					v.render(view);
					int w = 205;
					v.setSize(new Dimension(w, 10));
					v.doLayout();
					var pref = v.getPreferredSize();
					v.setSize(w, pref.height);
					layout(v);
					var img = new BufferedImage(w, Math.max(1, pref.height), BufferedImage.TYPE_INT_RGB);
					var g = img.createGraphics();
					g.setColor(new Color(0x28, 0x28, 0x28));
					g.fillRect(0, 0, img.getWidth(), img.getHeight());
					v.printAll(g);
					g.dispose();
					ImageIO.write(img, "png", png);
				}
				catch (Exception e)
				{
					throw new RuntimeException(e);
				}
			});
		}
	}

	static void layout(java.awt.Component c)
	{
		c.doLayout();
		if (c instanceof java.awt.Container)
		{
			for (var k : ((java.awt.Container) c).getComponents())
			{
				layout(k);
			}
		}
	}
}
