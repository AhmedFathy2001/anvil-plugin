package com.anvil.ui;

import java.awt.GridBagLayout;
import com.anvil.ui.SidebarDataSource;
import com.anvil.ui.ConnectionView;
import com.anvil.io.BannerSoundService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The buttons at the bottom of a card — the things the sidebar DOES rather than shows.
 *
 * <p>Two kinds, and they are different in a way worth keeping straight. Roster sync and profile sync
 * act on this clan's site and are gated on being able to: a button that can only collect a 403 is
 * worse than no button. The banner-sound rows act on a folder on this machine, belong to nobody's
 * account, and are always available.</p>
 *
 * <p>They ride at the bottom of an event view too. They belong to the clan, not to the page you
 * happen to be on — losing them the moment you opened an event was half of why the roster button
 * looked like it came and went.</p>
 */
public class PanelActions
{
	/** Clip rows shown before the rest collapse into a "+N more" line. */
	private static final int BANNER_CLIPS_SHOWN = 8;

	/**
	 * Roster sync and profile sync, when this account can do them here.
	 *
	 * <p>A roster is scraped from the clan channel you're standing in, so the button is absent for
	 * any clan but your own — an admin elsewhere still can't see a roster they aren't in — and it
	 * says why rather than vanishing without explanation. Both buttons carry the same limits as
	 * their in-game counterparts, which live in the plugin, not here.
	 */
	public JPanel buildPanelActions(ConnectionView c)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		SidebarDataSource.PanelActions actions = dataSource.actionsFor(c.instanceId);
		if (!actions.canSyncRoster && !actions.canSyncProfile && actions.rosterNote == null)
		{
			return panel; // nothing this account can do here; no empty heading either
		}

		panel.add(SidebarChrome.sectionHeader("This clan"));
		panel.add(SidebarChrome.gap(6));

		// Two buttons that do the same KIND of thing belong on one line: stacked full-width they read
		// as a list of unrelated commands, and they cost two rows of a panel that has none to spare.
		// Side by side the labels have to be short, which is why the pair says "Sync roster" and the
		// lone button (nothing to sit beside) keeps the longer "Sync clan roster".
		boolean both = actions.canSyncProfile && actions.canSyncRoster;
		if (both)
		{
			panel.add(SidebarChrome.buttonRow(
				SidebarChrome.actionButton("Sync profile", "Send your collection log and best times to this clan's site",
					dataSource::syncProfile),
				SidebarChrome.actionButton("Sync roster", "Push the in-game clan member list to the site",
					dataSource::syncRoster)));
		}
		else if (actions.canSyncProfile)
		{
			panel.add(SidebarChrome.fullWidth(SidebarChrome.actionButton("Sync profile",
				"Send your collection log and best times to this clan's site", dataSource::syncProfile)));
		}
		else if (actions.canSyncRoster)
		{
			panel.add(SidebarChrome.fullWidth(SidebarChrome.actionButton("Sync clan roster",
				"Push the in-game clan member list to the site", dataSource::syncRoster)));
		}

		if (!actions.canSyncRoster && actions.rosterNote != null)
		{
			if (actions.canSyncProfile)
			{
				panel.add(SidebarChrome.gap(4));
			}
			JLabel note = new JLabel(SidebarChrome.plainText(actions.rosterNote));
			note.setFont(FontManager.getRunescapeSmallFont());
			note.setForeground(SidebarChrome.VALUE_COLOR);
			note.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			panel.add(note);
		}

		return panel;
	}

	/**
	 * Banner clips, which are files in a folder on THIS machine.
	 *
	 * <p>Nothing here is per account or per clan — swapping character or clan doesn't change which
	 * .wav files are on your disk — so the list is the same wherever you are in the panel. A lit
	 * swatch is in the play cycle, a hollow one is muted, and clicking the row flips it.
	 */
	public JPanel buildBannerSounds()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		List<String> clips = dataSource.bannerSounds();
		panel.add(SidebarChrome.sectionHeader("Banner sounds"));
		panel.add(SidebarChrome.gap(6));

		if (clips.isEmpty())
		{
			JLabel none = new JLabel("None added — drop .wav files in the folder.");
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setForeground(SidebarChrome.VALUE_COLOR);
			none.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			panel.add(none);
			panel.add(SidebarChrome.gap(4));
		}
		else
		{
			for (String clip : clips.subList(0, Math.min(clips.size(), BANNER_CLIPS_SHOWN)))
			{
				panel.add(buildClipRow(clip, dataSource.bannerSoundOn(clip)));
				panel.add(SidebarChrome.gap(2));
			}
			if (clips.size() > BANNER_CLIPS_SHOWN)
			{
				JLabel more = new JLabel("+" + (clips.size() - BANNER_CLIPS_SHOWN) + " more in the folder");
				more.setBorder(BorderFactory.createEmptyBorder(2, CLIP_TEXT_INSET, 0, 0));
				more.setFont(FontManager.getRunescapeSmallFont());
				more.setForeground(SidebarChrome.VALUE_COLOR);
				more.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				panel.add(more);
			}
			panel.add(SidebarChrome.gap(4));
		}

		JButton add = new JButton("Add clip");
		SidebarChrome.styleFlatButton(add, Color.WHITE);
		add.setToolTipText("Pick .wav files to copy into the sounds folder");
		add.addActionListener(e -> dataSource.importBannerSounds());
		JButton open = new JButton("Copy folder path");
		SidebarChrome.styleFlatButton(open, Color.WHITE);
		open.setToolTipText("Copy the sounds folder's path — paste it into your file manager to rename or delete clips");
		open.addActionListener(e -> dataSource.copyBannerSoundsPath());
		panel.add(SidebarChrome.buttonRow(add, open));
		return panel;
	}

	/** How far a clip's name sits from the panel edge — the swatch's width plus its gap. */
	private static final int CLIP_TEXT_INSET = 14;

	/**
	 * One clip: a lit swatch, its name, and a click that mutes or unmutes it.
	 *
	 * <p>Colour alone carried this before — a green name meant playing, a grey one meant muted — which
	 * is invisible if you don't already know the rule, and unreadable if you can't separate the two
	 * greens. The swatch says on/off the way the in-game list does, the row lights under the pointer
	 * so it's clearly a control, and the tooltip names the action rather than the state.
	 */
	public JPanel buildClipRow(String clip, boolean on)
	{
		String display = BannerSoundService.displayName(clip);

		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 4));
		row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText(SidebarChrome.plainText(display) + (on ? " — click to mute" : " — muted; click to unmute"));

		JPanel swatch = new JPanel();
		swatch.setPreferredSize(new Dimension(8, 8));
		swatch.setMinimumSize(new Dimension(8, 8));
		swatch.setMaximumSize(new Dimension(8, 8));
		swatch.setBackground(on ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.DARK_GRAY_COLOR);
		swatch.setBorder(BorderFactory.createLineBorder(on
			? ColorScheme.PROGRESS_COMPLETE_COLOR : SidebarChrome.WIDGET_BORDER));
		// Centre the 8px square against the text line rather than stretching it down the row.
		JPanel swatchBox = new JPanel(new GridBagLayout());
		swatchBox.setOpaque(false);
		swatchBox.setPreferredSize(new Dimension(CLIP_TEXT_INSET - 6, 12));
		swatchBox.add(swatch);

		JLabel name = new JLabel(SidebarChrome.plainText(SidebarChrome.ellipsize(display, 26)));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(on ? ColorScheme.TEXT_COLOR : SidebarChrome.VALUE_COLOR);

		row.add(swatchBox, BorderLayout.WEST);
		row.add(name, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				dataSource.toggleBannerSound(clip);
				repaint.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(SidebarChrome.WIDGET_BG_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_COLOR);
			}
		});
		return row;
	}

	private final SidebarDataSource dataSource;

	/** Re-render after a button changes something the card is showing. */
	private final Runnable repaint;

	public PanelActions(SidebarDataSource dataSource, Runnable repaint)
	{
		this.dataSource = dataSource;
		this.repaint = repaint;
	}
}
