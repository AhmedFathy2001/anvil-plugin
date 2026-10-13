package com.anvil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Predicate;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;

/**
 * "Anvil" on a player's right-click menu: opens their character page, {@code /p/<rsn>}.
 *
 * <p>Two places a name can be clicked, wired the two ways RuneLite allows. A player standing in the
 * world gets a player-menu item (MenuManager owns those slots, shared with every other plugin). A name
 * in a list — friends, ignore, chat channel, clan panels, the chatbox, private messages — gets an
 * entry slotted next to the game's own options, the same placement the hiscores "Lookup" uses, so it
 * sits where a person already looks for "who is this".</p>
 *
 * <p>Where it lands: the side panel's Lookup tab when the site serves player cards, the browser when
 * it doesn't (an older site, or signed out).</p>
 *
 * <p>Your own card is the side panel's You tab, not this: the game offers no player menu on
 * yourself.</p>
 *
 * <p>The URL is built here and nowhere else. It always points at {@link BingoApiClient#CANONICAL_SITE}
 * — character pages are not clan-scoped, a character belongs to its player wherever they play.</p>
 */
@Singleton
final class ProfileLookup
{
	static final String OPTION = "Anvil";

	/** List options our entry goes AFTER (below) / BEFORE (above), matching the hiscores plugin. */
	private static final Set<String> AFTER_OPTIONS = Set.of("Message", "Add ignore", "Remove friend", "Delete", "Kick", "Reply");
	private static final Set<String> BEFORE_OPTIONS = Set.of("Add friend", "Remove ignore");

	private final Client client;
	private final MenuManager menuManager;
	private final AnvilConfig config;
	private final EventBus eventBus;

	private boolean playerItemShown;
	/** Opens a name in the side panel's Lookup tab; false when it can't (no card support), and we browse. */
	private volatile Predicate<String> panelOpener;

	@Inject
	ProfileLookup(Client client, MenuManager menuManager, AnvilConfig config, EventBus eventBus)
	{
		this.client = client;
		this.menuManager = menuManager;
		this.config = config;
		this.eventBus = eventBus;
	}

	/** The character page for {@code rsn}, or null when there is no name to look up. */
	static String profileUrl(String rsn)
	{
		if (rsn == null)
		{
			return null;
		}
		// The game pads names with U+00A0; the site folds case and spacing itself, so only the
		// padding has to go. Path-encoded, not form-encoded: '+' in a path is a literal plus.
		String name = rsn.replace(' ', ' ').trim();
		if (name.isEmpty())
		{
			return null;
		}
		return BingoApiClient.CANONICAL_SITE + "/p/" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/** The card API for {@code rsn} ({@code player-card} capability), or null when there is no name. */
	static String apiCardUrl(String rsn)
	{
		String page = profileUrl(rsn);
		return page == null ? null
			: BingoApiClient.CANONICAL_SITE + "/api/players/" + page.substring(page.lastIndexOf('/') + 1) + "/card";
	}

	/** Open {@code rsn}'s character page in the system browser. False when there was no name. */
	static boolean open(String rsn)
	{
		String url = profileUrl(rsn);
		if (url == null)
		{
			return false;
		}
		LinkBrowser.browse(url);
		return true;
	}

	/** The name lists (friends, ignores, chat channels, clans, raid party, private chat) that get the entry. */
	private static final Set<Integer> NAME_LISTS = Set.of(InterfaceID.FRIENDS, InterfaceID.IGNORE, InterfaceID.CHATCHANNEL_CURRENT,
		InterfaceID.CLANS_SIDEPANEL, InterfaceID.CLANS_GUEST_SIDEPANEL, InterfaceID.RAIDS_SIDEPANEL, InterfaceID.PM_CHAT);

	/**
	 * Which side of a list option our entry goes on: +1 after it, -1 before it, 0 not a list entry we
	 * add to. The chatbox's "Kick" is a clan-chat moderation entry, not a name menu, so it is skipped
	 * there — the same carve-out the hiscores plugin makes.
	 */
	static int listPlacement(int groupId, String option)
	{
		boolean nameList = NAME_LISTS.contains(groupId) || (groupId == InterfaceID.CHATBOX && !"Kick".equals(option));
		if (!nameList || option == null)
		{
			return 0;
		}
		if (AFTER_OPTIONS.contains(option))
		{
			return 1;
		}
		return BEFORE_OPTIONS.contains(option) ? -1 : 0;
	}

	static boolean sameTarget(String a, String b)
	{
		return Text.removeTags(a == null ? "" : a).equals(Text.removeTags(b == null ? "" : b));
	}

	void startUp()
	{
		eventBus.register(this);
		syncPlayerItem();
	}

	void shutDown()
	{
		eventBus.unregister(this);
		if (playerItemShown)
		{
			menuManager.removePlayerMenuItem(OPTION);
			playerItemShown = false;
		}
	}

	void setPanelOpener(Predicate<String> opener)
	{
		this.panelOpener = opener;
	}

	/** The panel's Lookup tab when it can show cards, the browser when it can't. */
	private void lookUp(String rsn)
	{
		Predicate<String> opener = panelOpener;
		if (opener != null && rsn != null && opener.test(rsn))
		{
			return;
		}
		open(rsn);
	}

	/** Add or remove the in-world player item to match the setting. Called on start and config change. */
	void syncPlayerItem()
	{
		boolean want = config.profileLookupMenu();
		if (want == playerItemShown)
		{
			return;
		}
		if (want)
		{
			menuManager.addPlayerMenuItem(OPTION);
		}
		else
		{
			menuManager.removePlayerMenuItem(OPTION);
		}
		playerItemShown = want;
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.profileLookupMenu())
		{
			return;
		}
		int groupId = WidgetUtil.componentToInterface(event.getActionParam1());
		int placement = listPlacement(groupId, event.getOption());
		if (placement == 0)
		{
			return;
		}
		// One per name. A name's menu often carries two of the options we sit beside ("Add friend" and
		// "Add ignore" on a stranger in chat), and each used to add its own "Anvil".
		for (net.runelite.api.MenuEntry existing : client.getMenu().getMenuEntries())
		{
			if (OPTION.equals(existing.getOption()) && sameTarget(existing.getTarget(), event.getTarget()))
			{
				return;
			}
		}
		client.getMenu().createMenuEntry(placement > 0 ? -2 : -1)
			.setOption(OPTION)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.setIdentifier(event.getIdentifier())
			.onClick(e -> lookUp(Text.removeTags(e.getTarget())));
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuAction() != MenuAction.RUNELITE_PLAYER || !OPTION.equals(event.getMenuOption()))
		{
			return;
		}
		Player player = event.getMenuEntry().getPlayer();
		if (player != null)
		{
			lookUp(player.getName());
		}
	}
}
