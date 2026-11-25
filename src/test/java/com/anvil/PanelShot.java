package com.anvil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
 * There is no ItemManager here, so item icons draw blank; with env PANELSHOT_ICONS=<dir of <itemId>.png>
 * (the wiki's inventory pictures) every standalone item icon is drawn from that folder instead.
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
			var ui = node.has("ui") ? node.get("ui") : node;
			var icons = System.getenv("PANELSHOT_ICONS");
			if (icons != null) ui = itemsAsImages(ui);
			view.add("ui", ui);
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

						public void image(String path, int w, int h, boolean motion, Consumer<javax.swing.Icon> done)
						{
							done.accept(icons == null || !path.startsWith(ITEM) ? null : itemIcon(new File(icons, path.substring(ITEM.length()) + ".png"), w, h));
						}
					}, null, null, null);
					v.render(view);
					// PANELSHOT_QUERY: typed into every search box, to see a filtered list.
					var q = System.getenv("PANELSHOT_QUERY");
					if (q != null) type(v, q);
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

	static final String ITEM = "item:";

	/** A copy of the tree with every standalone {@code {t: icon, item}} as an {@code img} the host above draws. */
	static JsonElement itemsAsImages(JsonElement e)
	{
		if (e.isJsonArray())
		{
			var out = new JsonArray();
			e.getAsJsonArray().forEach(x -> out.add(itemsAsImages(x)));
			return out;
		}
		if (!e.isJsonObject())
		{
			return e;
		}
		var o = e.getAsJsonObject();
		var out = new JsonObject();
		boolean item = o.has("t") && "icon".equals(o.get("t").getAsString()) && o.has("item");
		if (item)
		{
			out.addProperty("t", "img");
			out.addProperty("src", ITEM + o.get("item").getAsInt());
			out.addProperty("w", o.has("w") ? o.get("w").getAsInt() : 36);
			out.addProperty("h", o.has("h") ? o.get("h").getAsInt() : 32);
			if (o.has("tip"))
			{
				out.add("tip", o.get("tip"));
			}
			return out;
		}
		o.entrySet().forEach(x -> out.add(x.getKey(), itemsAsImages(x.getValue())));
		return out;
	}

	/** The picture shrunk to fit w x h keeping its shape (as UiView fits the game's own), or null. */
	static javax.swing.Icon itemIcon(File f, int w, int h)
	{
		try
		{
			var img = ImageIO.read(f);
			if (img == null)
			{
				return null;
			}
			double k = Math.min(1.0, Math.min((double) w / img.getWidth(), (double) h / img.getHeight()));
			int iw = Math.max(1, (int) Math.round(img.getWidth() * k));
			int ih = Math.max(1, (int) Math.round(img.getHeight() * k));
			var out = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
			var g = out.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(img, 0, 0, iw, ih, null);
			g.dispose();
			return new javax.swing.ImageIcon(out);
		}
		catch (java.io.IOException ex)
		{
			return null;
		}
	}

	static void type(java.awt.Component c, String q)
	{
		if (c instanceof javax.swing.text.JTextComponent)
		{
			((javax.swing.text.JTextComponent) c).setText(q);
		}
		if (c instanceof java.awt.Container)
		{
			for (var k : ((java.awt.Container) c).getComponents())
			{
				type(k, q);
			}
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
