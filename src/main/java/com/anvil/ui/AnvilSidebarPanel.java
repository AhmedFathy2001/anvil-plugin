package com.anvil.ui;

import com.anvil.api.BingoApiClient;
import com.anvil.io.DeviceSignIn;
import com.anvil.ui.view.BoardChoices;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Always-on progress sidebar — a {@link PluginPanel} showing, per connected clan, tiles done and nearest
 * completion. Decoupled from the network: reads everything through {@link SidebarDataSource} (site-relay or
 * single-home; see {@code FEDERATION_WIRE.md} §7/§10). Owns four view states (loading/error/empty/ready), a
 * clan filter, a manual Refresh, and an auto-refresh poll that runs only while the panel is open.
 *
 * <p>A clan can run several things at once — its board plus a live SOTW/BOTW. One event renders straight
 * in; several land on the clickable events list ({@link #eventsOf}) and drill into a board or weekly card
 * from there, with the choice held in {@link #selectedEventKey} so a refresh never bounces the member out.</p>
 *
 * <p><b>Threading:</b> all Swing mutation stays on the EDT; off-EDT work is the blocking
 * {@link SidebarDataSource#fetchConnections()} inside the worker plus the connect flow's scheduled steps,
 * whose callbacks marshal back via invokeLater. {@link Singleton} — one toolbar panel.</p>
 */
@Slf4j
@Singleton
public class AnvilSidebarPanel extends PluginPanel
{
	/** Auto-refresh cadence while the panel is open. Mirrors the plugin's other polls (config/board). */
	private static final int POLL_INTERVAL_MS = 15_000;


	// Anvil theme for the interactive widgets: flat dark surfaces with the gold/orange accent the
	// rest of the sidebar (title, bars, links) already uses — default Swing chrome sticks out badly.

	/** Wrap width for the connect-status line so a long notice wraps instead of clipping (tooltip carries full text). */




	/** Selection key for a clan's own bingo/ladder board in the events list. */


	private final SidebarDataSource dataSource;

	// Header controls (persistent across state changes).
	// Which clan the plugin is pointed at. One Anvil serves every clan and a person can hold seats in
	// several, so this is a real choice rather than a filter over data we already have: picking here
	// re-addresses everything the plugin does — board, stat pushes, submissions, notifications.
	private final JComboBox<BoardChoices.ClanChoice> clanPicker = new JComboBox<>();
	private final JButton refreshButton = new JButton("Refresh");

	// Device sign-in (home-native, DeviceSignIn): shown when a Site URL is configured but no
	// Account Token yet — replaces the copy-the-token-from-your-profile step.
	private final BingoApiClient apiClient;
	private final net.runelite.client.config.ConfigManager configManager;
	/** RuneLite's shared client-lifetime scheduler — paces the sign-in flow's approval polls. */
	private final ScheduledExecutorService executor;
	/** "Sign in with Discord", shown only while there is no Account Token. */
	private final SignInRow signIn;

	private final JPanel content = new JPanel();

	// Only re-renders while the panel is visible; started on activate, stopped on deactivate.
	private final Timer autoRefresh;

	/** The ladder missions board and its one-second tick. */
	private final LadderCard ladderCard = new LadderCard();

	/** The clan summary and the per-event cards under it. */
	private final BoardCards cards = new BoardCards(ladderCard, key -> {
		selectedEventKey = key;
		renderSelected();
	});

	/** Roster sync, profile sync, and the local banner clips. */
	private final PanelActions panelActions;

	/** The starting shot, while one is owed. */
	private final StartProofCard startProofCard;

	/** "Also live" — the boards running in the member's other clans. */
	private final OtherClanBoards otherBoards;

	/** One event in full, and the clan's home list of them. */
	private final EventPages pages;

	// Last snapshot + selected clan — preserved across refreshes so auto-refresh doesn't reset/flicker the list.
	private List<ConnectionView> connections = Collections.emptyList();

	/** Which event the member drilled into, or null for the list. Only meaningful when a clan runs several. */
	private String selectedEventKey;

	// Guards against ActionEvents fired while we rebuild the combo model, and against overlapping fetches.
	private boolean rebuildingPicker;
	private boolean fetchInFlight;

	@Inject
	public AnvilSidebarPanel(SidebarDataSource dataSource, BingoApiClient apiClient,
		net.runelite.client.config.ConfigManager configManager, ScheduledExecutorService executor)
	{
		super(true); // wrap in RuneLite's scroll pane so a long nearest-tiles list scrolls
		this.dataSource = dataSource;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.executor = executor;
		// The row fetches once a token lands, rather than waiting out the fifteen-second poll.
		this.signIn = new SignInRow(apiClient, configManager, executor, this::refresh);
		this.panelActions = new PanelActions(dataSource, this::renderSelected);
		this.startProofCard = new StartProofCard(dataSource);
		this.otherBoards = new OtherClanBoards(dataSource, slug -> {
			dataSource.chooseClan(slug);
			refresh();
		});
		this.pages = new EventPages(dataSource, ladderCard, cards, panelActions,
			startProofCard, otherBoards);

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(BORDER_OFFSET, BORDER_OFFSET, BORDER_OFFSET, BORDER_OFFSET));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildHeader(), BorderLayout.NORTH);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(content, BorderLayout.CENTER);

		autoRefresh = new Timer(POLL_INTERVAL_MS, e -> refresh());
		autoRefresh.setCoalesce(true);

		setContent(EmptyStates.renderLoading());
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel(new BorderLayout(0, 8));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

		JLabel title = new JLabel("Anvil Progress");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);

		SidebarChrome.styleFlatButton(refreshButton, Color.WHITE);
		refreshButton.addActionListener(e -> refresh(true));

		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleRow.add(title, BorderLayout.WEST);
		titleRow.add(refreshButton, BorderLayout.EAST);
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleRow.getPreferredSize().height));
		titleRow.setAlignmentX(LEFT_ALIGNMENT);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(titleRow);
		top.add(Box.createVerticalStrut(6));
		top.add(signIn.component());
		header.add(top, BorderLayout.NORTH);

		// Clan filter — selecting a clan re-renders from the held snapshot (no refetch).
		SidebarChrome.styleClanPicker(clanPicker);
		clanPicker.setRenderer(new SidebarChrome.ClanChoiceRenderer());
		clanPicker.setFocusable(false);
		clanPicker.addActionListener(e ->
		{
			if (rebuildingPicker)
			{
				return;
			}
			BoardChoices.ClanChoice sel = (BoardChoices.ClanChoice) clanPicker.getSelectedItem();
			if (sel == null || sel.slug.equals(dataSource.chosenClan()))
			{
				return;
			}
			// Not a filter over a snapshot we already hold — the other clan's board lives on the site,
			// so this hands the choice to the plugin (which persists it and re-addresses the client)
			// and shows the loading state until the answer comes back.
			selectedEventKey = null; // another clan's events are a different list — start at the top
			// No refresh() here on purpose. The source renders from the config the plugin holds, and
			// that config is still the old clan's until the refetch lands — refreshing now would repaint
			// the clan they just switched away from. The plugin repaints once it has the new answer.
			dataSource.chooseClan(sel.slug);
			setContent(EmptyStates.renderLoading());
		});
		header.add(clanPicker, BorderLayout.SOUTH);

		return header;
	}

	// ---- Lifecycle (Activatable) — poll only while the panel is on screen -------------------------

	@Override
	public void onActivate()
	{
		refresh();
		autoRefresh.start();
		ladderCard.startTicking();
	}

	@Override
	public void onDeactivate()
	{
		autoRefresh.stop();
		ladderCard.stopTicking();
	}

	/**
	 * A new mission opened, or one was claimed — pulse the ladder card gold.
	 *
	 * <p>Signalled from the plugin, off the EDT, which is why the card writes a wall-clock deadline
	 * rather than repainting: the tick it already runs picks the tint up on its next pass.</p>
	 */
	public void flashLadder()
	{
		ladderCard.flash();
	}

	public void clearForCredentialChange()
	{
		connections = Collections.emptyList();
		selectedEventKey = null;
		rebuildingPicker = true;
		clanPicker.removeAllItems();
		rebuildingPicker = false;
		clanPicker.setVisible(false); // no config for the new credentials yet, so no clans to choose between
		signIn.clearStatus();
		signIn.refresh();
		setContent(EmptyStates.renderLoading());
	}

	/** Fetch off the EDT and re-render on return. Cheap to call repeatedly; overlapping calls coalesce via {@link #fetchInFlight}. */
	public void refresh()
	{
		refresh(false);
	}

	/** As {@link #refresh()}; {@code manual} = the member clicked Refresh, so the source is asked to
	 * bypass what it normally caches (the weekly standings) rather than serve a minute-old answer. */
	private void refresh(boolean manual)
	{
		if (fetchInFlight)
		{
			return;
		}
		fetchInFlight = true;
		refreshButton.setEnabled(false);
		if (connections.isEmpty())
		{
			// Nothing on screen yet — show loading. A background poll keeps the current list visible (no flicker).
			setContent(EmptyStates.renderLoading());
		}

		new SwingWorker<List<ConnectionView>, Void>()
		{
			@Override
			protected List<ConnectionView> doInBackground() throws Exception
			{
				return dataSource.fetchConnections(manual);
			}

			@Override
			protected void done()
			{
				fetchInFlight = false;
				refreshButton.setEnabled(true);
				try
				{
					onConnections(get());
				}
				catch (Exception ex)
				{
					// InterruptedException / ExecutionException(SidebarDataException) — surface the message.
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					log.debug("sidebar fetch failed", cause);
					setContent(EmptyStates.renderError(cause.getMessage()));
				}
				signIn.refresh();
			}
		}.execute();
	}

	/** New snapshot in hand — refresh the clan dropdown and render the addressed clan. Runs on the EDT. */
	private void onConnections(List<ConnectionView> fetched)
	{
		connections = fetched == null ? Collections.emptyList() : fetched;
		rebuildClanPicker();

		if (connections.isEmpty())
		{
			selectedEventKey = null;
			setContent(EmptyStates.renderEmpty(apiClient.needsSignIn()));
			return;
		}
		renderSelected();
	}

	/**
	 * The clan the body is showing — the one this plugin is addressing.
	 *
	 * A list of one, in practice: the source renders the clan the client is pointed at, and pointing it
	 * somewhere else is a refetch rather than a re-render. It stays a list because a source is allowed
	 * to have nothing, and because the panel should not care which.
	 */
	private ConnectionView addressedClan()
	{
		return connections.isEmpty() ? null : connections.get(0);
	}

	/**
	 * Repopulate the clan dropdown, suppressing the selection-event storm a setModel provokes.
	 *
	 * <p>Fed from the site's list of this person's seats, not from the boards we happen to have loaded:
	 * the point of the control is to reach a clan whose board we have NOT loaded. Hidden below two
	 * entries, because one option that cannot be changed is not a choice.</p>
	 *
	 * <p>There used to be a rule here that landed a guest on their own clan instead. It is gone
	 * deliberately, not lost: the server applies the stronger version of it when it resolves a clanless
	 * request — a live event first, then a real membership before any guest seat — and two copies of a
	 * default that can disagree is worse than one copy that decides.</p>
	 */
	private void rebuildClanPicker()
	{
		List<BoardChoices.ClanChoice> choices = BoardChoices.ClanChoice.of(dataSource.clans());
		rebuildingPicker = true;
		try
		{
			DefaultComboBoxModel<BoardChoices.ClanChoice> model = new DefaultComboBoxModel<>();
			String chosen = dataSource.chosenClan();
			BoardChoices.ClanChoice toSelect = null;
			for (BoardChoices.ClanChoice c : choices)
			{
				model.addElement(c);
				if (c.slug.equals(chosen))
				{
					toSelect = c;
				}
			}
			clanPicker.setModel(model);
			// Nothing chosen — the site is deciding, which is the Auto row.
			clanPicker.setSelectedItem(toSelect != null ? toSelect : choices.isEmpty() ? null : choices.get(0));
			clanPicker.setVisible(choices.size() > 2);
		}
		finally
		{
			rebuildingPicker = false;
		}
	}


	/**
	 * Render the selected clan: one event drills straight in (today's board card), several show the
	 * events list until the member picks one. A drilled-into event that stopped running (a weekly ended
	 * mid-session) falls back to the list rather than a blank card.
	 */
	private void renderSelected()
	{
		ConnectionView selected = addressedClan();
		if (selected == null)
		{
			setContent(EmptyStates.renderEmpty(apiClient.needsSignIn()));
			return;
		}

		List<BoardChoices.EventEntry> events = BoardChoices.eventsOf(selected);
		BoardChoices.EventEntry chosen = BoardChoices.findEvent(events, selectedEventKey);
		if (chosen != null)
		{
			setContent(pages.eventPage(selected, chosen, true));
			return;
		}
		// Home. Always a real page, even for one event: it's where the clan's own actions live —
		// roster sync, profile sync, banner clips — and going "back" from an event has to arrive
		// somewhere. Opening straight onto a lone event's card left no room for any of that.
		selectedEventKey = null;
		setContent(pages.eventListPage(selected, events));
	}

	/** Swap the single content component and repaint. */
	private void setContent(Component component)
	{
		SwingUtilities.invokeLater(() ->
		{
			content.removeAll();
			if (component instanceof JComponent)
			{
				((JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
			}
			content.add(component);
			content.revalidate();
			content.repaint();
		});
	}

}
