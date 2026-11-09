package com.anvil;

import static net.runelite.client.ui.ColorScheme.*;
import com.anvil.AnvilSidebarDataSource.*;
import lombok.Setter;
import com.google.gson.JsonObject;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.*;
import javax.swing.*;
import javax.swing.Timer;
import java.awt.event.*;
import javax.swing.plaf.basic.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.*;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.client.game.*;

import static com.anvil.Ui.*;
import net.runelite.client.config.ConfigManager;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The side panel. What it holds natively is what only this client has: signing in, the account line,
 * the clan picker, the tab strip, the Lookup search box, settings and banner sounds, and the starting
 * shot. Everything to look at — the Clan tab's events and boards, You, what Lookup found — is a view
 * the site builds and {@link UiView} draws. An auto-refresh runs only while the panel is open.
 *
 * <p><b>Threading:</b> all Swing mutation stays on the EDT; the config re-read and the views' fetches
 * run off it and hand back via invokeLater. {@link Singleton} — one toolbar panel.</p>
 */
@Slf4j
@Singleton
public class AnvilSidebarPanel extends PluginPanel {
	/** Auto-refresh cadence while the panel is open. Mirrors the plugin's other polls. */
	static final int POLL_INTERVAL_MS = 15_000;
	/** While the live push is up, one poll per this many ticks (5 minutes). */
	static final int PUSH_SAFETY_TICKS = 20;
	/** Client property on the start-proof checks panel: what it shows now (renderStartProofChecks). */
	static final String START_PROOF_SHOWN = "anvil.startProofShown";


	// Anvil theme for the interactive widgets: flat dark surfaces with the gold/orange accent the
	// rest of the sidebar (title, bars, links) already uses — default Swing chrome sticks out badly.
	static final Color WIDGET_BG = DARKER_GRAY_COLOR;
	static final Color WIDGET_BG_HOVER = DARK_GRAY_HOVER_COLOR;
	static final Color WIDGET_BORDER = new Color(0x47_47_47);
	// Buttons sit a step above the panel, not sunk below it, so they read as something to press.
	static final Color BUTTON_BG = new Color(0x3a_3a_3a);
	static final Color BUTTON_HOVER = new Color(0x47_47_47);

	/** Wrap width for the connect-status line so a long notice wraps instead of clipping (tooltip carries full text). */
	static final int STATUS_WRAP_PX = PluginPanel.PANEL_WIDTH - 40;


	/** Clip rows on the home page before the rest collapse into a "+N more" line. */
	static final int BANNER_CLIPS_SHOWN = 8;




	final AnvilSidebarDataSource dataSource;
	/** Item and boss icons for the player card. Null in tests that build the panel by hand. */
	final ItemManager itemManager;
	final SpriteManager spriteManager;
	final SkillIconManager skillIconManager;

	// ---- Tabs: Clan · You · Lookup ----------------------------------------------------------------
	// Clan is everything the panel always was — `content` below, untouched. You and Lookup are views
	// the site builds (UiView), and only exist once the site has answered: until then the strip hides
	// and the Clan tab says it can't reach the site, with the sound lists under it (renderEmpty).
	enum Tab { CLAN, FEED, YOU, LOOKUP, SETTINGS }
	Tab tab = Tab.CLAN;
	/** Whether the strip is showing, i.e. signed in to a site that serves player cards. */
	boolean tabsOn;
	// Holds ONLY the selected tab's panel. A CardLayout sized every tab to the tallest one — usually
	// Clan mid-event — and BoxLayout spread that extra height through the You card: log bars drifted
	// apart and the grids were squeezed until their last row clipped.
	final JPanel tabBody = new JPanel(new BorderLayout());
	final JPanel tabStrip = new JPanel(new GridBagLayout());
	final Map<Tab, JLabel> tabLabels = new EnumMap<>(Tab.class);
	final JPanel lookupPanel = column();
	/** Lookup is for a player or a clan; this says which the search box means. */
	boolean lookupClans;
	/** Players | Clans, redrawn when the mode changes (styleLookupModes). */
	final JPanel lookupModes = column();
	Boolean lookupModesShown;
	/** The site-built views: the Clan tab, the You tab, and what Lookup found. */
	final UiView clanView;
	final UiView youView;
	final UiView lookupView;
	/** The Feed tab: the clan's and the board's live lists, and the board's chat (view 'feed'). */
	final UiView feedView;
	final UiView[] views;
	/** Whose card You holds. */
	String youRsn;
	/** The Clan tab has asked for its view since it was last reset (sign-in, a clan switch). */
	boolean clanShown;
	/** When the Clan tab last asked the site, so a burst of repaints asks once. */
	long clanAskedAt;
	/** ⚙: syncing and this client's own settings — profile sync, roster sync for admins, sounds. */
	final JPanel settingsPanel = column();
	final JPanel lookupRecentRow = column();
	final JTextField lookupSearch = new JTextField();
	/** Fills in names as you type in {@link #lookupSearch}; built with the tab bodies. */
	LookupSuggestions lookupCompleter;
	final JLabel lookupClear = new JLabel(new GlyphIcon(GlyphIcon.Shape.CLOSE, 10, MUTED));
	final JPanel lookupBox = new JPanel(new BorderLayout(6, 0));
	/** Newest first, at most five, this session only. */
	final LinkedList<String> recentLookups = new LinkedList<>();
	/** The clan dropdown is wanted by the clan logic AND only shown on the Clan tab. */
	boolean pickerWanted;

	// Header controls (persistent across state changes).
	// Which clan the plugin is pointed at. One Anvil serves every clan and a person can hold seats in
	// several, so this is a real choice rather than a filter over data we already have: picking here
	// re-addresses everything the plugin does — board, stat pushes, submissions, notifications.
	final JComboBox<ClanChoice> clanPicker = new JComboBox<>();
	final JButton refreshButton = new JButton("Refresh");

	// Device sign-in (home-native, DeviceSignIn): shown whenever there is no Account Token yet — the
	// main way in, so nobody has to copy a token out of the site.
	final BingoApiClient apiClient;
	final ConfigManager configManager;
	/** RuneLite's shared client-lifetime scheduler — paces the sign-in flow's approval polls. */
	final ScheduledExecutorService executor;
	final JButton signInButton = new JButton("Sign in with Discord");
	final JLabel signInStatus = small("", MUTED);
	final JPanel signInRow = new JPanel(new BorderLayout(0, 2));
	// The token isn't in RuneLite's settings (AnvilConfig.playerToken is hidden), so the panel carries
	// both ends: a manual paste for when the browser sign-in can't work, and Sign out.
	final JLabel pasteTokenLink = new ClipLabel("Paste a token instead");
	/** Opens the account menu: Not my account / Sign out. */
	final JLabel accountMenuLink = new JLabel(new GlyphIcon(GlyphIcon.Shape.ELLIPSIS, 14));
	/**
	 * Between {@link #onActivate} and {@link #onDeactivate}. Off screen, a refresh is skipped outright:
	 * XP bumps, config changes and logins all ask for one, and drawing a panel nobody can see is EDT
	 * time for nothing — opening it refreshes anyway.
	 */
	boolean onScreen;
	boolean signInInFlight;
	volatile DeviceSignIn signIn;

	// The logged-in character's row (AnvilSidebarDataSource.AccountRow) — one quiet line, because almost
	// everyone reading it is the owner: "Tracking X · Not you? ×", "Not tracking X here · Track again",
	// or "X is someone else's · It's mine?". The way out for a sibling or a visitor; × hides the
	// tracking line for that character for good.
	final JLabel accountLabel = small("", MUTED);
	/** "Tracking" + the tracked name in its Supporter colours (ChatFlair), swapped as the look changes. */
	final JPanel accountLine = new JPanel();
	JLabel accountName;
	final JLabel accountLink = small("", BRAND_ORANGE);
	final JLabel accountStatus = small("", MUTED);
	final JPanel accountRowPanel = new JPanel(new BorderLayout(4, 2));
	/** On a seasonal world: whether its progress is shared with the clan, and a switch for it. */
	final JPanel seasonRow = new JPanel(new BorderLayout(6, 0));
	final JLabel seasonLabel = small("Sharing season progress", MUTED);
	final JLabel seasonLink = new JLabel("Turn off");
	AccountRow shownAccountRow;
	boolean accountActionInFlight;

	final JPanel content = column();

	// Only re-renders while the panel is visible; started on activate, stopped on deactivate.
	final Timer autoRefresh;

	/** Re-checks the starting-shot card's requirements once a second while it's up; null with no card. */
	final Timer startProofTimer = new Timer(1000, e -> tickStartProof());
	Runnable startProofTick;

	// Guards against ActionEvents fired while we rebuild the combo model.
	boolean rebuildingPicker;

	/**
	 * The executor for fetches somebody clicked and is now watching a spinner for — off RuneLite's
	 * single shared thread (see {@link BingoApiClient#interactiveExecutor}).
	 */
	java.util.concurrent.Executor interactive() {
		return apiClient != null ? apiClient.interactiveExecutor() : executor;
	}

	@Inject
	public AnvilSidebarPanel(AnvilSidebarDataSource dataSource, BingoApiClient apiClient,
		ConfigManager configManager, ScheduledExecutorService executor,
		ItemManager itemManager, SpriteManager spriteManager, SkillIconManager skillIconManager) {
		super(true); // wrap in RuneLite's scroll pane so a long nearest-tiles list scrolls
		this.dataSource = dataSource;

		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		this.skillIconManager = skillIconManager;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.executor = executor;
		clanView = new UiView(uiHost(), itemManager, spriteManager, skillIconManager);
		youView = new UiView(uiHost(), itemManager, spriteManager, skillIconManager);
		lookupView = new UiView(uiHost(), itemManager, spriteManager, skillIconManager);
		feedView = new UiView(uiHost(), itemManager, spriteManager, skillIconManager);
		views = new UiView[]{clanView, youView, lookupView, feedView};

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(BORDER_OFFSET, BORDER_OFFSET, BORDER_OFFSET, BORDER_OFFSET));
		setBackground(DARK_GRAY_COLOR);

		add(buildHeader(), BorderLayout.NORTH);
		SlimScrollBarUI.applyTo(getWrappedPanel());

		buildTabBodies();
		tabBody.setBackground(DARK_GRAY_COLOR);
		tabBody.add(content, BorderLayout.NORTH);
		add(tabBody, BorderLayout.CENTER);

		autoRefresh = new Timer(POLL_INTERVAL_MS, e -> {
			// With the live push up, a push asks for the board when it moves (boardChanged) and "2m ago"
			// times tick locally (UiView), so the timer is only a safety net: every 5 minutes.
			if (!typingInSearch() && (!pushLive.getAsBoolean() || ++quietTicks % PUSH_SAFETY_TICKS == 0)) poll();
		});
		autoRefresh.setCoalesce(true);

		renderLoading();
	}

	JPanel buildHeader() {
		var header = new JPanel(new BorderLayout(0, 8));
		header.setBackground(DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

		var title = label("Anvil Progress", BOLD, BRAND_ORANGE);

		styleFlatButton(refreshButton, Color.WHITE);
		refreshButton.addActionListener(e -> refresh(true));
		// As wide as "Refreshing…" from the start, so the header doesn't shift while it works.
		refreshButton.setText("Refreshing…");
		refreshButton.setPreferredSize(refreshButton.getPreferredSize());
		refreshButton.setText("Refresh");

		var titleRow = left(new JPanel(new BorderLayout()));
		titleRow.setBackground(DARK_GRAY_COLOR);
		titleRow.add(title, BorderLayout.WEST);
		titleRow.add(refreshButton, BorderLayout.EAST);
		capH(titleRow);

		// Sign-in affordance — visible whenever there is no token (see refreshSignInRow).
		styleFlatButton(signInButton, BRAND_ORANGE);
		signInButton.addActionListener(e -> startSignIn());
		signInStatus.setVisible(false);
		signInRow.setBackground(DARK_GRAY_COLOR);
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
		var accountActions = new JPanel(new BorderLayout(8, 0));
		accountActions.setBackground(DARKER_GRAY_COLOR);
		accountActions.add(accountLink, BorderLayout.CENTER);
		accountActions.add(accountMenuLink, BorderLayout.EAST);
		accountRowPanel.setBackground(DARKER_GRAY_COLOR);
		accountRowPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		accountLine.setLayout(new BoxLayout(accountLine, BoxLayout.X_AXIS));
		accountLine.setOpaque(false);
		accountLine.add(accountLabel);
		accountRowPanel.add(accountLine, BorderLayout.CENTER);
		accountRowPanel.add(accountActions, BorderLayout.EAST);
		accountRowPanel.add(accountStatus, BorderLayout.SOUTH);
		left(accountRowPanel).setVisible(false);

		styleLink(seasonLink, "Your seasonal progress (relics, areas, tasks, with a screenshot) goes to your clan."
			+ " Your clan chooses what gets posted on the Anvil website.", () -> dataSource.setShareSeasonal(!dataSource.shareSeasonal()));
		seasonRow.setBackground(DARKER_GRAY_COLOR);
		seasonRow.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		seasonRow.add(seasonLabel, BorderLayout.CENTER);
		seasonRow.add(seasonLink, BorderLayout.EAST);
		left(capH(seasonRow)).setVisible(false);

		var top = column();
		top.add(titleRow);
		top.add(gap(6));
		top.add(signInRow);
		top.add(accountRowPanel);
		top.add(seasonRow);
		header.add(top, BorderLayout.NORTH);

		// Clan filter — selecting a clan re-renders from the held snapshot (no refetch).
		styleCombo(clanPicker);
		clanPicker.setRenderer(new ClanChoiceRenderer());
		clanPicker.setFocusable(false);
		clanPicker.addActionListener(e -> {
			if (rebuildingPicker) return;
			var sel = (ClanChoice) clanPicker.getSelectedItem();
			if (sel == null || sel.slug.equals(dataSource.chosenClan())) return;
			switchClan(sel.slug);
		});
		var south = new JPanel(new BorderLayout(0, 8));
		south.setBackground(DARK_GRAY_COLOR);
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
	void switchClan(String slug) {
		clanShown = false; // another clan's events are a different list — start at the top
		dataSource.chooseClan(slug);
		renderLoading();
	}

	// ---- Tabs ---------------------------------------------------------------------------------------

	JPanel buildTabStrip() {
		tabStrip.setBackground(DARK_GRAY_COLOR);
		var gc = new GridBagConstraints();
		gc.fill = GridBagConstraints.BOTH;
		gc.gridy = 0;
		for (Tab t : Tab.values()) {
			JLabel label;
			if (t == Tab.SETTINGS) {
				// An icon, not a fourth word: three named tabs and a narrow gear keep the strip calm.
				label = new JLabel(new GlyphIcon(GlyphIcon.Shape.GEAR, 13), SwingConstants.CENTER);
				label.setToolTipText("Syncing and settings");
				label.setPreferredSize(new Dimension(34, label.getPreferredSize().height));
				gc.weightx = 0;
			} else {
				label = new ClipLabel(t == Tab.CLAN ? "Clan" : t == Tab.FEED ? "Feed" : t == Tab.YOU ? "You" : "Lookup", SwingConstants.CENTER);
				label.setFont(SMALL);
				gc.weightx = 1;
			}
			tabLabels.put(t, onClick(label, () -> {
				if (tabsOn) selectTab(t);
			}));
			gc.gridx = t.ordinal();
			tabStrip.add(label, gc);
		}
		styleTabs();
		tabStrip.setVisible(false);
		return tabStrip;
	}

	/** A creator space is not a clan: its tab says "Space". Follows the config on every refresh. EDT. */
	void relabelClanTab() {
		JLabel label = tabLabels.get(Tab.CLAN);
		if (label == null) return;
		var space = dataSource.activeIsSpace();
		var text = space ? "Space" : "Clan";
		if (!text.equals(label.getText())) {
			label.setText(text);
			label.setToolTipText(space ? "This creator's space and events" : null);
		}
	}

	/** Selected: white with an orange underline. The rest: grey over a hairline; all greyed while signed out. */
	void styleTabs() {
		for (Map.Entry<Tab, JLabel> e : tabLabels.entrySet()) {
			var on = tabsOn && e.getKey() == tab;
			e.getValue().setForeground(on ? Color.WHITE : tabsOn ? MUTED : MEDIUM_GRAY_COLOR);
			e.getValue().setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, on ? 2 : 1, 0, on ? BRAND_ORANGE : MEDIUM_GRAY_COLOR),
				BorderFactory.createEmptyBorder(4, 0, on ? 3 : 4, 0)));
		}
	}

	void buildTabBodies() {

		// Our own field rather than RuneLite's IconTextField: that one draws typed text grey and a
		// light-grey clear button with a red cross, neither of which can be restyled from outside.
		styleSearch(lookupSearch);
		lookupSearch.setBorder(BorderFactory.createEmptyBorder());
		lookupSearch.setToolTipText("A player's name — press Enter");
		lookupSearch.addActionListener(e -> {
			if (lookupClans) {
				lookupView.show("clans", Collections.singletonMap("q", lookupSearch.getText().trim()), "Searching…");
			} else showLookup(lookupSearch.getText());
		});
		onEdit(lookupSearch, () -> lookupClear.setVisible(!lookupSearch.getText().isEmpty()));
		// Players only: clan names aren't in any list the game keeps.
		lookupCompleter = new LookupSuggestions(lookupSearch, () -> !lookupClans,
			() -> new ArrayList<>(recentLookups), dataSource::myRsn, dataSource::lookupNames);
		lookupClear.setVisible(false);
		lookupClear.setToolTipText("Clear");
		onClick(lookupClear, () -> {
			lookupSearch.setText("");
			lookupSearch.requestFocusInWindow();
			lookupView.say(lookupClans ? "Search a clan by name." : LOOKUP_HINT);
		});
		lookupBox.setBackground(DARKER_GRAY_COLOR);
		setBoxLine(MEDIUM_GRAY_COLOR);
		lookupBox.add(small("Name", MUTED), BorderLayout.WEST);
		lookupBox.add(lookupSearch, BorderLayout.CENTER);
		lookupBox.add(lookupClear, BorderLayout.EAST);
		capH(left(lookupBox));
		// Focus shows as the underline turning orange.
		lookupSearch.addFocusListener(new FocusAdapter() {
			@Override
			public void focusGained(FocusEvent e) {
				setBoxLine(BRAND_ORANGE);
				lookupCompleter.refresh();
			}

			@Override
			public void focusLost(FocusEvent e) {
				setBoxLine(MEDIUM_GRAY_COLOR);
			}
		});

		styleLookupModes();
		lookupPanel.add(lookupModes);
		lookupPanel.add(gap(6));
		lookupPanel.add(lookupBox);
		lookupPanel.add(gap(6));
		lookupPanel.add(lookupRecentRow);
		lookupPanel.add(lookupView);
		lookupView.say(LOOKUP_HINT);
	}

	static final String LOOKUP_HINT = "Search a name — or right-click anyone in game and pick Anvil.";

	void setBoxLine(Color c) {
		lookupBox.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, c),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
	}

	/** Signed in to a site that builds the panel's cards — the condition for the strip to show at all. */
	boolean playerCardsOn() {
		return !apiClient.needsSignIn() && dataSource.supportsPluginUi();
	}

	/** Re-checked on every render: the config's answer brings the tabs, and sign-out takes them away. */
	void updateTabStrip() {
		relabelClanTab();
		styleLookupModes();
		var on = playerCardsOn();
		// Signed out the strip still shows, greyed: there is more here once you sign in.
		tabStrip.setVisible(on || apiClient.needsSignIn());
		if (on != tabsOn) {
			tabsOn = on;
			if (!on && tab != Tab.CLAN) selectTab(Tab.CLAN);
			styleTabs();
		}
		applyPicker();
	}

	void setPickerWanted(boolean wanted) {
		pickerWanted = wanted;
		applyPicker();
	}

	void applyPicker() {
		clanPicker.setVisible(pickerWanted && tab == Tab.CLAN);
	}

	void selectTab(Tab next) {
		tab = next;
		dataSource.feedOnScreen(onScreen && next == Tab.FEED);
		tabBody.removeAll();
		tabBody.add(next == Tab.CLAN ? content : next == Tab.FEED ? feedView : next == Tab.YOU ? youView : next == Tab.LOOKUP ? lookupPanel : settingsPanel,
			BorderLayout.NORTH);
		tabBody.revalidate();
		styleTabs();
		applyPicker();
		if (next == Tab.YOU) {
			renderYou(true);
		} else if (next == Tab.FEED) {
			renderFeed();
		} else if (next == Tab.SETTINGS) renderSettings();
		revalidate();
		repaint();
	}

	/** The ⋯ menu's "View my profile": your own card. */
	public void showYou() {
		SwingUtilities.invokeLater(() -> selectTab(Tab.YOU));
	}

	/** Whether a lookup can open in the panel; false ⇒ the caller opens the browser instead. */
	public boolean playerCardsAvailable() {
		return playerCardsOn();
	}

	/** Look a character up in the Lookup tab — the right-click "Anvil" entry lands here. */
	public void showLookup(String rawName) {
		SwingUtilities.invokeLater(() -> {
			var name = rawName == null ? "" : rawName.replace(' ', ' ').trim();
			if (tab != Tab.LOOKUP) selectTab(Tab.LOOKUP);
			if (name.isEmpty()) return;
			if (lookupClans) setLookupMode(false);
			lookupSearch.setText(name);
			remember(name);
			lookupView.show("player", Collections.singletonMap("rsn", name), "Looking up " + name + "…");
		});
	}

	/** {@code fetch} on the interactive executor, then {@code then} with its answer on the EDT. */
	/**
	 * The last answer for each request (clan, character, live board, view and args), drawn the moment it's asked
	 * for again while the fresh one is on its way — going back, or between tabs, is instant. Anything
	 * sent to the site (a claim, a pin) may change every view, so it empties. EDT only.
	 */
	final Map<String, JsonObject> uiCache = new LinkedHashMap<String, JsonObject>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, JsonObject> eldest) {
			return size() > 40;
		}
	};

	<T> void async(Supplier<T> fetch, Consumer<T> then) {
		interactive().execute(() -> {
			T result = fetch.get();
			SwingUtilities.invokeLater(() -> then.accept(result));
		});
	}

	/**
	 * Where the site-built views ({@link UiView}) fetch, post and send their clicks: the interactive
	 * executor for the calls, the browser for the site's own pages, Lookup for a name or a clan.
	 */
	UiView.Host uiHost() {
		return new UiView.Host() {
			@Override
			public void load(String view, Map<String, String> args, Consumer<JsonObject> done) {
				var clan = dataSource.activeClan();
				var sent = withPins(args);
				// The live board's id too: "event 0" (the board page's tiles, teams) means whichever board is live,
				// so an answer kept for one board must never be drawn once another one is.
				String key = clan + '|' + apiClient.getCurrentRsn() + '|' + dataSource.activeEventId() + '|' + view + '|' + new TreeMap<>(sent);
				var seen = uiCache.get(key);
				if (seen != null) done.accept(seen);
				async(() -> apiClient.fetchUi(view, sent, clan), v -> {
					if (v != null) uiCache.put(key, v);
					if (v != null || seen == null) {
						done.accept(v); // a failed fetch leaves the remembered answer up
					}
				});
			}

			@Override
			public void pin(int event, int tile, boolean on) {
				uiCache.clear();
				if (tile <= 0) {
					dataSource.unpinAll(event);
				} else dataSource.setTileTracked(event, tile, on);
			}

			@Override
			public void switchClan(String slug) {
				AnvilSidebarPanel.this.switchClan(slug);
			}

			@Override
			public void openTab(String name, Map<String, String> args) {
				// Only a tab this panel is showing.
				var feed = tabLabels.get(Tab.FEED);
				if (!"feed".equals(name) || feed == null || !feed.isShowing()) return;
				feedArgs = args == null ? Collections.emptyMap() : args;
				selectTab(Tab.FEED);
			}

			@Override
			public long count(int tile) {
				return dataSource.tileCount(tile);
			}

			@Override
			public String myName() {
				return dataSource.myRsn();
			}

			@Override
			public void gameFocus() {
				dataSource.gameFocus();
			}

			@Override
			public JComponent nativePart(String name) {
				var proof = "start-proof".equals(name) ? dataSource.startProof() : null;
				return proof == null ? null : buildStartProofCard(proof);
			}

			@Override
			public void post(String act, Map<String, String> args, Map<String, String> fields, Consumer<JsonObject> done) {
				uiCache.clear();
				async(() -> apiClient.postUi(act, args, fields), done);
			}

			@Override
			public void site(String path) {
				LinkBrowser.browse(apiClient.siteLink(path));
			}

			@Override
			public void image(String path, int w, int h, boolean motion, Consumer<Icon> done) {
				apiClient.thumb(path, w, h, motion && GradientLabel.animate, img -> SwingUtilities.invokeLater(() -> done.accept(img)));
			}

			@Override
			public void lookup(String rsn) {
				showLookup(rsn);
			}

			@Override
			public void clan(String slug) {
				if (tab != Tab.LOOKUP) selectTab(Tab.LOOKUP);
				setLookupMode(true);
				lookupView.show("clan", Collections.singletonMap("slug", slug), "Loading the clan…");
			}
		};
	}

	/**
	 * A view's args plus what only this client knows: whether the member picked this clan (rather than
	 * Auto), and whether it can pin a tracker on the board here (in game, tracker on) and which tiles
	 * it already has.
	 */
	Map<String, String> withPins(Map<String, String> args) {
		int given = parseIntOr(args.get("event"), 0);
		int event = given > 0 ? given : dataSource.activeEventId();
		Map<String, String> out = new HashMap<>(args);
		out.put("chosen", dataSource.chosenClan().isEmpty() ? "" : "1");
		if (dataSource.canTrackTiles(event, "1".equals(args.get("pre")))) {
			out.put("track", "1");
			var pins = new StringBuilder();
			for (int id : dataSource.pinnedTiles(event)) pins.append(pins.length() > 0 ? "," : "").append(id);
			out.put("pins", pins.toString());
		}
		return out;
	}

	static int parseIntOr(String s, int fallback) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	void remember(String name) {
		recentLookups.removeIf(n -> Rsn.same(n, name));
		recentLookups.addFirst(name);
		while (recentLookups.size() > 5) recentLookups.removeLast();
		lookupRecentRow.removeAll();
		if (recentLookups.size() > 1) {
			// Recent names as chips: a grey outline, one click looks them up again.
			var wrap = left(new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4)));
			wrap.setBackground(DARK_GRAY_COLOR);
			for (String n : recentLookups.subList(1, recentLookups.size())) {
				var chip = new Round(new BorderLayout(), 3, null, MEDIUM_GRAY_COLOR, null);
				chip.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
				chip.add(small(plainText(n), LIGHT_GRAY_COLOR));
				chip.setToolTipText(plainText("Look up " + n));
				var slot = new JPanel(new BorderLayout());
				slot.setOpaque(false);
				slot.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
				slot.add(onClick(chip, () -> showLookup(n)));
				wrap.add(slot);
			}
			wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, wrap.getPreferredSize().height * 2));
			lookupRecentRow.add(wrap);
			lookupRecentRow.add(gap(6));
		}
		lookupRecentRow.revalidate();
	}

	/**
	 * The Feed tab. Opening it asks again (the list is live from there on, push rows grow it), so a
	 * row missed while the socket was down is back the next time anyone looks.
	 */
	void renderFeed() {
		if (!dataSource.feedTabAvailable()) {
			feedView.say("Sign in to see the live feed.");
			return;
		}
		feedView.show("feed", feedArgs, "Loading the feed…");
		feedArgs = Collections.emptyMap(); // a board's list once; the tab itself opens on its default after
	}

	/** The list the next Feed-tab open shows (a board's feed or chat from its buttons); empty = the default. */
	Map<String, String> feedArgs = Collections.emptyMap();

	/** Push rows for the Feed tab's lists (AnvilPlugin.applyPush), from any thread. */
	public void feedPush(JsonObject m) {
		SwingUtilities.invokeLater(() -> feedView.feedPush(m));
	}

	/**
	 * Your own card and goals. Opening the tab fetches them again; a refresh only notices a change of
	 * character, so the card isn't rebuilt (and its open section reset) every few seconds.
	 */
	void renderYou(boolean refetch) {
		var me = dataSource.myRsn();
		if (me == null) {
			youRsn = null;
			youView.say("Log in to a character to see your card.");
			return;
		}
		var same = Rsn.same(me, youRsn);
		if (refetch || !same) {
			youRsn = me;
			youView.show("you", Collections.emptyMap(), same ? null : "Loading your card…");
		}
	}

	void setLookupMode(boolean clans) {
		lookupClans = clans;
		styleLookupModes();
		lookupView.say(clans ? "Search a clan by name." : LOOKUP_HINT);
	}

	void styleLookupModes() {
		if (Boolean.valueOf(lookupClans).equals(lookupModesShown)) return;
		lookupModesShown = lookupClans;
		lookupModes.removeAll();
		lookupModes.add(UiView.segments(Arrays.asList("Players", "Clans"), lookupClans ? "Clans" : "Players",
			m -> setLookupMode("Clans".equals(m))));
		lookupModes.revalidate();
		lookupModes.repaint();
	}

	/**
	 * ⚙ — everything that's syncing or a setting rather than something to look at, as cards: Syncing,
	 * Banner sounds, then the site's settings as rows with ↗ (they open the browser). No gold: nothing
	 * here is urgent.
	 */
	void renderSettings() {
		var actions = dataSource.actionsFor();
		// Asked on every refresh, but what it shows only moves with these: rebuild only when one does.
		var key = settingsKey(actions);
		if (key.equals(settingsShown)) return;
		settingsShown = key;
		settingsPanel.removeAll();
		var sync = buildPanelActions(actions);
		if (sync.getComponentCount() > 0) addCard(sync);
		addCard(buildBannerSounds());
		var web = small("On the Anvil website", Color.WHITE);
		web.setFont(web.getFont().deriveFont(Font.BOLD));
		settingsPanel.add(web);
		settingsPanel.add(gap(6));
		siteRow("Discord posts & screenshot privacy", "What you post to your clan's Discord", "/settings/announcements");
		siteRow("All your Anvil settings", "Profile, characters, name colours and more", "/settings");
		siteRow("Guides", "The side panel, events, proofs, Supporter perks", "/guide");
		siteRow("What's new", "Everything new on Anvil, for your roles", "/whats-new");
		settingsPanel.add(gap(6));
		var support = linkRow(DARKER_GRAY_COLOR, small((isSupporter() ? "★ Your Supporter perks" : "★ Become a Supporter"), new Color(0xFF_D3_5C)), null,
			"Name colours, profile banners, clan boosts — and it keeps Anvil running", this::openSupporter);
		settingsPanel.add(support);
		settingsPanel.revalidate();
		settingsPanel.repaint();
	}

	/** A section as a card: the darker rounded surface, 10px in. */
	void addCard(JPanel inner) {
		darken(inner);
		var card = left(new Round(new BorderLayout(), 6, DARKER_GRAY_COLOR, null, null));
		card.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		card.add(inner);
		settingsPanel.add(capH(card));
		settingsPanel.add(gap(10));
	}

	/** Panel-grey grounds inside a card take the card's colour. */
	static void darken(Component c) {
		if (c instanceof JPanel && !(c instanceof Round) && DARK_GRAY_COLOR.equals(c.getBackground())) c.setBackground(DARKER_GRAY_COLOR);
		if (c instanceof Container) for (Component k : ((Container) c).getComponents()) darken(k);
	}

	/** A page of the site as a raised row: its name, ↗, and what's there under it. */
	void siteRow(String name, String sub, String path) {
		settingsPanel.add(linkRow(UiView.RAISED, small(name, Color.WHITE), note(sub, 160), sub, () -> LinkBrowser.browse(apiClient.siteLink(path))));
		settingsPanel.add(gap(4));
	}

	static Round linkRow(Color fill, JLabel name, JLabel sub, String tip, Runnable open) {
		var row = left(new Round(new BorderLayout(4, 2), 4, fill, null, new Color(0x47_47_47)));
		row.setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));
		row.add(name, BorderLayout.CENTER);
		row.add(small("↗", MUTED), BorderLayout.EAST);
		if (sub != null) row.add(sub, BorderLayout.SOUTH);
		row.setToolTipText(tip);
		return capH(onClick(row, open));
	}

	/** What the settings tab last drew from (settingsKey), so an unchanged refresh skips the rebuild. */
	String settingsShown;

	String settingsKey(PanelActions actions) {
		var k = new StringBuilder()
			.append(actions.canSyncProfile).append('|').append(actions.canSyncRoster).append('|').append(actions.rosterNote)
			.append('|').append(isSupporter());
		for (String clip : dataSource.sounds(BannerSoundService.Kind.BANNER)) k.append('|').append(clip).append(dataSource.bannerSoundOn(clip) ? '+' : '-');
		return k.append("|m").append(dataSource.sounds(BannerSoundService.Kind.MISSION).size()).toString();
	}

	// ---- Lifecycle (Activatable) — poll only while the panel is on screen -------------------------

	@Override
	public void onActivate() {
		onScreen = true;
		dataSource.feedOnScreen(tab == Tab.FEED);
		refresh();
		autoRefresh.start();
		for (var v : views) v.resume();
		startProofTimer.start();
	}

	@Override
	public void onDeactivate() {
		onScreen = false;
		dataSource.feedOnScreen(false);
		autoRefresh.stop();
		for (var v : views) v.stop();
		startProofTimer.stop();
	}

	// ---- Device sign-in (DeviceSignIn) ------------------------------------------------------

	/**
	 * Show the Sign-in button whenever there is no Account Token — and say where it will connect.
	 *
	 * The destination is stated BEFORE the click: nothing is sent to it until then, and pressing the
	 * button is the member explicitly choosing to connect.
	 */
	void refreshSignInRow() {
		if (!signInInFlight) {
			var show = apiClient.needsSignIn();
			signInRow.setVisible(show);
			if (show) setSignInStatus("Connects to " + SetupNudge.SITE_HOST + ".");
			signInRow.revalidate();
			signInRow.repaint();
		}
	}

	/** Stop a sign-in that is still polling — the plugin is shutting down. */
	void cancelSignIn() {
		var flow = signIn;
		if (flow != null) flow.cancel();
	}

	/** Run the device sign-in (async, every step on the shared executor); on success store the token —
	 * onConfigChanged does the rest. Both callbacks arrive off the EDT, so they marshal via invokeLater. */
	void startSignIn() {
		if (signInInFlight) return;
		signInInFlight = true;
		signInButton.setEnabled(false);
		setSignInStatus("Starting…");

		signIn = new DeviceSignIn(apiClient, executor);
		signIn.run(
			line -> SwingUtilities.invokeLater(() -> setSignInStatus(line)),
			result -> SwingUtilities.invokeLater(() -> {
				signInInFlight = false;
				signInButton.setEnabled(true);
				if (result.outcome == DeviceSignIn.Outcome.SIGNED_IN) {
					// Storing the token fires the plugin's onConfigChanged → client reconfigure,
					// identity stamp + greet, and a sidebar refresh — the same path as a manual paste.
					configManager.setConfiguration("osrsbingo", "playerToken", result.token);

					// Read it straight back. A signed-in-but-empty config is the one failure mode a
					// member can't diagnose: the site says they approved, the panel keeps asking them
					// to sign in, and nothing explains why. It happens when the config write doesn't
					// take — a synced RuneLite profile clobbered by a second client, most often — and
					// it MUST NOT look like the sign-in itself failed. Say what happened and give them
					// the manual route, which always works.
					var stored = configManager.getConfiguration("osrsbingo", "playerToken");
					if (stored == null || stored.isEmpty()) {
						log.warn("Token did not persist");
						setSignInStatus("Signed in, but RuneLite didn't save the token — copy it "
							+ "from Profile > RuneLite plugin on the site and use Paste a token below.");
					} else setSignInStatus("");
				}
				refreshSignInRow();
				refresh();
			}));
	}

	/** A small orange link that runs {@code action}. */
	static JLabel styleLink(JLabel link, String tooltip, Runnable action) {
		link.setFont(SMALL);
		link.setForeground(BRAND_ORANGE);
		link.setToolTipText(tooltip);
		return onClick(link, action);
	}

	/** The manual route: paste the token from the site. Storing it runs the same path as Sign in. */
	void pasteToken() {
		if (signInInFlight) return;
		var token = JOptionPane.showInputDialog(this,
			"Paste your token from anvilosrs.com (Profile → RuneLite plugin):", "Anvil", JOptionPane.PLAIN_MESSAGE);
		if (token == null || token.trim().isEmpty()) return;
		configManager.setConfiguration("osrsbingo", "playerToken", token.trim());
		setSignInStatus("");
		refreshSignInRow();
		refresh();
	}

	/** The tracked character wears Supporter flair (any tier), so the link offers perks, not a pitch. */
	boolean isSupporter() {
		var me = dataSource.myRsn();
		var e = me == null ? null : dataSource.flairOf(me);
		return e != null && Boolean.TRUE.equals(e.s);
	}

	void openSupporter() {
		LinkBrowser.browse(apiClient.siteLink(isSupporter() ? "/settings/supporter" : "/supporter"));
	}

	void showAccountMenu(Component anchor) {
		var menu = new JPopupMenu();
		var row = shownAccountRow;
		if (row != null && row.state == AccountRow.State.TRACKING) {
			var profile = new JMenuItem("View my profile on Anvil");
			profile.setToolTipText("Open " + row.rsn + "'s Anvil profile in your browser");
			profile.addActionListener(e -> {
				if (tabsOn) {
					showYou();
				} else ProfileLookup.open(row.rsn);
			});
			menu.add(profile);
		}
		var support = new JMenuItem(isSupporter() ? "Your Supporter perks" : "Become a Supporter");
		support.setToolTipText("Name colours, profile banners, clan boosts — and it keeps Anvil running");
		support.addActionListener(e -> openSupporter());
		menu.add(support);
		if (row != null && row.state == AccountRow.State.TRACKING && !accountActionInFlight) {
			var notMine = new JMenuItem("Not my account…");
			notMine.setToolTipText("Someone else plays " + row.rsn + " here — stop tracking it on this computer");
			notMine.addActionListener(e -> onAccountButton());
			menu.add(notMine);
			menu.addSeparator();
		}
		var signOut = new JMenuItem("Sign out…");
		signOut.addActionListener(e -> signOut());
		menu.add(signOut);
		menu.show(anchor, 0, anchor.getHeight());
	}

	void signOut() {
		int choice = JOptionPane.showConfirmDialog(this,
			"Sign this computer out of Anvil? Tracking stops until you sign in again.",
			"Anvil", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) return;
		// Clearing the token fires onConfigChanged, which drops the session state (configureApiClient).
		configManager.setConfiguration("osrsbingo", "playerToken", "");
		refreshSignInRow();
		refresh();
	}

	/** The line under the sign-in button — most importantly the one carrying the approval code. */
	void setSignInStatus(String text) {
		var show = setStatus(signInStatus, text, signInRow);
		signInStatus.setToolTipText(show ? text : null);
		signInRow.repaint();
	}

	// ---- The logged-in character's account row -----------------------------------------------------

	void refreshAccountRow() {
		seasonalChanged();
		var row = dataSource.accountRow();
		var changed = shownAccountRow == null || row == null
			|| shownAccountRow.state != row.state || !shownAccountRow.rsn.equals(row.rsn);
		shownAccountRow = row;
		// Shown whenever this computer is signed in — it is also where Sign out lives.
		accountRowPanel.setVisible(!apiClient.needsSignIn());
		if (row == null) {
			setAccountLabel("Signed in", "Signed in to Anvil on this computer. Log in to a character to start tracking it.", false);
			accountLink.setVisible(false);
			setAccountStatus("");
			accountRowPanel.repaint();
			return;
		}
		if (changed && !accountActionInFlight) setAccountStatus("");
		var name = esc(row.rsn);
		switch (row.state) {
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
				setAccountLabel("Tracking&nbsp;", "Tracking " + row.rsn + " for your Anvil account.", false);
				showAccountName(row.rsn);
				// Everyday state: nothing to decide, so the action lives in the Account menu.
				accountLink.setVisible(false);
				break;
		}
		accountLink.setEnabled(!accountActionInFlight);
		capH(accountRowPanel).revalidate();
		accountRowPanel.repaint();
	}

	void onAccountButton() {
		var row = shownAccountRow;
		if (row == null || accountActionInFlight) return;
		AccountRow.Action action;
		String question;
		switch (row.state) {
			case PAUSED:
				action = AccountRow.Action.TRACK_AGAIN;
				question = null; // undoing is harmless — no confirm
				break;
			case OTHER:
				action = AccountRow.Action.DISPUTE;
				question = "Ask Anvil staff to move " + row.rsn + " to your Anvil account?\n\n"
					+ "It's linked to someone else right now. Staff will check and move it if it's yours.";
				break;
			default:
				action = AccountRow.Action.NOT_MINE;
				question = "Stop tracking " + row.rsn + " on this computer?\n\n"
					+ "Use this if someone else plays it here — a sibling, a friend. It's unlinked from your "
					+ "Anvil account and nothing is sent while it's logged in. Your other accounts aren't affected, "
					+ "and you can undo it from this panel.";
				break;
		}
		if (question != null && JOptionPane.showConfirmDialog(this, question, "Anvil",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
			return;
		}
		accountActionInFlight = true;
		accountLink.setEnabled(false);
		setAccountStatus("Working…");
		dataSource.accountAction(action, outcome -> SwingUtilities.invokeLater(() -> {
			accountActionInFlight = false;
			refreshAccountRow();
			setAccountStatus(outcome);
			refresh();
		}));
	}

	/** The Supporter list moved: the tracked name's colours may have. Any thread. */
	void flairChanged() {
		SwingUtilities.invokeLater(this::refreshAccountRow);
	}

	/**
	 * The tracked name after "Tracking": bold, in its Supporter look when it has one (a gradient, moving
	 * for a Patron, with the star), else plain white.
	 */
	void showAccountName(String rsn) {
		var e = dataSource.flairOf(rsn);
		// The panel is a dark surface: the owner's own dark look as-is, else the adapted main one.
		var own = e == null || e.d == null || ChatPalette.nameColor(e.d.c) == null ? null : e.d;
		var source = own != null ? own.g : e == null ? null : e.g;
		List<Color> stops = new ArrayList<>();
		for (String g : source == null ? Collections.<String>emptyList() : source) {
			if (stops.size() < 4) stops.add(own != null ? Ui.teamColor("#" + ChatPalette.nameColor(g), Color.WHITE) : ChatPalette.readableColor(g, false, Color.WHITE));
		}
		var star = e != null && Boolean.TRUE.equals(e.s);
		var l = stops.size() >= 2 ? new GradientLabel(stops.toArray(new Color[0]), star) : new JLabel();
		l.setText(rsn);
		l.setFont(accountLabel.getFont().deriveFont(Font.BOLD));
		l.setForeground(own != null ? Ui.teamColor("#" + ChatPalette.nameColor(own.c), Color.WHITE)
			: ChatPalette.readableColor(e == null ? null : e.c, false, Color.WHITE));
		l.setToolTipText(accountLabel.getToolTipText());
		if (star) {
			l.setIcon(new GlyphIcon(GlyphIcon.Shape.STAR, 10));
			l.setIconTextGap(2);
			if (l instanceof GradientLabel) ((GradientLabel) l).setTintIcon(true);
		}
		accountName = l;
		accountLine.add(l);
		accountLine.revalidate();
	}

	/** Room the label leaves for the link (and ⋯) to its right. */
	static final int ACCOUNT_LINK_ROOM_PX = 72;

	/**
	 * The everyday "Tracking X" stays one quiet line (a long name is clipped; the tooltip has it all).
	 * The rare states that ask for a decision wrap instead, so they're read in full.
	 */
	void setAccountLabel(String html, String tooltip, boolean wrap) {
		if (accountName != null) {
			accountLine.remove(accountName);
			accountName = null;
		}
		accountLabel.setText(wrap
			? wrapHtml(html, STATUS_WRAP_PX - ACCOUNT_LINK_ROOM_PX)
			: "<html><nobr>" + html + "</nobr></html>");
		accountLabel.setToolTipText(tooltip);
	}

	void setAccountStatus(String text) {
		setStatus(accountStatus, text, accountRowPanel);
	}

	/**
	 * A status line under a control, wrapped as HTML so a long line breaks instead of being clipped at
	 * the edge of a narrow sidebar, and hidden while empty. The row holding it is re-capped on every
	 * change: capped once at construction, while the line was hidden, it could never grow to fit it.
	 */
	static boolean setStatus(JLabel status, String text, JPanel row) {
		var plain = text == null ? "" : text;
		var show = !plain.isEmpty();
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
	public void clearForCredentialChange() {
		clanShown = false;
		rebuildingPicker = true;
		clanPicker.removeAllItems();
		rebuildingPicker = false;
		setPickerWanted(false); // no config for the new credentials yet, so no clans to choose between
		setSignInStatus("");
		refreshSignInRow();
		renderLoading();
	}

	/** Ask again: the clan picker and sign-in state from the config the plugin holds, the tab from the site. */
	public void refresh() {
		refresh(false);
	}

	/** EDT: show or update the seasonal-sharing switch (seasonal world, setting changed, config polled). */
	public void seasonalChanged() {
		var show = dataSource.seasonalSharingOffered();
		if (show) {
			var on = dataSource.shareSeasonal();
			seasonLabel.setText(on ? "Sharing season progress" : "Season progress not shared");
			seasonLink.setText(on ? "Turn off" : "Turn on");
		}
		if (seasonRow.isVisible() != show) seasonRow.setVisible(show);
		seasonRow.revalidate();
	}

	/** Rebuild the visible site view so animated image nodes swap to their still/animated rendition. */
	public void motionChanged() {
		if (!onScreen) return;
		var view = tab == Tab.YOU ? youView : tab == Tab.LOOKUP ? lookupView : tab == Tab.FEED ? feedView : clanView;
		view.refresh();
	}

	/** A background refresh (the timer): the same, and the view redraws only if the site's answer changed. */
	public void poll() {
		refresh(false);
	}

	/**
	 * The plugin's config re-read happens off the EDT (so a fetch never holds the panel), then the
	 * picker and strip redraw from it and the Clan tab asks the site. A burst of repaints — config,
	 * hello, a login — asks the site once; a click on Refresh always asks.
	 */
	void refresh(boolean manual) {
		if (!onScreen) return;
		refreshAccountRow();
		if (manual) {
			apiClient.clearThumbs();
			for (var v : views) v.invalidateDrawn();
			// A refresh with nothing new changes nothing on screen: the button says it's working.
			refreshButton.setEnabled(false);
			refreshButton.setText("Refreshing…");
		}
		interactive().execute(() -> {
			dataSource.refreshConfig(manual);
			SwingUtilities.invokeLater(() -> {
				if (manual) {
					refreshButton.setText("Refresh");
					refreshButton.setEnabled(true);
				}
				rebuildClanPicker();
				updateTabStrip();
				refreshSignInRow();
				refreshAccountRow();
				if (tab == Tab.YOU) {
					renderYou(false); // a refresh can change what You shows: the character, the card
					// Same character: ask the site again in place, or a new banner/avatar only showed
					// after leaving the tab and coming back.
					if (manual) youView.refresh();
				} else if (tab == Tab.LOOKUP && manual) {
					lookupView.refresh();
				} else if (tab == Tab.SETTINGS) {
					renderSettings(); // admin rights and the sound list can change under it
				} else if (tab == Tab.FEED && manual && !feedView.refresh()) {
					// The Feed asks the site again in place (its scope, pane and filter kept): blanking it to
					// "Loading the feed…" first collapsed the tab, then it sprang back.
					renderFeed();
				}
				showClan(manual);
			});
		});
	}

	/** The Clan tab: the sign-in card, a line, or the site's view of the clan. EDT. */
	void showClan(boolean manual) {
		if (apiClient.needsSignIn() || !dataSource.supportsPluginUi()) {
			clanShown = false;
			if (dataSource.awaitingFirstConfig()) {
				renderLoading();
			} else renderEmpty();
			return;
		}
		var now = System.currentTimeMillis();
		if (!clanShown) {
			clanShown = true;
			clanAskedAt = now;
			setContent(clanView);
			clanView.show("home", Collections.emptyMap(), "Loading progress…");
		} else if (manual || now - clanAskedAt >= MIN_ASK_GAP_MS && !typingInSearch()) {
			clanAskedAt = now;
			clanView.refresh();
		}
	}

	/** The least time between the Clan tab's own asks, Refresh aside. */
	static final long MIN_ASK_GAP_MS = 5_000L;

	/** Somebody is in the tiles or Teams search: don't ask again under their caret. */
	boolean typingInSearch() {
		var w = SwingUtilities.getWindowAncestor(clanView);
		var f = w == null ? null : w.getFocusOwner();
		return f instanceof JTextField && SwingUtilities.isDescendingFrom(f, clanView);
	}

	/** The site confirmed a tile done: ask now, so a tile's detail doesn't say "Open" after the chat said done. */
	public void tileCompleted() {
		// The whole page: a race's road and "Up next" are on it, not in a slot a plain refresh would pick.
		uiCache.clear();
		clanAskedAt = System.currentTimeMillis();
		if (onScreen && clanShown) clanView.refresh(true);
		refresh();
	}

	/**
	 * EDT: the open Clan view asks the site again right away, past the 5s gap and the search-box guard:
	 * a reload keeps what's typed, and after a completion or our own proof a detail left saying "Open"
	 * reads as broken. Remembered answers are dropped first so none is redrawn on the way.
	 */
	void askAgainNow() {
		uiCache.clear();
		clanAskedAt = System.currentTimeMillis();
		if (onScreen && clanShown) clanView.refresh();
	}

	@Setter BooleanSupplier pushLive = () -> false;
	int quietTicks;

	/** The live push says the board moved: ask now. */
	void boardChanged() {
		// Every remembered view may hold the old numbers: never redraw one of those first.
		SwingUtilities.invokeLater(() -> {
			askAgainNow();
			poll();
		});
	}

	final AtomicBoolean liveQueued = new AtomicBoolean();
	/** After counts alone moved, the rest of the view (who's on what, the feed) is asked for this much later. */
	static final int COUNTS_CATCH_UP_MS = 15_000;
	final Timer countsCatchUp = new Timer(COUNTS_CATCH_UP_MS, e -> boardChanged());

	/** Counts moved here (own progress, the push): the Clan tab's live ones redraw, at most once per frame. Any thread. */
	void liveCountsMoved() {
		if (liveQueued.compareAndSet(false, true)) {
			SwingUtilities.invokeLater(() -> {
				liveQueued.set(false);
				clanView.tickLive();
			});
		}
	}

	/** The push carried counts only: they're on screen already; the rest catches up once, a little later. */
	void countsChanged() {
		SwingUtilities.invokeLater(() -> {
			uiCache.clear(); // see boardChanged
			if (!countsCatchUp.isRunning()) {
				countsCatchUp.setRepeats(false);
				countsCatchUp.start();
			}
		});
	}

	void tickStartProof() {
		if (startProofTick != null) startProofTick.run();
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
	void rebuildClanPicker() {
		var choices = ClanChoice.of(dataSource.clans());
		rebuildingPicker = true;
		try {
			DefaultComboBoxModel<ClanChoice> model = new DefaultComboBoxModel<>();
			var chosen = dataSource.chosenClan();
			ClanChoice toSelect = null;
			for (ClanChoice c : choices) {
				model.addElement(c);
				if (c.slug.equals(chosen)) toSelect = c;
			}
			clanPicker.setModel(model);
			// Nothing chosen — the site is deciding, which is the Auto row.
			clanPicker.setSelectedItem(toSelect != null ? toSelect : choices.isEmpty() ? null : choices.get(0));
			setPickerWanted(choices.size() > 2);
		} finally {
			rebuildingPicker = false;
		}
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
	static final class ClanChoice {
		/** Clan slug, or "" for Auto. */
		final String slug;
		final String label;
		/** Second line — the board running there, or what this seat is. Never null. */
		final String detail;

		ClanChoice(String slug, String label, String detail) {
			this.slug = slug == null ? "" : slug;
			this.label = label == null ? "" : label;
			this.detail = detail == null ? "" : detail;
		}

		/** Auto first, then the clans in the order the site sent them (newest seat first). */
		static List<ClanChoice> of(List<PluginConfigResponse.ClanRef> clans) {
			List<ClanChoice> out = new ArrayList<>();
			if (clans == null || clans.isEmpty()) return out;
			var anySpace = false;
			var anyClan = false;
			for (PluginConfigResponse.ClanRef c : clans) {
				if (c != null) {
					anySpace |= c.space;
					anyClan |= !c.space;
				}
			}
			// The Auto row names what it covers: clans, spaces, or both.
			var all = anySpace && anyClan ? "All clans & spaces" : anySpace ? "All spaces" : "All clans";
			out.add(new ClanChoice("", all, "Everything you're playing"));
			for (PluginConfigResponse.ClanRef c : clans) {
				if (c == null || c.slug == null || c.slug.isEmpty()) continue;
				out.add(new ClanChoice(c.slug, c.name == null || c.name.isEmpty() ? c.slug : c.name, detailOf(c)));
			}
			return out;
		}

		/** What is happening in this clan, in one short line — the reason to pick it or not. */
		static String detailOf(PluginConfigResponse.ClanRef c) {
			// A space says so up front: in a list of clans and spaces, the name alone doesn't tell them apart.
			var tag = c.space ? "Space \u00b7 " : "guest".equals(c.kind) ? "Guest \u00b7 " : "";
			if (c.live == null || c.live.eventName == null || c.live.eventName.isEmpty()) return tag + (tag.isEmpty() ? "Nothing live" : "nothing live");
			// A competition has a leaderboard rather than a board to fill, so "0/0" would be a lie
			// dressed as progress. The name is the whole answer there.
			var head = c.live.isWeekly() || c.live.tilesTotal <= 0
				? c.live.eventName
				: c.live.eventName + "  " + c.live.tilesComplete + "/" + c.live.tilesTotal;
			// One line names ONE of them. Say how many were not named rather than letting an arbitrary
			// pick stand in for the whole clan — the number is why you would open the clan at all.
			int more = c.liveCount - 1;
			return (c.space ? "Space \u00b7 " : "") + (more > 0 ? head + "  +" + more + " more" : head);
		}

		@Override
		public String toString() {
			return label;
		}
	}


	// ---- Render states ----------------------------------------------------------------------------

	void renderLoading() {
		// The picker STAYS. Switching clan renders this state on purpose, and yanking the control the
		// member is holding — then putting it back a second later — reads as the click having failed.
		JLabel loading = new ClipLabel("Loading progress…", SwingConstants.CENTER);
		loading.setForeground(MUTED);
		loading.setBorder(BorderFactory.createEmptyBorder(24, 0, 0, 0));
		setContent(loading);
	}

	void renderEmpty() {
		// TWO DIFFERENT NOTHINGS, and they were being told the same story. "Link a clan on your Anvil
		// site" is advice for somebody who HAS a site and an account; a fresh install has neither, and
		// reading it as their first contact with the plugin leaves them looking for a clan page nobody
		// has given them the address of. Signed out is the state to answer first, because it is the
		// state every new install starts in.
		if (apiClient.needsSignIn()) {
			setContent(firstRunCard());
			return;
		}
		// Signed in, but no config answered: the site can't be reached yet. Say that rather than
		// "no clans", and keep the roster button and the sound lists, since the tab strip is hidden here.
		var empty = new PluginErrorPanel();
		empty.setContent("Can't show your clan here", "Anvil can't be reached right now. It'll keep trying.");
		renderSettings();
		var stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setOpaque(false);
		stack.add(empty);
		stack.add(settingsPanel);
		setContent(stack);
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
	JPanel firstRunCard() {
		var card = section("New here?");
		card.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		card.add(note("Anvil tracks your OSRS progress — collection log, personal bests, records — and"
			+ " your clan's bingo boards. Press Sign in with Discord above to make your free account"
			+ " and connect this client in one step."));
		card.add(gap(6));
		card.add(note("You don't need a clan: your profile is your own. If you run one, starting it"
			+ " there is free too."));
		var site = styleLink(new ClipLabel(BingoApiClient.site().replaceFirst("^https?://", "")), "Open Anvil in your browser",
			() -> LinkBrowser.browse(BingoApiClient.site()));
		site.setIcon(new GlyphIcon(GlyphIcon.Shape.EXTERNAL, 11));
		site.setHorizontalTextPosition(SwingConstants.LEADING);
		site.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		card.add(site);

		return card;
	}

	/** A wrapped grey paragraph at the panel's width — long copy clips rather than wraps without it. */
	static JLabel note(String text) {
		return note(text, MUTED);
	}

	static JLabel note(String text, Color color) {
		JLabel label = new ClipLabel(wrapHtml(esc(text == null ? "" : text), STATUS_WRAP_PX));
		label.setFont(SMALL);
		label.setForeground(color);
		return label;
	}

	/**

	// ---- Events list (a clan running more than one thing) ------------------------------------------

	/**
	 * Roster sync and profile sync, when this account can do them here.
	 *
	 * <p>A roster is scraped from the clan channel you're standing in, so the button is absent for
	 * any clan but your own — an admin elsewhere still can't see a roster they aren't in — and it
	 * says why rather than vanishing without explanation. Both buttons carry the same limits as
	 * their in-game counterparts, which live in the plugin, not here.
	 */
	JPanel buildPanelActions(PanelActions actions) {
		var panel = column();
		if (!actions.canSyncRoster && !actions.canSyncProfile && actions.rosterNote == null) {
			return panel; // nothing this account can do here; no empty card either
		}
		panel.add(cardTitle("Syncing"));
		if (actions.canSyncProfile) {
			panel.add(fullWidthButton("Sync profile", "Send your collection log and best times to Anvil", dataSource::syncProfile));
			panel.add(gap(4));
			panel.add(note("Send your collection log and best times to Anvil", 165));
		}
		if (actions.canSyncRoster) {
			if (actions.canSyncProfile) panel.add(gap(8));
			panel.add(fullWidthButton("Sync clan roster", "Push the in-game clan member list to the site", dataSource::syncRoster));
			panel.add(gap(4));
			panel.add(note("Clan admins: push the in-game member list to the site", 165));
		} else if (actions.rosterNote != null) {
			if (actions.canSyncProfile) panel.add(gap(8));
			panel.add(note(actions.rosterNote, 165));
		}
		return panel;
	}

	/** Muted small text that wraps at {@code widthPt} rather than clipping. */
	static JLabel note(String text, int widthPt) {
		var l = new JLabel(wrapHtml(esc(text), widthPt));
		l.setFont(SMALL);
		l.setForeground(MUTED);
		return left(l);
	}

	/** A card's white bold title and the gap under it. */
	static JPanel cardTitle(String text) {
		var l = small(text, Color.WHITE);
		l.setFont(l.getFont().deriveFont(Font.BOLD));
		var p = column();
		p.add(left(l));
		p.add(gap(6));
		return capH(p);
	}

	/**
	 * Banner clips, which are files in a folder on THIS machine.
	 *
	 * <p>Nothing here is per account or per clan — swapping character or clan doesn't change which
	 * .wav files are on your disk — so the list is the same wherever you are in the panel. A lit
	 * swatch is in the play cycle, a hollow one is muted, and clicking the row flips it.
	 */
	JPanel buildBannerSounds() {
		var clips = dataSource.sounds(BannerSoundService.Kind.BANNER);
		var panel = column();
		panel.add(cardTitle("Banner sounds"));

		if (clips.isEmpty()) {
			panel.add(note("None added — use Add clip, or the banner folder.", 165));
			panel.add(gap(4));
		} else {
			for (String clip : clips.subList(0, Math.min(clips.size(), BANNER_CLIPS_SHOWN))) {
				panel.add(buildClipRow(clip, dataSource.bannerSoundOn(clip)));
				panel.add(gap(2));
			}
			if (clips.size() > BANNER_CLIPS_SHOWN) {
				var more = small("+" + (clips.size() - BANNER_CLIPS_SHOWN) + " more in the folder", MUTED);
				more.setBorder(BorderFactory.createEmptyBorder(2, CLIP_TEXT_INSET, 0, 0));
				panel.add(more);
			}
			panel.add(gap(4));
		}

		panel.add(buttonRow(
			flatButton("Add clip", "Pick .wav files to copy into the banner sounds folder", Color.WHITE,
				() -> dataSource.importSounds(BannerSoundService.Kind.BANNER)),
			flatButton("Copy folder", "Copy the sounds folder's path — paste it into your file manager to rename or delete clips",
				Color.WHITE, dataSource::copyBannerSoundsPath)));

		// MISSIONS, stated rather than configured. A mission dropping should not sound like a tile
		// being finished, and what it sounds like is decided by whether this folder has anything in
		// it — so the panel says which of the two is happening, and the button is how you change it.
		panel.add(gap(8));
		int missions = dataSource.sounds(BannerSoundService.Kind.MISSION).size();
		panel.add(small(missions == 0
			? "Missions: a game sound (no clip added)"
			: "Missions: " + missions + (missions == 1 ? " clip" : " clips"), MUTED));
		panel.add(gap(4));
		panel.add(fullWidth(flatButton(missions == 0 ? "Add mission clip" : "Add mission clips",
			"Pick .wav files to play when a mission drops, instead of the game sound used by default",
			Color.WHITE, () -> dataSource.importSounds(BannerSoundService.Kind.MISSION))));
		return panel;
	}

	/** How far a clip's name sits from the panel edge — the swatch's width plus its gap. */
	static final int CLIP_TEXT_INSET = 14;

	/**
	 * One clip: a lit swatch, its name, and a click that mutes or unmutes it.
	 *
	 * <p>Colour alone carried this before — a green name meant playing, a grey one meant muted — which
	 * is invisible if you don't already know the rule, and unreadable if you can't separate the two
	 * greens. The swatch says on/off the way the in-game list does, the row lights under the pointer
	 * so it's clearly a control, and the tooltip names the action rather than the state.
	 */
	JPanel buildClipRow(String clip, boolean on) {
		var display = BannerSoundService.displayName(clip);

		var row = left(new JPanel(new BorderLayout(6, 0)));
		row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 4));
		row.setToolTipText(plainText(display) + (on ? " — click to mute" : " — muted; click to unmute"));

		var swatch = new JPanel();
		swatch.setPreferredSize(new Dimension(8, 8));
		swatch.setMinimumSize(new Dimension(8, 8));
		swatch.setMaximumSize(new Dimension(8, 8));
		swatch.setBackground(on ? PROGRESS_COMPLETE_COLOR : DARK_GRAY_COLOR);
		swatch.setBorder(BorderFactory.createLineBorder(on
			? PROGRESS_COMPLETE_COLOR : WIDGET_BORDER));
		// Centre the 8px square against the text line rather than stretching it down the row.
		var swatchBox = new JPanel(new GridBagLayout());
		swatchBox.setOpaque(false);
		swatchBox.setPreferredSize(new Dimension(CLIP_TEXT_INSET - 6, 12));
		swatchBox.add(swatch);

		row.add(swatchBox, BorderLayout.WEST);
		row.add(small(display.length() <= 26 ? display : display.substring(0, 25).trim() + "…", on ? TEXT_COLOR : MUTED), BorderLayout.CENTER);
		hoverable(capH(row), DARKER_GRAY_COLOR, () -> {
			dataSource.toggleBannerSound(clip);
			renderSettings();
		});
		return row;
	}

	// ---- Component builders -----------------------------------------------------------------------

	/** A small gold section label, matching the panel's header style. */
	static JLabel sectionHeader(String text) {
		return small(text, BRAND_ORANGE);
	}

	/** A section heading and the gap under it, added to {@code into}. */
	static void heading(JPanel into, String text) {
		into.add(sectionHeader(text));
		into.add(gap(6));
	}

	/** A {@link Ui#column} that opens with a section heading. */
	static JPanel section(String text) {
		var p = column();
		heading(p, text);
		return p;
	}

	/**
	 * The starting-shot prompt: where to stand, this account's keyword, and the one button that
	 * captures the frame, burns the proof banner onto it and files it. Rendered only while a shot is
	 * actually owed — {@link AnvilSidebarDataSource#startProof()} returns null the moment one is filed.
	 */
	JPanel buildStartProofCard(PluginConfigResponse.StartProof proof) {
		var card = block();
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(BRAND_ORANGE),
			BorderFactory.createEmptyBorder(6, 8, 8, 8)));
		card.add(small("Starting shot needed", BRAND_ORANGE));

		if (proof.location != null && !proof.location.isEmpty()) {
			var where = small("Go to " + proof.location, Color.WHITE);
			where.setToolTipText(plainText(proof.location));
			card.add(gap(4));
			card.add(where);
		}

		if (proof.keyword != null && !proof.keyword.isEmpty()) {
			card.add(gap(2));
			card.add(small("Keyword: " + proof.keyword, MUTED));
		}

		// The requirements the button checks — session age, position — shown live, so a player can
		// see each one go green instead of finding out from a refusal in chat. Ticks every second
		// while the card is on screen, on the panel's own second tick; dropped once the card is replaced.
		var checks = column();
		checks.setOpaque(false);
		renderStartProofChecks(checks, proof);
		if (checks.getComponentCount() > 0) {
			card.add(gap(4));
			card.add(checks);
			startProofTick = () -> {
				if (checks.isDisplayable()) {
					renderStartProofChecks(checks, proof);
				} else startProofTick = null;
			};
		}

		if ("rejected".equals(proof.status)) {
			card.add(gap(2));
			card.add(warningLabel("Your last shot was rejected — take another."));
		}

		// WHY it matters, which the card never said. A player who reads "starting shot needed" and
		// carries on has no way to know their drops are landing in a review queue meanwhile.
		card.add(gap(4));
		card.add(warningLabel("Drops you send now are held for review until this is filed."));

		var left = StartProofRules.describeWindow(proof, System.currentTimeMillis());
		if (left != null) {
			var expires = small("Asked for another " + left + ", then it lapses", Color.WHITE);
			expires.setToolTipText("Six hours in, the game has logged everyone out anyway, so the shot stops being asked for.");
			card.add(gap(2));
			card.add(expires);
		}

		if (proof.spot != null && dataSource.autoStartShot()) {
			card.add(gap(2));
			card.add(small("Taken and sent automatically once both are green.", LIGHT_GRAY_COLOR));

		}

		var take = new JButton("Take starting shot");
		styleFlatButton(take, BRAND_ORANGE);
		take.addActionListener(e -> {
			take.setEnabled(false);
			take.setText("Sending...");
			// The capture itself hops to the next rendered frame and then to a worker; the panel just
			// asks for it. The button goes away entirely on the next poll, once the site agrees.
			dataSource.captureStartProof();
			// ...unless the capture refused it — standing in the wrong place, or a session too old to
			// have flushed the hiscores. That answer arrives in chat, so the button has to come back
			// rather than sit on "Sending..." until the next poll redraws the card.
			Timer restore = new Timer(4000, ev -> {
				take.setText("Take starting shot");
				take.setEnabled(true);
			});
			restore.setRepeats(false);
			restore.start();
		});
		card.add(gap(6));
		card.add(take);

		if (proof.spot != null) {
			card.add(gap(4));
			card.add(fullWidthButton("Route me there", "Draws the way there with the Shortest Path plugin, if you have it",
				dataSource::routeToStartSpot));
		}

		return card;
	}

	void renderStartProofChecks(JPanel into, PluginConfigResponse.StartProof proof) {
		var list = dataSource.startProofChecks(proof);
		// Ticked every second, but the checks only move when you log in or walk to the spot: rebuild
		// only when what they say changes.
		var said = new StringBuilder();
		for (StartProofRules.Check c : list) said.append(c.state).append('|').append(c.text).append('\n');
		var key = said.toString();
		if (key.equals(into.getClientProperty(START_PROOF_SHOWN))) return;
		into.putClientProperty(START_PROOF_SHOWN, key);
		into.removeAll();
		for (StartProofRules.Check c : list) {
			var color = c.state == StartProofRules.Check.State.OK ? PROGRESS_COMPLETE_COLOR
				: c.state == StartProofRules.Check.State.BAD ? PROGRESS_ERROR_COLOR
				: LIGHT_GRAY_COLOR;
			var shape = c.state == StartProofRules.Check.State.OK ? GlyphIcon.Shape.CHECK : GlyphIcon.Shape.CLOSE;
			var row = small(c.text, color);
			row.setIcon(new GlyphIcon(shape, 9, color));
			row.setIconTextGap(4);
			row.setToolTipText("Hiscores only save when you log out, so a fresh session is what sets your starting totals.");
			into.add(row);
		}
		into.revalidate();
		into.repaint();
	}

	// ---- Tiles and Teams pages --------------------------------------------------------------------

	/** Install one row listener on the panel and every label nested inside it. */
	static void addMouseListenerToTree(Component component, MouseAdapter listener) {
		component.addMouseListener(listener);
		if (component instanceof Container) {
			for (Component child : ((Container) component).getComponents()) addMouseListenerToTree(child, listener);
		}
	}

	JLabel warningLabel(String message) {
		var warn = small(message, PROGRESS_ERROR_COLOR);
		return warn;
	}

	/** Swap the single content component and repaint. */
	void setContent(Component component) {
		SwingUtilities.invokeLater(() -> {
			if (content.getComponentCount() == 1 && content.getComponent(0) == component) return;
			content.removeAll();
			if (component instanceof JComponent) ((JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
			content.add(component);
			content.revalidate();
			content.repaint();
		});
	}

	// ---- Anvil-themed widget chrome ---------------------------------------------------------------

	/** A themed action button in the panel's orange, wired to one thing it does. */
	static JButton flatButton(String label, String tooltip, Runnable onClick) {
		return flatButton(label, tooltip, BRAND_ORANGE, onClick);
	}

	static JButton flatButton(String label, String tooltip, Color foreground, Runnable onClick) {
		var b = new JButton(label);
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
	static JPanel buttonRow(JButton left, JButton right) {
		return buttons(new GridLayout(1, 2, 4, 0), left, right);
	}

	/** {@link #flatButton} holding a whole row, so a lone action doesn't sit in half a line. */
	static JPanel fullWidthButton(String label, String tooltip, Runnable onClick) {
		return fullWidth(flatButton(label, tooltip, onClick));
	}

	static JPanel fullWidth(JButton button) {
		return buttons(new GridLayout(1, 1), button);
	}

	static JPanel buttons(GridLayout layout, JButton... buttons) {
		var row = left(new JPanel(layout));
		row.setBackground(DARK_GRAY_COLOR);
		for (JButton b : buttons) row.add(b);
		return capH(row);
	}

	static void styleFlatButton(JButton b, Color foreground) {
		b.setFocusPainted(false);
		b.setForeground(foreground);
		b.setBackground(BUTTON_BG);
		b.setFont(SMALL);
		b.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
		// A raised row, like the site's: a rounded fill and no outline, painted once for every button.
		b.setContentAreaFilled(false);
		b.setOpaque(false);
		b.setUI(new BasicButtonUI() {
			@Override
			public void paint(Graphics g, JComponent c) {
				var g2 = smooth(g);
				g2.setColor(c.getBackground());
				g2.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), 8, 8);
				g2.dispose();
				super.paint(g, c);
			}
		});
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseEntered(MouseEvent e) {
				if (b.isEnabled()) b.setBackground(BUTTON_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				b.setBackground(BUTTON_BG);
			}
		});
	}

	/** Flat combo for the clan selector and tile-status filter: dark field, gold arrow — default Swing sticks out. */
	static void styleCombo(JComboBox<?> combo) {
		combo.setBackground(WIDGET_BG);
		combo.setForeground(Color.WHITE);
		combo.setFont(SMALL);
		combo.setBorder(BorderFactory.createLineBorder(WIDGET_BORDER));
		combo.setUI(new BasicComboBoxUI() {
			@Override
			protected JButton createArrowButton() {
				var arrow = new BasicArrowButton(
					SwingConstants.SOUTH, WIDGET_BG, WIDGET_BG, BRAND_ORANGE, WIDGET_BG);
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
	static final class ClanChoiceRenderer implements ListCellRenderer<ClanChoice> {
		final JPanel row = new JPanel(new BorderLayout(0, 1));
		final JLabel name = new ClipLabel();
		final JLabel detail = new ClipLabel();

		ClanChoiceRenderer() {
			name.setFont(SMALL);
			detail.setFont(SMALL);
			row.setOpaque(true);
			row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			row.add(name, BorderLayout.NORTH);
			row.add(detail, BorderLayout.SOUTH);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends ClanChoice> list, ClanChoice value,
			int index, boolean isSelected, boolean cellHasFocus) {
			// index >= 0 → a popup row; -1 → the closed field. Dark rows, gold-tinted hover.
			var hover = isSelected && index >= 0;
			row.setBackground(hover ? WIDGET_BG_HOVER : WIDGET_BG);
			name.setForeground(hover ? BRAND_ORANGE : Color.WHITE);
			detail.setForeground(MUTED);

			name.setText(value == null ? "" : plainText(value.label));
			// The closed field shows the name alone — a second line there would resize the header on
			// every refresh, and the detail is what you read while choosing, not after.
			var showDetail = index >= 0 && value != null && !value.detail.isEmpty();
			detail.setText(showDetail ? plainText(value.detail) : "");
			detail.setVisible(showDetail);
			return row;
		}
	}
}
