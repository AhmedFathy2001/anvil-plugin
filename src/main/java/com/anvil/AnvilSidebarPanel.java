package com.anvil;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicComboBoxUI;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.game.SkillIconManager;

import static com.anvil.Ui.*;

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

	/** A teammate's claim on a tile — a calm blue, so it reads as a plan rather than progress. */
	private static final Color CLAIM_COLOR = new Color(0x7D_C4_F0);
	private static final int PROGRESS_BAR_HEIGHT = 6;

	// Anvil theme for the interactive widgets: flat dark surfaces with the gold/orange accent the
	// rest of the sidebar (title, bars, links) already uses — default Swing chrome sticks out badly.
	private static final Color WIDGET_BG = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color WIDGET_BG_HOVER = ColorScheme.DARK_GRAY_HOVER_COLOR;
	private static final Color WIDGET_BORDER = new Color(0x47_47_47);

	/** Wrap width for the connect-status line so a long notice wraps instead of clipping (tooltip carries full text). */
	private static final int STATUS_WRAP_PX = PluginPanel.PANEL_WIDTH - 40;

	/** Max activity rows rendered — keeps the sidebar glanceable; the rest collapse into a "+N more". */
	private static final int ACTIVITY_ROWS_SHOWN = 12;

	/** Clip rows on the home page before the rest collapse into a "+N more" line. */
	private static final int BANNER_CLIPS_SHOWN = 8;

	/** Leaderboard rows rendered on a weekly card before the caller's own (out-of-view) row is spliced in. */
	private static final int WEEKLY_ROWS_SHOWN = 10;

	/** Full-board catalogue rows per page. Large Leagues boards can contain hundreds of tasks. */
	private static final int TILE_CATALOGUE_PAGE_SIZE = 15;

	/** Selection key for a clan's own bingo/ladder board in the events list. */
	private static final String BOARD_EVENT_KEY = "board";

	/** Roughly what fits on one small-font line at {@link PluginPanel#PANEL_WIDTH} — event cards clip to it. */
	private static final int CARD_LINE_CHARS = 30;

	private final AnvilSidebarDataSource dataSource;
	/** Item and boss icons for the player card. Null in tests that build the panel by hand. */
	private final ItemManager itemManager;
	private final SpriteManager spriteManager;
	private final SkillIconManager skillIconManager;

	// ---- Tabs: Clan · You · Lookup ----------------------------------------------------------------
	// Clan is everything the panel always was — `content` below, untouched. You and Lookup are the
	// player card (PlayerCardView), and only exist while the site serves cards (the `player-card`
	// capability): without it the strip hides and the panel is exactly what it was, Sync profile and
	// the sound lists included. That is the rollback — pull the capability and every client goes back.
	enum Tab { CLAN, YOU, LOOKUP, SETTINGS }
	private Tab tab = Tab.CLAN;
	/** Whether the strip is showing, i.e. signed in to a site that serves player cards. */
	private boolean tabsOn;
	// Holds ONLY the selected tab's panel. A CardLayout sized every tab to the tallest one — usually
	// Clan mid-event — and BoxLayout spread that extra height through the You card: log bars drifted
	// apart and the grids were squeezed until their last row clipped.
	private final JPanel tabBody = new JPanel(new BorderLayout());
	private final JPanel tabStrip = new JPanel(new GridBagLayout());
	private final Map<Tab, JLabel> tabLabels = new java.util.EnumMap<>(Tab.class);
	private final JPanel youPanel = column();
	private final JPanel lookupPanel = column();
	/** Lookup is for a player or a clan; this says which the search box means. */
	private boolean lookupClans;
	private final JLabel lookupModePlayers = new JLabel("Players", SwingConstants.CENTER);
	private final JLabel lookupModeClans = new JLabel("Clans", SwingConstants.CENTER);
	/** The player card on screen in Lookup, for "Compare with me". */
	private PlayerCard lookupCard;
	/** Your clan's card under Events — cached per slug for a few minutes; the clan doesn't change by the second. */
	private ClanCard ownClanCard;
	private String ownClanSlug;
	private long ownClanAt;
	private boolean ownClanLoading;
	/** ⚙: syncing and this client's own settings — profile sync, roster sync for admins, sounds. */
	private final JPanel settingsPanel = column();
	private final JPanel lookupResult = column();
	private final JPanel lookupRecentRow = column();
	private final JTextField lookupSearch = new JTextField();
	private final JLabel lookupClear = new JLabel(new GlyphIcon(GlyphIcon.Shape.CLOSE, 10, MUTED));
	private final JPanel lookupBox = new JPanel(new BorderLayout(6, 0));
	/** Newest first, at most five, this session only. */
	private final java.util.LinkedList<String> recentLookups = new java.util.LinkedList<>();
	/** The clan dropdown is wanted by the clan logic AND only shown on the Clan tab. */
	private boolean pickerWanted;
	/** Latest clan view, so the You tab can offer Sync profile for the clan being addressed. */
	private ConnectionView lastConnection;
	/** Discards a card that lands after a newer request was made. */
	private int lookupSeq;
	private int youSeq;
	/** Which profile section each tab's card has open, so a refresh doesn't snap it back. */
	private String youSection;
	private String lookupSection;
	private PlayerCard youCard;
	/** The You card as last built, reused while {@link #youCard} is unchanged — a 15s refresh must not
	 * rebuild it (that reset the open section's layout and collapsed any open log category). */
	private JPanel youCardView;
	private PlayerCard youCardViewOf;
	/** Your goals as last fetched (null until the first fetch lands), and the last thing the site refused. */
	private java.util.List<BingoApiClient.PlayerGoal> youGoals;
	private String goalsError;
	private String youCardRsn;

	// Header controls (persistent across state changes).
	// Which clan the plugin is pointed at. One Anvil serves every clan and a person can hold seats in
	// several, so this is a real choice rather than a filter over data we already have: picking here
	// re-addresses everything the plugin does — board, stat pushes, submissions, notifications.
	private final JComboBox<ClanChoice> clanPicker = new JComboBox<>();
	private final JButton refreshButton = new JButton("Refresh");

	// Device sign-in (home-native, DeviceSignIn): shown whenever there is no Account Token yet — the
	// main way in, so nobody has to copy a token out of the site.
	private final BingoApiClient apiClient;
	private final net.runelite.client.config.ConfigManager configManager;
	/** RuneLite's shared client-lifetime scheduler — paces the sign-in flow's approval polls. */
	private final ScheduledExecutorService executor;
	private final JButton signInButton = new JButton("Sign in with Discord");
	private final JLabel signInStatus = small("", MUTED);
	private final JPanel signInRow = new JPanel(new BorderLayout(0, 2));
	// The token isn't in RuneLite's settings (AnvilConfig.playerToken is hidden), so the panel carries
	// both ends: a manual paste for when the browser sign-in can't work, and Sign out.
	private final JLabel pasteTokenLink = new JLabel("Paste a token instead");
	/** Opens the account menu: Not my account / Sign out. */
	private final JLabel accountMenuLink = new JLabel(new GlyphIcon(GlyphIcon.Shape.ELLIPSIS, 14));
	/** A refresh asked for while one was running — run it when that one lands, don't drop it. */
	private boolean refreshQueued;
	private boolean signInInFlight;

	// The logged-in character's row (SidebarDataSource.AccountRow) — one quiet line, because almost
	// everyone reading it is the owner: "Tracking X · Not you? ×", "Not tracking X here · Track again",
	// or "X is someone else's · It's mine?". The way out for a sibling or a visitor; × hides the
	// tracking line for that character for good.
	private final JLabel accountLabel = small("", MUTED);
	private final JLabel accountLink = small("", ColorScheme.BRAND_ORANGE);
	private final JLabel accountStatus = small("", MUTED);
	private final JPanel accountRowPanel = new JPanel(new BorderLayout(4, 2));
	private SidebarDataSource.AccountRow shownAccountRow;
	private boolean accountActionInFlight;

	private final JPanel content = column();

	// Only re-renders while the panel is visible; started on activate, stopped on deactivate.
	private final Timer autoRefresh;

	// --- Ladder missions board (DMM-All-Stars style) --------------------------------------------
	/** How long a card pulses gold after a new mission / claim (signalled from the plugin off-EDT). */
	private static final int LADDER_FLASH_MS = 4000;
	private static final Color LADDER_FLASH_COLOR = ColorScheme.BRAND_ORANGE;
	/** Ticks the live countdown + per-mission grow/decay value once a second, with NO refetch. */
	private final Timer ladderTick = new Timer(1000, e -> tickLadder());
	/** The currently-rendered ladder card's data (countdown target, decay, missions), or null. */
	private ConnectionView.Ladder ladderState;
	/** Held label refs for the rendered ladder card so the tick updates them in place. */
	private JLabel ladderCountdownLabel;
	private final List<LadderValueLabel> ladderValueLabels = new ArrayList<>();
	private JPanel ladderCardPanel;
	/** Wall-clock (ms) until which the card pulses; written off-EDT by {@link #flashLadder()}. */
	private volatile long ladderFlashUntil;
	/** True while the card currently shows a coloured (flash) border — lets the tick reset it once. */
	private boolean ladderFlashPainted;

	// Last snapshot + selected clan — preserved across refreshes so auto-refresh doesn't reset/flicker the list.
	private ConnectionView connection;

	/** Which event the member drilled into, or null for the list. Only meaningful when a clan runs several. */
	private String selectedEventKey;

	// Full-board catalogue state. The compact nearest-ten list remains the default live view; this is
	// fetched only when a member opens all tiles, or when a revealed pre-start board is opened.
	private final Map<String, BingoApiClient.BoardResponse> boardCatalogues = new HashMap<>();
	private final Set<String> loadingBoardCatalogues = new HashSet<>();
	private final Set<String> failedBoardCatalogues = new HashSet<>();
	private final Map<String, String> boardSearches = new HashMap<>();
	private final Map<String, TileStatusFilter> boardStatusFilters = new HashMap<>();
	private final Map<String, Integer> boardCataloguePages = new HashMap<>();
	/** Tile detail currently drilled into. Kept across the 15-second refresh instead of snapping shut. */
	private String openCatalogueDetailKey;
	private int openCatalogueDetailTileId = -1;
	private boolean showAllActiveTiles;
	/** The open Teams page, kept across refreshes so its search field and page survive. Null = closed. */
	private TeamsView teamsView;
	private int boardCatalogueGeneration;
	/** Do not replace the whole Swing tree underneath somebody who is typing in the tile search. */
	private boolean boardSearchFocused;

	// Guards against ActionEvents fired while we rebuild the combo model, and against overlapping fetches.
	private boolean rebuildingPicker;
	private boolean fetchInFlight;

	/**
	 * The executor for fetches somebody clicked and is now watching a spinner for — off RuneLite's
	 * single shared thread (see {@link BingoApiClient#interactiveExecutor}).
	 */
	private java.util.concurrent.Executor interactive()
	{
		return apiClient != null ? apiClient.interactiveExecutor() : executor;
	}

	/** {@code dataSource} is the Guice-bound {@link AnvilSidebarDataSource}, its only implementation. */
	@Inject
	public AnvilSidebarPanel(SidebarDataSource dataSource, BingoApiClient apiClient,
		net.runelite.client.config.ConfigManager configManager, ScheduledExecutorService executor,
		ItemManager itemManager, SpriteManager spriteManager, SkillIconManager skillIconManager)
	{
		super(true); // wrap in RuneLite's scroll pane so a long nearest-tiles list scrolls
		this.dataSource = (AnvilSidebarDataSource) dataSource;

		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		this.skillIconManager = skillIconManager;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.executor = executor;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(BORDER_OFFSET, BORDER_OFFSET, BORDER_OFFSET, BORDER_OFFSET));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildHeader(), BorderLayout.NORTH);
		SlimScrollBarUI.applyTo(getWrappedPanel());

		buildTabBodies();
		tabBody.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabBody.add(content, BorderLayout.NORTH);
		add(tabBody, BorderLayout.CENTER);

		autoRefresh = new Timer(POLL_INTERVAL_MS, e ->
		{
			if (!boardSearchFocused)
			{
				refresh();
			}
		});
		autoRefresh.setCoalesce(true);

		renderLoading();
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel(new BorderLayout(0, 8));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

		JLabel title = label("Anvil Progress", BOLD, ColorScheme.BRAND_ORANGE);

		styleFlatButton(refreshButton, Color.WHITE);
		refreshButton.addActionListener(e -> refresh(true));

		JPanel titleRow = left(new JPanel(new BorderLayout()));
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleRow.add(title, BorderLayout.WEST);
		titleRow.add(refreshButton, BorderLayout.EAST);
		capH(titleRow);

		// Sign-in affordance — visible whenever there is no token (see refreshSignInRow).
		styleFlatButton(signInButton, ColorScheme.BRAND_ORANGE);
		signInButton.addActionListener(e -> startSignIn());
		signInStatus.setVisible(false);
		signInRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		signInRow.add(signInButton, BorderLayout.NORTH);
		signInRow.add(signInStatus, BorderLayout.CENTER);
		styleLink(pasteTokenLink, "Paste the token from anvilosrs.com (Profile → RuneLite plugin)", this::pasteToken);
		signInRow.add(pasteTokenLink, BorderLayout.SOUTH);
		left(capH(signInRow)).setVisible(false);

		onClick(accountLink, this::onAccountButton);
		accountStatus.setVisible(false);
		styleLink(accountMenuLink, "Account: View my profile, Not my account, Sign out", () -> showAccountMenu(accountMenuLink));
		// One strip for who you are here: the character being tracked on the left, and everything
		// about the sign-in itself behind the ⋯ menu on the right. BorderLayout, not FlowLayout: every
		// label is centred on the same line, where FlowLayout sat the link on its own baseline.
		JPanel accountActions = new JPanel(new BorderLayout(8, 0));
		accountActions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		accountActions.add(accountLink, BorderLayout.CENTER);
		accountActions.add(accountMenuLink, BorderLayout.EAST);
		accountRowPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		accountRowPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		accountRowPanel.add(accountLabel, BorderLayout.CENTER);
		accountRowPanel.add(accountActions, BorderLayout.EAST);
		accountRowPanel.add(accountStatus, BorderLayout.SOUTH);
		left(accountRowPanel).setVisible(false);

		JPanel top = column();
		top.add(titleRow);
		top.add(gap(6));
		top.add(signInRow);
		top.add(accountRowPanel);
		header.add(top, BorderLayout.NORTH);

		// Clan filter — selecting a clan re-renders from the held snapshot (no refetch).
		styleCombo(clanPicker);
		clanPicker.setRenderer(new ClanChoiceRenderer());
		clanPicker.setFocusable(false);
		clanPicker.addActionListener(e ->
		{
			if (rebuildingPicker)
			{
				return;
			}
			ClanChoice sel = (ClanChoice) clanPicker.getSelectedItem();
			if (sel == null || sel.slug.equals(dataSource.chosenClan()))
			{
				return;
			}
			switchClan(sel.slug);
		});
		JPanel south = new JPanel(new BorderLayout(0, 8));
		south.setBackground(ColorScheme.DARK_GRAY_COLOR);
		south.add(buildTabStrip(), BorderLayout.NORTH);
		south.add(clanPicker, BorderLayout.SOUTH);
		header.add(south, BorderLayout.SOUTH);

		return header;
	}

	/**
	 * Address another clan. Not a filter over a snapshot we already hold — the other clan's board lives
	 * on the site, so this hands the choice to the plugin (which persists it and re-addresses the
	 * client) and shows the loading state until the answer comes back.
	 *
	 * <p>No refresh() here on purpose. The source renders from the config the plugin holds, and that
	 * config is still the old clan's until the refetch lands — refreshing now would repaint the clan
	 * they just switched away from. The plugin repaints once it has the new answer.</p>
	 */
	private void switchClan(String slug)
	{
		selectedEventKey = null; // another clan's events are a different list — start at the top
		clearBoardCatalogueState();
		dataSource.chooseClan(slug);
		renderLoading();
	}

	// ---- Tabs ---------------------------------------------------------------------------------------

	private JPanel buildTabStrip()
	{
		tabStrip.setBackground(ColorScheme.DARK_GRAY_COLOR);
		GridBagConstraints gc = new GridBagConstraints();
		gc.fill = GridBagConstraints.BOTH;
		gc.gridy = 0;
		for (Tab t : Tab.values())
		{
			JLabel label;
			if (t == Tab.SETTINGS)
			{
				// An icon, not a fourth word: three named tabs and a narrow gear keep the strip calm.
				label = new JLabel(new GlyphIcon(GlyphIcon.Shape.GEAR, 13), SwingConstants.CENTER);
				label.setToolTipText("Syncing and settings");
				label.setPreferredSize(new Dimension(34, label.getPreferredSize().height));
				gc.weightx = 0;
			}
			else
			{
				label = new JLabel(t == Tab.CLAN ? "Clan" : t == Tab.YOU ? "You" : "Lookup", SwingConstants.CENTER);
				label.setFont(SMALL);
				gc.weightx = 1;
			}
			tabLabels.put(t, onClick(label, () -> selectTab(t)));
			gc.gridx = t.ordinal();
			tabStrip.add(label, gc);
		}
		styleTabs();
		tabStrip.setVisible(false);
		return tabStrip;
	}

	/** Selected: white with an orange underline. The rest: grey over a hairline, like WOM's tabs. */
	private void styleTabs()
	{
		for (Map.Entry<Tab, JLabel> e : tabLabels.entrySet())
		{
			boolean on = e.getKey() == tab;
			e.getValue().setForeground(on ? Color.WHITE : MUTED);
			e.getValue().setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, on ? 2 : 1, 0, on ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR),
				BorderFactory.createEmptyBorder(4, 0, on ? 3 : 4, 0)));
		}
	}

	private void buildTabBodies()
	{

		// Our own field rather than RuneLite's IconTextField: that one draws typed text grey and a
		// light-grey clear button with a red cross, neither of which can be restyled from outside.
		styleSearch(lookupSearch);
		lookupSearch.setBorder(BorderFactory.createEmptyBorder());
		lookupSearch.setToolTipText("A player's name — press Enter");
		lookupSearch.addActionListener(e ->
		{
			if (lookupClans)
			{
				searchClans(lookupSearch.getText());
			}
			else
			{
				showLookup(lookupSearch.getText());
			}
		});
		onEdit(lookupSearch, () -> lookupClear.setVisible(!lookupSearch.getText().isEmpty()));
		lookupClear.setVisible(false);
		lookupClear.setToolTipText("Clear");
		onClick(lookupClear, () ->
		{
			lookupSearch.setText("");
			lookupSearch.requestFocusInWindow();
			showLookupMessage(LOOKUP_HINT);
		});
		lookupBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setBoxLine(ColorScheme.MEDIUM_GRAY_COLOR);
		lookupBox.add(small("Name", MUTED), BorderLayout.WEST);
		lookupBox.add(lookupSearch, BorderLayout.CENTER);
		lookupBox.add(lookupClear, BorderLayout.EAST);
		capH(left(lookupBox));
		// Focus shows as the underline turning orange.
		lookupSearch.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusGained(java.awt.event.FocusEvent e)
			{
				setBoxLine(ColorScheme.BRAND_ORANGE);
			}

			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				setBoxLine(ColorScheme.MEDIUM_GRAY_COLOR);
			}
		});

		JPanel modes = left(new JPanel(new GridLayout(1, 2, 4, 0)));
		modes.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (JLabel m : new JLabel[]{lookupModePlayers, lookupModeClans})
		{
			m.setFont(SMALL);
			m.setOpaque(true);
			m.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
			modes.add(onClick(m, () -> setLookupMode(m == lookupModeClans)));
		}
		capH(modes);
		styleLookupModes();
		lookupPanel.add(modes);
		lookupPanel.add(gap(6));
		lookupPanel.add(lookupBox);
		lookupPanel.add(gap(6));
		lookupPanel.add(lookupRecentRow);
		lookupPanel.add(lookupResult);
		lookupResult.add(cardMessage(LOOKUP_HINT));
	}

	private static final String LOOKUP_HINT = "Search a name — or right-click anyone in game and pick Anvil.";

	/**
	 * Replace whatever Lookup shows with one line, and drop any answer still on its way for what was
	 * there. Returns the new request number for a fetch to check its answer against.
	 */
	private int showLookupMessage(String text)
	{
		lookupResult.removeAll();
		lookupResult.add(cardMessage(text));
		lookupResult.revalidate();
		lookupResult.repaint();
		return ++lookupSeq;
	}


	private void setBoxLine(Color c)
	{
		lookupBox.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, c),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
	}

	/** Signed in to a site that serves player cards — the condition for the strip to show at all. */
	private boolean playerCardsOn()
	{
		return !apiClient.needsSignIn() && dataSource.supportsPlayerCard();
	}

	/** Re-checked on every render: the capability arrives with the config, and sign-out takes it away. */
	private void updateTabStrip()
	{
		styleLookupModes(); // the Clans switch appears once the site says it has clan cards
		boolean on = playerCardsOn();
		if (on != tabsOn)
		{
			tabsOn = on;
			tabStrip.setVisible(on);
			if (!on && tab != Tab.CLAN)
			{
				selectTab(Tab.CLAN);
			}
		}
		applyPicker();
	}

	private void setPickerWanted(boolean wanted)
	{
		pickerWanted = wanted;
		applyPicker();
	}

	private void applyPicker()
	{
		clanPicker.setVisible(pickerWanted && tab == Tab.CLAN);
	}

	private void selectTab(Tab next)
	{
		tab = next;
		tabBody.removeAll();
		tabBody.add(next == Tab.CLAN ? content : next == Tab.YOU ? youPanel : next == Tab.LOOKUP ? lookupPanel : settingsPanel,
			BorderLayout.NORTH);
		tabBody.revalidate();
		styleTabs();
		applyPicker();
		if (next == Tab.YOU)
		{
			renderYou(true);
		}
		else if (next == Tab.SETTINGS)
		{
			renderSettings();
		}
		revalidate();
		repaint();
	}

	/** The ⋯ menu's "View my profile": your own card. */
	public void showYou()
	{
		SwingUtilities.invokeLater(() -> selectTab(Tab.YOU));
	}

	/** Whether a lookup can open in the panel; false ⇒ the caller opens the browser instead. */
	public boolean playerCardsAvailable()
	{
		return playerCardsOn();
	}

	/** Look a character up in the Lookup tab — the right-click "Anvil" entry lands here. */
	public void showLookup(String rawName)
	{
		SwingUtilities.invokeLater(() ->
		{
			String name = rawName == null ? "" : rawName.replace(' ', ' ').trim();
			if (tab != Tab.LOOKUP)
			{
				selectTab(Tab.LOOKUP);
			}
			if (name.isEmpty())
			{
				return;
			}
			lookupSearch.setText(name);
			remember(name);
			lookupThen("Looking up " + name + "…", () -> dataSource.fetchPlayerCard(name), result ->
			{
				lookupResult.add(cardFor(name, result, false));
				lookupCard = result != null ? result.card : null;
				String me = dataSource.myRsn();
				if (lookupCard != null && me != null && !Rsn.same(me, lookupCard.rsn))
				{
					lookupButton("Compare with me", "Side by side: levels, bosses and the log", () -> showPlayerCompare(lookupCard));
				}
			});
		});
	}

	/**
	 * Show {@code waiting} in Lookup, fetch off the EDT, then fill Lookup from the answer — unless a
	 * newer lookup started meanwhile, in which case this answer is for something nobody's looking at.
	 */
	private <T> void lookupThen(String waiting, java.util.function.Supplier<T> fetch, java.util.function.Consumer<T> show)
	{
		int seq = showLookupMessage(waiting);
		async(fetch, result ->
		{
			if (seq != lookupSeq)
			{
				return;
			}
			lookupResult.removeAll();
			show.accept(result);
			lookupResult.revalidate();
			lookupResult.repaint();
		});
	}

	/** {@code fetch} on the interactive executor, then {@code then} with its answer on the EDT. */
	private <T> void async(java.util.function.Supplier<T> fetch, java.util.function.Consumer<T> then)
	{
		interactive().execute(() ->
		{
			T result = fetch.get();
			SwingUtilities.invokeLater(() -> then.accept(result));
		});
	}

	private void lookupButton(String label, String tooltip, Runnable action)
	{
		lookupResult.add(gap(6));
		lookupResult.add(fullWidthButton(label, tooltip, action));
	}

	private void remember(String name)
	{
		recentLookups.removeIf(n -> Rsn.same(n, name));
		recentLookups.addFirst(name);
		while (recentLookups.size() > 5)
		{
			recentLookups.removeLast();
		}
		lookupRecentRow.removeAll();
		if (recentLookups.size() > 1)
		{
			JPanel wrap = left(new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)));
			wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
			wrap.add(small("Recent: ", MUTED));
			boolean first = true;
			for (String n : recentLookups.subList(1, recentLookups.size()))
			{
				if (!first)
				{
					wrap.add(small(" · ", MUTED));
				}
				first = false;
				wrap.add(styleLink(new JLabel(plainText(n)), plainText("Look up " + n), () -> showLookup(n)));
			}
			wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, wrap.getPreferredSize().height * 2));
			lookupRecentRow.add(wrap);
			lookupRecentRow.add(gap(6));
		}
		lookupRecentRow.revalidate();
	}

	/**
	 * Your own card, Sync profile and your sounds.
	 *
	 * @param refetch fetch the card again (opening the tab) rather than redraw what we hold (a refresh)
	 */
	private void renderYou(boolean refetch)
	{
		youPanel.removeAll();
		String me = dataSource.myRsn();
		if (me == null)
		{
			youPanel.add(cardMessage("Log in to a character to see your card."));
		}
		else
		{
			boolean cached = youCard != null && Rsn.same(youCardRsn, me);
			if (cached && youCardViewOf != youCard)
			{
				youCardView = PlayerCardView.render(youCard, cardIcons(youCard.rsn), youSection, sec -> youSection = sec);
				youCardViewOf = youCard;
			}
			youPanel.add(cached ? youCardView : cardMessage("Loading your card…"));
			if (refetch || !cached)
			{
				fetchYou(me);
			}
			if (dataSource.supportsGoals())
			{
				youPanel.add(gap(6));
				youPanel.add(GoalsView.render(youGoals, youCard, this::addGoal, this::removeGoal, goalsError));
				if (refetch || youGoals == null)
				{
					fetchGoals();
				}
			}
		}

		youPanel.revalidate();
		youPanel.repaint();
	}

	private void setLookupMode(boolean clans)
	{
		lookupClans = clans;
		styleLookupModes();
		showLookupMessage(clans ? "Search a clan by name." : LOOKUP_HINT);
	}

	private void styleLookupModes()
	{
		for (JLabel m : new JLabel[]{lookupModePlayers, lookupModeClans})
		{
			boolean on = (m == lookupModeClans) == lookupClans;
			m.setForeground(on ? Color.WHITE : MUTED);
			m.setBackground(on ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
		}
		// Clans only where the site has them; otherwise Lookup stays players-only, as it was.
		lookupModeClans.setVisible(dataSource.supportsClanCard());
		lookupModePlayers.setVisible(dataSource.supportsClanCard());
	}

	private void searchClans(String q)
	{
		String query = q == null ? "" : q.trim();
		lookupThen("Searching…", () -> dataSource.lookupClans(query), found ->
		{
			if (found.isEmpty())
			{
				lookupResult.add(cardMessage("No listed clan matches \"" + query + "\"."));
			}
			for (ClanCard.Found f : found)
			{
				JPanel row = left(new JPanel(new BorderLayout(6, 0)));
				row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
				row.add(small(f.name, Color.WHITE), BorderLayout.CENTER);
				row.add(new JLabel(new GlyphIcon(GlyphIcon.Shape.CHEVRON_RIGHT, 9, MUTED)), BorderLayout.EAST);
				hoverable(capH(row), ColorScheme.DARKER_GRAY_COLOR, () -> showClan(f.slug));
				lookupResult.add(row);
				lookupResult.add(gap(2));
			}
		});
	}

	/** A clan's card in Lookup — its members open as player cards, and it compares with yours. */
	private void showClan(String slug)
	{
		lookupThen("Loading the clan…", () -> dataSource.fetchClanCard(slug), card ->
		{
			if (card == null)
			{
				lookupResult.add(cardMessage("That clan isn't listed on Anvil."));
				return;
			}
			String mine = dataSource.activeClan();
			boolean own = mine != null && mine.equalsIgnoreCase(card.slug);
			lookupResult.add(ClanCardView.render(card, cardIcons(null), name ->
			{
				setLookupMode(false);
				showLookup(name);
			}, own || mine == null || mine.isEmpty() ? null : () -> showClanCompare(card, mine)));
		});
	}

	private void showClanCompare(ClanCard theirs, String mySlug)
	{
		lookupThen("Comparing…", () -> dataSource.fetchClanCard(mySlug), mine ->
		{
			lookupResult.add(backRow("Back to " + theirs.name, () -> showClan(theirs.slug)));
			lookupResult.add(mine == null ? cardMessage("Couldn't load your clan — try again in a moment.") : ClanCardView.compare(mine, theirs));
			if (mine != null)
			{
				lookupButton("Full comparison on the site ↗", "Top players, gainers and highlights side by side",
					() -> LinkBrowser.browse(BingoApiClient.CANONICAL_SITE + "/compare/clans?a=" + urlPart(mine.slug) + "&b=" + urlPart(theirs.slug)));
			}
		});
	}

	private void showPlayerCompare(PlayerCard them)
	{
		String me = dataSource.myRsn();
		if (me == null)
		{
			return;
		}
		lookupThen("Comparing…", () ->
		{
			if (youCard != null && Rsn.same(youCardRsn, me))
			{
				return youCard;
			}
			BingoApiClient.PlayerCardResult r = dataSource.fetchPlayerCard(me);
			return r == null ? null : r.card;
		}, myCard ->
		{
			lookupResult.add(backRow("Back to " + them.rsn, () -> showLookup(them.rsn)));
			lookupResult.add(myCard == null ? cardMessage("Couldn't load your card — try again in a moment.")
				: CompareView.players(myCard, them, cardIcons(null)));
			if (myCard != null)
			{
				// The panel shows the headline and the biggest gaps; the page has every skill, boss and page.
				lookupButton("Full comparison on the site ↗", "Every skill, boss and log page side by side",
					() -> LinkBrowser.browse(BingoApiClient.CANONICAL_SITE + "/compare?a=" + urlPart(myCard.rsn) + "&b=" + urlPart(them.rsn)));
			}
		});
	}

	private static String urlPart(String s)
	{
		return java.net.URLEncoder.encode(s == null ? "" : s.replace('\u00A0', ' ').trim(), java.nio.charset.StandardCharsets.UTF_8)
			.replace("+", "%20");
	}

	private JPanel backRow(String text, Runnable back)
	{
		JPanel row = left(new JPanel(new BorderLayout()));
		row.setBorder(BorderFactory.createEmptyBorder(4, 4, 6, 4));
		JLabel l = small(text, MUTED);
		l.setIcon(new GlyphIcon(GlyphIcon.Shape.CHEVRON_LEFT, 9, MUTED));
		row.add(l, BorderLayout.WEST);
		hoverable(capH(row), ColorScheme.DARK_GRAY_COLOR, back);
		return row;
	}

	/** Your clan's card for the Clan tab, or null when the site has none or there's no clan. Cached. */
	private JPanel ownClanBlock()
	{
		if (!dataSource.supportsClanCard())
		{
			return null;
		}
		String slug = dataSource.activeClan();
		if (slug == null || slug.isEmpty())
		{
			return null;
		}
		boolean fresh = ownClanCard != null && slug.equalsIgnoreCase(ownClanSlug)
			&& System.currentTimeMillis() - ownClanAt < 5 * 60_000L;
		if (!fresh && !ownClanLoading)
		{
			ownClanLoading = true;
			async(() -> dataSource.fetchClanCard(slug), card ->
			{
				ownClanLoading = false;
				ownClanAt = System.currentTimeMillis();
				if (card != null)
				{
					ownClanCard = card;
					ownClanSlug = slug;
					if (tab == Tab.CLAN)
					{
						renderSelected();
					}
				}
			});
		}
		if (ownClanCard == null || !slug.equalsIgnoreCase(ownClanSlug))
		{
			return null;
		}
		JPanel wrap = section("Your clan");
		wrap.add(ClanCardView.render(ownClanCard, cardIcons(null), name ->
		{
			if (tabsOn)
			{
				selectTab(Tab.LOOKUP);
				setLookupMode(false);
				showLookup(name);
			}
		}, null));
		return wrap;
	}

	/** ⚙ — everything that's syncing or a setting rather than something to look at. */
	private void renderSettings()
	{
		settingsPanel.removeAll();
		ConnectionView c = lastConnection;
		SidebarDataSource.PanelActions actions = c == null ? SidebarDataSource.PanelActions.NONE : dataSource.actionsFor();
		if (actions.canSyncProfile)
		{
			heading(settingsPanel, "Your profile");
			settingsPanel.add(fullWidthButton("Sync profile",
				"Send your collection log and best times to Anvil", dataSource::syncProfile));
			settingsPanel.add(gap(12));
		}
		if (c != null)
		{
			// The roster button for an admin at home, or the reason it's absent (buildPanelActions).
			JPanel roster = buildPanelActions(false);
			if (roster.getComponentCount() > 0)
			{
				settingsPanel.add(roster);
				settingsPanel.add(gap(12));
			}
		}
		settingsPanel.add(buildBannerSounds());
		settingsPanel.revalidate();
		settingsPanel.repaint();
	}

	private void fetchGoals()
	{
		async(dataSource::fetchGoals, r -> applyGoals(r, false));
	}

	private void addGoal()
	{
		GoalsView.NewGoal goal = GoalsView.ask(this, youCard);
		if (goal == null)
		{
			return;
		}
		async(() -> dataSource.addGoal(goal.kind, goal.metric, goal.target, goal.dueOn), r -> applyGoals(r, true));
	}

	private void removeGoal(int id)
	{
		async(() -> dataSource.removeGoal(id), r -> applyGoals(r, true));
	}

	/** On the EDT: take the site's answer (the whole list, or why it refused) and redraw You. */
	private void applyGoals(BingoApiClient.GoalsResult result, boolean userAction)
	{
		if (result == null)
		{
			goalsError = userAction ? "Couldn't reach Anvil — try again in a moment." : goalsError;
		}
		else
		{
			goalsError = result.error;
			if (result.goals != null)
			{
				youGoals = result.goals;
			}
		}
		if (tab == Tab.YOU)
		{
			renderYou(false);
		}
	}

	private void fetchYou(String me)
	{
		if (youCardRsn != null && !Rsn.same(youCardRsn, me))
		{
			youGoals = null; // another character: their goals, not the last one's
			goalsError = null;
		}
		int seq = ++youSeq;
		async(() -> dataSource.fetchPlayerCard(me), result ->
		{
			if (seq != youSeq)
			{
				return;
			}
			if (result != null && result.card != null)
			{
				youCard = result.card;
				youCardRsn = me;
				if (tab == Tab.YOU)
				{
					renderYou(false);
				}
			}
			else if (tab == Tab.YOU)
			{
				youPanel.remove(0);
				youPanel.add(cardFor(me, result, true), 0);
				youPanel.revalidate();
				youPanel.repaint();
			}
		});
	}

	private PlayerCardView.Icons cardIcons(String rsn)
	{
		return new PlayerCardView.Icons(itemManager, spriteManager, skillIconManager,
			(page, done) -> async(() -> dataSource.fetchClogPage(rsn, page), done));
	}

	private JPanel cardFor(String name, BingoApiClient.PlayerCardResult result, boolean you)
	{
		if (result == null)
		{
			return cardMessage("Couldn't reach Anvil — try again in a moment.");
		}
		if (result.notFound || result.card == null)
		{
			return PlayerCardView.notFound(name);
		}
		return you
			? PlayerCardView.render(result.card, cardIcons(result.card.rsn), youSection, sec -> youSection = sec)
			: PlayerCardView.render(result.card, cardIcons(result.card.rsn), lookupSection, sec -> lookupSection = sec);
	}

	private static JPanel cardMessage(String text)
	{
		JPanel p = left(new JPanel(new BorderLayout()));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8));
		p.add(small(text, MUTED), BorderLayout.CENTER);
		return capH(p);

	}

	// ---- Lifecycle (Activatable) — poll only while the panel is on screen -------------------------

	@Override
	public void onActivate()
	{
		refresh();
		autoRefresh.start();
		ladderTick.start();
	}

	@Override
	public void onDeactivate()
	{
		autoRefresh.stop();
		ladderTick.stop();
	}

	// ---- Device sign-in (DeviceSignIn) ------------------------------------------------------

	/**
	 * Show the Sign-in button whenever there is no Account Token — and say where it will connect.
	 *
	 * The destination is stated BEFORE the click: nothing is sent to it until then, and pressing the
	 * button is the member explicitly choosing to connect.
	 */
	private void refreshSignInRow()
	{
		if (!signInInFlight)
		{
			boolean show = apiClient.needsSignIn();
			signInRow.setVisible(show);
			if (show)
			{
				setSignInStatus("Connects to " + SetupNudge.SITE_HOST + ".");
			}
			signInRow.revalidate();
			signInRow.repaint();
		}
	}

	/** Run the device sign-in (async, every step on the shared executor); on success store the token —
	 * onConfigChanged does the rest. Both callbacks arrive off the EDT, so they marshal via invokeLater. */
	private void startSignIn()
	{
		if (signInInFlight)
		{
			return;
		}
		signInInFlight = true;
		signInButton.setEnabled(false);
		setSignInStatus("Starting…");

		new DeviceSignIn(apiClient, executor).run(
			line -> SwingUtilities.invokeLater(() -> setSignInStatus(line)),
			result -> SwingUtilities.invokeLater(() ->
			{
				signInInFlight = false;
				signInButton.setEnabled(true);
				if (result.outcome == DeviceSignIn.Outcome.SIGNED_IN)
				{
					// Storing the token fires the plugin's onConfigChanged → client reconfigure,
					// identity stamp + greet, and a sidebar refresh — the same path as a manual paste.
					configManager.setConfiguration("osrsbingo", "playerToken", result.token);

					// Read it straight back. A signed-in-but-empty config is the one failure mode a
					// member can't diagnose: the site says they approved, the panel keeps asking them
					// to sign in, and nothing explains why. It happens when the config write doesn't
					// take — a synced RuneLite profile clobbered by a second client, most often — and
					// it MUST NOT look like the sign-in itself failed. Say what happened and give them
					// the manual route, which always works.
					String stored = configManager.getConfiguration("osrsbingo", "playerToken");
					if (stored == null || stored.isEmpty())
					{
						log.warn("Anvil: signed in but the token did not persist to the RuneLite config");
						setSignInStatus("Signed in, but RuneLite didn't save the token — copy it "
							+ "from Profile > RuneLite plugin on the site and use Paste a token below.");
					}
					else
					{
						setSignInStatus("");
					}
				}
				refreshSignInRow();
				refresh();
			}));
	}

	/** A small orange link that runs {@code action}. */
	private static JLabel styleLink(JLabel link, String tooltip, Runnable action)
	{
		link.setFont(SMALL);
		link.setForeground(ColorScheme.BRAND_ORANGE);
		link.setToolTipText(tooltip);
		return onClick(link, action);
	}

	/** The manual route: paste the token from the site. Storing it runs the same path as Sign in. */
	private void pasteToken()
	{
		if (signInInFlight)
		{
			return;
		}
		String token = JOptionPane.showInputDialog(this,
			"Paste your token from anvilosrs.com (Profile → RuneLite plugin):", "Anvil", JOptionPane.PLAIN_MESSAGE);
		if (token == null || token.trim().isEmpty())
		{
			return;
		}
		configManager.setConfiguration("osrsbingo", "playerToken", token.trim());
		setSignInStatus("");
		refreshSignInRow();
		refresh();
	}

	private void showAccountMenu(java.awt.Component anchor)
	{
		JPopupMenu menu = new JPopupMenu();
		SidebarDataSource.AccountRow row = shownAccountRow;
		if (row != null && row.state == SidebarDataSource.AccountRow.State.TRACKING)
		{
			JMenuItem profile = new JMenuItem("View my profile on Anvil");
			profile.setToolTipText("Open " + row.rsn + "'s Anvil profile in your browser");
			profile.addActionListener(e ->
			{
				if (tabsOn)
				{
					showYou();
				}
				else
				{
					ProfileLookup.open(row.rsn);
				}
			});
			menu.add(profile);
		}
		if (row != null && row.state == SidebarDataSource.AccountRow.State.TRACKING && !accountActionInFlight)
		{
			JMenuItem notMine = new JMenuItem("Not my account…");
			notMine.setToolTipText("Someone else plays " + row.rsn + " here — stop tracking it on this computer");
			notMine.addActionListener(e -> onAccountButton());
			menu.add(notMine);
			menu.addSeparator();
		}
		JMenuItem signOut = new JMenuItem("Sign out…");
		signOut.addActionListener(e -> signOut());
		menu.add(signOut);
		menu.show(anchor, 0, anchor.getHeight());
	}

	private void signOut()
	{
		int choice = JOptionPane.showConfirmDialog(this,
			"Sign this computer out of Anvil? Tracking stops until you sign in again.",
			"Anvil", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION)
		{
			return;
		}
		// Clearing the token fires onConfigChanged, which drops the session state (configureApiClient).
		configManager.setConfiguration("osrsbingo", "playerToken", "");
		refreshSignInRow();
		refresh();
	}

	/** The line under the sign-in button — most importantly the one carrying the approval code. */
	private void setSignInStatus(String text)
	{
		boolean show = setStatus(signInStatus, text, signInRow);
		signInStatus.setToolTipText(show ? text : null);
		signInRow.repaint();
	}

	// ---- The logged-in character's account row -----------------------------------------------------

	private void refreshAccountRow()
	{
		SidebarDataSource.AccountRow row = dataSource.accountRow();
		boolean changed = shownAccountRow == null || row == null
			|| shownAccountRow.state != row.state || !shownAccountRow.rsn.equals(row.rsn);
		shownAccountRow = row;
		// Shown whenever this computer is signed in — it is also where Sign out lives.
		accountRowPanel.setVisible(!apiClient.needsSignIn());
		if (row == null)
		{
			setAccountLabel("Signed in", "Signed in to Anvil on this computer. Log in to a character to start tracking it.", false);
			accountLink.setVisible(false);
			setAccountStatus("");
			accountRowPanel.repaint();
			return;
		}
		if (changed && !accountActionInFlight)
		{
			setAccountStatus("");
		}
		String name = esc(row.rsn);
		switch (row.state)
		{
			case PAUSED:
				setAccountLabel("Not tracking <b>" + name + "</b> on this computer", row.rsn + " is marked Not my account here — nothing is sent for it.", true);
				accountLink.setText("Track again");
				accountLink.setVisible(true);
				break;
			case OTHER:
				setAccountLabel("<b>" + name + "</b> is linked to another Anvil account", row.rsn + " is linked to another Anvil account, so nothing is tracked for you.", true);
				accountLink.setText("It's mine?");
				accountLink.setVisible(true);
				break;
			default:
				setAccountLabel("Tracking <font color='#ffffff'><b>" + name + "</b></font>", "Tracking " + row.rsn + " for your Anvil account.", false);
				// Everyday state: nothing to decide, so the action lives in the Account menu.
				accountLink.setVisible(false);
				break;
		}
		accountLink.setEnabled(!accountActionInFlight);
		capH(accountRowPanel).revalidate();
		accountRowPanel.repaint();
	}

	private void onAccountButton()
	{
		SidebarDataSource.AccountRow row = shownAccountRow;
		if (row == null || accountActionInFlight)
		{
			return;
		}
		SidebarDataSource.AccountRow.Action action;
		String question;
		switch (row.state)
		{
			case PAUSED:
				action = SidebarDataSource.AccountRow.Action.TRACK_AGAIN;
				question = null; // undoing is harmless — no confirm
				break;
			case OTHER:
				action = SidebarDataSource.AccountRow.Action.DISPUTE;
				question = "Ask Anvil staff to move " + row.rsn + " to your Anvil account?\n\n"
					+ "It's linked to someone else right now. Staff will check and move it if it's yours.";
				break;
			default:
				action = SidebarDataSource.AccountRow.Action.NOT_MINE;
				question = "Stop tracking " + row.rsn + " on this computer?\n\n"
					+ "Use this if someone else plays it here — a sibling, a friend. It's unlinked from your "
					+ "Anvil account and nothing is sent while it's logged in. Your other accounts aren't affected, "
					+ "and you can undo it from this panel.";
				break;
		}
		if (question != null && JOptionPane.showConfirmDialog(this, question, "Anvil",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION)
		{
			return;
		}
		accountActionInFlight = true;
		accountLink.setEnabled(false);
		setAccountStatus("Working…");
		dataSource.accountAction(action, outcome -> SwingUtilities.invokeLater(() ->
		{
			accountActionInFlight = false;
			refreshAccountRow();
			setAccountStatus(outcome);
			refresh();
		}));
	}

	/** Room the label leaves for the link (and ⋯) to its right. */
	private static final int ACCOUNT_LINK_ROOM_PX = 72;

	/**
	 * The everyday "Tracking X" stays one quiet line (a long name is clipped; the tooltip has it all).
	 * The rare states that ask for a decision wrap instead, so they're read in full.
	 */
	private void setAccountLabel(String html, String tooltip, boolean wrap)
	{
		accountLabel.setText(wrap
			? wrapHtml(html, STATUS_WRAP_PX - ACCOUNT_LINK_ROOM_PX)
			: "<html><nobr>" + html + "</nobr></html>");
		accountLabel.setToolTipText(tooltip);
	}

	private void setAccountStatus(String text)
	{
		setStatus(accountStatus, text, accountRowPanel);
	}

	/**
	 * A status line under a control, wrapped as HTML so a long line breaks instead of being clipped at
	 * the edge of a narrow sidebar, and hidden while empty. The row holding it is re-capped on every
	 * change: capped once at construction, while the line was hidden, it could never grow to fit it.
	 */
	private static boolean setStatus(JLabel status, String text, JPanel row)
	{
		String plain = text == null ? "" : text;
		boolean show = !plain.isEmpty();
		status.setText(show ? wrapHtml(esc(plain), STATUS_WRAP_PX) : "");
		status.setVisible(show);
		capH(row).revalidate();
		return show;
	}


	// ---- Refresh flow -----------------------------------------------------------------------------

	/**
	 * Forget everything on screen, because it belongs to a site or an account we are no longer using.
	 *
	 * <p>Called the moment the token changes. Without it the panel kept rendering the
	 * previous clan's events, board and roster until a fetch against the NEW credentials succeeded —
	 * which is indefinitely when the new ones are wrong, so the member sits looking at a clan they
	 * just left and reasonably concludes the change didn't take.
	 */
	public void clearForCredentialChange()
	{
		connection = null;
		selectedEventKey = null;
		clearBoardCatalogueState();
		rebuildingPicker = true;
		clanPicker.removeAllItems();
		rebuildingPicker = false;
		setPickerWanted(false); // no config for the new credentials yet, so no clans to choose between
		setSignInStatus("");
		refreshSignInRow();
		renderLoading();
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
			// The login burst asks twice in a row (config, then hello + admin). Dropping the second
			// left the admin button and account strip missing until a manual Refresh.
			refreshQueued = true;
			return;
		}
		fetchInFlight = true;
		refreshButton.setEnabled(false);
		refreshAccountRow();
		if (connection == null)
		{
			// Nothing on screen yet — show loading. A background poll keeps the current list visible (no flicker).
			renderLoading();
		}

		new SwingWorker<ConnectionView, Void>()
		{
			@Override
			protected ConnectionView doInBackground()
			{
				return dataSource.fetch(manual);
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
					// InterruptedException / ExecutionException — surface the message.
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					log.debug("sidebar fetch failed", cause);
					renderError(cause.getMessage());
				}
				refreshSignInRow();
				refreshAccountRow();
				if (refreshQueued)
				{
					refreshQueued = false;
					refresh();
				}
			}
		}.execute();
	}

	/** New snapshot in hand — refresh the clan dropdown and render the addressed clan. Runs on the EDT. */
	private void onConnections(ConnectionView fetched)
	{
		connection = fetched;
		rebuildClanPicker();

		if (connection == null)
		{
			selectedEventKey = null;
			renderEmpty();
			return;
		}
		// A refresh that began just before the member focused Search may finish while they are typing.
		// Keep the new snapshot, but do not replace the field, drop its focus and reset its caret.
		if (boardSearchFocused || teamsView != null)
		{
			return;
		}
		// Refresh an already-visible catalogue too. ETags make unchanged boards cheap, while completed
		// states and scheduled reveals still arrive without making the member close/reopen the list.
		if (showAllActiveTiles)
		{
			loadBoardCatalogue("active", 0, true);
		}
		EventEntry selected = findEvent(eventsOf(connection), selectedEventKey);
		if (selected != null && selected.scheduled != null && selected.scheduled.tileCount > 0)
		{
			loadBoardCatalogue("preview:" + selected.scheduled.id, selected.scheduled.id, true);
		}
		renderSelected();
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
		List<ClanChoice> choices = ClanChoice.of(dataSource.clans());
		rebuildingPicker = true;
		try
		{
			DefaultComboBoxModel<ClanChoice> model = new DefaultComboBoxModel<>();
			String chosen = dataSource.chosenClan();
			ClanChoice toSelect = null;
			for (ClanChoice c : choices)
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
			setPickerWanted(choices.size() > 2);
		}
		finally
		{
			rebuildingPicker = false;
		}
	}

	/**
	 * The live boards in your OTHER clans — everything the merged view adds to the addressed clan's card.
	 *
	 * <p>Pure and static so the two rules that are easy to get quietly wrong can be tested without a
	 * Swing component in sight.</p>
	 *
	 * <p><b>Dedup by event id, not by name.</b> A co-hosted event belongs to every host, so somebody
	 * seated in two co-hosting clans has the same board listed under each. Showing it twice is wrong,
	 * and "Summer Bingo" is not a rare enough name to dedup on.</p>
	 *
	 * <p><b>The board on screen is excluded by its EVENT, not by its slug.</b> Same reason: when the
	 * board you are already looking at in full is co-hosted, the other host's row is that same board.
	 * Filtering only on the slug would list it again, under a different clan's name, as though it
	 * were somewhere else to go.</p>
	 *
	 * <p>And the board on screen is passed IN rather than read off the addressed clan's row, because
	 * those two can disagree. A co-host's own row names whatever that clan reports as live — its
	 * Skill of the Week, say — while the card above shows the co-hosted bingo the member is actually
	 * playing, held on a seat in the host clan. Reading the row would then miss it, and the same
	 * event appeared twice, its two tallies disagreeing.</p>
	 *
	 * @param shownBoard identity of the board the card above is rendering ({@code "bingo:<id>"}), or
	 *                   null/"" when there is none
	 */
	static List<PluginConfigResponse.ClanRef> otherLiveBoards(
		List<PluginConfigResponse.ClanRef> clans, String addressedSlug, String shownBoard)
	{
		List<PluginConfigResponse.ClanRef> out = new ArrayList<>();
		if (clans == null || clans.isEmpty())
		{
			return out;
		}
		java.util.Set<String> seen = new HashSet<>();
		if (shownBoard != null && !shownBoard.isEmpty())
		{
			seen.add(shownBoard); // the board on screen, whichever clan's row also reports it
		}
		String addressed = addressedSlug == null ? "" : addressedSlug;
		for (PluginConfigResponse.ClanRef c : clans)
		{
			if (c != null && addressed.equalsIgnoreCase(c.slug) && c.live != null)
			{
				seen.add(c.live.identity()); // whatever else the addressed clan is running
			}
		}
		for (PluginConfigResponse.ClanRef c : clans)
		{
			if (c == null || c.live == null || c.slug == null || c.slug.isEmpty())
			{
				continue;
			}
			if (addressed.equalsIgnoreCase(c.slug) || !seen.add(c.live.identity()))
			{
				continue;
			}
			out.add(c);
		}
		return out;
	}

	/**
	 * One row of the clan dropdown: a clan to narrow to, or all of them.
	 *
	 * "All clans" is the DEFAULT and it is a real row, not an implied initial state, because it is
	 * also the only way back. A member who narrows to a clan that later goes quiet would otherwise be
	 * stuck watching a dead board while their live one runs somewhere else, with nothing in the UI
	 * admitting it.
	 *
	 * On "All clans" the site decides which board the plugin addresses — live event first — and the
	 * rest appear under "Also live". Choosing a clan narrows to it and moves where submissions file,
	 * which is a bigger deal than a filter usually is; the rows say so.
	 */
	static final class ClanChoice
	{
		/** Clan slug, or "" for Auto. */
		final String slug;
		final String label;
		/** Second line — the board running there, or what this seat is. Never null. */
		final String detail;

		ClanChoice(String slug, String label, String detail)
		{
			this.slug = slug == null ? "" : slug;
			this.label = label == null ? "" : label;
			this.detail = detail == null ? "" : detail;
		}

		/** Auto first, then the clans in the order the site sent them (newest seat first). */
		static List<ClanChoice> of(List<PluginConfigResponse.ClanRef> clans)
		{
			List<ClanChoice> out = new ArrayList<>();
			if (clans == null || clans.isEmpty())
			{
				return out;
			}
			out.add(new ClanChoice("", "All clans", "Everything you're playing"));
			for (PluginConfigResponse.ClanRef c : clans)
			{
				if (c == null || c.slug == null || c.slug.isEmpty())
				{
					continue;
				}
				out.add(new ClanChoice(c.slug, c.name == null || c.name.isEmpty() ? c.slug : c.name, detailOf(c)));
			}
			return out;
		}

		/** What is happening in this clan, in one short line — the reason to pick it or not. */
		private static String detailOf(PluginConfigResponse.ClanRef c)
		{
			if (c.live == null || c.live.eventName == null || c.live.eventName.isEmpty())
			{
				return "guest".equals(c.kind) ? "Guest \u00b7 nothing live" : "Nothing live";
			}
			// A competition has a leaderboard rather than a board to fill, so "0/0" would be a lie
			// dressed as progress. The name is the whole answer there.
			String head = c.live.isWeekly() || c.live.tilesTotal <= 0
				? c.live.eventName
				: c.live.eventName + "  " + c.live.tilesComplete + "/" + c.live.tilesTotal;
			// One line names ONE of them. Say how many were not named rather than letting an arbitrary
			// pick stand in for the whole clan — the number is why you would open the clan at all.
			int more = c.liveCount - 1;
			return more > 0 ? head + "  +" + more + " more" : head;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}


	// ---- Render states ----------------------------------------------------------------------------

	private void renderLoading()
	{
		// The picker STAYS. Switching clan renders this state on purpose, and yanking the control the
		// member is holding — then putting it back a second later — reads as the click having failed.
		JLabel loading = new JLabel("Loading progress…", SwingConstants.CENTER);
		loading.setForeground(MUTED);
		loading.setBorder(BorderFactory.createEmptyBorder(24, 0, 0, 0));
		setContent(loading);
	}

	private void renderError(String message)
	{
		// A clan we cannot reach is still a clan they can switch away FROM, which is the likeliest thing
		// they want next. Keep the control; the dropdown's own rebuild decides whether it has rows.
		PluginErrorPanel error = new PluginErrorPanel();
		error.setContent("Couldn't load progress",
			(message == null || message.isEmpty() ? "Something went wrong." : message)
				+ " Use Refresh to try again.");
		setContent(error);
	}

	private void renderEmpty()
	{
		// TWO DIFFERENT NOTHINGS, and they were being told the same story. "Link a clan on your Anvil
		// site" is advice for somebody who HAS a site and an account; a fresh install has neither, and
		// reading it as their first contact with the plugin leaves them looking for a clan page nobody
		// has given them the address of. Signed out is the state to answer first, because it is the
		// state every new install starts in.
		if (apiClient.needsSignIn())
		{
			setContent(firstRunCard());
			return;
		}
		PluginErrorPanel empty = new PluginErrorPanel();
		empty.setContent("No connected clans",
			"Join your clan on the Anvil site — or start one, it's free — and its board progress shows up here.");
		setContent(empty);
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
	private JPanel firstRunCard()
	{
		JPanel card = section("New here?");
		card.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		card.add(note("Anvil tracks your OSRS progress — collection log, personal bests, records — and"
			+ " your clan's bingo boards. Press Sign in with Discord above to make your free account"
			+ " and connect this client in one step."));
		card.add(gap(6));
		card.add(note("You don't need a clan: your profile is your own. If you run one, starting it"
			+ " there is free too."));
		card.add(siteLink(BingoApiClient.CANONICAL_SITE.replaceFirst("^https?://", "") + " ↗",
			"Open Anvil in your browser", BingoApiClient.CANONICAL_SITE));

		return card;
	}

	/** A wrapped grey paragraph at the panel's width — long copy clips rather than wraps without it. */
	private static JLabel note(String text)
	{
		return note(text, MUTED);
	}

	private static JLabel note(String text, Color color)
	{
		JLabel label = new JLabel(wrapHtml(esc(text == null ? "" : text), STATUS_WRAP_PX));
		label.setFont(SMALL);
		label.setForeground(color);
		return label;
	}

	/**

	 * Redraw from what's already held — the account strip and the clan's actions — with no fetch. For
	 * answers that change locally (name stamped, clan channel loaded, admin confirmed, log opened),
	 * which used to wait for the next 15s poll. Call on the EDT.
	 */
	public void redrawLocal()
	{
		refreshSignInRow();
		refreshAccountRow();
		if (connection != null && !boardSearchFocused)
		{
			renderSelected();
		}
	}

	/**
	 * Render the addressed clan: its home page (the events list), or the event drilled into. A
	 * drilled-into event that stopped running (a weekly ended mid-session) falls back to the list.
	 */
	private void renderSelected()
	{
		ConnectionView selected = connection;

		if (selected == null)
		{
			renderEmpty();
			return;
		}

		List<EventEntry> events = eventsOf(selected);
		EventEntry chosen = findEvent(events, selectedEventKey);
		if (chosen != null)
		{
			renderEvent(selected, chosen, true);
			return;
		}
		// Home. Always a real page, even for one event: it's where the clan's own actions live —
		// roster sync, profile sync, banner clips — and going "back" from an event has to arrive
		// somewhere. Opening straight onto a lone event's card left no room for any of that.
		selectedEventKey = null;
		renderEventList(selected, events);
	}

	/** One event's full card. {@code entry} null (or a board entry) renders the board; weeklies get their own. */
	private void renderEvent(ConnectionView selected, EventEntry entry, boolean withBack)
	{
		JPanel body = column();
		String title = entry != null && !entry.title.isEmpty() ? entry.title : "Event";

		// TEAMS — its own page with a way back, like "All tiles": a roster can be 1,200 names long.
		if (teamsView != null)
		{
			subPage(body, title, this::disposeTeams, "Teams", teamsView);
			return;
		}

		if (withBack)
		{
			body.add(backLink());
			body.add(gap(8));
		}

		if (entry != null && entry.weekly != null)
		{
			clearLadderRefs(); // a weekly card holds no ladder labels — leave the tick idle
			body.add(buildWeeklyCard(entry.weekly));
			if (!entry.weekly.upcoming)
			{
				body.add(gap(12));
				body.add(buildWeeklyStandings(entry.weekly));
			}
			setContent(body);
			return;
		}

		if (entry != null && entry.scheduled != null)
		{
			clearLadderRefs();
			body.add(buildScheduledCard(entry.scheduled));
			if (dataSource.supportsEventRoster())
			{
				final int scheduledId = entry.scheduled.id;
				body.add(gap(8));
				body.add(fullWidthButton("Teams", "Who's on which team", () -> openTeams(scheduledId)));
			}
			if (entry.scheduled.tileCount > 0)
			{
				body.add(gap(12));
				heading(body, entry.scheduled.live ? "Tiles" : "Revealed tiles");
				body.add(buildBoardCatalogue("preview:" + entry.scheduled.id, entry.scheduled.id,
					entry.scheduled.url, !entry.scheduled.live));
			}
			else if (!entry.scheduled.live)
			{
				body.add(gap(10));
				body.add(small("Tiles will appear here when the host reveals them.", MUTED));
			}
			setContent(body);
			return;
		}

		// The full board is its own page rather than an expansion under the event: twenty-odd tile rows
		// pushed everything else off a small client, and "back" is a clearer way out than "Hide".
		if (showAllActiveTiles && selected.tilesTotal > 0)
		{
			body.removeAll();
			subPage(body, title, () -> showAllActiveTiles = false, "All tiles",
				buildBoardCatalogue("active", 0, selected.boardUrl, false));
			return;
		}

		if (selected.hasError())
		{
			body.add(warningLabel(selected.error));
			body.add(gap(8));
		}

		body.add(buildSummary(selected));
		body.add(gap(12));
		if (dataSource.supportsEventRoster())
		{
			body.add(fullWidthButton("Teams", "Who's on which team", () -> openTeams(0)));
			body.add(gap(12));
		}

		// STARTING SHOT — the one thing here that blocks play, so it sits directly under the board
		// summary rather than below the feed: it's an obligation on THIS account.
		PluginConfigResponse.StartProof startProof = dataSource.startProof();
		if (startProof != null)
		{
			body.add(buildStartProofCard(startProof));
			body.add(gap(12));
		}

		// Active now — tiles you and teammates are working right now (deduped by tile).
		if (!selected.activeNow.isEmpty())
		{
			heading(body, "Active now");
			addSpaced(body, selected.activeNow, 8, this::buildActiveRow);
			body.add(gap(12));
		}

		// Missions on an ordinary bingo: their own strip with the same countdown + live values a ladder
		// gets. Without this they only ever showed up as "New tile revealed" lines in the activity feed,
		// which is neither a timer nor a mission board. A ladder's card already IS this, so it's skipped.
		if (selected.ladder != null && !selected.ladder.ladderFormat && !selected.ladder.missions.isEmpty())
		{
			heading(body, "Missions");
			body.add(buildMissionStrip(selected.ladder));
			body.add(gap(12));
		}

		// Player activity — credited events, newest first. Reveals are BOARD news, not something a
		// player did, so they're split out below rather than sitting in a feed of people's actions.
		List<ActivityEntry> actions = new ArrayList<>();
		List<ActivityEntry> reveals = new ArrayList<>();
		for (ActivityEntry e : selected.recentActivity)
		{
			(e.kind == ActivityEntry.Kind.REVEAL ? reveals : actions).add(e);
		}
		if (!actions.isEmpty())
		{
			// "Team activity" is a lie on an individual ladder, where every team is one person.
			heading(body, selected.ladder != null && selected.ladder.ladderFormat ? "Recent activity" : "Team activity");
			body.add(buildActivityFeed(actions));
			body.add(gap(12));
		}
		// A ladder's card and a bingo's mission strip already show what's open with a live countdown, so
		// repeating each drop as a feed line is noise. Only boards WITHOUT that strip list reveals.
		boolean missionStripShown = selected.ladder != null
			&& (selected.ladder.ladderFormat || !selected.ladder.missions.isEmpty());
		if (!reveals.isEmpty() && !missionStripShown)
		{
			heading(body, "Just opened");
			body.add(buildActivityFeed(reveals));
			body.add(gap(12));
		}

		// No live board at all (stub home card / event-less clan) → the summary line already says why;
		// an empty "Nearest tiles" section under it would just restate the absence.
		if (selected.tilesTotal > 0)
		{
			heading(body, "Nearest tiles");
			if (selected.nearestTiles.isEmpty())
			{
				body.add(small("No tiles to show yet.", MUTED));
			}
			addSpaced(body, selected.nearestTiles, 8, this::buildTileRow);
			body.add(gap(8));
			body.add(fullWidthButton("View all " + selected.tilesTotal + " tiles",
				"Search the full board, including manual tiles", () ->
				{
					showAllActiveTiles = true;
					loadBoardCatalogue("active", 0, false);
					renderSelected();
				}));
		}

		// Clan actions (roster/profile sync) live on the clan's home page only: an event view is about
		// that event, and the home page is one click away on "All events".
		setContent(body);
	}

	/** A drilled-in page of an event: "‹ {back}" (running {@code up}), then a heading over {@code content}. */
	private void subPage(JPanel body, String back, Runnable up, String title, Component content)
	{
		body.add(backLink(back, up));
		body.add(gap(8));
		heading(body, title);
		body.add(content);
		setContent(body);
	}

	/** One component per item, {@code gapPx} between them. */
	private static <T> void addSpaced(JPanel into, List<T> items, int gapPx, java.util.function.Function<T, Component> build)
	{
		for (int i = 0; i < items.size(); i++)
		{
			if (i > 0)
			{
				into.add(gap(gapPx));
			}
			into.add(build.apply(items.get(i)));
		}
	}

	// ---- Events list (a clan running more than one thing) ------------------------------------------

	/**
	 * Everything selectable on this clan card: its live board (when there is one) followed by the live
	 * weeklies. Static + package-private so the list/drill-in rule is unit-testable without Swing.
	 */
	static List<EventEntry> eventsOf(ConnectionView c)
	{
		List<EventEntry> out = new ArrayList<>();
		boolean hasBoard = (c.eventName != null && !c.eventName.isEmpty()) || c.tilesTotal > 0;
		String boardTitle = null;
		if (hasBoard)
		{
			// Your own board leads — it's the one with progress on it.
			String title = c.eventName == null || c.eventName.isEmpty() ? c.clanName : c.eventName;
			boardTitle = title;
			out.add(new EventEntry(BOARD_EVENT_KEY, title,
				c.ladder != null && c.ladder.ladderFormat ? "Ladder" : "Bingo", null, null));
		}
		for (ConnectionView.WeeklyView w : c.weeklies)
		{
			out.add(new EventEntry("weekly:" + w.id, w.title, w.kindLabel(), w, null));
		}
		for (ConnectionView.ScheduledView s : c.scheduled)
		{
			// NOT THE BOARD ABOVE, AGAIN. The live board is keyed by a constant and a scheduled entry
			// by its event id, so nothing stopped the same event appearing twice — once as your board
			// with its progress on it, and once from the schedule underneath saying "Running — you're
			// not in it", which is the same event contradicting itself two rows apart.
			//
			// Matched on the title because that is what this view carries; within one clan's own
			// schedule two live boards sharing a name would be the ambiguity, and that is a naming
			// problem rather than one worth a wider fix here.
			if (hasBoard && s.title != null && s.title.equals(boardTitle))
			{
				continue;
			}
			out.add(new EventEntry("event:" + s.id, s.title, s.kindLabel(), null, s));
		}
		return out;
	}

	private static EventEntry findEvent(List<EventEntry> events, String key)
	{
		if (key == null)
		{
			return null;
		}
		for (EventEntry e : events)
		{
			if (e.key.equals(key))
			{
				return e;
			}
		}
		return null;
	}

	/**
	 * One selectable event on a clan card — exactly one of three things: the clan's own board (both
	 * payloads null), a weekly competition, or another/soon bingo event off the schedule.
	 */
	static final class EventEntry
	{
		/** Stable selection key, so the choice survives an auto-refresh. */
		final String key;
		final String title;
		/** "Bingo" / "Ladder" / "Skill of the Week" / "Boss of the Week". */
		final String kind;
		/** The weekly this entry stands for; null unless it IS a weekly. */
		final ConnectionView.WeeklyView weekly;
		/** The scheduled bingo this entry stands for; null unless it IS one. */
		final ConnectionView.ScheduledView scheduled;

		EventEntry(String key, String title, String kind, ConnectionView.WeeklyView weekly,
			ConnectionView.ScheduledView scheduled)
		{
			this.key = key;
			this.title = title == null ? "" : title;
			this.kind = kind == null ? "" : kind;
			this.weekly = weekly;
			this.scheduled = scheduled;
		}

		boolean isBoard()
		{
			return weekly == null && scheduled == null;
		}
	}

	/** The clan's events as clickable cards — the landing view whenever a clan runs more than one. */
	private void renderEventList(ConnectionView c, List<EventEntry> events)
	{
		clearLadderRefs();

		JPanel body = column();

		if (c.hasError())
		{
			body.add(warningLabel(c.error));
			body.add(gap(8));
		}

		if (events.isEmpty())
		{
			// Nothing running is a state, not an error: say so and leave the clan's own actions below.
			body.add(small("No active event yet.", MUTED));
		}
		else
		{
			heading(body, "Events");
			addSpaced(body, events, 8, e -> buildEventCard(c, e));
		}
		body.add(gap(12));

		// Everything else you are playing, in your other clans. Only in the merged view — picking a
		// clan in the dropdown is a filter, and a filter that still showed the others would not be one.
		if (dataSource.chosenClan().isEmpty())
		{
			JPanel elsewhere = buildOtherClanBoards();
			if (elsewhere != null)
			{
				body.add(elsewhere);
				body.add(gap(12));
			}
		}

		// Your clan, under its events — who's carrying the week, what dropped, what's running.
		JPanel clan = ownClanBlock();
		if (clan != null)
		{
			body.add(clan);
			body.add(gap(12));
		}

		// The clan's own controls, under whatever is running. Home only, and only what this account
		// can actually do — see SidebarDataSource.actionsFor.
		lastConnection = c;
		if (!playerCardsOn())
		{
			// With tabs on, syncing and sounds live under ⚙; without them (an older site) they stay here.
			body.add(buildPanelActions(true));
			body.add(gap(12));
			body.add(buildBannerSounds());
		}
		setContent(body);
	}

	/**
	 * "Also live" — your boards in the clans this plugin is NOT currently addressing.
	 *
	 * <p>Summaries rather than cards, and that is a limit rather than a design preference: the rich
	 * layers (nearest tiles, the live feed, the ladder) come out of the ONE config response the plugin
	 * polls, which is about one clan. What the site sends for the others is a name and a tally, so a
	 * name and a tally is what these rows can honestly show.</p>
	 *
	 * <p>Clicking one addresses that clan, which turns its summary into the full card — and moves
	 * where submissions file, which is why each row says so rather than looking like a tab.</p>
	 *
	 * @return null when there is nothing to add, so the caller adds no empty heading
	 */
	private JPanel buildOtherClanBoards()
	{
		List<PluginConfigResponse.ClanRef> others =
			otherLiveBoards(dataSource.clans(), dataSource.activeClan(), dataSource.addressedBoard());
		if (others.isEmpty())
		{
			return null;
		}

		JPanel panel = section("Also live");
		addSpaced(panel, others, 6, this::buildOtherClanRow);
		return panel;
	}

	/** One "also live" row: whose board it is, what it is, and how far along. Click to go there. */
	private JPanel buildOtherClanRow(PluginConfigResponse.ClanRef c)
	{
		String clanName = c.name == null || c.name.isEmpty() ? c.slug : c.name;

		JPanel card = drillCard(clanName, "Switch to " + plainText(clanName) + " — the sidebar and your submissions follow",
			() -> switchClan(c.slug));
		card.add(cardLine(c.live.eventName, ColorScheme.LIGHT_GRAY_COLOR));
		if (c.live.isWeekly() || c.live.tilesTotal <= 0)
		{
			// A competition fills no board, so it gets neither a tally nor a bar — saying "0 / 0 tiles"
			// under a running SOTW reads as a broken board rather than as a leaderboard.
			card.add(small("Competition running", MUTED));
		}
		else
		{
			card.add(small(c.live.tilesComplete + " / " + c.live.tilesTotal
				+ (c.live.pointsScored ? " points" : " tiles"), MUTED));
		}
		if (c.liveCount > 1)
		{
			// This card shows one of them. Switching is how you reach the rest, so the row has to admit
			// there ARE others rather than presenting the freshest as everything happening there.
			card.add(label("+ " + (c.liveCount - 1) + " more here",
				SMALL, ColorScheme.BRAND_ORANGE));
		}

		if (!c.live.isWeekly() && c.live.tilesTotal > 0)
		{
			cardBar(card, Math.max(0, Math.min(100, (int) Math.round(100.0 * c.live.tilesComplete / c.live.tilesTotal))),
				ColorScheme.BRAND_ORANGE);
		}
		return capH(card);
	}

	/**
	 * A clickable card on a list — its title and a chevron marking it as a drill-in, lighting up under
	 * the pointer. The caller adds its lines, then caps its height.
	 */
	private static JPanel drillCard(String title, String tooltip, Runnable open)
	{
		JPanel card = block();
		hoverable(card, ColorScheme.DARKER_GRAY_COLOR, open);
		card.setToolTipText(tooltip);
		JPanel titleRow = row(6);
		JLabel chevron = new JLabel(new GlyphIcon(GlyphIcon.Shape.CHEVRON_RIGHT, 12));
		chevron.setForeground(ColorScheme.BRAND_ORANGE);
		titleRow.add(label(ellipsize(title, 22), REGULAR, ColorScheme.TEXT_COLOR), BorderLayout.CENTER);
		titleRow.add(chevron, BorderLayout.EAST);
		card.add(capH(titleRow));
		return card;
	}

	/** One small line of a list card, clipped to what fits. */
	private static JLabel cardLine(String text, Color color)
	{
		return small(ellipsize(text, CARD_LINE_CHARS), color);
	}

	/** A list card's progress bar, under its lines. */
	private static void cardBar(JPanel card, int percent, Color fg)
	{
		card.add(gap(4));
		card.add(left(capH(bar(percent, fg, ColorScheme.DARK_GRAY_COLOR, PROGRESS_BAR_HEIGHT))));
	}

	/**
	 * Roster sync and profile sync, when this account can do them here.
	 *
	 * <p>A roster is scraped from the clan channel you're standing in, so the button is absent for
	 * any clan but your own — an admin elsewhere still can't see a roster they aren't in — and it
	 * says why rather than vanishing without explanation. Both buttons carry the same limits as
	 * their in-game counterparts, which live in the plugin, not here.
	 */
	private JPanel buildPanelActions(boolean withProfile)
	{
		JPanel panel = column();
		SidebarDataSource.PanelActions full = dataSource.actionsFor();
		// Sync profile sends YOUR log and best times; with the You tab showing it lives there.
		SidebarDataSource.PanelActions actions = withProfile ? full
			: new SidebarDataSource.PanelActions(full.canSyncRoster, false, full.rosterNote);
		if (!actions.canSyncRoster && !actions.canSyncProfile && actions.rosterNote == null)
		{
			return panel; // nothing this account can do here; no empty heading either
		}

		heading(panel, "This clan");

		// Two buttons that do the same KIND of thing belong on one line: stacked full-width they read
		// as a list of unrelated commands, and they cost two rows of a panel that has none to spare.
		// Side by side the labels have to be short, which is why the pair says "Sync roster" and the
		// lone button (nothing to sit beside) keeps the longer "Sync clan roster".
		boolean both = actions.canSyncProfile && actions.canSyncRoster;
		if (both)
		{
			panel.add(buttonRow(
				flatButton("Sync profile", "Send your collection log and best times to this clan's site",
					dataSource::syncProfile),
				flatButton("Sync roster", "Push the in-game clan member list to the site",
					dataSource::syncRoster)));
		}
		else if (actions.canSyncProfile)
		{
			panel.add(fullWidthButton("Sync profile",
				"Send your collection log and best times to this clan's site", dataSource::syncProfile));
		}
		else if (actions.canSyncRoster)
		{
			panel.add(fullWidthButton("Sync clan roster",
				"Push the in-game clan member list to the site", dataSource::syncRoster));
		}

		if (!actions.canSyncRoster && actions.rosterNote != null)
		{
			if (actions.canSyncProfile)
			{
				panel.add(gap(4));
			}
			panel.add(small(actions.rosterNote, MUTED));
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
	private JPanel buildBannerSounds()
	{
		java.util.List<String> clips = dataSource.bannerSounds();
		JPanel panel = section("Banner sounds");

		if (clips.isEmpty())
		{
			panel.add(small("None added — use Add clip, or the banner folder.", MUTED));
			panel.add(gap(4));
		}
		else
		{
			for (String clip : clips.subList(0, Math.min(clips.size(), BANNER_CLIPS_SHOWN)))
			{
				panel.add(buildClipRow(clip, dataSource.bannerSoundOn(clip)));
				panel.add(gap(2));
			}
			if (clips.size() > BANNER_CLIPS_SHOWN)
			{
				JLabel more = small("+" + (clips.size() - BANNER_CLIPS_SHOWN) + " more in the folder", MUTED);
				more.setBorder(BorderFactory.createEmptyBorder(2, CLIP_TEXT_INSET, 0, 0));
				panel.add(more);
			}
			panel.add(gap(4));
		}

		panel.add(buttonRow(
			flatButton("Add clip", "Pick .wav files to copy into the banner sounds folder", Color.WHITE,
				dataSource::importBannerSounds),
			flatButton("Copy folder path", "Copy the sounds folder's path — paste it into your file manager to rename or delete clips",
				Color.WHITE, dataSource::copyBannerSoundsPath)));

		// MISSIONS, stated rather than configured. A mission dropping should not sound like a tile
		// being finished, and what it sounds like is decided by whether this folder has anything in
		// it — so the panel says which of the two is happening, and the button is how you change it.
		panel.add(gap(8));
		int missions = dataSource.missionSounds().size();
		panel.add(small(missions == 0
			? "Missions: a game sound (no clip added)"
			: "Missions: " + missions + (missions == 1 ? " clip" : " clips"), MUTED));
		panel.add(gap(4));
		panel.add(fullWidth(flatButton(missions == 0 ? "Add mission clip" : "Add mission clips",
			"Pick .wav files to play when a mission drops, instead of the game sound used by default",
			Color.WHITE, dataSource::importMissionSounds)));
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
	private JPanel buildClipRow(String clip, boolean on)
	{
		String display = BannerSoundService.displayName(clip);

		JPanel row = left(new JPanel(new BorderLayout(6, 0)));
		row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 4));
		row.setToolTipText(plainText(display) + (on ? " — click to mute" : " — muted; click to unmute"));

		JPanel swatch = new JPanel();
		swatch.setPreferredSize(new Dimension(8, 8));
		swatch.setMinimumSize(new Dimension(8, 8));
		swatch.setMaximumSize(new Dimension(8, 8));
		swatch.setBackground(on ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.DARK_GRAY_COLOR);
		swatch.setBorder(BorderFactory.createLineBorder(on
			? ColorScheme.PROGRESS_COMPLETE_COLOR : WIDGET_BORDER));
		// Centre the 8px square against the text line rather than stretching it down the row.
		JPanel swatchBox = new JPanel(new GridBagLayout());
		swatchBox.setOpaque(false);
		swatchBox.setPreferredSize(new Dimension(CLIP_TEXT_INSET - 6, 12));
		swatchBox.add(swatch);

		row.add(swatchBox, BorderLayout.WEST);
		row.add(small(ellipsize(display, 26), on ? ColorScheme.TEXT_COLOR : MUTED), BorderLayout.CENTER);
		hoverable(capH(row), ColorScheme.DARK_GRAY_COLOR, () ->
		{
			dataSource.toggleBannerSound(clip);
			renderSelected();
			if (tab == Tab.SETTINGS)
			{
				renderSettings();
			}
		});
		return row;
	}

	/** One row of the events list: name, kind, a one-line status, and (for a board) its progress bar. */
	private JPanel buildEventCard(ConnectionView c, EventEntry entry)
	{
		JPanel card = drillCard(entry.title, plainText(entry.title), () ->
		{
			selectedEventKey = entry.key;
			showAllActiveTiles = false;
			disposeTeams();
			renderSelected();
		});
		card.add(small(entry.kind, ColorScheme.LIGHT_GRAY_COLOR));

		if (entry.isBoard())
		{
			card.add(cardLine(boardStatusLine(c), MUTED));
			// A ladder has no fixed board to fill, so its row shows standing instead of a progress bar.
			if (c.tilesTotal > 0 && c.ladder == null)
			{
				cardBar(card, c.completionPercent(), c.completionPercent() >= 100
					? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE);
			}
		}
		else if (entry.weekly != null)
		{
			ConnectionView.WeeklyView w = entry.weekly;
			card.add(cardLine(w.metricLabel() + timingSuffix(w.upcoming, w.startDate, w.endDate), MUTED));
			if (!w.upcoming)
			{
				card.add(cardLine(yourStandingLine(w), w.yourRank > 0 ? ColorScheme.BRAND_ORANGE : MUTED));
			}
		}
		else
		{
			ConnectionView.ScheduledView s = entry.scheduled;
			String timing = timingLabel(!s.live, s.startDate, s.endDate);
			card.add(cardLine(timing == null ? s.sizeLabel() : timing, MUTED));
			// A live event you're not in is worth flagging as joinable; an upcoming one just needs its size.
			card.add(cardLine(s.live ? "Running — you're not in it" : s.sizeLabel(), MUTED));
		}
		return capH(card);
	}

	/** The board row's status line: "14 / 25 tiles · 56%", where you stand on a ladder, or why neither. */
	private static String boardStatusLine(ConnectionView c)
	{
		if (c.ladder != null && c.ladder.ladderFormat)
		{
			return c.ladder.monthRank > 0
				? "You: #" + c.ladder.monthRank + " · " + c.ladder.monthPoints + " pts this month"
				: "Unranked — claim a mission";
		}
		if (c.tilesTotal > 0)
		{
			return c.tilesComplete + " / " + c.tilesTotal + " " + c.unitNoun() + " · " + c.completionPercent() + "%";
		}
		return c.statusNote != null && !c.statusNote.isEmpty() ? c.statusNote : "No board to show yet.";
	}

	/** "‹ All events" — back out of a drilled-into event to the clan's list. */
	private JLabel backLink()
	{
		return backLink("All events", () ->
		{
			selectedEventKey = null;
			showAllActiveTiles = false;
			disposeTeams();
		});
	}

	/** "‹ {label}" — one level up: {@code up} resets whatever drilled in, then the view redraws. */
	private JLabel backLink(String label, Runnable up)
	{
		JLabel back = onClick(small(label, ColorScheme.BRAND_ORANGE), () ->
		{
			up.run();
			renderSelected();
		});
		back.setIcon(new GlyphIcon(GlyphIcon.Shape.CHEVRON_LEFT, 11));
		back.setIconTextGap(2);
		return back;
	}

	// ---- Weekly competition card (SOTW / BOTW) -----------------------------------------------------

	/** The weekly's own summary: what it tracks, how long is left, and where you stand in it. */
	private JPanel buildWeeklyCard(ConnectionView.WeeklyView w)
	{
		JPanel panel = titledCard(w.title, w.kindLabel() + " · " + w.metricLabel(), ColorScheme.LIGHT_GRAY_COLOR);

		String timing = timingLabel(w.upcoming, w.startDate, w.endDate);
		String headcount = w.participants > 0
			? w.participants + (w.participants == 1 ? " player" : " players") : null;
		String line = timing == null ? headcount : (headcount == null ? timing : timing + " · " + headcount);
		if (line != null)
		{
			panel.add(small(line, MUTED));
		}

		panel.add(gap(4));
		// Nothing has been gained yet in a comp that hasn't started — say what it'll be instead of "#0".
		panel.add(w.upcoming
			? small("No standings until it starts.", ColorScheme.LIGHT_GRAY_COLOR)
			: small(yourStandingLine(w), w.yourRank > 0 ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR));

		if (w.url != null && !w.url.isEmpty())
		{
			// Nothing to stand in yet on an upcoming comp — link to the comp itself instead.
			panel.add(w.upcoming ? eventLink(w.url) : boardLink(w.url));
		}

		return capH(panel);
	}

	/** A card opening with a title and the line under it. */
	private static JPanel titledCard(String title, String sub, Color subColor)
	{
		JPanel panel = block();
		panel.add(label(title, REGULAR, ColorScheme.TEXT_COLOR));
		panel.add(small(sub, subColor));
		return panel;
	}

	/**
	 * A bingo on the schedule that isn't yours: what it is, when it runs, how big, and a link to the
	 * site to sign up. No progress section — there's no board of yours to track yet.
	 */
	private JPanel buildScheduledCard(ConnectionView.ScheduledView s)
	{
		JPanel panel = titledCard(s.title, s.kindLabel(), ColorScheme.LIGHT_GRAY_COLOR);

		String timing = timingLabel(!s.live, s.startDate, s.endDate);
		if (timing != null)
		{
			panel.add(small(timing, s.live ? ColorScheme.PROGRESS_COMPLETE_COLOR : MUTED));
		}
		if (!s.sizeLabel().isEmpty())
		{
			panel.add(small(s.sizeLabel(), MUTED));
		}

		panel.add(gap(4));
		// Per CHARACTER: the site answers for the account you're logged into, so a main that's entered
		// and an alt that isn't read differently here.
		if ("entered".equals(s.yourEntry))
		{
			panel.add(note(s.live ? "You're in — your board shows once it loads." : "You're signed up — waiting for it to start.",
				ColorScheme.PROGRESS_COMPLETE_COLOR));
		}
		else if ("pending".equals(s.yourEntry))
		{
			panel.add(note("Signed up — waiting for the host to approve."));
		}
		else
		{
			panel.add(note(s.live ? "You're not enrolled in this one." : "Sign up on the site to take part.",
				ColorScheme.LIGHT_GRAY_COLOR));
		}

		if (s.url != null && !s.url.isEmpty())
		{
			panel.add(eventLink(s.url));
		}
		return capH(panel);

	}

	/** "You: #3 · +1.2M xp", or an honest line when the caller isn't on the board (or it wouldn't load). */
	private static String yourStandingLine(ConnectionView.WeeklyView w)
	{
		if (w.yourRank > 0)
		{
			return "You: #" + w.yourRank + " · +" + w.formatGain(w.yourGained);
		}
		return w.top.isEmpty() ? "Standings unavailable" : "You're not on the board yet";
	}

	/** The head of the weekly's leaderboard, with the caller's row spliced in when it's further down. */
	private JPanel buildWeeklyStandings(ConnectionView.WeeklyView w)
	{
		JPanel list = section("Standings");
		if (w.top.isEmpty())
		{
			list.add(small("No one has scored yet.", MUTED));
			return capH(list);
		}

		int shown = 0;
		for (ConnectionView.Standing s : w.top)
		{
			if (shown >= WEEKLY_ROWS_SHOWN && !s.self)
			{
				continue;
			}
			// The caller's row is kept even when it ranks below the cut — mark the jump so #3 → #37 reads right.
			if (shown >= WEEKLY_ROWS_SHOWN)
			{
				list.add(gap(3));
				JLabel more = new JLabel(new GlyphIcon(GlyphIcon.Shape.ELLIPSIS, 14));
				more.setForeground(MUTED);
				list.add(left(more));
			}
			list.add(gap(3));
			list.add(buildStandingRow(s, w));
			shown++;
		}
		return capH(list);
	}

	/** One standings row: rank, RSN, gain. The caller's row leads in gold like their own activity does. */
	private JPanel buildStandingRow(ConnectionView.Standing s, ConnectionView.WeeklyView w)
	{
		JPanel row = left(new JPanel(new BorderLayout(6, 0)));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel rank = small(String.valueOf(s.rank), MUTED);
		rank.setPreferredSize(new Dimension(22, rank.getPreferredSize().height));
		rank.setHorizontalAlignment(SwingConstants.RIGHT);

		JLabel name = small(ellipsize(s.rsn, 16), s.self ? ColorScheme.BRAND_ORANGE : ColorScheme.TEXT_COLOR);
		name.setToolTipText(plainText(s.rsn));

		JLabel gained = small("+" + w.formatGain(s.gained), s.self ? ColorScheme.BRAND_ORANGE : MUTED);
		gained.setHorizontalAlignment(SwingConstants.RIGHT);

		row.add(rank, BorderLayout.WEST);
		row.add(name, BorderLayout.CENTER);
		row.add(gained, BorderLayout.EAST);
		return capH(row);
	}


	/** " · ends in 2d 4h" / " · starts in 3d 1h" for the compact list row; "" when there's no usable date. */
	private static String timingSuffix(boolean upcoming, String startIso, String endIso)
	{
		String label = timingLabel(upcoming, startIso, endIso);
		return label == null ? "" : " · " + label.substring(0, 1).toLowerCase() + label.substring(1);
	}

	/** What matters about the clock right now: when it starts if it hasn't, else when it ends. */
	static String timingLabel(boolean upcoming, String startIso, String endIso)
	{
		return upcoming ? gapLabel("Starts in ", startIso, "Starting…") : endsInLabel(endIso);
	}

	/** "Ends in 2d 4h" / "Ends in 42m" / "Ended", or null when the date is missing or unparseable. */
	static String endsInLabel(String endIso)
	{
		return gapLabel("Ends in ", endIso, "Ended");
	}

	/** "{prefix}2d 4h" until {@code iso}; {@code passed} once it's behind us; null when unparseable. */
	private static String gapLabel(String prefix, String iso, String passed)
	{
		long at = epochMillis(iso);
		if (at < 0)
		{
			return null;
		}
		long left = at - System.currentTimeMillis();
		if (left <= 0)
		{
			return passed;
		}
		long mins = left / 60_000;
		if (mins < 60)
		{
			return prefix + Math.max(1, mins) + "m";
		}
		long hours = mins / 60;
		if (hours < 24)
		{
			return prefix + hours + "h " + (mins % 60) + "m";
		}
		return prefix + (hours / 24) + "d " + (hours % 24) + "h";
	}

	/** ISO date ("2026-06-21") or UTC datetime → epoch millis, or -1 when unparseable. */
	private static long epochMillis(String iso)
	{
		if (iso == null || iso.length() < 10)
		{
			return -1;
		}
		try
		{
			if (iso.length() == 10)
			{
				return java.time.LocalDate.parse(iso)
					.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
			}
			String s = iso.trim().replace(' ', 'T');
			return java.time.Instant.parse(s.endsWith("Z") ? s : s + "Z").toEpochMilli();
		}
		catch (RuntimeException e)
		{
			return -1;
		}
	}

	// ---- Component builders -----------------------------------------------------------------------

	/** A small gold section label, matching the panel's header style. */
	private static JLabel sectionHeader(String text)
	{
		return small(text, ColorScheme.BRAND_ORANGE);
	}

	/** A section heading and the gap under it, added to {@code into}. */
	private static void heading(JPanel into, String text)
	{
		into.add(sectionHeader(text));
		into.add(gap(6));
	}

	/** A {@link Ui#column} that opens with a section heading. */
	private static JPanel section(String text)
	{
		JPanel p = column();
		heading(p, text);
		return p;
	}

	/**
	 * One "Active now" row: tile name + a "who's on it" byline + a thin progress bar. Your own tasks lead in
	 * gold, teammate-only stay neutral. Value/label are JLabels (never painted inside the bar) for crisp text.
	 */
	/**
	 * The starting-shot prompt: where to stand, this account's keyword, and the one button that
	 * captures the frame, burns the proof banner onto it and files it. Rendered only while a shot is
	 * actually owed — {@link SidebarDataSource#startProof()} returns null the moment one is filed.
	 */
	private JPanel buildStartProofCard(PluginConfigResponse.StartProof proof)
	{
		JPanel card = block();
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
			BorderFactory.createEmptyBorder(6, 8, 8, 8)));
		card.add(small("Starting shot needed", ColorScheme.BRAND_ORANGE));

		if (proof.location != null && !proof.location.isEmpty())
		{
			JLabel where = small("Go to " + proof.location, Color.WHITE);
			where.setToolTipText(plainText(proof.location));
			card.add(gap(4));
			card.add(where);
		}

		if (proof.keyword != null && !proof.keyword.isEmpty())
		{
			card.add(gap(2));
			card.add(small("Keyword: " + proof.keyword, MUTED));
		}

		// The requirements the button checks — session age, position — shown live, so a player can
		// see each one go green instead of finding out from a refusal in chat. Ticks every second
		// while the card is on screen; the timer stops itself once the card is replaced.
		JPanel checks = column();
		checks.setOpaque(false);
		renderStartProofChecks(checks, proof);
		if (checks.getComponentCount() > 0)
		{
			card.add(gap(4));
			card.add(checks);
			javax.swing.Timer live = new javax.swing.Timer(1000, null);
			live.addActionListener(ev ->
			{
				if (!checks.isDisplayable())
				{
					live.stop();
					return;
				}
				renderStartProofChecks(checks, proof);
			});
			live.start();
		}

		if ("rejected".equals(proof.status))
		{
			card.add(gap(2));
			card.add(warningLabel("Your last shot was rejected — take another."));
		}

		// WHY it matters, which the card never said. A player who reads "starting shot needed" and
		// carries on has no way to know their drops are landing in a review queue meanwhile.
		card.add(gap(4));
		card.add(warningLabel("Drops you send now are held for review until this is filed."));

		String left = StartProofRules.describeWindow(proof, System.currentTimeMillis());
		if (left != null)
		{
			JLabel expires = small("Asked for another " + left + ", then it lapses", Color.WHITE);
			expires.setToolTipText("Six hours in, the game has logged everyone out anyway, so the shot stops being asked for.");
			card.add(gap(2));
			card.add(expires);
		}

		if (proof.spot != null && dataSource.autoStartShot())
		{
			card.add(gap(2));
			card.add(small("Taken and sent automatically once both are green.", ColorScheme.LIGHT_GRAY_COLOR));

		}

		JButton take = new JButton("Take starting shot");
		styleFlatButton(take, ColorScheme.BRAND_ORANGE);
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
			javax.swing.Timer restore = new javax.swing.Timer(4000, ev ->
			{
				take.setText("Take starting shot");
				take.setEnabled(true);
			});
			restore.setRepeats(false);
			restore.start();
		});
		card.add(gap(6));
		card.add(take);

		return card;
	}

	private void renderStartProofChecks(JPanel into, PluginConfigResponse.StartProof proof)
	{
		java.util.List<StartProofRules.Check> list = dataSource.startProofChecks(proof);
		into.removeAll();
		for (StartProofRules.Check c : list)
		{
			Color color = c.state == StartProofRules.Check.State.OK ? ColorScheme.PROGRESS_COMPLETE_COLOR
				: c.state == StartProofRules.Check.State.BAD ? ColorScheme.PROGRESS_ERROR_COLOR
				: ColorScheme.LIGHT_GRAY_COLOR;
			GlyphIcon.Shape shape = c.state == StartProofRules.Check.State.OK ? GlyphIcon.Shape.CHECK : GlyphIcon.Shape.CLOSE;
			JLabel row = small(c.text, color);
			row.setIcon(new GlyphIcon(shape, 9, color));
			row.setIconTextGap(4);
			row.setToolTipText("Hiscores only save when you log out, so a fresh session is what sets your starting totals.");
			into.add(row);
		}
		into.revalidate();
		into.repaint();
	}

	private JPanel buildActiveRow(ConnectionView.ActiveTask task)
	{
		ClogTaskModel.TaskRow tile = task.tile;
		JLabel name = small(ellipsize(tile.label, 24), task.includesSelf ? ColorScheme.BRAND_ORANGE : ColorScheme.TEXT_COLOR);
		name.setToolTipText(plainText(tile.label));
		return progressRow(name, progressValue(tile),
			small(task.workersLabel(), task.includesSelf ? ColorScheme.BRAND_ORANGE : MUTED),
			bar(tile.goal > 0 ? Math.min(100, (int) Math.round(tile.current * 100.0 / tile.goal)) : 0,
				task.includesSelf ? ColorScheme.BRAND_ORANGE : ColorScheme.PROGRESS_INPROGRESS_COLOR,
				ColorScheme.DARKER_GRAY_COLOR, PROGRESS_BAR_HEIGHT));
	}

	/**
	 * A tile's progress line: its name with the count at the right, an optional byline (who's on it),
	 * then a thin bar across the row. Value/label are JLabels, never painted inside the bar, for crisp text.
	 */
	private static JPanel progressRow(JLabel name, String value, JLabel byline, JProgressBar bar)
	{
		JPanel row = left(new JPanel(new GridBagLayout()));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;
		row.add(name, gbc);

		gbc.gridx = 1;
		gbc.weightx = 0;
		gbc.fill = GridBagConstraints.NONE;
		gbc.anchor = GridBagConstraints.EAST;
		row.add(small(value, MUTED), gbc);

		gbc.gridx = 0;
		gbc.gridwidth = 2;
		gbc.weightx = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;
		if (byline != null)
		{
			gbc.gridy = 1;
			gbc.insets = new java.awt.Insets(1, 0, 0, 0);
			row.add(byline, gbc);
		}
		gbc.gridy++;
		gbc.insets = new java.awt.Insets(3, 0, 0, 0);
		row.add(bar, gbc);
		return capH(row);
	}

	/**
	 * "1.5M / 2M", "248 / 500", or "" for an untargeted tile — big numbers abbreviated for the narrow
	 * panel, by the same formatter as the weekly gains so the card and the standings can't drift apart.
	 */
	private static String progressValue(ClogTaskModel.TaskRow tile)
	{
		return tile.goal > 0 ? ConnectionView.formatCount(tile.current) + " / " + ConnectionView.formatCount(tile.goal) : "";
	}

	/** The team activity feed — one colored line per event (newest first), capped so the panel stays glanceable. */
	private JPanel buildActivityFeed(List<ActivityEntry> entries)
	{
		JPanel list = column();
		addSpaced(list, entries.subList(0, Math.min(entries.size(), ACTIVITY_ROWS_SHOWN)), 3, this::buildActivityRow);
		if (entries.size() > ACTIVITY_ROWS_SHOWN)
		{
			list.add(gap(3));
			list.add(small("+" + (entries.size() - ACTIVITY_ROWS_SHOWN) + " more", MUTED));
		}
		return capH(list);
	}

	private JLabel buildActivityRow(ActivityEntry e)
	{
		// Completions read as wins (green); reveals are announcements (gold, like your own actions);
		// your own actions stand out (gold); teammates stay neutral.
		JLabel row = small(ellipsize(e.summary(), 36), e.isCompletion() ? ColorScheme.PROGRESS_COMPLETE_COLOR
			: e.kind == ActivityEntry.Kind.REVEAL || e.self ? ColorScheme.BRAND_ORANGE : ColorScheme.TEXT_COLOR);
		row.setToolTipText(plainText(e.summary()));
		return row;
	}

	/** Clip an over-long label to width with an ellipsis so a feed/spotlight row never overflows the panel. */
	private static String ellipsize(String s, int max)
	{
		if (s == null)
		{
			return "";
		}
		return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)).trim() + "…";
	}

	/**
	 * Drop the held ladder-card refs. Only one card renders at a time, so every render path that isn't
	 * building a ladder card must clear them — otherwise the 1 s tick keeps updating labels that are no
	 * longer on screen.
	 */
	private void clearLadderRefs()
	{
		ladderState = null;
		ladderCountdownLabel = null;
		ladderCardPanel = null;
		ladderValueLabels.clear();
	}

	private JPanel buildSummary(ConnectionView c)
	{
		// Only one card renders at a time (renderSelected), so the ladder tick binds to a single set of
		// held refs. Reset them each render; a non-ladder card leaves the tick idle.
		clearLadderRefs();
		// A ladder IS its missions board, so the card replaces the summary. A bingo that merely carries
		// missions keeps its tile-count summary and gets the mission strip as its own section below.
		if (c.ladder != null && c.ladder.ladderFormat)
		{
			return buildLadderCard(c);
		}

		JPanel panel = left(new JPanel(new BorderLayout(0, 4)));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.add(label(eventLine(c), REGULAR, ColorScheme.TEXT_COLOR), BorderLayout.NORTH);

		// Count is a FontManager JLabel, NOT painted inside the bar (default L&F font rendered poorly).
		JPanel bottom = new JPanel(new BorderLayout(0, 2));
		bottom.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		if (c.tilesTotal <= 0)
		{
			// No live board on this clan (federated homes with no running event report 0/0) — a bare
			// "0 / 0 tiles · 0%" reads like a bug, so say what's actually going on instead. The view
			// can carry a more specific line (e.g. the logged-out home's "Log in in-game …").
			bottom.add(small(c.statusNote != null && !c.statusNote.isEmpty()
				? c.statusNote : "No active event yet.", ColorScheme.LIGHT_GRAY_COLOR), BorderLayout.NORTH);
		}
		else
		{
			bottom.add(small(boardStatusLine(c), MUTED), BorderLayout.NORTH);
			bottom.add(bar(c.completionPercent(), c.completionPercent() >= 100
				? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE,
				ColorScheme.DARK_GRAY_COLOR, PROGRESS_BAR_HEIGHT + 1), BorderLayout.CENTER);

			// Reveal-policy boards: "4 tiles hidden · next in 42m" under the bar, so members know
			// more is coming (and when) without opening the site. Null on classic boards.
			if (c.revealNote != null && !c.revealNote.isEmpty())
			{
				bottom.add(small(c.revealNote, ColorScheme.BRAND_ORANGE), BorderLayout.SOUTH);
			}
		}

		panel.add(bottom, BorderLayout.CENTER);

		if (c.boardUrl != null && !c.boardUrl.isEmpty())
		{
			JPanel south = column();
			south.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			south.add(boardLink(c.boardUrl));
			panel.add(south, BorderLayout.SOUTH);
		}
		return capH(panel);
	}

	/** The card's headline: the event, else the clan when there's no event. */
	private static String eventLine(ConnectionView c)
	{
		return c.eventName == null || c.eventName.isEmpty() ? c.clanName : c.eventName;
	}

	/**
	 * One open mission: label on the left, live grow/decay value on the right. Shared by the ladder
	 * card and the bingo mission strip so both age their values on the same per-second tick — the
	 * label is registered with {@link #ladderValueLabels} either way.
	 */
	private JPanel buildMissionRow(ConnectionView.Ladder.Mission m, ConnectionView.Ladder l, long now)
	{
		JPanel row = left(new JPanel(new BorderLayout(6, 0)));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		long val = LadderMissions.liveValue(m.face, m.revealedAtIso, l.decay, now);
		JLabel value = small(LadderMissions.valueLabel(m.face, val), valueColor(m.face, val));
		value.setHorizontalAlignment(SwingConstants.RIGHT);

		row.add(small(ellipsize(m.label, 22), ColorScheme.TEXT_COLOR), BorderLayout.CENTER);
		row.add(value, BorderLayout.EAST);
		capH(row);
		ladderValueLabels.add(new LadderValueLabel(value, m.face, m.revealedAtIso));
		return row;
	}

	/**
	 * Missions on an ordinary bingo — the countdown to the next drop plus what's open right now, with
	 * the same live values a ladder shows. No rank line: a bingo scores by team, not by a personal
	 * ladder position.
	 */
	private JPanel buildMissionStrip(ConnectionView.Ladder l)
	{
		final long now = System.currentTimeMillis();
		JPanel panel = block();
		JLabel countdown = label(countdownText(l, now), BOLD, ColorScheme.BRAND_ORANGE);
		panel.add(countdown);
		panel.add(gap(4));
		for (ConnectionView.Ladder.Mission m : l.missions)
		{
			panel.add(buildMissionRow(m, l, now));
		}
		// Bind the per-second tick to this strip, exactly as the ladder card does for itself — without
		// it the countdown would sit frozen until the next config poll.
		return bindLadder(l, countdown, capH(panel));
	}

	/** Point the 1s tick at this card: its countdown, the value labels already registered, its flash. */
	private JPanel bindLadder(ConnectionView.Ladder l, JLabel countdown, JPanel card)
	{
		ladderState = l;
		ladderCountdownLabel = countdown;
		ladderCardPanel = card;
		return card;
	}

	/**
	 * The DMM-All-Stars-style missions board for a ladder event: your rank, a live per-second countdown
	 * to the next drop, and the currently-open missions with a live grow/decay value each. Replaces the
	 * tile-count summary + reveal note (a rotating daily ladder has no fixed board to count). The held
	 * label refs let {@link #tickLadder()} update the countdown + values once a second without a refetch.
	 */
	private JPanel buildLadderCard(ConnectionView c)
	{
		ConnectionView.Ladder l = c.ladder;
		final long now = System.currentTimeMillis();

		JPanel panel = titledCard(eventLine(c), rankLine(l), MUTED);
		panel.add(gap(6));

		JLabel countdown = label(countdownText(l, now), BOLD, ColorScheme.BRAND_ORANGE);
		panel.add(countdown);
		panel.add(gap(6));

		if (l.missions.isEmpty())
		{
			panel.add(small("Waiting for the next mission…", ColorScheme.LIGHT_GRAY_COLOR));
		}
		else
		{
			panel.add(small("Active missions", ColorScheme.LIGHT_GRAY_COLOR));
			panel.add(gap(2));
			for (ConnectionView.Ladder.Mission m : l.missions)
			{
				panel.add(buildMissionRow(m, l, now));
			}
		}

		if (c.boardUrl != null && !c.boardUrl.isEmpty())
		{
			panel.add(gap(6));
			panel.add(boardLink(c.boardUrl));
		}
		return bindLadder(l, countdown, capH(panel));
	}

	/** "You: #4 this month · #12 all-time", or an encouraging line when the caller hasn't scored yet. */
	private static String rankLine(ConnectionView.Ladder l)
	{
		if (l.monthRank <= 0)
		{
			return "You: unranked — finish a mission to get on the board";
		}
		String line = "You: #" + l.monthRank + " this month · " + l.monthPoints + " pts";
		if (l.allTimeRank > 0)
		{
			line += " · #" + l.allTimeRank + " all-time";
		}
		return line;
	}

	/** "Next mission in 12:34" / "New mission dropping…" / "Next mission: on a claim" (bounty, no clock). */
	private static String countdownText(ConnectionView.Ladder l, long now)
	{
		String cd = LadderMissions.countdown(l.nextRevealAtIso, now);
		if (cd == null)
		{
			return "Next mission drops on a claim";
		}
		return "now".equals(cd) ? "New mission dropping…" : "Next mission in " + cd;
	}

	/** Grey when unchanged, green when it grew, orange when it's decaying. */
	private static Color valueColor(long face, long current)
	{
		if (current == face)
		{
			return MUTED;
		}
		return current > face ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE;
	}

	/** Live per-second refresh of the shown ladder card: the countdown, each mission's value, the flash. */
	private void tickLadder()
	{
		final long now = System.currentTimeMillis();
		ConnectionView.Ladder l = ladderState;
		if (l != null)
		{
			if (ladderCountdownLabel != null)
			{
				ladderCountdownLabel.setText(plainText(countdownText(l, now)));
			}
			for (LadderValueLabel v : ladderValueLabels)
			{
				long val = LadderMissions.liveValue(v.face, v.revealedAtIso, l.decay, now);
				v.label.setText(LadderMissions.valueLabel(v.face, val));
				v.label.setForeground(valueColor(v.face, val));
			}
		}
		applyFlash(now);
	}

	/** Pulse the card border gold while a new-mission / claim signal is fresh (see {@link #flashLadder()}). */
	private void applyFlash(long now)
	{
		if (ladderCardPanel == null)
		{
			return;
		}
		if (now < ladderFlashUntil)
		{
			boolean on = ((ladderFlashUntil - now) / 350) % 2 == 0;
			ladderCardPanel.setBorder(BorderFactory.createMatteBorder(8, 8, 8, 8,
				on ? LADDER_FLASH_COLOR : ColorScheme.DARKER_GRAY_COLOR));
			ladderFlashPainted = true;
		}
		else if (ladderFlashPainted)
		{
			ladderCardPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
			ladderFlashPainted = false;
		}
	}

	/**
	 * Signal a fresh new-mission / claim so the ladder card pulses gold for a few seconds. Called from the
	 * plugin's config-refresh diff OFF the EDT — a plain volatile write the 1s tick picks up on the EDT.
	 */
	public void flashLadder()
	{
		ladderFlashUntil = System.currentTimeMillis() + LADDER_FLASH_MS;
	}

	/** Held ref for one mission's value label so the tick recomputes its grow/decay value in place. */
	private static final class LadderValueLabel
	{
		final JLabel label;
		final int face;
		final String revealedAtIso;

		LadderValueLabel(JLabel label, int face, String revealedAtIso)
		{
			this.label = label;
			this.face = face;
			this.revealedAtIso = revealedAtIso;
		}
	}

	/** A clickable "View standings" link opening the board's site page in the system browser. */
	private JLabel boardLink(String url)
	{
		return siteLink("View standings ↗", "Open the full standings on the Anvil site", url);
	}

	/** As {@link #boardLink}, for an event you're not in yet — the site page is where you sign up. */
	private JLabel eventLink(String url)
	{
		return siteLink("View event ↗", "Open this event on the Anvil site", url);
	}

	private JLabel siteLink(String text, String tooltip, String url)
	{
		JLabel link = styleLink(new JLabel(text.replace(" ↗", "").trim()), tooltip, () ->
		{
			if (isSafeHttpUrl(url)) // defense-in-depth: never a javascript:/data:/file: or creds@host URL
			{
				LinkBrowser.browse(url);
			}
		});
		if (text.endsWith("↗"))
		{
			// Drawn, not typed — the RuneScape font has no ↗ and macOS often substitutes nothing.
			link.setIcon(new GlyphIcon(GlyphIcon.Shape.EXTERNAL, 11));
			link.setHorizontalTextPosition(SwingConstants.LEADING);
			link.setIconTextGap(2);
		}
		link.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		return link;
	}

	/** True only for a well-formed absolute {@code http}/{@code https} URL with a host and no embedded
	 *  credentials — refuses {@code javascript:} / {@code data:} / {@code file:} and {@code user@host} tricks. */
	static boolean isSafeHttpUrl(String url)
	{
		if (url == null || url.isEmpty())
		{
			return false;
		}
		try
		{
			java.net.URI u = new java.net.URI(url);
			String scheme = u.getScheme();
			return u.getHost() != null && u.getUserInfo() == null
				&& ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
		}
		catch (java.net.URISyntaxException ex)
		{
			return false;
		}
	}

	private JPanel buildTileRow(ConnectionView.TileProgressView tile)
	{
		Color done = tile.complete ? ColorScheme.PROGRESS_COMPLETE_COLOR : null;
		return progressRow(small(tile.name, done != null ? done : ColorScheme.TEXT_COLOR), progressText(tile), null,
			bar(tile.percent(), done != null ? done : ColorScheme.PROGRESS_INPROGRESS_COLOR,
				ColorScheme.DARKER_GRAY_COLOR, PROGRESS_BAR_HEIGHT));
	}

	/** "c / t" for counted tiles, "42%" for very large targets (XP), or "Done"/"—". */
	private static String progressText(ConnectionView.TileProgressView tile)
	{
		if (tile.complete)
		{
			return "Done";
		}
		if (tile.target <= 0)
		{
			return "—";
		}
		if (tile.target >= 100_000)
		{
			// Big numeric goals (XP, gp) read better as a percentage than as raw counts.
			return tile.percent() + "%";
		}
		return tile.current + " / " + tile.target;
	}

	// ---- Full tile catalogue ---------------------------------------------------------------------

	enum TileStatusFilter
	{
		ALL("All"), OPEN("Not done"), DONE("Done");

		private final String label;

		TileStatusFilter(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** Pure filter used by the Swing view and unit tests. Pre-start boards deliberately have no done state. */
	static List<BingoApiClient.BoardTile> filterBoardTiles(List<BingoApiClient.BoardTile> tiles,
		String search, TileStatusFilter status, boolean preStart)
	{
		List<BingoApiClient.BoardTile> out = new ArrayList<>();
		String needle = normalizeTileSearch(search);
		String[] terms = needle.isEmpty() ? new String[0] : needle.split(" ");
		TileStatusFilter wanted = status == null ? TileStatusFilter.ALL : status;
		if (tiles == null)
		{
			return out;
		}
		// A complete category/tier name behaves like the filter the player meant, without adding a
		// second wall of filter controls to a 240px sidebar. Tier wins when names collide ("Troll" is
		// both a common category and the default lowest difficulty); arbitrary words stay free text.
		boolean tierFacet = !needle.isEmpty() && tiles.stream().filter(java.util.Objects::nonNull)
			.anyMatch(tile -> tileHasTier(tile, needle));
		boolean categoryFacet = !tierFacet && !needle.isEmpty()
			&& tiles.stream().filter(java.util.Objects::nonNull)
				.anyMatch(tile -> tileHasCategory(tile.category, needle));
		// A tile TYPE ("timed", "drop", "boss", "manual") is a facet too. When the word is also a
		// category name both readings are kept — "pvp" shows the PvP-kill tiles and the PvP category.
		boolean kindFacet = !tierFacet && !needle.isEmpty()
			&& tiles.stream().filter(java.util.Objects::nonNull)
				.anyMatch(tile -> tileKindNames(tile).contains(needle));
		for (BingoApiClient.BoardTile tile : tiles)
		{
			if (tile == null)
			{
				continue;
			}
			boolean done = !preStart && tile.complete;
			if ((wanted == TileStatusFilter.OPEN && done) || (wanted == TileStatusFilter.DONE && !done))
			{
				continue;
			}
			if (kindFacet)
			{
				if (!tileKindNames(tile).contains(needle) && !tileHasCategory(tile.category, needle))
				{
					continue;
				}
			}
			else if ((tierFacet && !tileHasTier(tile, needle))
				|| (categoryFacet && !tileHasCategory(tile.category, needle)))
			{
				continue;
			}
			String haystack = normalizeTileSearch((tile.label == null ? "" : tile.label) + " "
				+ (tile.category == null ? "" : tile.category) + " "
				+ (tile.tier == null ? "" : tile.tier) + " "
				+ (tile.tierKey == null ? "" : tile.tierKey) + " "
				+ (tile.requirement == null ? "" : tile.requirement) + " "
				+ (tile.description == null ? "" : tile.description));
			boolean matches = true;
			for (String term : terms)
			{
				if (!tierFacet && !categoryFacet && !kindFacet && !haystack.contains(term))
				{
					matches = false;
					break;
				}
			}
			if (matches)
			{
				out.add(tile);
			}
		}
		if (!needle.isEmpty())
		{
			out.sort((a, b) ->
			{
				int relevance = Integer.compare(tileSearchRank(a, needle, terms), tileSearchRank(b, needle, terms));
				return relevance != 0 ? relevance : Integer.compare(a.position, b.position);
			});
		}
		return out;
	}

	/**
	 * The words a player might type for this tile's TYPE, normalised like the search. Several per
	 * type on purpose — "boss", "kc" and "boss kc" are one thing to a player.
	 */
	static java.util.Set<String> tileKindNames(BingoApiClient.BoardTile tile)
	{
		java.util.Set<String> out = new java.util.HashSet<>();
		if (tile == null)
		{
			return out;
		}
		if (tileIsManual(tile))
		{
			out.add("manual");
		}
		String stat = tile.statType == null ? "" : tile.statType.trim().toLowerCase(Locale.ROOT);
		if (tile.statName != null && !tile.statName.trim().isEmpty())
		{
			if ("boss".equals(stat) || "kc".equals(stat))
			{
				java.util.Collections.addAll(out, "boss", "kc", "boss kc", "bosses");
			}
			else
			{
				java.util.Collections.addAll(out, "skill", "xp", "skilling", "skills");
			}
		}
		String type = tile.tileType == null ? "" : tile.tileType.trim().toLowerCase(Locale.ROOT);
		for (String[] words : TYPE_WORDS)
		{
			if (words[0].equals(type))
			{
				out.addAll(java.util.Arrays.asList(words).subList(1, words.length));
			}
		}
		if ("drop".equals(type) && tile.itemRequirements != null && !tile.itemRequirements.isEmpty())
		{
			java.util.Collections.addAll(out, "set", "sets", "item set", "collection");
		}
		return out;
	}

	/** Per tile type (the first word), what a player might type to find it. */
	private static final String[][] TYPE_WORDS = {
		{"drop", "drop", "drops"},
		{"kill", "kill", "kills"},
		{"lap", "lap", "laps", "agility"},
		{"pvp", "pvp"},
		{"gain", "gain", "gains", "item gain", "gather"},
		{"timed", "timed", "time", "speedrun"},
		{"deathless", "deathless"},
		{"diary", "diary", "diaries", "achievement diary"},
		{"ca", "ca", "cas", "combat task", "combat achievement"},
		{"lms", "lms"},
		{"value", "value", "loot value", "gp"},
		{"valuetotal", "value", "loot value", "gp"},
	};

	private static boolean tileHasTier(BingoApiClient.BoardTile tile, String normalized)
	{
		return tile != null && (normalizeTileSearch(tile.tier).equals(normalized)
			|| normalizeTileSearch(tile.tierKey).equals(normalized));
	}

	private static boolean tileHasCategory(String categories, String normalized)
	{
		if (categories == null || normalized.isEmpty())
		{
			return false;
		}
		for (String category : categories.split(","))
		{
			if (normalizeTileSearch(category).equals(normalized))
			{
				return true;
			}
		}
		return false;
	}

	/** Lowercase words with punctuation collapsed, so "Barrows—chest" matches "barrows chest". */
	private static String normalizeTileSearch(String value)
	{
		return (value == null ? "" : value)
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", " ")
			.trim();
	}

	/** Lower is better: label phrase → label words → category → requirement → description. */
	private static int tileSearchRank(BingoApiClient.BoardTile tile, String phrase, String[] terms)
	{
		if (tileHasTier(tile, phrase)) return 0;
		if (tileHasCategory(tile.category, phrase)) return 2;
		String label = normalizeTileSearch(tile.label);
		if (label.equals(phrase)) return 4;
		if (label.startsWith(phrase)) return 5;
		if (label.contains(phrase)) return 10;

		String category = normalizeTileSearch(tile.category);
		String tier = normalizeTileSearch((tile.tier == null ? "" : tile.tier) + " "
			+ (tile.tierKey == null ? "" : tile.tierKey));
		String requirement = normalizeTileSearch(tile.requirement);
		String description = normalizeTileSearch(tile.description);
		int score = 20;
		for (String term : terms)
		{
			if (label.startsWith(term)) score += 0;
			else if (label.contains(term)) score += 2;
			else if (category.contains(term)) score += 8;
			else if (tier.contains(term)) score += 10;
			else if (requirement.contains(term)) score += 14;
			else if (description.contains(term)) score += 22;
			else score += 100;
		}
		return score;
	}

	/** JTextField has no placeholder API; paint one without putting fake text into the query. */
	private static final class TileSearchField extends JTextField
	{
		private final String placeholder;

		TileSearchField(String placeholder, String value)
		{
			super(value == null ? "" : value);
			this.placeholder = placeholder;
			addFocusListener(new java.awt.event.FocusAdapter()
			{
				@Override public void focusGained(java.awt.event.FocusEvent e) { repaint(); }
				@Override public void focusLost(java.awt.event.FocusEvent e) { repaint(); }
			});
		}

		@Override
		protected void paintComponent(java.awt.Graphics graphics)
		{
			super.paintComponent(graphics);
			if (!getText().isEmpty() || isFocusOwner())
			{
				return;
			}
			java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
			g.setColor(MUTED);
			g.setFont(getFont());
			java.awt.FontMetrics metrics = g.getFontMetrics();
			java.awt.Insets insets = getInsets();
			int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
			g.drawString(placeholder, insets.left, y);
			g.dispose();
		}
	}

	private JPanel buildBoardCatalogue(String key, int eventId, String boardUrl, boolean preStart)
	{
		JPanel panel = column();

		BingoApiClient.BoardResponse board = boardCatalogues.get(key);
		if (board == null)
		{
			if (!loadingBoardCatalogues.contains(key) && !failedBoardCatalogues.contains(key))
			{
				loadBoardCatalogue(key, eventId, false);
			}
			if (failedBoardCatalogues.contains(key))
			{
				panel.add(small("Couldn't load the board.", ColorScheme.PROGRESS_ERROR_COLOR));
				panel.add(gap(5));
				panel.add(fullWidthButton("Retry", "Try loading all tiles again", () ->
					loadBoardCatalogue(key, eventId, true)));
			}
			else
			{
				panel.add(small("Loading tiles…", MUTED));
			}
			return capH(panel);
		}

		List<BingoApiClient.BoardTile> all = board.tiles == null
			? java.util.Collections.emptyList() : board.tiles;
		if (all.isEmpty())
		{
			panel.add(small(board.tilesRevealed ? "No visible tiles." : "Tiles haven't been revealed yet.", MUTED));
			return capH(panel);

		}

		TileSearchField search = new TileSearchField(
			"Search " + all.size() + " tiles…", boardSearches.getOrDefault(key, ""));
		search.setToolTipText("Search tile names, descriptions, requirements and categories");
		search.setFont(SMALL);
		search.setForeground(Color.WHITE);
		search.setCaretColor(Color.WHITE);
		search.setBackground(WIDGET_BG);
		search.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(WIDGET_BORDER), BorderFactory.createEmptyBorder(4, 6, 4, 6)));
		search.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override public void focusGained(java.awt.event.FocusEvent e) { boardSearchFocused = true; }
			@Override public void focusLost(java.awt.event.FocusEvent e) { boardSearchFocused = false; }
		});

		JButton clearSearch = flatButton("", "Clear tile search", () -> search.setText(""));
		clearSearch.setIcon(new GlyphIcon(GlyphIcon.Shape.CLOSE, 12));
		clearSearch.setEnabled(!search.getText().isEmpty());
		clearSearch.setPreferredSize(new Dimension(30, search.getPreferredSize().height));
		clearSearch.setBorder(BorderFactory.createLineBorder(WIDGET_BORDER));

		JComboBox<TileStatusFilter> status = preStart ? null : new JComboBox<>(TileStatusFilter.values());
		if (status != null)
		{
			status.setSelectedItem(boardStatusFilters.getOrDefault(key, TileStatusFilter.ALL));
			styleCombo(status);
			status.setToolTipText("Filter tiles by completion state");
		}

		JPanel controls = left(new JPanel(new BorderLayout(5, 0)));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controls.add(search, BorderLayout.CENTER);
		controls.add(clearSearch, BorderLayout.EAST);
		capH(controls);
		String countLine = all.size() + (all.size() == 1 ? " visible tile" : " visible tiles");
		if (board.hiddenTileCount > 0)
		{
			countLine += " · " + board.hiddenTileCount + " still hidden";
		}
		boolean catalogueClaimable = !board.readOnly && dataSource.supportsTileClaims();
		boolean loggedOut = catalogueClaimable && !dataSource.canClaimNow();
		panel.add(small(countLine, ColorScheme.LIGHT_GRAY_COLOR));
		panel.add(gap(2));
		panel.add(small(loggedOut
			? "Click for details · log in to claim tiles."
			: catalogueClaimable
			? "Click for details · right-click to claim."
			: (board.readOnly ? "Click for details · join this event to claim tiles."
				: "Click for details · claims aren't available on this site."), MUTED));
		panel.add(gap(7));
		panel.add(controls);
		if (status != null)
		{
			JPanel filterRow = left(new JPanel(new BorderLayout(6, 0)));
			filterRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
			filterRow.add(small("Show", MUTED), BorderLayout.WEST);
			filterRow.add(status, BorderLayout.CENTER);
			panel.add(gap(5));
			panel.add(capH(filterRow));
		}
		panel.add(gap(7));

		JPanel rows = column();

		Runnable repaintRows = () ->
		{
			boardSearches.put(key, search.getText());
			TileStatusFilter selected = status == null
				? TileStatusFilter.ALL : (TileStatusFilter) status.getSelectedItem();
			boardStatusFilters.put(key, selected == null ? TileStatusFilter.ALL : selected);
			renderBoardCatalogueRows(panel, rows, key, all, search.getText(), selected, preStart, boardUrl);
		};
		// A new search or filter starts at page one — and searching from a tile's detail means "find
		// another one", so it shows the results.
		Runnable refilter = () ->
		{
			boardCataloguePages.put(key, 0);
			closeCatalogueDetail(key);
			repaintRows.run();
		};
		onEdit(search, () ->
		{
			clearSearch.setEnabled(!search.getText().isEmpty());
			refilter.run();
		});
		if (status != null)
		{
			status.addActionListener(e -> refilter.run());
		}
		panel.add(rows);
		repaintRows.run();
		return capH(panel);
	}

	private void closeCatalogueDetail(String key)
	{
		if (key.equals(openCatalogueDetailKey))
		{
			openCatalogueDetailKey = null;
			openCatalogueDetailTileId = -1;
		}
	}

	private void renderBoardCatalogueRows(JPanel catalogue, JPanel rows, String key,
		List<BingoApiClient.BoardTile> all, String search, TileStatusFilter status, boolean preStart, String boardUrl)
	{
		rows.removeAll();
		Runnable again = () -> renderBoardCatalogueRows(catalogue, rows, key, all, search, status, preStart, boardUrl);
		BingoApiClient.BoardResponse shownBoard = boardCatalogues.get(key);
		boolean claimable = shownBoard != null
			&& !shownBoard.readOnly && dataSource.supportsTileClaims() && dataSource.canClaimNow();
		if (key.equals(openCatalogueDetailKey) && openCatalogueDetailTileId > 0)
		{
			for (BingoApiClient.BoardTile tile : all)
			{
				if (tile.tileId == openCatalogueDetailTileId)
				{
					renderTileDetail(catalogue, rows, tile, boardUrl, preStart, key, claimable && !tile.complete, () ->
					{
						openCatalogueDetailKey = null;
						openCatalogueDetailTileId = -1;
						again.run();
					});
					return;
				}
			}
			closeCatalogueDetail(key);
		}
		List<BingoApiClient.BoardTile> filtered = filterBoardTiles(all, search, status, preStart);
		if (filtered.isEmpty())
		{
			rows.add(small("No matching tiles.", MUTED));
		}
		else
		{
			int pageCount = Math.max(1,
				(int) Math.ceil(filtered.size() / (double) TILE_CATALOGUE_PAGE_SIZE));
			int page = Math.max(0, Math.min(boardCataloguePages.getOrDefault(key, 0), pageCount - 1));
			boardCataloguePages.put(key, page);
			int from = page * TILE_CATALOGUE_PAGE_SIZE;
			int to = Math.min(filtered.size(), from + TILE_CATALOGUE_PAGE_SIZE);
			rows.add(small((from + 1) + "–" + to + " of " + filtered.size(), MUTED));
			rows.add(gap(5));
			// A writable response is already scoped by the server to the enrolled player's team. Public
			// and non-member previews remain read-only, including before the event starts.
			boolean first = true;
			for (BingoApiClient.BoardTile tile : filtered.subList(from, to))
			{
				if (!first)
				{
					rows.add(gap(5));
				}
				boolean canClaim = claimable && !tile.complete;
				String claimUnavailable = canClaim ? null : (tile.complete
					? "This tile is already complete."
					: (shownBoard != null && shownBoard.readOnly
						? "Join this event to claim tiles."
						: (!dataSource.canClaimNow() ? "Log in to claim tiles."
							: "Claims aren't available on this site.")));
				rows.add(buildCatalogueTileRow(tile, preStart, key, canClaim, claimUnavailable, () ->
				{
					openCatalogueDetailKey = key;
					openCatalogueDetailTileId = tile.tileId;
					again.run();
				}));
				first = false;
			}
			if (pageCount > 1)
			{
				rows.add(gap(7));
				final int shownPage = page;
				JButton previous = flatButton("Previous", "Show the previous tiles", () ->
				{
					boardCataloguePages.put(key, shownPage - 1);
					again.run();
				});
				previous.setEnabled(page > 0);
				previous.setIcon(new GlyphIcon(GlyphIcon.Shape.CHEVRON_LEFT, 11));
				JButton next = flatButton("Next", "Show the next tiles", () ->
				{
					boardCataloguePages.put(key, shownPage + 1);
					again.run();
				});
				next.setEnabled(page < pageCount - 1);
				next.setIcon(new GlyphIcon(GlyphIcon.Shape.CHEVRON_RIGHT, 11));
				next.setHorizontalTextPosition(SwingConstants.LEADING);
				rows.add(buttonRow(previous, next));
			}
		}
		relayout(catalogue, rows);
	}

	/** The catalogue's rows changed: re-cap both panels to their new height and redraw. */
	private void relayout(JPanel catalogue, JPanel rows)
	{
		rows.revalidate();
		capH(rows);
		catalogue.revalidate();
		capH(catalogue);
		content.revalidate();
		rows.repaint();
		content.repaint();
	}

	private JPanel buildCatalogueTileRow(BingoApiClient.BoardTile tile, boolean preStart,
		String key, boolean canClaim, String claimUnavailable, Runnable viewDetails)
	{
		JPanel row = left(new JPanel(new BorderLayout(6, 0)));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(6, 7, 6, 7));

		String label = tileName(tile);
		JLabel name = small(ellipsize(label, 28), !preStart && tile.complete ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.TEXT_COLOR);
		String details = tile.description == null || tile.description.trim().isEmpty()
			? tile.requirement : tile.description;
		String tooltip = details == null || details.isEmpty() ? label : label + " — " + details;
		if (canClaim)
		{
			tooltip += " (right-click to claim)";
		}
		name.setToolTipText(plainText(tooltip));
		row.add(name, BorderLayout.CENTER);

		String rowState = tileRowStateText(tile, preStart);
		if (rowState != null)
		{
			// Manual reads in the site's badge colour, so it's spotted in a list of auto-tracked tiles.
			JLabel state = small(rowState.startsWith("✓ ") ? rowState.substring(2) : rowState,
				tile.complete ? ColorScheme.PROGRESS_COMPLETE_COLOR : tileIsManual(tile) ? MANUAL_COLOR : MUTED);
			if (rowState.startsWith("✓ "))
			{
				state.setIcon(new GlyphIcon(GlyphIcon.Shape.CHECK, 11));
				state.setIconTextGap(2);
			}
			row.add(state, BorderLayout.EAST);
		}

		// Who on your team is going for it. Plain text: the RuneScape font has no emoji.
		String planning = claimLine(tile.claims);
		if (planning != null)
		{
			JLabel who = small(ellipsize(planning, 36), claimedByMe(tile.claims) ? ColorScheme.BRAND_ORANGE : CLAIM_COLOR);
			who.setToolTipText(plainText(claimTooltip(tile.claims)));
			row.add(who, BorderLayout.SOUTH);
		}

		if (viewDetails != null || canClaim)
		{
			if (viewDetails != null)
			{
				row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			}
			MouseAdapter listener = new MouseAdapter()
			{
				@Override public void mouseClicked(MouseEvent e)
				{
					// Left-click stays in RuneLite; the detail view carries the explicit site link.
					if (viewDetails != null && SwingUtilities.isLeftMouseButton(e))
					{
						viewDetails.run();
					}
				}
				@Override public void mousePressed(MouseEvent e) { maybeClaimMenu(e); }
				@Override public void mouseReleased(MouseEvent e) { maybeClaimMenu(e); }
				@Override public void mouseEntered(MouseEvent e) { row.setBackground(WIDGET_BG_HOVER); }
				@Override public void mouseExited(MouseEvent e)
				{
					// Moving between the row and one of its labels is still inside the same clickable tile.
					Component source = e.getComponent();
					if (!row.contains(SwingUtilities.convertPoint(source, e.getPoint(), row)))
					{
						row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
					}
				}

				private void maybeClaimMenu(MouseEvent e)
				{
					if (e.isPopupTrigger())
					{
						JPopupMenu menu = canClaim ? claimMenu(tile, key) : claimUnavailableMenu(claimUnavailable);
						menu.show(e.getComponent(), e.getX(), e.getY());
					}
				}
			};
			// Swing sends a mouse event to the deepest component under the pointer; it does not bubble
			// through the JLabel text to this JPanel. Wire the full visible row so its text is not a dead
			// overlay over the apparent button (and right-click works on the same whole target).
			addMouseListenerToTree(row, listener);
		}
		return capH(row);
	}

	private static String tileName(BingoApiClient.BoardTile tile)
	{
		return tile.label == null || tile.label.trim().isEmpty() ? "Tile " + tile.tileId : tile.label;
	}

	/** One tile's full in-sidebar view. The browser is an explicit choice, not the row's default action. */
	private void renderTileDetail(JPanel catalogue, JPanel rows, BingoApiClient.BoardTile tile,
		String boardUrl, boolean preStart, String key, boolean canClaim, Runnable backToTiles)
	{
		rows.removeAll();
		rows.add(fullWidthButton("Back to tiles", "Return to the searchable tile list", backToTiles));
		rows.add(gap(7));

		JPanel card = block();
		card.setBorder(BorderFactory.createEmptyBorder(9, 9, 9, 9));

		JLabel title = note(tileName(tile), ColorScheme.TEXT_COLOR);
		title.setFont(BOLD);
		card.add(title);
		card.add(gap(4));

		List<String> facts = tileDetailFacts(tile, preStart);
		if (!facts.isEmpty())
		{
			detailHeading(card, 0, "Details");
			for (String fact : facts)
			{
				card.add(note(fact));
			}
		}
		if (tile.description != null && !tile.description.trim().isEmpty())
		{
			detailHeading(card, 9, "Objective");
			card.add(note(tile.description.trim()));
		}

		List<String> tracked = tileTrackingDetails(tile);
		if (tileIsManual(tile))
		{
			detailHeading(card, 9, "Manual completion");
			card.add(note(tile.manualOnly
				? "The plugin can't see this item arrive — it isn't a drop (it's bought, made or handed over), so it isn't tracked. Screenshot it and submit it on the site."
				: "This task is not completed automatically. A captain or admin marks it done."));
		}
		else if (!tracked.isEmpty())
		{
			// The rule in plain words before the list — the same sentence the site's board shows, so
			// "one set" vs "any of" vs "different items" is never left to be inferred from a list.
			if (tile.howItCounts != null && !tile.howItCounts.trim().isEmpty())
			{
				detailHeading(card, 9, "How it counts");
				card.add(note(tile.howItCounts.trim(), ColorScheme.BRAND_ORANGE));
			}
			detailHeading(card, 9, "Tracked automatically");
			boolean first = true;
			int headers = 0;
			for (String line : tracked)
			{
				card.add(trackedLine(line, first, headers));
				if (line.endsWith(":"))
				{
					headers++;
				}
				first = false;
			}
		}
		if (tile.requirement != null && !tile.requirement.trim().isEmpty()
			&& !tile.requirement.trim().equals(tile.description == null ? "" : tile.description.trim())
			&& !tracked.contains(tile.requirement.trim()))
		{
			detailHeading(card, 9, "Requirements");
			card.add(note(tile.requirement.trim()));
		}

		String planning = claimTooltip(tile.claims);
		if (planning != null && !planning.isEmpty())
		{
			card.add(gap(7));
			card.add(note(planning.replace("\n", " · "), claimedByMe(tile.claims) ? ColorScheme.BRAND_ORANGE : CLAIM_COLOR));
		}

		if (canClaim)
		{
			card.add(gap(8));
			JButton primary;
			JButton secondary;
			if (claimedByMe(tile.claims))
			{
				primary = flatButton("Change note…", "Edit the note your team sees", () -> askNoteAndClaim(tile, key));
				secondary = flatButton("Drop claim", "Stop planning this tile", () ->
					runClaimChange(key, () -> dataSource.unclaimTile(catalogueEventId(key), tile.tileId)));
			}
			else
			{
				primary = flatButton("Claim tile", "Tell your team you're going for this", () ->
					runClaimChange(key, () -> dataSource.claimTile(catalogueEventId(key), tile.tileId, null)));
				secondary = flatButton("Add note…", "Claim it with a note for your team", () -> askNoteAndClaim(tile, key));
			}
			card.add(buttonRow(primary, secondary));
		}

		String tileUrl = tileUrl(boardUrl, tile.tileId);
		if (isSafeHttpUrl(tileUrl))
		{
			card.add(gap(5));
			card.add(siteLink("Open on Anvil ↗", "Open this tile on the Anvil site", tileUrl));
		}

		rows.add(capH(card));
		relayout(catalogue, rows);
	}

	/** A heading inside the tile detail, {@code above} px under what came before it. */
	private static void detailHeading(JPanel card, int above, String text)
	{
		if (above > 0)
		{
			card.add(gap(above));
		}
		card.add(sectionHeader(text));
		card.add(gap(2));
	}

	/** Stable compact metadata used by the detail view and its regression tests. */
	static List<String> tileDetailFacts(BingoApiClient.BoardTile tile, boolean preStart)
	{
		List<String> facts = new ArrayList<>();
		if (!preStart)
		{
			facts.add("Status: " + tileStateText(tile).replace("✓ ", ""));
		}
		else if (tileIsManual(tile))
		{
			facts.add("Completion: manual");
		}
		if (tile.points > 0)
		{
			facts.add("Points: " + tile.points);
		}
		if (tile.tier != null && !tile.tier.trim().isEmpty())
		{
			facts.add("Tier: " + tile.tier.trim());
		}
		if (tile.category != null && !tile.category.trim().isEmpty())
		{
			facts.add("Category: " + tile.category.trim().replace(",", " ·"));
		}
		return facts;
	}

	/** Pre-start rows hide completion, but a manual-only task must still identify itself. */
	static String tileRowStateText(BingoApiClient.BoardTile tile, boolean preStart)
	{
		return preStart ? (tileIsManual(tile) ? "(manual)" : null) : tileStateText(tile);
	}

	/** Compact state shown at the right edge of a catalogue row. */
	static String tileStateText(BingoApiClient.BoardTile tile)
	{
		return (tile.complete ? "✓ Done" : "Open") + (tileIsManual(tile) ? " (manual)" : "");
	}

	/**
	 * A tile can be manual because the host flipped off otherwise-valid tracking, or because its
	 * shape has no plugin tracker at all. The board API has always sent the former; deriving the
	 * latter here keeps old sites honest too.
	 */
	static boolean tileIsManual(BingoApiClient.BoardTile tile)
	{
		if (tile.autoTrackDisabled != 0 || tile.manualOnly)
		{
			return true;
		}
		if (tile.statName != null && !tile.statName.trim().isEmpty())
		{
			return false;
		}
		String type = tile.tileType == null ? "" : tile.tileType.trim().toLowerCase(Locale.ROOT);
		if ("drop".equals(type) || "gain".equals(type))
		{
			return (tile.itemIds == null || tile.itemIds.isEmpty())
				&& (tile.trackedItems == null || tile.trackedItems.isEmpty())
				&& (tile.itemRequirements == null || tile.itemRequirements.isEmpty());
		}
		return !TRACKED_TYPES.contains(type);
	}

	/** Tile types the plugin tracks off something other than an item list. */
	private static final Set<String> TRACKED_TYPES = Set.of(
		"kill", "lap", "pvp", "diary", "ca", "timed", "lms", "value", "valuetotal", "deathless");

	// Set headers ("Ahrim's set — all 4:") stand out from the items under them: their own colour, a
	// gap above, and the items indented. HTML collapses the leading spaces tileTrackingDetails uses to
	// mark an item line, so the indent is a border, not whitespace.
	// Each header its own colour, cycling — six Barrows sets read as six blocks. Green is kept for
	// "collected" and orange for the How-it-counts rule, so neither is in the palette.
	private static final Color[] SET_HEADER_COLORS = {
		new Color(0xE0_C0_6A), // gold
		new Color(0x6A_C8_E0), // sky
		new Color(0xE0_8A_C4), // pink
		new Color(0xA8_9A_F0), // lavender
		new Color(0x5F_D4_BE), // teal
		new Color(0xE8_A8_7A), // peach
	};
	private static final Color DONE_COLOR = new Color(0x6F_C2_6F);
	private static final Color MANUAL_COLOR = new Color(0xE8_B8_3A);

	private JLabel trackedLine(String line, boolean first, int headerIndex)
	{
		boolean header = line.endsWith(":");
		boolean item = line.startsWith("  ");
		String text = line.trim();
		boolean ticked = text.startsWith("✓");
		if (ticked)
		{
			text = text.substring(1).trim();
		}
		// A done set's "✓" / "✓×2" reads as words in the header; the item ticks become drawn icons.
		text = text.replace(" ✓×", " · done ×").replace(" ✓:", " · done:");
		JLabel label = note(text);
		if (ticked)
		{
			label.setIcon(new GlyphIcon(GlyphIcon.Shape.CHECK, 11, DONE_COLOR));
			label.setIconTextGap(3);
		}
		if (header)
		{
			label.setForeground(SET_HEADER_COLORS[headerIndex % SET_HEADER_COLORS.length]);
		}
		else if (ticked)
		{
			label.setForeground(DONE_COLOR);
		}
		int top = header && !first ? 6 : 0;
		int left = item ? 8 : 0;
		label.setBorder(javax.swing.BorderFactory.createEmptyBorder(top, left, 0, 0));
		return label;
	}

	private static String itemName(int itemId, String name)
	{
		return name == null || name.trim().isEmpty() ? "Item " + itemId : name.trim();
	}

	/**
	 * A collection's items, grouped the way the site's board groups them: always-required items
	 * first, then one header per set saying how much of it is needed, with a ✓ on what's in. A flat
	 * "all of these" list gets one header too, so it can't be mistaken for "any of".
	 */
	static void itemSetLines(BingoApiClient.BoardTile tile, List<String> lines)
	{
		List<PluginConfigResponse.ItemRequirement> ungrouped = new ArrayList<>();
		java.util.LinkedHashMap<String, List<PluginConfigResponse.ItemRequirement>> groups = new java.util.LinkedHashMap<>();
		java.util.Map<String, String> displayName = new java.util.HashMap<>();
		for (PluginConfigResponse.ItemRequirement req : tile.itemRequirements)
		{
			if (req == null)
			{
				continue;
			}
			String g = req.group == null ? "" : req.group.trim();
			if (g.isEmpty())
			{
				ungrouped.add(req);
				continue;
			}
			String key = g.toLowerCase(java.util.Locale.ROOT);
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(req);
			displayName.putIfAbsent(key, g);
		}
		if (!ungrouped.isEmpty())
		{
			lines.add(groups.isEmpty() ? "All of these:" : "Always required:");
			for (PluginConfigResponse.ItemRequirement req : ungrouped)
			{
				lines.add(itemLine(req));
			}
		}
		for (java.util.Map.Entry<String, List<PluginConfigResponse.ItemRequirement>> e : groups.entrySet())
		{
			List<PluginConfigResponse.ItemRequirement> set = e.getValue();
			int need = 0;
			for (PluginConfigResponse.ItemRequirement r : set)
			{
				need = Math.max(need, r.groupRequire);
			}
			need = need <= 0 ? set.size() : Math.min(need, set.size());
			int times = ClogTaskModel.setTimes(set, need);
			String howMuch = need < set.size() ? "any " + need + " of " + set.size() : "all " + set.size();
			lines.add(displayName.get(e.getKey()) + " set — " + howMuch + (times > 0 ? (times > 1 ? " ✓×" + times : " ✓") : "") + ":");
			for (PluginConfigResponse.ItemRequirement req : set)
			{
				lines.add(itemLine(req));
			}
		}
	}

	private static String itemLine(PluginConfigResponse.ItemRequirement req)
	{
		int need = Math.max(1, req.requiredAmount);
		String name = itemName(req.itemId, req.name);
		if (req.currentAmount >= need)
		{
			return "  ✓ " + (need > 1 || req.currentAmount > need ? req.currentAmount + "× " : "") + name;
		}
		return "  " + (req.currentAmount > 0 ? req.currentAmount + "/" + need : need + "×") + " " + name;
	}

	/** Exact inputs the plugin watches, shown in the detail view instead of hiding them behind prose. */
	static List<String> tileTrackingDetails(BingoApiClient.BoardTile tile)
	{
		List<String> lines = new ArrayList<>();
		if (tileIsManual(tile))
		{
			return lines;
		}
		if (tile.itemRequirements != null && !tile.itemRequirements.isEmpty())
		{
			itemSetLines(tile, lines);
		}
		else if (tile.trackedItems != null && !tile.trackedItems.isEmpty())
		{
			if (tile.trackedItems.size() == 1)
			{
				BingoApiClient.TrackedItem item = tile.trackedItems.get(0);
				lines.add(Math.max(1, tile.requiredAmount) + "× " + itemName(item.itemId, item.name));
			}
			else
			{
				boolean distinct = "distinct".equals(tile.countMode);
				int need = Math.max(1, tile.requiredAmount);
				lines.add(distinct
					? need + " DIFFERENT items from:"
					: need == 1 ? "Any ONE of:" : "Any " + need + " drops (repeats count) from:");
				for (BingoApiClient.TrackedItem item : tile.trackedItems)
				{
					boolean counted = distinct && tile.countedItemIds != null && tile.countedItemIds.contains(item.itemId);
					lines.add((counted ? "✓ " : "- ") + itemName(item.itemId, item.name));
				}
			}
		}
		else if (tile.itemIds != null && !tile.itemIds.isEmpty())
		{
			lines.add("Tracked item IDs: " + tile.itemIds.toString().replace("[", "").replace("]", ""));
		}
		else if (tile.statName != null && !tile.statName.trim().isEmpty())
		{
			String unit = "boss".equalsIgnoreCase(tile.statType) || "kc".equalsIgnoreCase(tile.statType)
				? " KC" : " XP";
			lines.add(tile.statName.trim() + unit);
		}
		else if (tile.requirement != null && !tile.requirement.trim().isEmpty())
		{
			lines.add(tile.requirement.trim());
		}
		if (tile.sources != null && !tile.sources.isEmpty())
		{
			lines.add("Only from: " + String.join(", ", tile.sources));
		}
		return lines;
	}

	/** Install one row listener on the panel and every label nested inside it. */
	static void addMouseListenerToTree(Component component, MouseAdapter listener)
	{
		component.addMouseListener(listener);
		if (component instanceof java.awt.Container)
		{
			for (Component child : ((java.awt.Container) component).getComponents())
			{
				addMouseListenerToTree(child, listener);
			}
		}
	}

	/** Right-click on a tile row: claim it (optionally with a note), or drop your own claim. */
	private JPopupMenu claimMenu(BingoApiClient.BoardTile tile, String key)
	{
		JPopupMenu menu = new JPopupMenu();
		if (claimedByMe(tile.claims))
		{
			JMenuItem note = new JMenuItem("Change my note…");
			note.addActionListener(ev -> askNoteAndClaim(tile, key));
			menu.add(note);
			JMenuItem drop = new JMenuItem("Drop my claim");
			drop.addActionListener(ev -> runClaimChange(key, () ->
				dataSource.unclaimTile(catalogueEventId(key), tile.tileId)));
			menu.add(drop);
		}
		else
		{
			JMenuItem go = new JMenuItem("I'm going for this");
			go.addActionListener(ev -> runClaimChange(key, () ->
				dataSource.claimTile(catalogueEventId(key), tile.tileId, null)));
			menu.add(go);
			JMenuItem withNote = new JMenuItem("I'm going for this, with a note…");
			withNote.addActionListener(ev -> askNoteAndClaim(tile, key));
			menu.add(withNote);
		}
		return menu;
	}

	/** Right-click should explain an unavailable action rather than silently doing nothing. */
	private static JPopupMenu claimUnavailableMenu(String reason)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem item = new JMenuItem(reason == null ? "Claims aren't available here." : reason);
		item.setEnabled(false);
		menu.add(item);
		return menu;
	}

	private void askNoteAndClaim(BingoApiClient.BoardTile tile, String key)
	{
		String current = myClaimNote(tile.claims);
		Object answer = JOptionPane.showInputDialog(this, "Note for your team (optional, e.g. \"doing it tonight\"):",
			"Claim tile", JOptionPane.PLAIN_MESSAGE, null, null, current == null ? "" : current);
		if (answer == null)
		{
			return; // cancelled
		}
		String note = answer.toString();
		runClaimChange(key, () -> dataSource.claimTile(catalogueEventId(key), tile.tileId, note));
	}

	/** Event identity travels with claim mutations so an enrolled upcoming preview stays scoped. */
	private int catalogueEventId(String key)
	{
		BingoApiClient.BoardResponse board = boardCatalogues.get(key);
		return board == null ? 0 : board.eventId;
	}

	@FunctionalInterface
	private interface ClaimCall
	{
		void run() throws java.io.IOException;
	}

	/** Do the claim change off the EDT, then reload the board so every row shows the new state. */
	private void runClaimChange(String key, ClaimCall call)
	{
		new SwingWorker<Void, Void>()
		{
			@Override
			protected Void doInBackground() throws Exception
			{
				call.run();
				return null;
			}

			@Override
			protected void done()
			{
				try
				{
					get();
				}
				catch (Exception ex)
				{
					Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
					JOptionPane.showMessageDialog(AnvilSidebarPanel.this, cause.getMessage(), "Claim",
						JOptionPane.WARNING_MESSAGE);
				}
				loadBoardCatalogue(key, catalogueEventId(key), true);
			}
		}.execute();
	}

	/** "Planning: You, Alice +2" for a tile's claims, or null when nobody has claimed it. */
	static String claimLine(java.util.List<BingoApiClient.BoardClaim> claims)
	{
		if (claims == null || claims.isEmpty())
		{
			return null;
		}
		java.util.List<String> names = new java.util.ArrayList<>();
		for (BingoApiClient.BoardClaim c : claims)
		{
			if (c == null)
			{
				continue;
			}
			// Yours first: it's the one you most need to notice.
			if (c.mine)
			{
				names.add(0, "You");
			}
			else if (c.name != null && !c.name.trim().isEmpty())
			{
				names.add(c.name.trim());
			}
		}
		if (names.isEmpty())
		{
			return null;
		}
		String shown = String.join(", ", names.subList(0, Math.min(2, names.size())));
		return "Planning: " + shown + (names.size() > 2 ? " +" + (names.size() - 2) : "");
	}

	/** Every claimer with their note, one per line, for the hover. */
	static String claimTooltip(java.util.List<BingoApiClient.BoardClaim> claims)
	{
		if (claims == null || claims.isEmpty())
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (BingoApiClient.BoardClaim c : claims)
		{
			if (c == null)
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(" · ");
			}
			sb.append(c.mine ? "You" : (c.name == null ? "A teammate" : c.name));
			if (c.note != null && !c.note.trim().isEmpty())
			{
				sb.append(" (").append(c.note.trim()).append(')');
			}
		}
		return sb.toString();
	}

	static boolean claimedByMe(java.util.List<BingoApiClient.BoardClaim> claims)
	{
		return claims != null && claims.stream().anyMatch(c -> c != null && c.mine);
	}

	private static String myClaimNote(java.util.List<BingoApiClient.BoardClaim> claims)
	{
		if (claims == null)
		{
			return null;
		}
		for (BingoApiClient.BoardClaim c : claims)
		{
			if (c != null && c.mine)
			{
				return c.note;
			}
		}
		return null;
	}

	static String tileUrl(String boardUrl, int tileId)
	{
		if (!isSafeHttpUrl(boardUrl) || tileId <= 0)
		{
			return null;
		}
		return boardUrl + (boardUrl.contains("?") ? "&" : "?") + "tile=" + tileId;
	}

	private void loadBoardCatalogue(String key, int eventId, boolean force)
	{
		if (loadingBoardCatalogues.contains(key) || (!force && boardCatalogues.containsKey(key)))
		{
			return;
		}
		loadingBoardCatalogues.add(key);
		failedBoardCatalogues.remove(key);
		final int generation = boardCatalogueGeneration;
		new SwingWorker<BingoApiClient.BoardResponse, Void>()
		{
			@Override
			protected BingoApiClient.BoardResponse doInBackground()
			{
				return dataSource.fetchBoard(eventId);
			}

			@Override
			protected void done()
			{
				if (generation != boardCatalogueGeneration)
				{
					return;
				}
				loadingBoardCatalogues.remove(key);
				try
				{
					BingoApiClient.BoardResponse board = get();
					if (board == null)
					{
						failedBoardCatalogues.add(key);
					}
					else
					{
						boardCatalogues.put(key, board);
					}
				}
				catch (Exception ex)
				{
					failedBoardCatalogues.add(key);
					log.debug("tile catalogue fetch failed", ex);
				}
				if (!boardSearchFocused)
				{
					renderSelected();
				}
			}
		}.execute();
	}

	private void clearBoardCatalogueState()
	{
		boardCatalogueGeneration++;
		boardCatalogues.clear();
		loadingBoardCatalogues.clear();
		failedBoardCatalogues.clear();
		boardSearches.clear();
		boardStatusFilters.clear();
		boardCataloguePages.clear();
		openCatalogueDetailKey = null;
		openCatalogueDetailTileId = -1;
		showAllActiveTiles = false;
		boardSearchFocused = false;
		disposeTeams();
	}

	/**
	 * Open the Teams page. {@code eventId <= 0} is the active board, whose id comes from the board
	 * itself (ETag-cached, so usually a 304) — off the EDT, inside the view's first fetch.
	 */
	private void openTeams(int eventId)
	{
		disposeTeams();
		teamsView = new TeamsView(dataSource::fetchRoster, interactive(), eventId, () ->
		{
			BingoApiClient.BoardResponse board = dataSource.fetchBoard(0);
			return board == null ? 0 : board.eventId;
		}, playerCardsOn() ? this::showLookup : null);
		renderSelected();
	}

	private void disposeTeams()
	{
		if (teamsView != null)
		{
			teamsView.dispose();
			teamsView = null;
		}
	}

	private JLabel warningLabel(String message)
	{
		JLabel warn = small(message, ColorScheme.PROGRESS_ERROR_COLOR);
		return warn;
	}

	/** Swap the single content component and repaint. */
	private void setContent(Component component)
	{
		SwingUtilities.invokeLater(() ->
		{
			updateTabStrip();
			if (tab == Tab.YOU)
			{
				renderYou(false); // a refresh can change what You shows: the character, the card
			}
			else if (tab == Tab.SETTINGS)
			{
				renderSettings(); // admin rights and the sound list can change under it
			}
			content.removeAll();
			if (component instanceof javax.swing.JComponent)
			{
				((javax.swing.JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
			}
			content.add(component);
			content.revalidate();
			content.repaint();
		});
	}

	// ---- Anvil-themed widget chrome ---------------------------------------------------------------

	/** A themed action button in the panel's orange, wired to one thing it does. */
	static JButton flatButton(String label, String tooltip, Runnable onClick)
	{
		return flatButton(label, tooltip, ColorScheme.BRAND_ORANGE, onClick);
	}

	private static JButton flatButton(String label, String tooltip, Color foreground, Runnable onClick)
	{
		JButton b = new JButton(label);
		styleFlatButton(b, foreground);
		b.setToolTipText(tooltip);
		b.addActionListener(e -> onClick.run());
		return b;
	}

	/**
	 * Two buttons on one line, equal halves.
	 *
	 * <p>The height cap matters: a grid inside a vertical BoxLayout will happily stretch to whatever
	 * space is left, which turns a pair of buttons into a pair of slabs.
	 */
	private static JPanel buttonRow(JButton left, JButton right)
	{
		return buttons(new GridLayout(1, 2, 4, 0), left, right);
	}

	/** {@link #flatButton} holding a whole row, so a lone action doesn't sit in half a line. */
	static JPanel fullWidthButton(String label, String tooltip, Runnable onClick)
	{
		return fullWidth(flatButton(label, tooltip, onClick));
	}

	private static JPanel fullWidth(JButton button)
	{
		return buttons(new GridLayout(1, 1), button);
	}

	private static JPanel buttons(GridLayout layout, JButton... buttons)
	{
		JPanel row = left(new JPanel(layout));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (JButton b : buttons)
		{
			row.add(b);
		}
		return capH(row);
	}

	private static void styleFlatButton(JButton b, Color foreground)
	{
		b.setFocusPainted(false);
		b.setForeground(foreground);
		b.setBackground(WIDGET_BG);
		b.setFont(SMALL);
		b.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(WIDGET_BORDER),
			BorderFactory.createEmptyBorder(4, 10, 4, 10)));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (b.isEnabled())
				{
					b.setBackground(WIDGET_BG_HOVER);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				b.setBackground(WIDGET_BG);
			}
		});
	}

	/** Flat combo for the clan selector and tile-status filter: dark field, gold arrow — default Swing sticks out. */
	private static void styleCombo(JComboBox<?> combo)
	{
		combo.setBackground(WIDGET_BG);
		combo.setForeground(Color.WHITE);
		combo.setFont(SMALL);
		combo.setBorder(BorderFactory.createLineBorder(WIDGET_BORDER));
		combo.setUI(new BasicComboBoxUI()
		{
			@Override
			protected JButton createArrowButton()
			{
				BasicArrowButton arrow = new BasicArrowButton(
					SwingConstants.SOUTH, WIDGET_BG, WIDGET_BG, ColorScheme.BRAND_ORANGE, WIDGET_BG);
				arrow.setBorder(BorderFactory.createEmptyBorder());
				return arrow;
			}
		});
	}

	/**
	 * Two real labels, not one HTML one.
	 *
	 * The second line was <code>&lt;font size='-2'&gt;</code> inside a label already set to
	 * RuneScape's small font. That font is a bitmap face: asked to render smaller still it does not
	 * get daintier, it gets illegible — and the size the browser-ish HTML renderer picks is not one
	 * the face was drawn at. The rest of this panel never shrinks type to mean "secondary"; it keeps
	 * the same small font and drops the colour to {@link #MUTED}. This does the same.
	 *
	 * Building the row out of components also means no markup, which means no clan name can smuggle
	 * any: {@link #plainText} was guarding a string we were about to concatenate into HTML ourselves,
	 * which is the one case it does not guard.
	 */
	private static final class ClanChoiceRenderer implements ListCellRenderer<ClanChoice>
	{
		private final JPanel row = new JPanel(new BorderLayout(0, 1));
		private final JLabel name = new JLabel();
		private final JLabel detail = new JLabel();

		ClanChoiceRenderer()
		{
			name.setFont(SMALL);
			detail.setFont(SMALL);
			row.setOpaque(true);
			row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			row.add(name, BorderLayout.NORTH);
			row.add(detail, BorderLayout.SOUTH);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends ClanChoice> list, ClanChoice value,
			int index, boolean isSelected, boolean cellHasFocus)
		{
			// index >= 0 → a popup row; -1 → the closed field. Dark rows, gold-tinted hover.
			boolean hover = isSelected && index >= 0;
			row.setBackground(hover ? WIDGET_BG_HOVER : WIDGET_BG);
			name.setForeground(hover ? ColorScheme.BRAND_ORANGE : Color.WHITE);
			detail.setForeground(MUTED);

			name.setText(value == null ? "" : plainText(value.label));
			// The closed field shows the name alone — a second line there would resize the header on
			// every refresh, and the detail is what you read while choosing, not after.
			boolean showDetail = index >= 0 && value != null && !value.detail.isEmpty();
			detail.setText(showDetail ? plainText(value.detail) : "");
			detail.setVisible(showDetail);
			return row;
		}
	}
}
