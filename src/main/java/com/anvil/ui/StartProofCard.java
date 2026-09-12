package com.anvil.ui;

import com.anvil.detect.StartProofRules;
import java.awt.Color;
import com.anvil.api.dto.StartProof;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The starting shot: where to stand, this account's keyword, and the button that files it.
 *
 * <p>It captures the frame, burns the proof banner onto it and uploads it. Rendered only while a
 * shot is actually owed — {@link SidebarDataSource#startProof()} returns null the moment one is
 * filed, so the card disappears on its own rather than needing to be dismissed.</p>
 */
public class StartProofCard
{
	private final SidebarDataSource dataSource;

	public StartProofCard(SidebarDataSource dataSource)
	{
		this.dataSource = dataSource;
	}

	/**
	 * The starting-shot prompt: where to stand, this account's keyword, and the one button that
	 * captures the frame, burns the proof banner onto it and files it. Rendered only while a shot is
	 * actually owed — {@link SidebarDataSource#startProof()} returns null the moment one is filed.
	 */
	public JPanel build(StartProof proof)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
			BorderFactory.createEmptyBorder(6, 8, 8, 8)));

		JLabel title = new JLabel("Starting shot needed");
		title.setFont(FontManager.getRunescapeSmallFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		card.add(title);

		if (proof.location != null && !proof.location.isEmpty())
		{
			JLabel where = new JLabel(SidebarChrome.plainText("Go to " + proof.location));
			where.setFont(FontManager.getRunescapeSmallFont());
			where.setForeground(Color.WHITE);
			where.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			where.setToolTipText(SidebarChrome.plainText(proof.location));
			card.add(SidebarChrome.gap(4));
			card.add(where);
		}

		if (proof.keyword != null && !proof.keyword.isEmpty())
		{
			JLabel word = new JLabel(SidebarChrome.plainText("Keyword: " + proof.keyword));
			word.setFont(FontManager.getRunescapeSmallFont());
			word.setForeground(SidebarChrome.VALUE_COLOR);
			word.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			card.add(SidebarChrome.gap(2));
			card.add(word);
		}

		if (proof.maxSessionMinutes > 0)
		{
			// Said here as well as in chat, because it's the one requirement you can't fix after the
			// fact: the logout is what flushes the hiscores the event's baseline is read from.
			JLabel relog = new JLabel(SidebarChrome.plainText("Log out and back in first (within " + proof.maxSessionMinutes + " min)"));
			relog.setFont(FontManager.getRunescapeSmallFont());
			relog.setForeground(Color.WHITE);
			relog.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			relog.setToolTipText("Hiscores only save when you log out, so this is what sets your starting totals.");
			card.add(SidebarChrome.gap(2));
			card.add(relog);
		}

		if ("rejected".equals(proof.status))
		{
			card.add(SidebarChrome.gap(2));
			card.add(SidebarChrome.warningLabel("Your last shot was rejected — take another."));
		}

		// WHY it matters, which the card never said. A player who reads "starting shot needed" and
		// carries on has no way to know their drops are landing in a review queue meanwhile.
		card.add(SidebarChrome.gap(4));
		card.add(SidebarChrome.warningLabel("Drops you send now are held for review until this is filed."));

		String left = StartProofRules.describeWindow(proof, System.currentTimeMillis());
		if (left != null)
		{
			JLabel expires = new JLabel(SidebarChrome.plainText("Asked for another " + left + ", then it lapses"));
			expires.setFont(FontManager.getRunescapeSmallFont());
			expires.setForeground(Color.WHITE);
			expires.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			expires.setToolTipText("Six hours in, the game has logged everyone out anyway, so the shot stops being asked for.");
			card.add(SidebarChrome.gap(2));
			card.add(expires);
		}

		JButton take = new JButton("Take starting shot");
		SidebarChrome.styleFlatButton(take, ColorScheme.BRAND_ORANGE);
		take.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		take.addActionListener(e ->
		{
			take.setEnabled(false);
			take.setText("Sending...");
			// The capture itself hops to the next rendered frame and then to a worker; the panel just
			// asks for it. The button goes away entirely on the next poll, once the site agrees.
			dataSource.captureStartProof();
			// ...unless the capture refused it — standing in the wrong place, or a session too old to
			// have flushed the hiscores. That answer arrives in chat, so the button has to come back
			// rather than sit on "Sending..." until the next poll redraws the card.
			Timer restore = new Timer(4000, ev ->
			{
				take.setText("Take starting shot");
				take.setEnabled(true);
			});
			restore.setRepeats(false);
			restore.start();
		});
		card.add(SidebarChrome.gap(6));
		card.add(take);

		return card;
	}
}
