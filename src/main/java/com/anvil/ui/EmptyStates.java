package com.anvil.ui;

import com.anvil.api.BingoApiClient;
import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.PluginErrorPanel;

/**
 * What the sidebar shows when it has nothing to show.
 *
 * <p>Three states and they are deliberately different, because the reasons are. Loading keeps the
 * clan picker in place — switching clan renders this state on purpose, and yanking the control the
 * member is holding, then putting it back a second later, reads as the click having failed. An error
 * keeps it for the same reason: a clan we cannot reach is still one they can switch away FROM, which
 * is the likeliest thing they want next.</p>
 *
 * <p>And "nothing" is two different nothings that were once told the same story: an install that has
 * never been connected to anything, and a connected account with no event running. The first needs
 * setting up; the second is working fine and has nothing on today.</p>
 */
public final class EmptyStates
{
	private EmptyStates()
	{
	}

	// ---- Render states ----------------------------------------------------------------------------

	public static Component renderLoading()
	{
		// The picker STAYS. Switching clan renders this state on purpose, and yanking the control the
		// member is holding — then putting it back a second later — reads as the click having failed.
		JLabel loading = new JLabel("Loading progress…", SwingConstants.CENTER);
		loading.setForeground(SidebarChrome.VALUE_COLOR);
		loading.setBorder(BorderFactory.createEmptyBorder(24, 0, 0, 0));
		return loading;
	}

	public static Component renderError(String message)
	{
		// A clan we cannot reach is still a clan they can switch away FROM, which is the likeliest thing
		// they want next. Keep the control; the dropdown's own rebuild decides whether it has rows.
		PluginErrorPanel error = new PluginErrorPanel();
		error.setContent("Couldn't load progress",
			(message == null || message.isEmpty() ? "Something went wrong." : message)
				+ " Use Refresh to try again.");
		return error;
	}

	/** @param needsSignIn true when there is no Account Token — a fresh install, not a quiet week. */
	public static Component renderEmpty(boolean needsSignIn)
	{
		// TWO DIFFERENT NOTHINGS, and they were being told the same story. "Link a clan on your Anvil
		// site" is advice for somebody who HAS a site and an account; a fresh install has neither, and
		// reading it as their first contact with the plugin leaves them looking for a clan page nobody
		// has given them the address of. Signed out is the state to answer first, because it is the
		// state every new install starts in.
		if (needsSignIn)
		{
			return firstRunCard();
		}
		PluginErrorPanel empty = new PluginErrorPanel();
		empty.setContent("No connected clans",
			"Join your clan on the Anvil site — or start one, it's free — and its board progress shows up here.");
		return empty;
	}

	/**
	 * What a fresh install sees: what this is, the one button that connects it, and where that goes.
	 *
	 * <p>The sign-in button itself lives in the header (it is shown in exactly this state), so this
	 * points at it rather than repeating it. What it adds is the half a stranger cannot guess — that
	 * the account is free, that it is theirs rather than their clan's, and that a person with no clan
	 * still gets something out of it. The link is the only address here, and it is the same one
	 * pressing Sign in would write.</p>
	 */
	private static JPanel firstRunCard()
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARK_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		card.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		card.add(SidebarChrome.sectionHeader("New here?"));
		card.add(SidebarChrome.gap(6));
		card.add(SidebarChrome.note("Anvil tracks your OSRS progress — collection log, personal bests, records — and"
			+ " your clan's bingo boards. Press Sign in with Discord above to make your free account"
			+ " and connect this client in one step."));
		card.add(SidebarChrome.gap(6));
		card.add(SidebarChrome.note("You don't need a clan: your profile is your own. If you run one, starting it"
			+ " there is free too."));
		card.add(SidebarChrome.siteLink(BingoApiClient.CANONICAL_SITE.replaceFirst("^https?://", "") + " ↗",
			"Open Anvil in your browser", BingoApiClient.CANONICAL_SITE));

		return card;
	}
}
