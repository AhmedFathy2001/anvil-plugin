package com.anvil.ui;

import com.anvil.AnvilConfig;
import com.anvil.api.BingoApiClient;
import com.anvil.api.EventConfigStore;
import com.anvil.clan.ClanRosterService;
import com.anvil.clog.ProfileSync;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;

/**
 * The two "Anvil" buttons the plugin draws into the game's own title bars.
 *
 * <p>One sits in the collection log's bar beside WikiSync's, and syncs the whole log; the other sits
 * in the clan window's bar beside Wise Old Man's "Sync WOM Group", and pushes the roster. Both are
 * gated on being able to do the thing they offer — a button that can only produce "you are not
 * signed in" is worse than no button.</p>
 *
 * <h2>Why they are re-evaluated every tick</h2>
 *
 * <p>They were once drawn on two scripts and never reconsidered, so anything that changed while the
 * interface stayed open was invisible to them: enabling WikiSync stacked its button on top of ours,
 * disabling it left ours in the far slot with a gap beside it, and turning our own sync setting off
 * left a button that no longer did anything. WikiSync's own button appears and disappears instantly
 * because it is re-evaluated, not because it is drawn more cleverly.</p>
 *
 * <p>A tick is 600ms and {@code refresh()} returns on an identity scan when nothing has moved, so
 * this is cheap; it only runs at all while the relevant window is open.</p>
 */
@Singleton
public class GameTabButtons
{
	// Where our title-bar buttons sit, measured from each bar's RIGHT edge. The collection log's
	// close button ends at 28 and WikiSync's button takes 33..104, so we start after it; the clan
	// window has only its close button, so we sit straight beside that.
	private static final int CLOG_BUTTON_OFFSET = 109;

	// The slot WikiSync would occupy (33..104), taken when it is not running. Without this the far
	// offset was unconditional and the button sat a slot-width from the close button with an empty
	// gap between, reserved for a plugin that was not there.
	private static final int CLOG_BUTTON_NEAR_OFFSET = 33;

	private static final int CLAN_BUTTON_OFFSET = 33;

	private final Client client;
	private final ClientThread clientThread;
	private final AnvilConfig config;
	private final BingoApiClient apiClient;
	private final ProfileSync profileSync;
	private final ClanRosterService roster;
	private final EventConfigStore board;

	@Inject
	GameTabButtons(Client client, ClientThread clientThread, AnvilConfig config,
		BingoApiClient apiClient, ProfileSync profileSync, ClanRosterService roster,
		EventConfigStore board)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.profileSync = profileSync;
		this.roster = roster;
		this.board = board;
	}

	/**
	 * The "Anvil" button in the collection log header.
	 *
	 * <p>Built on start-up rather than in a field initialiser: {@code client} arrives by injection,
	 * so an initialiser would capture null. Enabled on the same condition the sidebar's Sync profile
	 * is — a site and a token.</p>
	 */
	private HeaderButton clogSyncButton;

	/**
	 * The "Anvil" button in the CLAN window's header.
	 *
	 * <p>Gated on the same three things the sidebar's Sync-roster button is: a configured site, site
	 * admin in the clan being addressed, and a readable clan channel. The admin answer is per clan
	 * and dropped on a switch (see forgetAdminAnswerOnClanChange), so this cannot linger into a clan
	 * where pressing it could only collect a 403.</p>
	 */
	private HeaderButton clanSyncButton;

	/**
	 * Build both buttons.
	 *
	 * <p>The action is the verb ALONE: the game renders a menu entry as "&lt;action&gt; &lt;name&gt;",
	 * and the name is "Anvil", so spelling it in both produced "Sync to Anvil Anvil".</p>
	 *
	 * <p>UNIVERSE, not the entry pane's HEADER the collection-log button spent its first release in —
	 * that is the strip naming the boss you have selected, halfway down the interface. UNIVERSE is
	 * the container WikiSync and RuneProfile draw into, which is what puts us in the title bar beside
	 * them and in the same stacking order rather than behind them.</p>
	 *
	 * <p>The offset walks left from the bar's right edge: the close button ends at 28, WikiSync takes
	 * 33..104, and we take the next slot along. An absolute-right offset holds at any window size,
	 * where measuring off a neighbour moves the moment they move.</p>
	 */
	public void onStartUp()
	{
		clogSyncButton = new HeaderButton(
			client, InterfaceID.Collection.UNIVERSE, InterfaceID.Collection.SEARCH_TOGGLE,
			CLOG_BUTTON_OFFSET, CLOG_BUTTON_NEAR_OFFSET, "Anvil", "Sync to",
			() -> apiClient.isConfigured() && config.syncClog(), profileSync::syncProfileNow);
		clanSyncButton = new HeaderButton(
			client, InterfaceID.ClansInfo.UNIVERSE, InterfaceID.ClansInfo.CLOSE,
			CLAN_BUTTON_OFFSET, "Anvil", "Sync roster to",
			() -> apiClient.isConfigured() && roster.isAdmin() && roster.isClanRosterReadable(),
			this::syncRosterFromPanel);
	}

	/**
	 * The clan window's button, pressed.
	 *
	 * <p>Same work as the sidebar's, with its own in-flight guard so a double click is one push, and
	 * the result reported in chat where the player is looking. Refused outright when the clan channel
	 * isn't readable — the roster is scraped from it, so there is nothing to send.</p>
	 */
	private void syncRosterFromPanel()
	{
		roster.syncFromPanel(board::repaintSidebar);
	}

	/**
	 * OUR WIDGETS ARE OURS TO CLEAR. Disabling the plugin left the buttons sitting in the game's
	 * title bars until the interface happened to be rebuilt — a control for a plugin that is no
	 * longer running, which does nothing when pressed.
	 */
	public void onShutDown()
	{
		if (clogSyncButton != null)
		{
			clientThread.invokeLater(() -> clogSyncButton.hideParts());
		}
		if (clanSyncButton != null)
		{
			clientThread.invokeLater(() -> clanSyncButton.hideParts());
		}
	}

	/** Keep both honest while their window is open. See the class note. */
	public void onGameTick()
	{
		if (clogSyncButton != null)
		{
			clogSyncButton.refresh();
		}
		if (clanSyncButton != null)
		{
			clanSyncButton.refresh();
		}
	}

	/**
	 * The clan window loaded.
	 *
	 * <p>Deferred a tick: the group has loaded, but the header's children (including other plugins'
	 * buttons, which is the whole thing we measure against) are not necessarily built yet.</p>
	 */
	public void onClanWindowLoaded()
	{
		if (clanSyncButton != null)
		{
			clientThread.invokeLater(() -> clanSyncButton.render());
		}
	}

	/**
	 * Draw the collection-log button, on the next client tick rather than inline.
	 *
	 * <p>WikiSync clears every dynamic child of this container before adding its own, on the same
	 * setup script — so drawing inline means whether our button exists depends on which plugin the
	 * event bus reaches first. Deferring puts us after all of them, whatever the load order.</p>
	 *
	 * <p>Called again on every list draw, which is the button's SECOND CHANCE and the reason it needs
	 * one: the setup script was once the only place we drew, and with WikiSync disabled nothing else
	 * touches this container — so a draw that came too early, or landed while the button was disabled
	 * in config, was the only attempt there would ever be and the bar stayed empty until the
	 * interface was rebuilt from scratch. The list draws on every tab change, so a missing button
	 * reappears at the next thing the player does.</p>
	 *
	 * <p>Idempotent: render() returns immediately when ours is still attached, so the common case
	 * costs one identity scan of the container's children.</p>
	 */
	public void renderClogButton()
	{
		if (clogSyncButton != null)
		{
			clientThread.invokeLater(() -> clogSyncButton.render());
		}
	}
}
