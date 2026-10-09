package com.anvil;

import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import okhttp3.OkHttpClient;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class SidebarTileDetailLayoutTest
{
	private static AnvilSidebarPanel panel()
	{
		SidebarDataSource stub = new SidebarDataSource()
		{
			@Override
			public List<ConnectionView> fetchConnections()
			{
				return Collections.emptyList();
			}
		};
		return new AnvilSidebarPanel(stub, new BingoApiClient(new Gson(), new OkHttpClient()),
			null, Executors.newSingleThreadScheduledExecutor());
	}

	private static BingoApiClient.BoardTile tile()
	{
		BingoApiClient.BoardTile t = new BingoApiClient.BoardTile();
		t.tileId = 12;
		t.position = 12;
		t.label = "Kill the Alchemical Hydra without losing a single prayer point";
		t.description = "Defeat the Alchemical Hydra in the Karuulm Slayer Dungeon while only using "
			+ "protection prayers at the correct times, keeping your prayer points above zero for the "
			+ "whole fight and finishing the kill without any downtime between phases.";
		t.requirement = "85 Slayer and a Ruby and Diamond bolt pattern for the final phase";
		t.category = "Bossing, Slayer, Combat achievements";
		t.tier = "Gold";
		t.points = 2;
		t.tileType = "kill";
		t.statName = "Alchemical Hydra";
		t.statType = "kc";
		return t;
	}

	private static void layoutTree(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				layoutTree(child);
			}
		}
	}

	private static void dump(Component c, String indent, StringBuilder sb)
	{
		sb.append(indent).append(c.getClass().getSimpleName())
			.append(" x=").append(c.getX()).append(" w=").append(c.getWidth())
			.append(" pref=").append(c.getPreferredSize().width)
			.append(" '").append(c instanceof JLabel ? ((JLabel) c).getText() : "").append("'\n");
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				dump(child, indent + "  ", sb);
			}
		}
	}

	private static void probeNoteLabel() throws Exception
	{
		java.awt.Font f = net.runelite.client.ui.FontManager.getRunescapeSmallFont();
		System.out.println("font=" + f.getFontName() + " size=" + f.getSize());
		String text = "Defeat the Alchemical Hydra in the Karuulm Slayer Dungeon while only using"
			+ " protection prayers at the correct times, keeping your prayer points above zero for the"
			+ " whole fight and finishing the kill without any downtime between phases.";
		String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");

		for (int css : new int[] { 50, 100, 185, 300 })
		{
			String html = "<html><body style='width:" + css + "px'>" + safe + "</body></html>";
			JLabel label = new JLabel(html);
			label.setFont(f);
			System.out.println("css=" + css + " pref=" + label.getPreferredSize().width
				+ "x" + label.getPreferredSize().height);
		}

		String shortHtml = "<html><body style='width:185px'>Status: Open</body></html>";
		JLabel s = new JLabel(shortHtml);
		s.setFont(f);
		System.out.println("short pref=" + s.getPreferredSize().width + "x" + s.getPreferredSize().height);

		java.awt.GraphicsConfiguration gc = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
			.getDefaultScreenDevice().getDefaultConfiguration();
		System.out.println("defaultTransform=" + gc.getDefaultTransform()
			+ " screenResolution=" + java.awt.Toolkit.getDefaultToolkit().getScreenResolution());
		System.out.println("sun.java2d.uiScale=" + System.getProperty("sun.java2d.uiScale"));

		for (int size : new int[] { 8, 10, 12, 16, 24 })
		{
			JLabel l = new JLabel("<html><body style='width:100px'>Hello world</body></html>");
			l.setFont(f.deriveFont((float) size));
			System.out.println("size=" + size + " pref=" + l.getPreferredSize().width);
		}
		JLabel unitless = new JLabel("<html><body style='width:100'>Hello world</body></html>");
		unitless.setFont(f);
		System.out.println("unitless pref=" + unitless.getPreferredSize().width);

		// Render the note label at the width the card actually gives it and see how far right the
		// glyphs land: past the label width means the text spills out of the card.
		for (int given : new int[] { 195, 205, 213, 223 })
		{
			JLabel label = new JLabel("<html><body style='width:185px'>" + safe + "</body></html>");
			label.setFont(f);
			label.setForeground(java.awt.Color.WHITE);
			label.setOpaque(false);
			label.setSize(given, 2000);
			java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(600, 2000,
				java.awt.image.BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D g = img.createGraphics();
			g.setColor(java.awt.Color.BLACK);
			g.fillRect(0, 0, 600, 2000);
			label.paint(g);
			g.dispose();
			int maxTextX = 0;
			int maxTextY = 0;
			int nonBlack = 0;
			for (int y = 0; y < 2000; y++)
			{
				for (int x = 0; x < 600; x++)
				{
					if ((img.getRGB(x, y) & 0xFFFFFF) != 0)
					{
						nonBlack++;
						if (x > maxTextX) maxTextX = x;
						if (y > maxTextY) maxTextY = y;
					}
				}
			}
			System.out.println("given=" + given + " maxTextX=" + maxTextX + " maxTextY=" + maxTextY
				+ " nonBlack=" + nonBlack);
		}
	}

	@Test
	public void tileDetailFitsTheRealSidebar() throws Exception
	{
		AnvilSidebarPanel p = panel();

		Method render = AnvilSidebarPanel.class.getDeclaredMethod("renderTileDetail",
			JPanel.class, JPanel.class, BingoApiClient.BoardTile.class, String.class,
			boolean.class, String.class, boolean.class, Runnable.class);
		render.setAccessible(true);

		Field contentField = AnvilSidebarPanel.class.getDeclaredField("content");
		contentField.setAccessible(true);
		JPanel content = (JPanel) contentField.get(p);

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(body);

		JPanel catalogue = new JPanel();
		catalogue.setLayout(new BoxLayout(catalogue, BoxLayout.Y_AXIS));
		catalogue.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(catalogue);

		JPanel rows = new JPanel();
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setAlignmentX(Component.LEFT_ALIGNMENT);
		catalogue.add(rows);

		Object[] args = { catalogue, rows, tile(), "https://anvil.site/b/1", false, "active",
			true, (Runnable) () -> {} };
		render.invoke(p, args);

		// RuneLite puts the panel's wrapped panel straight into the sidebar JTabbedPane and then
		// sizes that tabbed pane to its own preferred size — reproduce exactly that.
		JTabbedPane sidebar = new JTabbedPane(4);
		sidebar.add(p.getWrappedPanel());
		Dimension pref = sidebar.getPreferredSize();
		sidebar.setSize(pref);
		layoutTree(sidebar);

		StringBuilder sb = new StringBuilder();
		sb.append("tabbed pref=").append(pref.width)
			.append(" wrapped=").append(p.getWrappedPanel().getWidth())
			.append(" panel=").append(p.getWidth())
			.append(" content=").append(content.getWidth()).append('\n');
		dump(body, "  ", sb);
		System.out.println(sb);

		probeNoteLabel();

		// Render the whole panel as the sidebar would show it and save it for inspection.
		int h = Math.max(1, p.getPreferredSize().height);
		java.awt.image.BufferedImage shot = new java.awt.image.BufferedImage(242, h,
			java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D sg = shot.createGraphics();
		sg.setColor(java.awt.Color.DARK_GRAY);
		sg.fillRect(0, 0, 242, h);
		p.getWrappedPanel().paint(sg);
		sg.dispose();
		javax.imageio.ImageIO.write(shot, "png", new java.io.File("/tmp/opencode-sidebar.png"));
		System.out.println("wrote /tmp/opencode-sidebar.png " + 242 + "x" + h);

		int contentWidth = content.getWidth();
		Insets insets = content.getInsets();
		int usable = contentWidth - insets.left - insets.right;

		for (Component child : rows.getComponents())
		{
			if (!(child instanceof JPanel) || child.getWidth() == 0)
			{
				continue;
			}
			int cardWidth = child.getWidth();
			System.out.println("card width=" + cardWidth + " usable=" + usable);
			assertTrue("tile detail card (" + cardWidth + "px) must fit the sidebar content ("
				+ usable + "px)", cardWidth <= usable);
		}
	}
}
