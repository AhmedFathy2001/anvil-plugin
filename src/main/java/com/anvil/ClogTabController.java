package com.anvil;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ScriptEvent;
import net.runelite.api.ScriptID;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;

/**
 * Injects a native-feeling "Bingo" category into the in-game collection log (group 621): a real
 * 3-slice sprite tab that shows a proper selected state, with the boss list hidden and replaced
 * by a Leagues-style filter column while it's active, and the player's bingo tasks rendered as an
 * accordion task list (icon + title + reward points + team progress + expandable description).
 *
 * Geometry/sprites are measured from the live interface (see plugin_clog_plan.md). Everything is
 * null-guarded and gated behind {@code config.bingoClogTab()} so a game update can at worst make
 * the tab not draw.
 */
@Singleton
@Slf4j
public class ClogTabController
{
	private static final int ALIGN_CENTER = 1;
	private static final int ALIGN_RIGHT = 2;
	private static final int FONT_PLAIN = 495;
	private static final int COL_ORANGE = 0xff9040;
	private static final int BODY_TOP = 8; // top margin so content isn't flush against the divider

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final AnvilPlugin plugin;
	private final AnvilConfig config;
	private final ChatboxPanelManager chatboxPanelManager;

	private boolean clogOpen;
	private boolean bingoTabActive;

	private ClogTaskModel.StatusFilter statusFilter = ClogTaskModel.StatusFilter.ALL;
	private ClogTaskModel.TypeFilter typeFilter = ClogTaskModel.TypeFilter.ALL;
	private String tierFilter = ""; // "" = all tiers; otherwise a served band key
	private String categoryFilter = ""; // "" = all categories
	private String searchText = "";
	private boolean searchInputOpen; // our chatbox search prompt is currently open
	private int expandedTileId = -1;
	// Admin-only "Sync clan roster" button state (schedule-home left column). True while a sync POST
	// is in flight so the button shows a disabled "Syncing clan…" state and ignores repeat clicks.
	private boolean clanSyncInProgress;

	/**
	 * Hub navigation: SCHEDULE is the home; the rest are drill-ins.
	 *   EVENT       — Leagues-style points accordion (format=bingo, scoringMode=points)
	 *   GRID        — classic square bingo grid (format=bingo, scoringMode=tiles)
	 *   RACE        — tile-race track (format=tilerace)
	 *   GRID_TILE   — single-tile detail page within a grid
	 *   LEADERBOARD — weekly SOTW/BOTW standings
	 *   POINTS      — read-only points list for a Leagues event you're NOT enrolled in (upcoming
	 *                 preview, or a live event you're not competing in). The enrolled view is EVENT.
	 */
	private enum HubView { SCHEDULE, EVENT, GRID, GRID_TILE, RACE, LEADERBOARD, POINTS }
	private HubView hubView = HubView.SCHEDULE;
	// Schedule home event-type filter: "" = all, else "bingo" | "boss" | "skill".
	private String eventTypeFilter = "";
	// Leaderboard drill-in state.
	private Integer selectedWeeklyId;
	private BingoApiClient.WeeklyLeaderboard cachedLeaderboard;
	private boolean loadingLeaderboard;
	// Live countdown on the leaderboard detail header. We keep a handle to that one banner line so
	// onGameTick can refresh just it (a single setText) instead of rebuilding the whole view — the
	// label is minute-granular, so the text actually changes ~once a minute. Null when not shown.
	private Widget countdownLine;
	private String countdownStart;
	private String countdownEnd;
	private String countdownSuffix = "";
	private String countdownText = "";
	// Board drill-in state (shared by the GRID and RACE views — both read the same payload).
	// cachedBoard = your own active event (interactive). cachedPreview = the last read-only preview
	// of some *other* event (an upcoming one, or a live event you're not competing in).
	private BingoApiClient.BoardResponse cachedBoard;
	private BingoApiClient.BoardResponse cachedPreview;
	private boolean loadingBoard;
	// The event currently shown in the GRID/RACE/GRID_TILE views — your active event, or a preview.
	private int viewingEventId = -1;
	// Title of the event being viewed, so the header reads correctly while its board is still loading.
	private String viewingTitle;
	// Event id we've already kicked off a schedule-home board fetch for, so the pinned progress
	// label loads the board exactly once per event (no fetch loop when the board is unavailable).
	private int boardRequestedEventId = -1;
	// Grid tap-to-inspect: the tile whose detail page is shown (-1 = none).
	private int selectedGridTileId = -1;

	@Inject
	public ClogTabController(Client client, ClientThread clientThread, ItemManager itemManager,
		AnvilPlugin plugin, AnvilConfig config, ChatboxPanelManager chatboxPanelManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.plugin = plugin;
		this.config = config;
		this.chatboxPanelManager = chatboxPanelManager;
	}

	// ---- lifecycle hooks (called from the plugin's @Subscribe delegators) ----

	public void onWidgetLoaded(int groupId)
	{
		if (groupId != InterfaceID.COLLECTION_LOG && !isAdventureLogGroup(groupId))
		{
			return;
		}
		clogOpen = true;
		bingoTabActive = false;
		if (isEnabled())
		{
			clientThread.invokeLater(this::injectBingoTab);
		}
	}

	public void onWidgetClosed(int groupId)
	{
		if (groupId == InterfaceID.COLLECTION_LOG || isAdventureLogGroup(groupId))
		{
			clogOpen = false;
			bingoTabActive = false;
			closeSearchInput();
			searchText = "";
		}
	}

	/** Config refreshed in the background — re-render if our tab is open and active so it updates live. */
	public void onConfigRefreshed()
	{
		if (isEnabled() && clogOpen && bingoTabActive)
		{
			clientThread.invokeLater(this::renderHub);
		}
	}

	/**
	 * The native list redrew (COLLECTION_DRAW_LIST). We never trigger this ourselves, so a redraw
	 * while our tab is active means the user clicked a native tab/entry — drop back to native
	 * content (restore the boss list, drop our filter column). Re-apply our tab regardless.
	 */
	public void onCollectionDrawList()
	{
		if (!clogOpen)
		{
			return;
		}
		if (!isEnabled())
		{
			return;
		}
		if (bingoTabActive)
		{
			bingoTabActive = false;
			// Leaving our tab: drop the search so it doesn't linger (or trap a half-typed prompt)
			// when the user clicks a native tab/entry.
			closeSearchInput();
			searchText = "";
			setBossListHidden(false);
			hideHeaderBook(false);
			removeAnvilLeft();
		}
		injectBingoTab();
	}

	/**
	 * Per-tick safety net. The search prompt is a chatbox input that can swallow the click that
	 * would normally fire COLLECTION_DRAW_LIST, so a plain navigate-away doesn't always tear us
	 * down. Here we (a) close the search whenever it's open but our tab isn't active, and (b) detect
	 * the native list reappearing under us (user switched tabs) and drop back to native content.
	 */
	public void onGameTick()
	{
		if (!clogOpen)
		{
			closeSearchInput();
			return;
		}
		if (!isEnabled())
		{
			return;
		}
		// Native list became visible again while we thought our tab was active → we were bypassed.
		if (bingoTabActive && !isBossListHidden())
		{
			bingoTabActive = false;
			closeSearchInput();
			searchText = "";
			hideHeaderBook(false);
			removeAnvilLeft();
			injectBingoTab();
			return;
		}
		if (searchInputOpen && !bingoTabActive)
		{
			closeSearchInput();
			searchText = "";
		}
		// Tick the leaderboard countdown without rebuilding the view — repaint just the one line, and
		// only when its minute-granular text actually changes.
		if (bingoTabActive && hubView == HubView.LEADERBOARD && countdownLine != null)
		{
			String now = countdownLabel(countdownStart, countdownEnd);
			if (!now.equals(countdownText))
			{
				countdownText = now;
				countdownLine.setText(now + countdownSuffix);
				countdownLine.revalidate();
			}
		}
	}

	private boolean isBossListHidden()
	{
		Widget container = contentContainer();
		if (container == null)
		{
			return false;
		}
		Widget[] s = container.getStaticChildren();
		if (s != null && s.length > ClogIds.LEFT_LIST_CHILD && s[ClogIds.LEFT_LIST_CHILD] != null)
		{
			return s[ClogIds.LEFT_LIST_CHILD].isHidden();
		}
		return false;
	}

	// ---- filter API ----

	public void setStatusFilter(ClogTaskModel.StatusFilter f)
	{
		this.statusFilter = f;
		refreshIfActive();
	}

	public void setTypeFilter(ClogTaskModel.TypeFilter f)
	{
		this.typeFilter = f;
		refreshIfActive();
	}

	public void setTierFilter(String key)
	{
		this.tierFilter = key == null ? "" : key;
		refreshIfActive();
	}

	public void setSearchText(String s)
	{
		this.searchText = s == null ? "" : s;
		refreshIfActive();
	}

	private void refreshIfActive()
	{
		if (bingoTabActive)
		{
			clientThread.invokeLater(this::renderItems);
		}
	}

	// ---- tab injection ----

	private void injectBingoTab()
	{
		Widget tabs = client.getWidget(ComponentID.COLLECTION_LOG_TABS);
		if (tabs == null)
		{
			return;
		}

		// Re-space + restyle the 5 native tabs so a 6th fits. While our tab is active we force the
		// natives to the unselected sprites; otherwise we leave their sprites (native selection)
		// alone and only reposition.
		Widget[] natives = tabs.getStaticChildren();
		if (natives != null)
		{
			for (int i = 0; i < natives.length; i++)
			{
				layoutTab(natives[i], i * ClogIds.TAB_STEP, ClogIds.TAB_WIDTH,
					bingoTabActive ? Boolean.FALSE : null, null, false);
			}
		}

		// Our Bingo tab — a real 3-slice sprite tab, selected when active.
		Widget tab = findBingoTab(tabs);
		if (tab == null)
		{
			tab = tabs.createChild(-1, WidgetType.LAYER);
			tab.setName(ClogIds.BINGO_TAB_NAME);
			tab.setHasListener(true);
			tab.setAction(0, "View");
			tab.setOnOpListener((JavaScriptCallback) this::onBingoTabClicked);
		}
		layoutTab(tab, ClogIds.BINGO_TAB_INDEX * ClogIds.TAB_STEP, ClogIds.TAB_WIDTH,
			bingoTabActive ? Boolean.TRUE : Boolean.FALSE, ClogIds.BINGO_TAB_NAME, true);
		// Make our tab's slices render exactly like Jagex's by copying their render flags.
		if (natives != null && natives.length > 0)
		{
			copyTabRenderFlags(natives[0], tab);
		}
		tabs.revalidate();
	}

	/** Copy sprite-render flags (tiling/border/flip) from a native tab's 3 slices onto ours. */
	private void copyTabRenderFlags(Widget fromTab, Widget toTab)
	{
		Widget[] from = fromTab.getDynamicChildren();
		Widget[] to = toTab.getDynamicChildren();
		if (from == null || to == null)
		{
			return;
		}
		int n = Math.min(3, Math.min(from.length, to.length));
		for (int i = 0; i < n; i++)
		{
			if (from[i] == null || to[i] == null)
			{
				continue;
			}
			to[i].setSpriteTiling(from[i].getSpriteTiling());
			to[i].setBorderType(from[i].getBorderType());
			to[i].setFlippedVertically(from[i].isFlippedVertically());
			to[i].setFlippedHorizontally(from[i].isFlippedHorizontally());
			to[i].revalidate();
		}
	}

	/**
	 * Lay out a clog tab as a 3-slice sprite (left cap / stretched middle / right cap) + centred
	 * label at the given x/width. {@code selected} null = leave the background sprites untouched
	 * (keep native selection); true/false = force selected/unselected sprites. {@code ensure}
	 * creates the 4 children for our own tab.
	 */
	private void layoutTab(Widget tab, int x, int w, Boolean selected, String label, boolean ensure)
	{
		if (tab == null)
		{
			return;
		}
		place(tab, x, 0, w, ClogIds.TAB_HEIGHT);

		Widget[] kids = tab.getDynamicChildren();
		Widget cap0, mid, cap1, text;
		if (ensure && (kids == null || kids.length < 4))
		{
			cap0 = tab.createChild(-1, WidgetType.GRAPHIC);
			mid = tab.createChild(-1, WidgetType.GRAPHIC);
			cap1 = tab.createChild(-1, WidgetType.GRAPHIC);
			text = tab.createChild(-1, WidgetType.TEXT);
		}
		else if (kids != null && kids.length >= 4)
		{
			cap0 = kids[0];
			mid = kids[1];
			cap1 = kids[2];
			text = kids[3];
		}
		else
		{
			tab.revalidate();
			return;
		}

		// The middle slice is laid out FIRST and tucked under the rounded end-caps (like the native
		// tabs: their mid spans ~18..w-18 beneath the caps). Butting the slices edge-to-edge instead
		// lets the caps' transparent corners reveal the striped clog background — that was the bug.
		int overlap = 4;
		place(mid, ClogIds.TAB_CAP_W - overlap, 0, w - 2 * (ClogIds.TAB_CAP_W - overlap), ClogIds.TAB_HEIGHT);
		place(cap0, 0, 0, ClogIds.TAB_CAP_W, ClogIds.TAB_HEIGHT);
		place(cap1, w - ClogIds.TAB_CAP_W, 0, ClogIds.TAB_CAP_W, ClogIds.TAB_HEIGHT);

		if (selected != null)
		{
			// Only swap the sprite ids here; the render flags (tiling/border/flip) are copied
			// verbatim from a real native tab in copyTabRenderFlags so ours looks identical.
			int cap = selected ? ClogIds.TAB_CAP_SELECTED : ClogIds.TAB_CAP_UNSELECTED;
			int m = selected ? ClogIds.TAB_MID_SELECTED : ClogIds.TAB_MID_UNSELECTED;
			mid.setSpriteId(m);
			cap0.setSpriteId(cap);
			cap1.setSpriteId(cap);
		}

		if (label != null)
		{
			text.setText(label);
			text.setTextColor(COL_ORANGE);
			text.setFontId(FONT_PLAIN);
			text.setTextShadowed(true);
		}
		place(text, 0, 0, w, ClogIds.TAB_HEIGHT);
		text.setXTextAlignment(ALIGN_CENTER);
		text.setYTextAlignment(ALIGN_CENTER);

		cap0.revalidate();
		mid.revalidate();
		cap1.revalidate();
		text.revalidate();
		tab.revalidate();
	}

	/**
	 * Position + size a widget in absolute coordinates. Forcing absolute position/size MODES is
	 * essential: native clog widgets use relative/minus modes, so setting originalX/Width without
	 * this makes them land in the wrong place (which mangled the native tab bar).
	 */
	private static void place(Widget w, int x, int y, int width, int height)
	{
		w.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		w.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		w.setWidthMode(WidgetSizeMode.ABSOLUTE);
		w.setHeightMode(WidgetSizeMode.ABSOLUTE);
		w.setOriginalX(x);
		w.setOriginalY(y);
		w.setOriginalWidth(width);
		w.setOriginalHeight(height);
	}

	private Widget findBingoTab(Widget tabs)
	{
		Widget[] dyn = tabs.getDynamicChildren();
		if (dyn != null)
		{
			for (Widget c : dyn)
			{
				if (c != null && ClogIds.BINGO_TAB_NAME.equals(c.getName()))
				{
					return c;
				}
			}
		}
		return null;
	}

	private void onBingoTabClicked(ScriptEvent e)
	{
		bingoTabActive = true;
		setBossListHidden(true);
		hideHeaderBook(true);
		injectBingoTab();
		hubView = HubView.SCHEDULE;
		renderHub();
	}

	// ---- hub: Schedule home ⇄ drilled-in event view ----

	/** Re-render the left column + right content for the current view. */
	private void renderHub()
	{
		// Never paint our content unless our tab is actually the active view — otherwise a stray
		// re-render (e.g. a late search callback after the user clicked a native tab) bleeds the
		// Anvil panel over native content.
		if (!clogOpen || !bingoTabActive)
		{
			return;
		}
		hideHeaderBook(true);
		// Switching views (drill in / back / filter) starts at the top, not the old scroll offset.
		Widget items = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
		if (items != null)
		{
			items.setScrollY(0);
		}
		renderLeftColumn();
		switch (hubView)
		{
			case EVENT:
				renderBingoPage();
				break;
			case GRID:
				renderBingoGrid();
				break;
			case GRID_TILE:
				renderGridTileDetail();
				break;
			case RACE:
				renderTileRace();
				break;
			case LEADERBOARD:
				renderLeaderboardView();
				break;
			case POINTS:
				renderPointsPreview();
				break;
			case SCHEDULE:
			default:
				renderScheduleHome();
				break;
		}
	}

	/**
	 * Open the player's own active event in the right view: a tile race goes to the track, a
	 * points bingo keeps the Leagues accordion, and a classic (tile-scored) bingo opens the grid.
	 */
	private void openBingo()
	{
		if ("tilerace".equalsIgnoreCase(eventFormat()))
		{
			openRace();
		}
		else if ("points".equalsIgnoreCase(eventScoringMode()))
		{
			hubView = HubView.EVENT;
			clientThread.invokeLater(this::renderHub);
		}
		else
		{
			openGrid();
		}
	}

	private void openGrid()
	{
		viewingEventId = activeEventId();
		viewingTitle = eventName();
		hubView = HubView.GRID;
		selectedGridTileId = -1;
		loadBoard();
		clientThread.invokeLater(this::renderHub);
	}

	/** Drill into a single grid tile's detail page; Back (left column) returns to the grid. */
	private void openGridTile(int tileId)
	{
		selectedGridTileId = tileId;
		hubView = HubView.GRID_TILE;
		clientThread.invokeLater(this::renderHub);
	}

	private void backToGrid()
	{
		hubView = HubView.GRID;
		clientThread.invokeLater(this::renderHub);
	}

	private void openRace()
	{
		viewingEventId = activeEventId();
		viewingTitle = eventName();
		hubView = HubView.RACE;
		loadBoard();
		clientThread.invokeLater(this::renderHub);
	}

	/**
	 * Open a read-only preview of a scheduled event that isn't your own active one — an upcoming
	 * event's layout, or a live event you're not competing in (with all-team progress). Reuses the
	 * GRID / RACE renderers; the board's {@code readOnly} flag tweaks their presentation.
	 */
	private void openScheduledBingo(SchedEntry en)
	{
		if (en == null)
		{
			return;
		}
		viewingEventId = en.id;
		viewingTitle = en.title;
		selectedGridTileId = -1;
		// Mirror openBingo's routing for the unenrolled/preview side: tilerace→RACE, points→POINTS
		// (read-only list), classic tiles→GRID. Without the points branch a Leagues event wrongly
		// rendered as an N×N grid (boardSize is the tile count for points, not a side length).
		if ("tilerace".equalsIgnoreCase(en.format))
		{
			hubView = HubView.RACE;
		}
		else if ("points".equalsIgnoreCase(en.scoringMode))
		{
			hubView = HubView.POINTS;
		}
		else
		{
			hubView = HubView.GRID;
		}
		loadBoardPreview(en.id);
		clientThread.invokeLater(this::renderHub);
	}

	/**
	 * Kick off an async board fetch for the GRID / RACE views, repainting when it lands. Keeps any
	 * previously cached board on screen so a refresh doesn't flash an empty board.
	 */
	private void loadBoard()
	{
		loadingBoard = true;
		plugin.loadBoard(board -> {
			loadingBoard = false;
			if (board != null)
			{
				cachedBoard = board;
			}
			// Repaint whatever board-aware view is up (grid, race, tile detail, or the schedule
			// home, whose pinned row shows your team's board progress).
			if (bingoTabActive)
			{
				renderHub();
			}
		});
	}

	/** Header title for a board view: the board's own event name (works for previews too). */
	private String boardTitle(BingoApiClient.BoardResponse board)
	{
		if (board != null && board.name != null && !board.name.isEmpty())
		{
			return board.name;
		}
		// Board not loaded yet — use the title we were opened with so a preview doesn't briefly
		// flash your active event's name.
		if (viewingTitle != null && !viewingTitle.isEmpty())
		{
			return viewingTitle;
		}
		return eventName() != null ? eventName() : "Bingo";
	}

	/** Active event id from the plugin config, or -1 when not enrolled anywhere live. */
	private int activeEventId()
	{
		PluginConfigResponse cfg = plugin.getPluginConfig();
		return (cfg != null && cfg.event != null) ? cfg.event.id : -1;
	}

	/** Cached board, but only when it belongs to the current active event (else null = stale/unloaded). */
	private BingoApiClient.BoardResponse activeBoard()
	{
		BingoApiClient.BoardResponse b = cachedBoard;
		return (b != null && b.eventId == activeEventId()) ? b : null;
	}

	/** Async read-only preview fetch for the GRID / RACE views when viewing another event. */
	private void loadBoardPreview(int eventId)
	{
		loadingBoard = true;
		plugin.loadBoardPreview(eventId, board -> {
			loadingBoard = false;
			if (board != null)
			{
				cachedPreview = board;
			}
			if (bingoTabActive)
			{
				renderHub();
			}
		});
	}

	/**
	 * The board backing whatever the GRID/RACE/GRID_TILE views are showing right now — your own
	 * active event (interactive) or a read-only preview of another event — or null while it loads.
	 */
	private BingoApiClient.BoardResponse viewedBoard()
	{
		if (viewingEventId == activeEventId() && cachedBoard != null && cachedBoard.eventId == viewingEventId)
		{
			return cachedBoard;
		}
		if (cachedPreview != null && cachedPreview.eventId == viewingEventId)
		{
			return cachedPreview;
		}
		return null;
	}

	/**
	 * Sub-label for the pinned active-event row. A points bingo shows its task tally (the accordion's
	 * own metric); a grid or race shows your team's board progress (completed / total tiles), lazily
	 * fetching the board once so the count appears without opening the event.
	 */
	private String pinnedProgressLabel(String format, String scoringMode)
	{
		boolean accordion = "bingo".equalsIgnoreCase(format) && "points".equalsIgnoreCase(scoringMode);
		if (accordion)
		{
			List<ClogTaskModel.TaskRow> t = tasks();
			return t.isEmpty() ? "Open board" : ClogTaskModel.completedCount(t) + "/" + t.size() + " tasks done";
		}

		BingoApiClient.BoardResponse b = activeBoard();
		if (b == null || b.tiles == null)
		{
			// Fetch once per event so the next render can fill in the real count.
			int eid = activeEventId();
			if (eid > 0 && boardRequestedEventId != eid && !loadingBoard)
			{
				boardRequestedEventId = eid;
				loadBoard();
			}
			return "tap to view";
		}

		int done = 0;
		for (BingoApiClient.BoardTile t : b.tiles)
		{
			if (t != null && t.complete)
			{
				done++;
			}
		}
		boolean race = "tilerace".equalsIgnoreCase(format);
		return done + "/" + b.tiles.size() + (race ? " reached" : " done");
	}

	/** Format of the player's own active event ("bingo" | "tilerace"); defaults to "bingo". */
	private String eventFormat()
	{
		PluginConfigResponse cfg = plugin.getPluginConfig();
		if (cfg != null && cfg.event != null && cfg.event.format != null && !cfg.event.format.isEmpty())
		{
			return cfg.event.format;
		}
		return "bingo";
	}

	/** Scoring mode of the player's own active event ("tiles" | "points"); defaults to "tiles". */
	private String eventScoringMode()
	{
		PluginConfigResponse cfg = plugin.getPluginConfig();
		if (cfg != null && cfg.event != null && cfg.event.scoringMode != null && !cfg.event.scoringMode.isEmpty())
		{
			return cfg.event.scoringMode;
		}
		return "tiles";
	}

	private void openLeaderboard(int weeklyId)
	{
		hubView = HubView.LEADERBOARD;
		selectedWeeklyId = weeklyId;
		cachedLeaderboard = null;
		loadingLeaderboard = true;
		clientThread.invokeLater(this::renderHub);
		plugin.loadWeeklyLeaderboard(weeklyId, lb -> {
			loadingLeaderboard = false;
			cachedLeaderboard = lb;
			// Only paint if the player is still looking at this leaderboard.
			if (bingoTabActive && hubView == HubView.LEADERBOARD
				&& selectedWeeklyId != null && selectedWeeklyId == weeklyId)
			{
				renderLeaderboardView();
			}
		});
	}

	private void backToSchedule()
	{
		hubView = HubView.SCHEDULE;
		clientThread.invokeLater(this::renderHub);
	}


	/** Left column: event-type filter on the Schedule home, a Back button inside an event. */
	private void renderLeftColumn()
	{
		Widget container = contentContainer();
		if (container == null)
		{
			return;
		}
		removeAnvilLeft();

		int y = 24;
		Widget heading = container.createChild(-1, WidgetType.TEXT);
		heading.setText("Anvil");
		heading.setTextColor(COL_ORANGE);
		heading.setFontId(FONT_PLAIN);
		heading.setTextShadowed(true);
		place(heading, 8, y, ClogIds.LEFT_COL_W - 16, 16);
		heading.revalidate();
		y += 26;

		if (hubView != HubView.SCHEDULE)
		{
			// A tile-detail page steps back to its grid; everything else steps back to the schedule.
			boolean toGrid = hubView == HubView.GRID_TILE;
			Widget back = container.createChild(-1, WidgetType.TEXT);
			back.setText("<col=ffcc33>" + (toGrid ? "Back to Board" : "Back to Schedule") + "</col>");
			back.setFontId(FONT_PLAIN);
			back.setTextShadowed(true);
			place(back, 10, y, ClogIds.LEFT_COL_W - 20, 16);
			back.setHasListener(true);
			back.setAction(0, "Back");
			back.setOnOpListener((JavaScriptCallback) e -> {
				if (toGrid)
				{
					backToGrid();
				}
				else
				{
					backToSchedule();
				}
			});
			back.revalidate();
			return;
		}

		// Schedule home: event-type filter.
		Widget lbl = container.createChild(-1, WidgetType.TEXT);
		lbl.setText("<col=999999>Event type</col>");
		lbl.setFontId(FONT_PLAIN);
		place(lbl, 10, y, ClogIds.LEFT_COL_W - 20, 14);
		lbl.revalidate();

		Widget val = container.createChild(-1, WidgetType.TEXT);
		val.setText("<col=ffcc33>" + eventTypeLabel(eventTypeFilter) + "</col>");
		val.setFontId(FONT_PLAIN);
		val.setTextShadowed(true);
		place(val, 10, y + 13, ClogIds.LEFT_COL_W - 20, 16);
		val.setHasListener(true);
		val.setAction(0, "Cycle");
		val.setOnOpListener((JavaScriptCallback) e -> cycleEventTypeFilter());
		val.revalidate();

		// Admin-only: "Sync clan roster" — pushes the in-game clan roster to the site. It sits in the
		// pre-existing empty gap between the event-type value (ends at y+29) and the Banner sounds row
		// (starts at y+50): placed at y+32 with height 14 it occupies y+32..y+46, overlapping neither.
		// Rendered ONLY when plugin.isAdmin() is true, so for non-admins the gap stays empty exactly as
		// it is today (no reserved space, no reflow of any surrounding widget).
		if (plugin.isAdmin())
		{
			Widget sync = container.createChild(-1, WidgetType.TEXT);
			sync.setText(clanSyncInProgress
				? "<col=999999>Syncing clan…</col>"
				: "<col=ffcc33>Sync clan roster</col>");
			sync.setFontId(FONT_PLAIN);
			sync.setTextShadowed(true);
			place(sync, 10, y + 32, ClogIds.LEFT_COL_W - 20, 14);
			if (!clanSyncInProgress)
			{
				sync.setHasListener(true);
				sync.setAction(0, "Sync");
				sync.setOnOpListener((JavaScriptCallback) e -> triggerClanSync());
			}
			sync.revalidate();
		}

		// Banner sounds: the all-users entry point for adding your own clips (none ship with the
		// plugin). The admin sidebar is hidden from regular members, so this tab is the only shared UI.
		Widget sounds = container.createChild(-1, WidgetType.TEXT);
		sounds.setText("<col=ffcc33>Banner sounds</col>");
		sounds.setFontId(FONT_PLAIN);
		sounds.setTextShadowed(true);
		place(sounds, 10, y + 50, ClogIds.LEFT_COL_W - 20, 16);
		sounds.setHasListener(true);
		sounds.setAction(0, "Add");
		sounds.setAction(1, "Open folder");
		sounds.setOnOpListener((JavaScriptCallback) e -> {
			// Action index 1 ("Open folder") maps to menu op 2; default click imports. Deleting a clip
			// from that folder is the permanent remove.
			if (e.getOp() == 2)
			{
				plugin.openBannerSoundsFolder();
			}
			else
			{
				plugin.importBannerSounds();
			}
		});
		sounds.revalidate();

		// One toggle row per clip: green = in the play cycle, grey = muted; clicking flips it (persisted
		// via the comma-separated allowlist). The left column is fixed-height and doesn't scroll, so we
		// only render as many rows as fit and collapse the rest into an "Open folder" overflow line.
		int sy = y + 68;
		final int rowH = 15;
		List<String> clips = plugin.bannerSoundClips();
		int colH = container.getHeight();
		int fit = colH > 80 ? Math.max(1, (colH - sy - 6) / rowH) : 10;
		boolean overflow = clips.size() > fit;
		int shown = overflow ? Math.max(0, fit - 1) : clips.size(); // reserve a slot for the overflow line
		for (int i = 0; i < shown; i++)
		{
			final String clip = clips.get(i);
			boolean on = plugin.bannerSoundSelected(clip);
			String disp = clip.toLowerCase().endsWith(".wav") ? clip.substring(0, clip.length() - 4) : clip;
			if (disp.length() > 26)
			{
				disp = disp.substring(0, 25) + "…";
			}
			Widget row = container.createChild(-1, WidgetType.TEXT);
			row.setText("<col=" + (on ? "49c25e" : "777777") + ">" + disp + "</col>");
			row.setFontId(FONT_PLAIN);
			place(row, 16, sy, ClogIds.LEFT_COL_W - 26, 14);
			row.setHasListener(true);
			row.setAction(0, on ? "Mute" : "Enable");
			row.setOnOpListener((JavaScriptCallback) e -> plugin.toggleBannerSound(clip));
			row.revalidate();
			sy += rowH;
		}
		if (overflow)
		{
			Widget more = container.createChild(-1, WidgetType.TEXT);
			more.setText("<col=aaaaaa>+" + (clips.size() - shown) + " more — Open folder</col>");
			more.setFontId(FONT_PLAIN);
			place(more, 16, sy, ClogIds.LEFT_COL_W - 26, 14);
			more.setHasListener(true);
			more.setAction(0, "Open folder");
			more.setOnOpListener((JavaScriptCallback) e -> plugin.openBannerSoundsFolder());
			more.revalidate();
		}
	}

	private static String eventTypeLabel(String f)
	{
		switch (f)
		{
			case "bingo":
				return "Bingo";
			case "boss":
				return "Boss of the Week";
			case "skill":
				return "Skill of the Week";
			default:
				return "All";
		}
	}

	private void cycleEventTypeFilter()
	{
		String[] order = {"", "bingo", "boss", "skill"};
		int idx = 0;
		for (int i = 0; i < order.length; i++)
		{
			if (order[i].equals(eventTypeFilter))
			{
				idx = i;
				break;
			}
		}
		eventTypeFilter = order[(idx + 1) % order.length];
		clientThread.invokeLater(this::renderHub);
	}

	/**
	 * Admin "Sync clan roster" click handler. Flips the button to a disabled "Syncing clan…" state,
	 * kicks off the off-thread sync (which scrapes the in-game roster and POSTs it), and re-renders the
	 * left column when it completes. The success/failure summary is reported in-game by the plugin via
	 * sendChatMessage; here we only own the button's in-progress state.
	 */
	private void triggerClanSync()
	{
		if (clanSyncInProgress)
		{
			return;
		}
		clanSyncInProgress = true;
		// Repaint the left column so the button shows "Syncing clan…" and stops accepting clicks.
		if (bingoTabActive)
		{
			clientThread.invokeLater(this::renderLeftColumn);
		}
		plugin.syncClanRoster((ok, msg) -> clientThread.invokeLater(() ->
		{
			clanSyncInProgress = false;
			if (bingoTabActive && hubView == HubView.SCHEDULE)
			{
				renderLeftColumn();
			}
		}));
	}

	// ---- left column: hide native boss list, draw our filter column in its place ----

	private Widget contentContainer()
	{
		Widget tabs = client.getWidget(ComponentID.COLLECTION_LOG_TABS);
		return tabs == null ? null : tabs.getParent();
	}

	private void setBossListHidden(boolean hidden)
	{
		Widget container = contentContainer();
		if (container == null)
		{
			return;
		}
		Widget[] s = container.getStaticChildren();
		if (s != null && s.length > ClogIds.LEFT_LIST_CHILD && s[ClogIds.LEFT_LIST_CHILD] != null)
		{
			s[ClogIds.LEFT_LIST_CHILD].setHidden(hidden);
		}
	}

	/**
	 * Clear our injected left-column children. The content container has no native dynamic
	 * children (only static: tabs/list/content), so deleteAllChildren removes exactly our nav
	 * widgets — and lets us avoid setName() on them (which was leaking into hover tooltips).
	 */
	private void removeAnvilLeft()
	{
		Widget container = contentContainer();
		if (container != null)
		{
			container.deleteAllChildren();
		}
	}

	/**
	 * Sync the native scrollbar to our injected scroll content. After changing a scroll layer's
	 * height we must run UPDATE_SCROLLBAR or the thumb size/position stays wrong (and dragging
	 * misbehaves). The scrollbar is the thin tall sibling of the content layer.
	 */
	private void updateScrollbar(Widget scrollLayer)
	{
		if (scrollLayer == null)
		{
			return;
		}
		Widget parent = scrollLayer.getParent();
		if (parent == null)
		{
			return;
		}
		Widget scrollbar = scanForScrollbar(parent.getStaticChildren(), scrollLayer);
		if (scrollbar == null)
		{
			scrollbar = scanForScrollbar(parent.getDynamicChildren(), scrollLayer);
		}
		if (scrollbar != null)
		{
			client.runScript(ScriptID.UPDATE_SCROLLBAR, scrollbar.getId(), scrollLayer.getId(),
				scrollLayer.getScrollY());
		}
	}

	private static Widget scanForScrollbar(Widget[] kids, Widget exclude)
	{
		if (kids == null)
		{
			return null;
		}
		for (Widget c : kids)
		{
			if (c != null && c != exclude && c.getWidth() > 0 && c.getWidth() <= 24 && c.getHeight() >= 100)
			{
				return c;
			}
		}
		return null;
	}

	/**
	 * Hide/show the native header's static widgets (the Combat Achievements book button AND the
	 * leftover entry obtained-count text — the stray "9" that bled over our banner). On the Anvil
	 * tab we render our own banner as dynamic children, so we want none of the native static header
	 * content visible; it's restored when the player leaves the tab.
	 */
	private void hideHeaderBook(boolean hidden)
	{
		Widget header = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_HEADER);
		if (header == null)
		{
			return;
		}
		Widget[] s = header.getStaticChildren();
		if (s == null)
		{
			return;
		}
		for (Widget w : s)
		{
			if (w != null)
			{
				w.setHidden(hidden);
			}
		}
	}

	private void cycleStatusFilter()
	{
		ClogTaskModel.StatusFilter[] v = ClogTaskModel.StatusFilter.values();
		setStatusFilter(v[(statusFilter.ordinal() + 1) % v.length]);
	}

	private void cycleTypeFilter()
	{
		ClogTaskModel.TypeFilter[] v = ClogTaskModel.TypeFilter.values();
		setTypeFilter(v[(typeFilter.ordinal() + 1) % v.length]);
	}

	private void cycleTierFilter()
	{
		// Options: "" (All) followed by each served band key.
		List<String> options = new ArrayList<>();
		options.add("");
		for (PluginConfigResponse.TierBand b : tierBands())
		{
			if (b != null && b.key != null && !b.key.isEmpty())
			{
				options.add(b.key);
			}
		}
		int idx = 0;
		for (int i = 0; i < options.size(); i++)
		{
			if (options.get(i).equalsIgnoreCase(tierFilter))
			{
				idx = i;
				break;
			}
		}
		setTierFilter(options.get((idx + 1) % options.size()));
	}

	private void cycleCategoryFilter()
	{
		// Options: "" (All) followed by each distinct category.
		List<String> cats = ClogTaskModel.categories(tasks());
		List<String> options = new ArrayList<>();
		options.add("");
		options.addAll(cats);
		int idx = 0;
		for (int i = 0; i < options.size(); i++)
		{
			if (options.get(i).equalsIgnoreCase(categoryFilter))
			{
				idx = i;
				break;
			}
		}
		categoryFilter = options.get((idx + 1) % options.size());
		refreshIfActive();
	}

	// ---- right pane: points banner + accordion task list ----

	private void renderBingoPage()
	{
		renderHeader();
		renderItems();
	}

	private void renderHeader()
	{
		Widget header = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_HEADER);
		if (header == null)
		{
			return;
		}

		// Clear the native title/obtained/kills text lines (restored by the native redraw on
		// leave) and lay down our own banner — avoids the stale-pileup + wrong-order overlap from
		// reusing the native widgets.
		header.deleteAllChildren();

		List<ClogTaskModel.TaskRow> all = tasks();
		int done = ClogTaskModel.completedCount(all);
		int earned = ClogTaskModel.earnedPoints(all);
		int totalPts = ClogTaskModel.totalPoints(all);
		String subtitle = eventName() != null ? eventName()
			: "No active event";

		bannerLine(header, "Bingo Tasks", COL_ORANGE, 0);
		String pts = totalPts > 0
			? "<col=ffff00>" + earned + "</col> / " + totalPts + " points"
			: "Completed: <col=ffff00>" + done + "/" + all.size() + "</col>";
		bannerLine(header, pts, 0xffffff, BANNER_LINE_H);
		bannerLine(header, subtitle + "  <col=666666>·</col>  " + done + "/" + all.size() + " done", 0xaaaaaa, BANNER_LINE_H * 2);
		header.revalidate();
	}

	private static final int BANNER_TOP = 1; // top margin so the header isn't flush against the divider
	private static final int BANNER_LINE_H = 13; // per-line step; 3 lines must fit the clog header height

	private Widget bannerLine(Widget header, String text, int color, int y)
	{
		Widget line = header.createChild(-1, WidgetType.TEXT);
		place(line, 2, y + BANNER_TOP, 280, 15);
		line.setFontId(FONT_PLAIN);
		line.setText(text);
		line.setTextColor(color);
		line.setTextShadowed(true);
		line.revalidate();
		return line;
	}

	private void renderItems()
	{
		if (!clogOpen || !bingoTabActive)
		{
			return;
		}
		Widget items = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
		if (items == null)
		{
			return;
		}
		items.deleteAllChildren();

		int paneWidth = items.getWidth() > 0 ? items.getWidth() : 250;
		int top = renderBodyFilters(items, paneWidth);
		List<PluginConfigResponse.TierBand> bands = tierBands();
		List<ClogTaskModel.TaskRow> rows = ClogTaskModel.filter(tasks(), statusFilter, typeFilter, searchText,
			categoryFilter, effectiveTier(bands), bands);

		if (rows.isEmpty())
		{
			Widget empty = items.createChild(-1, WidgetType.TEXT);
			empty.setText("No bingo tasks match the current filters.");
			empty.setTextColor(0xaaaaaa);
			empty.setFontId(FONT_PLAIN);
			place(empty, 0, top, paneWidth, 20);
			empty.revalidate();
			items.setScrollHeight(top + ClogIds.ROW_H);
			items.revalidateScroll();
			updateScrollbar(items);
			return;
		}

		int y = top;
		int textWidth = Math.max(60, paneWidth - ClogIds.ROW_TEXT_X);
		for (ClogTaskModel.TaskRow row : rows)
		{
			y += renderTaskRow(items, row, y, textWidth);
		}

		items.setScrollHeight(y + 4);
		items.revalidateScroll();
		updateScrollbar(items);
	}

	/** Compact filter strip at the top of the Bingo body; returns the y where the task list starts. */
	private int renderBodyFilters(Widget items, int paneWidth)
	{
		int y = BODY_TOP;

		// Search bar (full width): left-click opens a chatbox text input; right-click clears.
		String query = searchText.trim();
		boolean hasQuery = !query.isEmpty();
		Widget search = items.createChild(-1, WidgetType.TEXT);
		search.setText(hasQuery
			? "<col=999999>Search:</col> <col=ffcc33>" + query + "</col>"
			: "<col=777777>Search tasks…</col>");
		search.setFontId(FONT_PLAIN);
		search.setTextShadowed(true);
		place(search, 0, y, paneWidth, 14);
		search.setHasListener(true);
		search.setAction(0, "Search");
		if (hasQuery)
		{
			search.setAction(1, "Clear");
		}
		search.setOnOpListener((JavaScriptCallback) e ->
		{
			if (e.getOp() == 2)
			{
				setSearchText("");
			}
			else
			{
				promptSearch();
			}
		});
		search.revalidate();
		y += 16;

		// Filter chips: Status + Type always, Category when any tile is categorised, Tier on
		// points events (tiers derive from point values). Laid out up to 3 per row so the labels
		// stay legible even with all four present.
		List<ClogTaskModel.TaskRow> all = tasks();
		boolean hasCats = !ClogTaskModel.categories(all).isEmpty();
		List<PluginConfigResponse.TierBand> bands = tierBands();
		boolean showTier = ClogTaskModel.totalPoints(all) > 0 && !bands.isEmpty();

		List<String> chipLabels = new ArrayList<>();
		List<String> chipValues = new ArrayList<>();
		List<JavaScriptCallback> chipActions = new ArrayList<>();

		chipLabels.add("Status");
		chipValues.add(pretty(statusFilter.name()));
		chipActions.add(e -> cycleStatusFilter());
		chipLabels.add("Type");
		chipValues.add(pretty(typeFilter.name()));
		chipActions.add(e -> cycleTypeFilter());
		if (hasCats)
		{
			chipLabels.add("Category");
			chipValues.add(categoryFilter.isEmpty() ? "All" : categoryFilter);
			chipActions.add(e -> cycleCategoryFilter());
		}
		if (showTier)
		{
			String tier = effectiveTier(bands);
			chipLabels.add("Tier");
			chipValues.add(tier.isEmpty() ? "All" : ClogTaskModel.tierLabel(tier, bands));
			chipActions.add(e -> cycleTierFilter());
		}

		int perRow = 3;
		int cols = Math.min(perRow, chipLabels.size());
		int gap = 6;
		int colW = (paneWidth - gap * (cols - 1)) / cols;
		for (int i = 0; i < chipLabels.size(); i++)
		{
			int cx = (i % perRow) * (colW + gap);
			int cy = y + (i / perRow) * 16;
			bodyFilterChip(items, chipLabels.get(i), chipValues.get(i), cx, cy, colW, chipActions.get(i));
		}
		int numRows = (chipLabels.size() + perRow - 1) / perRow;
		y += 16 * numRows;

		return y + 4;
	}

	/** Opens the chatbox text input to set the task-name search query. Filters live as you type. */
	private void promptSearch()
	{
		searchInputOpen = true;
		chatboxPanelManager.openTextInput("Search bingo tasks")
			.value(searchText)
			// Live: re-filter on every keystroke so results react as you type.
			.onChanged((String v) -> setSearchText(v))
			.onDone((String v) ->
			{
				searchInputOpen = false;
				setSearchText(v);
			})
			.onClose(() -> searchInputOpen = false)
			.build();
	}

	/** Closes our search prompt if it's open (e.g. when the user navigates away from the tab). */
	private void closeSearchInput()
	{
		if (searchInputOpen)
		{
			searchInputOpen = false;
			chatboxPanelManager.close();
		}
	}

	private void bodyFilterChip(Widget items, String label, String value, int x, int y, int w, JavaScriptCallback onClick)
	{
		Widget chip = items.createChild(-1, WidgetType.TEXT);
		chip.setText("<col=999999>" + label + ":</col> <col=ffcc33>" + value + "</col>");
		chip.setFontId(FONT_PLAIN);
		chip.setTextShadowed(true);
		place(chip, x, y, w, 14);
		chip.setHasListener(true);
		chip.setAction(0, "Cycle");
		chip.setOnOpListener(onClick);
		chip.revalidate();
	}

	/** Renders one accordion task row; returns the vertical space it consumed. */
	private int renderTaskRow(Widget items, ClogTaskModel.TaskRow row, int y, int textWidth)
	{
		int statusColor = colorFor(row.status);
		boolean expanded = row.tileId == expandedTileId;
		boolean hasDesc = row.description != null && !row.description.isEmpty();

		Widget icon = items.createChild(-1, WidgetType.GRAPHIC);
		if (row.itemId > 0)
		{
			icon.setItemId(row.itemId);
			icon.setItemQuantityMode(ItemQuantityMode.NEVER);
		}
		else
		{
			icon.setSpriteId(ClogIds.STAT_TILE_SPRITE);
		}
		place(icon, 2, y + (ClogIds.ROW_H - ClogIds.ROW_ICON) / 2, ClogIds.ROW_ICON, ClogIds.ROW_ICON);
		icon.setOpacity(row.status == ClogTaskModel.Status.NOT_STARTED ? 120 : 0);
		// Pure visual — no listener/name, so hovering an icon doesn't pop a tooltip.
		icon.revalidate();

		Widget name = items.createChild(-1, WidgetType.TEXT);
		String prefix = hasDesc ? "<col=ffcc33>" + (expanded ? "-" : "+") + "</col> " : "";
		name.setText(prefix + row.label);
		name.setTextColor(statusColor);
		name.setTextShadowed(true);
		name.setFontId(FONT_PLAIN);
		place(name, ClogIds.ROW_TEXT_X, y + 3, textWidth, 16);
		name.setHasListener(true);
		name.setAction(0, expanded ? "Collapse" : "Expand");
		name.setOnOpListener((JavaScriptCallback) e -> toggleExpand(row.tileId));
		name.revalidate();

		StringBuilder sub = new StringBuilder();
		if (row.points > 0)
		{
			sub.append("Reward: <col=ffcc33>").append(row.points).append(" points</col>");
		}
		if (row.goal > 0)
		{
			if (sub.length() > 0)
			{
				sub.append("  <col=666666>·</col>  ");
			}
			sub.append("<col=").append(hex(statusColor)).append(">")
				.append(row.current).append("/").append(row.goal).append("</col>");
		}
		Widget subText = items.createChild(-1, WidgetType.TEXT);
		subText.setText(sub.toString());
		subText.setTextColor(0x999999);
		subText.setTextShadowed(true);
		subText.setFontId(FONT_PLAIN);
		place(subText, ClogIds.ROW_TEXT_X, y + 18, textWidth, 14);
		subText.revalidate();

		int height = ClogIds.ROW_H;
		if (expanded && hasDesc)
		{
			Widget desc = items.createChild(-1, WidgetType.TEXT);
			desc.setText("<col=c0c0c0>" + row.description + "</col>");
			desc.setTextShadowed(true);
			desc.setFontId(FONT_PLAIN);
			place(desc, ClogIds.ROW_TEXT_X, y + ClogIds.ROW_H, textWidth, ClogIds.DESC_H);
			desc.revalidate();
			height += ClogIds.DESC_H + 2;
		}
		return height;
	}

	private void toggleExpand(int tileId)
	{
		expandedTileId = (expandedTileId == tileId) ? -1 : tileId;
		clientThread.invokeLater(this::renderItems);
	}

	// ---- Schedule section: upcoming bingo events + weekly competitions (from config.schedule) ----

	private static final int SECTION_HEADER_H = 22;
	private static final int SCHED_ROW_H = 34; // must match scheduleRow()'s returned height

	/** Faint dark-green panel + bright full-height left accent that contains the "Live now" block. */
	private void groupPanel(Widget items, int x, int y, int w, int h)
	{
		Widget bg = items.createChild(-1, WidgetType.RECTANGLE);
		bg.setFilled(true);
		bg.setTextColor(0x16240f);
		bg.setOpacity(200); // 0=opaque, 255=transparent — a gentle tint over the brown clog
		place(bg, x, y, w, h);
		bg.revalidate();

		Widget bar = items.createChild(-1, WidgetType.RECTANGLE);
		bar.setFilled(true);
		bar.setTextColor(0x4caf50);
		bar.setOpacity(40);
		place(bar, x, y, 3, h);
		bar.revalidate();
	}

	/** A small coloured section header ("Live now" / "Upcoming"); returns its height. */
	private int sectionHeader(Widget items, String label, int color, int y, int paneWidth)
	{
		Widget t = items.createChild(-1, WidgetType.TEXT);
		t.setText("<col=" + hex(color) + ">" + label + "</col>");
		t.setFontId(FONT_PLAIN);
		t.setTextShadowed(true);
		place(t, 10, y + 3, paneWidth - 16, 16);
		t.revalidate();
		return SECTION_HEADER_H;
	}

	private void renderScheduleHome()
	{
		Widget header = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_HEADER);
		if (header != null)
		{
			header.deleteAllChildren();
			bannerLine(header, "Schedule", COL_ORANGE, 0);
			bannerLine(header, "Your events & upcoming competitions", 0xaaaaaa, 16);
			header.revalidate();
		}

		Widget items = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
		if (items == null)
		{
			return;
		}
		items.deleteAllChildren();
		int paneWidth = items.getWidth() > 0 ? items.getWidth() : 250;
		boolean showBingo = eventTypeFilter.isEmpty() || eventTypeFilter.equals("bingo");
		int y = BODY_TOP;

		// Your active, openable bingo event is the headliner of the "Live now" block. Shown whenever
		// you're enrolled in a live event, even a manual classic grid with no tracked tiles.
		PluginConfigResponse activeCfg = plugin.getPluginConfig();
		boolean haveActiveEvent = activeCfg != null && activeCfg.event != null;
		boolean hasPinned = showBingo && haveActiveEvent;
		int pinnedEventId = hasPinned ? activeEventId() : -1;

		// Collect schedule entries (excluding the pinned event), then split into live vs upcoming.
		List<SchedEntry> entries = new ArrayList<>();
		PluginConfigResponse cfg = plugin.getPluginConfig();
		BingoApiClient.ScheduleResponse sched = cfg != null ? cfg.schedule : null;
		if (sched != null)
		{
			if (showBingo && sched.bingos != null)
			{
				for (BingoApiClient.ScheduledBingo b : sched.bingos)
				{
					if (b != null && b.id != pinnedEventId)
					{
						entries.add(new SchedEntry(b.id, "Bingo", b.title, bingoKindLabel(b.format, b.scoringMode),
							b.status, b.startDate, b.endDate, false, b.format, b.scoringMode));
					}
				}
			}
			if (sched.weeklies != null)
			{
				for (BingoApiClient.ScheduledWeekly w : sched.weeklies)
				{
					if (w != null && matchesWeeklyType(w.type))
					{
						String kind = "skill".equalsIgnoreCase(w.type) ? "Skill of the Week" : "Boss of the Week";
						entries.add(new SchedEntry(w.id, w.type, w.title, kind, w.status, w.startDate, w.endDate, true, null, null));
					}
				}
			}
		}
		List<SchedEntry> live = new ArrayList<>();
		List<SchedEntry> upcoming = new ArrayList<>();
		for (SchedEntry en : entries)
		{
			(en.active() ? live : upcoming).add(en);
		}
		live.sort(SCHED_ORDER);
		upcoming.sort(SCHED_ORDER);

		// ---- Live now: pinned event + any live weeklies, wrapped in a green-accented panel so the
		// active block reads as one cohesive, currently-running section. ----
		if (hasPinned || !live.isEmpty())
		{
			int rows = (hasPinned ? 1 : 0) + live.size();
			int sectionH = SECTION_HEADER_H + rows * SCHED_ROW_H + 4;
			groupPanel(items, 0, y, paneWidth, sectionH);
			y += sectionHeader(items, "Live now", 0x4caf50, y, paneWidth);
			if (hasPinned)
			{
				String name = eventName() != null ? eventName() : "Bingo";
				y += scheduleRow(items, "> " + name, bingoKindLabel(eventFormat(), eventScoringMode()),
					"active", pinnedProgressLabel(eventFormat(), eventScoringMode()), null, y, paneWidth, this::openBingo);
			}
			for (SchedEntry en : live)
			{
				Runnable onOpen = en.weekly ? (() -> openLeaderboard(en.id)) : (() -> openScheduledBingo(en));
				y += scheduleRow(items, en.title, en.kind, en.status, stateLabel(en.status),
					dateRange(en.start, en.end), y, paneWidth, onOpen);
			}
			y += 8;
		}

		// ---- Upcoming: plain list, visually secondary to the live block. ----
		if (!upcoming.isEmpty())
		{
			y += sectionHeader(items, "Upcoming", 0x9a9a9a, y, paneWidth);
			for (SchedEntry en : upcoming)
			{
				Runnable onOpen = en.weekly ? (() -> openLeaderboard(en.id)) : (() -> openScheduledBingo(en));
				y += scheduleRow(items, en.title, en.kind, en.status, stateLabel(en.status),
					dateRange(en.start, en.end), y, paneWidth, onOpen);
			}
		}

		if (y == BODY_TOP)
		{
			Widget empty = items.createChild(-1, WidgetType.TEXT);
			empty.setText(sched == null ? "Loading events..." : "No events match this filter.");
			empty.setTextColor(0xaaaaaa);
			empty.setFontId(FONT_PLAIN);
			place(empty, 0, BODY_TOP, paneWidth, 20);
			empty.revalidate();
			y = ClogIds.ROW_H;
		}

		items.setScrollHeight(y + 6);
		items.revalidateScroll();
		updateScrollbar(items);
	}

	/** A unified schedule entry (bingo or weekly) so the home list can be sorted across both. */
	private static final class SchedEntry
	{
		final int id;
		final String type;   // bingo: "Bingo"; weekly: "boss" | "skill"
		final String title;
		final String kind;   // display label
		final String status;
		final String start;
		final String end;
		final boolean weekly;
		final String format; // bingo event layout ("bingo" | "tilerace"); null for weeklies
		final String scoringMode; // bingo scoring ("tiles" | "points"); null for weeklies

		SchedEntry(int id, String type, String title, String kind, String status, String start, String end,
			boolean weekly, String format, String scoringMode)
		{
			this.id = id;
			this.type = type;
			this.title = title;
			this.kind = kind;
			this.status = status;
			this.start = start;
			this.end = end;
			this.weekly = weekly;
			this.format = format;
			this.scoringMode = scoringMode;
		}

		boolean active()
		{
			return "active".equalsIgnoreCase(status);
		}
	}

	// Live first, then by soonest start date (ISO strings sort chronologically); blanks last.
	private static final java.util.Comparator<SchedEntry> SCHED_ORDER = (a, b) -> {
		if (a.active() != b.active())
		{
			return a.active() ? -1 : 1;
		}
		String sa = a.start == null ? "" : a.start;
		String sb = b.start == null ? "" : b.start;
		if (sa.isEmpty() != sb.isEmpty())
		{
			return sa.isEmpty() ? 1 : -1;
		}
		return sa.compareTo(sb);
	};

	// ---- Leaderboard section: BOTW/SOTW ranked standings (fetched from the site) ----

	private void renderLeaderboardView()
	{
		BingoApiClient.WeeklyLeaderboard lb = cachedLeaderboard;

		Widget header = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_HEADER);
		countdownLine = null;
		if (header != null)
		{
			header.deleteAllChildren();
			if (lb != null && lb.competition != null)
			{
				String kind = "skill".equalsIgnoreCase(lb.competition.type) ? "Skill of the Week" : "Boss of the Week";
				bannerLine(header, lb.competition.title == null ? kind : lb.competition.title, COL_ORANGE, 0);
				bannerLine(header, kind + "  <col=666666>·</col>  " + nz(lb.competition.metric), 0xffffff, BANNER_LINE_H);
				// Live countdown instead of a plain date range — onGameTick refreshes this one line.
				countdownStart = lb.competition.startDate;
				countdownEnd = lb.competition.endDate;
				countdownSuffix = "  <col=666666>·</col>  " + lb.total + " players";
				countdownText = countdownLabel(countdownStart, countdownEnd);
				countdownLine = bannerLine(header, countdownText + countdownSuffix, 0xaaaaaa, BANNER_LINE_H * 2);
			}
			else
			{
				bannerLine(header, "Leaderboard", COL_ORANGE, 0);
				bannerLine(header, loadingLeaderboard ? "Loading…" : "Unavailable", 0xaaaaaa, 16);
			}
			header.revalidate();
		}

		Widget items = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
		if (items == null)
		{
			return;
		}
		items.deleteAllChildren();
		int paneWidth = items.getWidth() > 0 ? items.getWidth() : 250;
		int y = BODY_TOP;

		if (lb == null || lb.entries == null)
		{
			Widget m = items.createChild(-1, WidgetType.TEXT);
			m.setText(loadingLeaderboard ? "Loading leaderboard…" : "Couldn't load the leaderboard.");
			m.setTextColor(0xaaaaaa);
			m.setFontId(FONT_PLAIN);
			place(m, 4, y, paneWidth - 8, 16);
			m.revalidate();
			y += ClogIds.ROW_H;
		}
		else if (lb.entries.isEmpty())
		{
			Widget m = items.createChild(-1, WidgetType.TEXT);
			m.setText("No participants yet.");
			m.setTextColor(0xaaaaaa);
			m.setFontId(FONT_PLAIN);
			place(m, 4, y, paneWidth - 8, 16);
			m.revalidate();
			y += ClogIds.ROW_H;
		}
		else
		{
			String me = normalizeRsn(localRsn());
			for (BingoApiClient.LeaderboardEntry e : lb.entries)
			{
				if (e == null)
				{
					continue;
				}
				// Match on whitespace-normalized names — OSRS display names use non-breaking
				// spaces, so a raw equalsIgnoreCase can miss the local player (and mis-flag).
				boolean isMe = !me.isEmpty() && me.equals(normalizeRsn(e.rsn));
				y += leaderboardRow(items, e.rank, e.rsn, e.gained, isMe, y, paneWidth);
			}
		}

		items.setScrollHeight(y + 6);
		items.revalidateScroll();
		updateScrollbar(items);
	}

	private int leaderboardRow(Widget items, int rank, String rsn, long gained, boolean isMe, int y, int paneWidth)
	{
		int nameColor = isMe ? 0xffcc33
			: (rank <= 3 ? (ClogIds.COMPLETE_COLOR.getRGB() & 0xFFFFFF) : 0xe0e0e0);

		int ty = y + 4; // vertical padding inside the taller row

		Widget rk = items.createChild(-1, WidgetType.TEXT);
		rk.setText("<col=999999>" + rank + "</col>");
		rk.setFontId(FONT_PLAIN);
		rk.setTextShadowed(true);
		place(rk, 8, ty, 28, 16);
		rk.setXTextAlignment(ALIGN_RIGHT);
		rk.revalidate();

		Widget nm = items.createChild(-1, WidgetType.TEXT);
		nm.setText((rsn == null ? "?" : rsn) + (isMe ? "  <col=8a8a8a>(you)</col>" : ""));
		nm.setTextColor(nameColor);
		nm.setFontId(FONT_PLAIN);
		nm.setTextShadowed(true);
		place(nm, 44, ty, paneWidth - 44 - 92, 16);
		nm.revalidate();

		Widget gn = items.createChild(-1, WidgetType.TEXT);
		// Floor at 0 so a not-yet-fetched baseline can't show a negative "+-23".
		gn.setText("<col=ffcc33>+" + String.format("%,d", Math.max(0, gained)) + "</col>");
		gn.setFontId(FONT_PLAIN);
		gn.setTextShadowed(true);
		place(gn, paneWidth - 90, ty, 84, 16);
		gn.setXTextAlignment(ALIGN_RIGHT);
		gn.revalidate();
		return 24;
	}

	private String localRsn()
	{
		return client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
	}

	/** Lower-cased, whitespace-collapsed RSN for robust comparisons (handles non-breaking spaces). */
	private static String normalizeRsn(String rsn)
	{
		// Java's \s excludes U+00A0, which OSRS names use — match it explicitly.
		return rsn == null ? "" : rsn.replaceAll("[\\s\\u00a0]+", " ").trim().toLowerCase();
	}

	private boolean matchesWeeklyType(String weeklyType)
	{
		if (eventTypeFilter.isEmpty())
		{
			return true;
		}
		return eventTypeFilter.equalsIgnoreCase(weeklyType);
	}

	/**
	 * A two-line schedule row. {@code state} is the right-hand status word, {@code detail} the
	 * date range (or null). {@code onOpen} non-null makes the row clickable (drills into the event).
	 */
	private int scheduleRow(Widget items, String title, String kind, String status, String state, String detail,
		int y, int paneWidth, Runnable onOpen)
	{
		boolean active = "active".equalsIgnoreCase(status);
		int titleColor = onOpen != null ? COL_ORANGE : (active ? (ClogIds.COMPLETE_COLOR.getRGB() & 0xFFFFFF) : 0xe0e0e0);

		Widget t = items.createChild(-1, WidgetType.TEXT);
		t.setText(title == null || title.isEmpty() ? kind : title);
		t.setTextColor(titleColor);
		t.setTextShadowed(true);
		t.setFontId(FONT_PLAIN);
		place(t, 4, y, paneWidth - 8, 16);
		if (onOpen != null)
		{
			t.setHasListener(true);
			t.setAction(0, "Open");
			t.setOnOpListener((JavaScriptCallback) e -> onOpen.run());
		}
		t.revalidate();

		StringBuilder sub = new StringBuilder("<col=8a8a8a>").append(kind);
		if (state != null && !state.isEmpty())
		{
			sub.append("  <col=5a5a5a>|</col>  <col=8a8a8a>").append(state).append("</col>");
		}
		if (detail != null && !detail.isEmpty())
		{
			sub.append("  <col=5a5a5a>|</col>  <col=8a8a8a>").append(detail).append("</col>");
		}
		Widget subW = items.createChild(-1, WidgetType.TEXT);
		subW.setText(sub.toString());
		subW.setTextShadowed(true);
		subW.setFontId(FONT_PLAIN);
		place(subW, 4, y + 15, paneWidth - 8, 14);
		subW.revalidate();
		return 34;
	}

	private static String stateLabel(String status)
	{
		if ("active".equalsIgnoreCase(status))
		{
			return "live";
		}
		return status == null || status.isEmpty() ? "upcoming" : status;
	}

	private static final String[] MONTHS =
		{"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

	/** "2026-06-30" -> "Jun 30"; returns "" on anything unparseable. */
	private static String prettyDate(String iso)
	{
		if (iso == null || iso.length() < 10)
		{
			return "";
		}
		try
		{
			int m = Integer.parseInt(iso.substring(5, 7));
			int d = Integer.parseInt(iso.substring(8, 10));
			if (m < 1 || m > 12)
			{
				return "";
			}
			return MONTHS[m - 1] + " " + d;
		}
		catch (NumberFormatException ex)
		{
			return "";
		}
	}

	private static String dateRange(String start, String end)
	{
		String s = prettyDate(start);
		String e = prettyDate(end);
		if (s.isEmpty())
		{
			return e;
		}
		return e.isEmpty() ? s : s + " - " + e;
	}

	/**
	 * Relative countdown for a detail header: "Starts in 2d 4h" before it opens, "Ends in 3d 1h"
	 * while live, "Ended" after. Falls back to the plain date range when the end date is unusable.
	 */
	private static String countdownLabel(String start, String end)
	{
		long now = System.currentTimeMillis();
		long s = epochMillis(start);
		long e = epochMillis(end);
		if (s > 0 && now < s)
		{
			return "Starts in " + humanGap(s - now);
		}
		if (e > 0)
		{
			return now < e ? "Ends in " + humanGap(e - now) : "Ended";
		}
		return dateRange(start, end);
	}

	/** ISO date ("2026-06-21") or datetime (UTC) -> epoch millis, or -1 when unparseable. */
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
			return java.time.Instant.parse(iso.endsWith("Z") ? iso : iso + "Z").toEpochMilli();
		}
		catch (Exception ex)
		{
			return -1;
		}
	}

	/** Compact "2d 4h", "5h 12m", "8m", "<1m" for a positive millisecond gap. */
	private static String humanGap(long ms)
	{
		long totalMin = ms / 60000;
		long days = totalMin / 1440;
		long hours = (totalMin % 1440) / 60;
		long mins = totalMin % 60;
		if (days > 0)
		{
			return days + "d " + hours + "h";
		}
		if (hours > 0)
		{
			return hours + "h " + mins + "m";
		}
		return mins > 0 ? mins + "m" : "<1m";
	}

	// ---- classic bingo: square N×N grid (format=bingo, scoringMode=tiles) ----

	private void renderBingoGrid()
	{
		if (!clogOpen || !bingoTabActive)
		{
			return;
		}
		BingoApiClient.BoardResponse board = viewedBoard();

		Widget header = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_HEADER);
		if (header != null)
		{
			header.deleteAllChildren();
			bannerLine(header, boardTitle(board), COL_ORANGE, 0);
			if (board != null && board.tiles != null && !board.tiles.isEmpty())
			{
				int done = 0;
				for (BingoApiClient.BoardTile t : board.tiles)
				{
					if (t != null && t.complete)
					{
						done++;
					}
				}
				// In a read-only preview `complete` means "some team has it"; otherwise it's your team.
				bannerLine(header, "<col=ffff00>" + done + "</col> / " + board.tiles.size()
					+ (board.readOnly ? " tiles claimed" : " tiles done"), 0xffffff, BANNER_LINE_H);
				String sub = board.boardSize + "x" + board.boardSize + " board";
				if (board.readOnly)
				{
					sub += "  <col=666666>·</col>  <col=ffcc33>read-only preview</col>";
				}
				else if (!teamName().isEmpty())
				{
					sub += "  <col=666666>·</col>  " + teamName();
				}
				bannerLine(header, sub, 0xaaaaaa, BANNER_LINE_H * 2);
			}
			else
			{
				bannerLine(header, loadingBoard ? "Loading board..." : "Board unavailable", 0xaaaaaa, BANNER_LINE_H);
			}
			header.revalidate();
		}

		Widget items = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
		if (items == null)
		{
			return;
		}
		items.deleteAllChildren();
		int paneWidth = items.getWidth() > 0 ? items.getWidth() : 250;

		if (board == null || board.tiles == null || board.tiles.isEmpty())
		{
			Widget m = items.createChild(-1, WidgetType.TEXT);
			m.setText(loadingBoard ? "Loading board..." : "Couldn't load the board.");
			m.setTextColor(0xaaaaaa);
			m.setFontId(FONT_PLAIN);
			place(m, 4, BODY_TOP, paneWidth - 8, 16);
			m.revalidate();
			items.setScrollHeight(BODY_TOP + ClogIds.ROW_H);
			items.revalidateScroll();
			updateScrollbar(items);
			return;
		}

		int n = Math.max(1, board.boardSize);
		int gap = 4;
		int cell = (paneWidth - gap * (n - 1)) / n;
		if (cell < 18)
		{
			cell = 18; // floor so a large board stays tappable; it'll overflow + scroll instead
		}
		int gridW = n * cell + gap * (n - 1);
		int startX = Math.max(0, (paneWidth - gridW) / 2);
		int top = BODY_TOP;

		// In a read-only preview there's no "your team", so a claimed cell uses a neutral gold;
		// otherwise it's your team's colour.
		int yourColor = board.readOnly ? 0xc8a24b : teamColorById(board, board.yourTeamId);
		int maxRow = 0;
		for (BingoApiClient.BoardTile t : board.tiles)
		{
			if (t == null)
			{
				continue;
			}
			int cx = startX + t.col * (cell + gap);
			int cy = top + t.row * (cell + gap);
			renderGridCell(items, t, cx, cy, cell, yourColor);
			if (t.row > maxRow)
			{
				maxRow = t.row;
			}
		}

		int gridBottom = top + (maxRow + 1) * (cell + gap);

		items.setScrollHeight(gridBottom + 10);
		items.revalidateScroll();
		updateScrollbar(items);
	}

	/**
	 * Read-only points list for a Leagues-style (points-scored) event — the unenrolled / upcoming view.
	 * The interactive enrolled view is the config-driven accordion ({@link #renderBingoPage()}); this one
	 * renders straight from the fetched board, so it works for events you're not competing in. Points
	 * boards have no grid geometry ({@code boardSize} is the tile count), so we list rather than grid.
	 */
	private void renderPointsPreview()
	{
		if (!clogOpen || !bingoTabActive)
		{
			return;
		}
		BingoApiClient.BoardResponse board = viewedBoard();

		Widget header = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_HEADER);
		if (header != null)
		{
			header.deleteAllChildren();
			bannerLine(header, boardTitle(board), COL_ORANGE, 0);
			if (board != null && board.tiles != null && !board.tiles.isEmpty())
			{
				int totalPoints = 0;
				for (BingoApiClient.BoardTile t : board.tiles)
				{
					if (t != null)
					{
						totalPoints += t.points;
					}
				}
				bannerLine(header, "<col=ffff00>" + board.tiles.size() + "</col> tasks  <col=666666>·</col>  "
					+ "<col=ffff00>" + totalPoints + "</col> pts", 0xffffff, BANNER_LINE_H);
				String sub = "Leagues-style points";
				if (board.readOnly)
				{
					sub += "  <col=666666>·</col>  <col=ffcc33>read-only preview</col>";
				}
				bannerLine(header, sub, 0xaaaaaa, BANNER_LINE_H * 2);
			}
			else
			{
				bannerLine(header, loadingBoard ? "Loading board..." : "Board unavailable", 0xaaaaaa, BANNER_LINE_H);
			}
			header.revalidate();
		}

		// Board still loading → a notice in the body; otherwise reuse the FULL accordion body
		// (renderItems = search bar + Status/Type filters + filtered rich rows). tasks() yields this
		// board's tiles while hubView == POINTS, so the preview is identical to the enrolled view.
		if (board == null || board.tiles == null || board.tiles.isEmpty())
		{
			Widget items = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
			if (items != null)
			{
				items.deleteAllChildren();
				int paneWidth = items.getWidth() > 0 ? items.getWidth() : 250;
				Widget m = items.createChild(-1, WidgetType.TEXT);
				m.setText(loadingBoard ? "Loading board..." : "Couldn't load the board.");
				m.setTextColor(0xaaaaaa);
				m.setFontId(FONT_PLAIN);
				place(m, 4, BODY_TOP, paneWidth - 8, 16);
				m.revalidate();
				items.setScrollHeight(BODY_TOP + ClogIds.ROW_H);
				items.revalidateScroll();
				updateScrollbar(items);
			}
			return;
		}

		renderItems();
	}

	/** Map a fetched board's tiles into the accordion's TaskRow model so previews reuse renderTaskRow. */
	private List<ClogTaskModel.TaskRow> boardTaskRows(BingoApiClient.BoardResponse board)
	{
		List<ClogTaskModel.TaskRow> rows = new java.util.ArrayList<>();
		if (board == null || board.tiles == null)
		{
			return rows;
		}
		for (BingoApiClient.BoardTile t : board.tiles)
		{
			if (t == null)
			{
				continue;
			}
			boolean stat = t.requirement != null && !t.requirement.trim().isEmpty();
			ClogTaskModel.Kind kind = boardTileKind(t, stat);
			boolean hasItemIcon = kind == ClogTaskModel.Kind.DROP || kind == ClogTaskModel.Kind.COLLECTION;
			int itemId = hasItemIcon ? t.itemId : -1;
			int goal = t.requiredAmount;
			// Read-only preview has no per-you progress; only fill the bar if the board flags the tile
			// complete (which in a preview means "some team has it"). forceCompleted drives the colour.
			int current = t.complete ? Math.max(goal, 1) : 0;
			String name = (t.label == null || t.label.isEmpty()) ? ("Tile " + (t.index + 1)) : t.label;
			rows.add(new ClogTaskModel.TaskRow(t.tileId, name, kind, current, goal, itemId,
				t.points, t.description, t.category, t.complete));
		}
		return rows;
	}

	/** Classify a previewed board tile into the same 7-way kind the enrolled config view uses. */
	private static ClogTaskModel.Kind boardTileKind(BingoApiClient.BoardTile t, boolean stat)
	{
		String type = t.tileType == null ? "" : t.tileType.trim().toLowerCase(java.util.Locale.ROOT);
		switch (type)
		{
			case "kill":
				return ClogTaskModel.Kind.KILL;
			case "timed":
				return ClogTaskModel.Kind.TIMED;
			case "drop":
				return (t.itemRequirements != null && !t.itemRequirements.isEmpty())
					? ClogTaskModel.Kind.COLLECTION : ClogTaskModel.Kind.DROP;
			default:
				break;
		}
		if (stat)
		{
			boolean boss = "boss".equalsIgnoreCase(t.statType) || "kc".equalsIgnoreCase(t.statType);
			return boss ? ClogTaskModel.Kind.BOSS : ClogTaskModel.Kind.SKILL;
		}
		// Fallback for older servers that don't send tileType: an item icon ⇒ a drop, else standard.
		if (t.itemId > 0 || (t.itemIds != null && !t.itemIds.isEmpty()))
		{
			return (t.itemRequirements != null && !t.itemRequirements.isEmpty())
				? ClogTaskModel.Kind.COLLECTION : ClogTaskModel.Kind.DROP;
		}
		return ClogTaskModel.Kind.STANDARD;
	}

	/** One grid cell: filled background + outline + item icon (or label) + a transparent click layer. */
	private void renderGridCell(Widget items, BingoApiClient.BoardTile t, int x, int y, int size, int yourColor)
	{
		boolean done = t.complete;
		boolean selected = t.tileId == selectedGridTileId;
		int borderColor = selected ? COL_ORANGE : (done ? yourColor : 0x5a5a5a);

		// Filled background — faint dark for incomplete, a team-colour wash when done.
		Widget bg = items.createChild(-1, WidgetType.RECTANGLE);
		bg.setFilled(true);
		bg.setTextColor(done ? yourColor : 0x2a2620);
		bg.setOpacity(done ? 200 : 150); // 0 = opaque, 255 = transparent
		place(bg, x, y, size, size);
		bg.revalidate();

		// Outline (drawn unfilled on top of the background).
		Widget border = items.createChild(-1, WidgetType.RECTANGLE);
		border.setFilled(false);
		border.setTextColor(borderColor);
		border.setOpacity(selected ? 0 : (done ? 0 : 70));
		place(border, x, y, size, size);
		border.revalidate();

		// Icon for item tiles; an abbreviated label for manual/stat tiles with no item.
		if (t.itemId > 0)
		{
			int iconSize = Math.min(size - 6, 32);
			Widget icon = items.createChild(-1, WidgetType.GRAPHIC);
			icon.setItemId(t.itemId);
			icon.setItemQuantityMode(ItemQuantityMode.NEVER);
			icon.setOpacity(done ? 0 : 90);
			place(icon, x + (size - iconSize) / 2, y + (size - iconSize) / 2, iconSize, iconSize);
			icon.revalidate();
		}
		else
		{
			Widget lbl = items.createChild(-1, WidgetType.TEXT);
			lbl.setText(abbreviate(t.label, size));
			lbl.setTextColor(done ? 0xffffff : 0xb0b0b0);
			lbl.setFontId(FONT_PLAIN);
			lbl.setTextShadowed(true);
			place(lbl, x + 1, y + size / 2 - 7, size - 2, 14);
			lbl.setXTextAlignment(ALIGN_CENTER);
			lbl.setYTextAlignment(ALIGN_CENTER);
			lbl.revalidate();
		}

		// Transparent top layer carries the click so taps land anywhere on the cell (the icon
		// underneath has no listener of its own).
		Widget hit = items.createChild(-1, WidgetType.RECTANGLE);
		hit.setFilled(true);
		hit.setOpacity(255); // invisible but still interactive
		place(hit, x, y, size, size);
		hit.setHasListener(true);
		hit.setAction(0, "Inspect");
		final int tileId = t.tileId;
		hit.setOnOpListener((JavaScriptCallback) e -> openGridTile(tileId));
		hit.revalidate();
	}

	/** Full drill-in page for one grid tile: large icon, status, points, description, who's done it. */
	private void renderGridTileDetail()
	{
		if (!clogOpen || !bingoTabActive)
		{
			return;
		}
		BingoApiClient.BoardResponse board = viewedBoard();
		BingoApiClient.BoardTile sel = null;
		if (board != null && board.tiles != null)
		{
			for (BingoApiClient.BoardTile t : board.tiles)
			{
				if (t != null && t.tileId == selectedGridTileId)
				{
					sel = t;
					break;
				}
			}
		}

		Widget header = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_HEADER);
		if (header != null)
		{
			header.deleteAllChildren();
			bannerLine(header, boardTitle(board), COL_ORANGE, 0);
			bannerLine(header, sel != null ? sel.label : "Tile detail", 0xffffff, BANNER_LINE_H);
			header.revalidate();
		}

		Widget items = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
		if (items == null)
		{
			return;
		}
		items.deleteAllChildren();
		int paneWidth = items.getWidth() > 0 ? items.getWidth() : 250;
		int y = BODY_TOP + 4;

		if (sel == null)
		{
			Widget m = items.createChild(-1, WidgetType.TEXT);
			m.setText("<col=aaaaaa>Tile not found.</col>");
			m.setFontId(FONT_PLAIN);
			place(m, 6, y, paneWidth - 12, 16);
			m.revalidate();
			items.setScrollHeight(y + ClogIds.ROW_H);
			items.revalidateScroll();
			updateScrollbar(items);
			return;
		}

		// Large icon (item) or a stand-in sprite for manual/stat tiles.
		int iconSize = 42;
		Widget icon = items.createChild(-1, WidgetType.GRAPHIC);
		if (sel.itemId > 0)
		{
			icon.setItemId(sel.itemId);
			icon.setItemQuantityMode(ItemQuantityMode.NEVER);
		}
		else
		{
			icon.setSpriteId(ClogIds.STAT_TILE_SPRITE);
		}
		place(icon, 6, y, iconSize, iconSize);
		icon.revalidate();

		int tx = 6 + iconSize + 10;
		int tw = Math.max(60, paneWidth - tx - 6);

		Widget title = items.createChild(-1, WidgetType.TEXT);
		title.setText("<col=ff9040>" + sel.label + "</col>");
		title.setTextShadowed(true);
		title.setFontId(FONT_PLAIN);
		place(title, tx, y + 2, tw, 18);
		title.revalidate();

		Widget statusW = items.createChild(-1, WidgetType.TEXT);
		statusW.setText(sel.complete
			? (board.readOnly ? "<col=00ff80>Completed</col>" : "<col=00ff80>Completed by your team</col>")
			: "<col=ff3c3c>Not completed</col>");
		statusW.setTextShadowed(true);
		statusW.setFontId(FONT_PLAIN);
		place(statusW, tx, y + 22, tw, 16);
		statusW.revalidate();

		StringBuilder meta = new StringBuilder();
		if (sel.points > 0)
		{
			meta.append("<col=ffcc33>").append(sel.points).append(" pts</col>");
		}
		if (sel.requiredAmount > 1)
		{
			if (meta.length() > 0)
			{
				meta.append("  <col=666666>·</col>  ");
			}
			meta.append("<col=aaaaaa>need ").append(sel.requiredAmount).append("</col>");
		}
		if (meta.length() > 0)
		{
			Widget metaW = items.createChild(-1, WidgetType.TEXT);
			metaW.setText(meta.toString());
			metaW.setTextShadowed(true);
			metaW.setFontId(FONT_PLAIN);
			place(metaW, tx, y + 40, tw, 14);
			metaW.revalidate();
		}

		y += iconSize + 12;

		// The actual task for stat tiles (skill XP / boss KC) — the label alone (often a custom
		// name) doesn't say what to do, so surface the requirement prominently.
		if (sel.requirement != null && !sel.requirement.isEmpty())
		{
			Widget reqW = items.createChild(-1, WidgetType.TEXT);
			reqW.setText("<col=999999>Task:</col> <col=ffcc33>" + sel.requirement + "</col>");
			reqW.setTextShadowed(true);
			reqW.setFontId(FONT_PLAIN);
			place(reqW, 6, y, paneWidth - 12, 16);
			reqW.revalidate();
			y += 20;
		}

		if (sel.description != null && !sel.description.isEmpty())
		{
			Widget desc = items.createChild(-1, WidgetType.TEXT);
			desc.setText("<col=c0c0c0>" + sel.description + "</col>");
			desc.setTextShadowed(true);
			desc.setFontId(FONT_PLAIN);
			place(desc, 6, y, paneWidth - 12, 60);
			desc.revalidate();
			y += 64;
		}

		// Compound tile (e.g. a full-moon set): list each required item with its icon and your
		// team's progress, like the website. Simple single-item tiles skip this.
		if (sel.itemRequirements != null && sel.itemRequirements.size() > 1)
		{
			Widget setLbl = items.createChild(-1, WidgetType.TEXT);
			setLbl.setText("<col=999999>Set items:</col>");
			setLbl.setFontId(FONT_PLAIN);
			setLbl.setTextShadowed(true);
			place(setLbl, 6, y, paneWidth - 12, 14);
			setLbl.revalidate();
			y += 16;

			for (PluginConfigResponse.ItemRequirement req : sel.itemRequirements)
			{
				if (req == null)
				{
					continue;
				}
				boolean itemDone = req.currentAmount >= req.requiredAmount;
				Widget ic = items.createChild(-1, WidgetType.GRAPHIC);
				ic.setItemId(req.itemId);
				ic.setItemQuantityMode(ItemQuantityMode.NEVER);
				ic.setOpacity(itemDone ? 0 : 110);
				place(ic, 8, y, 20, 20);
				ic.revalidate();

				Widget nm = items.createChild(-1, WidgetType.TEXT);
				nm.setText(req.name == null ? "?" : req.name);
				nm.setTextColor(itemDone ? (ClogIds.COMPLETE_COLOR.getRGB() & 0xFFFFFF) : 0xe0e0e0);
				nm.setFontId(FONT_PLAIN);
				nm.setTextShadowed(true);
				place(nm, 32, y + 3, paneWidth - 32 - 56, 16);
				nm.revalidate();

				Widget prog = items.createChild(-1, WidgetType.TEXT);
				prog.setText("<col=" + (itemDone ? "00ff80" : "ffcc33") + ">"
					+ Math.max(0, req.currentAmount) + "/" + req.requiredAmount + "</col>");
				prog.setFontId(FONT_PLAIN);
				prog.setTextShadowed(true);
				place(prog, paneWidth - 54, y + 3, 48, 16);
				prog.setXTextAlignment(ALIGN_RIGHT);
				prog.revalidate();
				y += 22;
			}
			y += 4;
		}

		// Who's completed it, across all teams (the per-team board is yours, but a detail page is a
		// good place to surface the full standings for this tile).
		String done = completedByLabel(board, sel.tileId);
		Widget cb = items.createChild(-1, WidgetType.TEXT);
		cb.setText(done.isEmpty()
			? "<col=777777>No team has completed this yet.</col>"
			: "<col=999999>Completed by:</col> " + done);
		cb.setTextShadowed(true);
		cb.setFontId(FONT_PLAIN);
		place(cb, 6, y, paneWidth - 12, 28);
		cb.revalidate();
		y += 30;

		items.setScrollHeight(y + 6);
		items.revalidateScroll();
		updateScrollbar(items);
	}

	/** Comma-separated, team-coloured list of teams that have completed the given tile (may be ""). */
	private static String completedByLabel(BingoApiClient.BoardResponse board, int tileId)
	{
		if (board == null || board.teams == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (BingoApiClient.BoardTeam tm : board.teams)
		{
			if (tm == null || tm.completedTileIds == null || !tm.completedTileIds.contains(tileId))
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append("<col=999999>, </col>");
			}
			sb.append("<col=").append(String.format("%06x", parseColor(tm.color, 0xffffff)))
				.append(">").append(tm.name == null ? "?" : tm.name).append("</col>");
		}
		return sb.toString();
	}

	// ---- tile race: ordered track with a team pip on each team's furthest-reached tile ----

	private static final int RACE_ROW_H = 26;

	private void renderTileRace()
	{
		if (!clogOpen || !bingoTabActive)
		{
			return;
		}
		BingoApiClient.BoardResponse board = viewedBoard();

		Widget header = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_HEADER);
		if (header != null)
		{
			header.deleteAllChildren();
			bannerLine(header, board != null ? boardTitle(board) : "Tile Race", COL_ORANGE, 0);
			if (board != null && board.tiles != null)
			{
				bannerLine(header, "Tile Race  <col=666666>·</col>  " + board.tiles.size() + " tiles",
					0xffffff, BANNER_LINE_H);
				int teamCount = board.teams != null ? board.teams.size() : 0;
				String line = teamCount + " team" + (teamCount == 1 ? "" : "s") + " racing";
				if (board.readOnly)
				{
					line += "  <col=666666>·</col>  <col=ffcc33>read-only preview</col>";
				}
				bannerLine(header, line, 0xaaaaaa, BANNER_LINE_H * 2);
			}
			else
			{
				bannerLine(header, loadingBoard ? "Loading race..." : "Race unavailable", 0xaaaaaa, BANNER_LINE_H);
			}
			header.revalidate();
		}

		Widget items = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
		if (items == null)
		{
			return;
		}
		items.deleteAllChildren();
		int paneWidth = items.getWidth() > 0 ? items.getWidth() : 250;
		int y = BODY_TOP;

		if (board == null || board.tiles == null || board.tiles.isEmpty())
		{
			Widget m = items.createChild(-1, WidgetType.TEXT);
			m.setText(loadingBoard ? "Loading race..." : "Couldn't load the race.");
			m.setTextColor(0xaaaaaa);
			m.setFontId(FONT_PLAIN);
			place(m, 4, y, paneWidth - 8, 16);
			m.revalidate();
			items.setScrollHeight(y + ClogIds.ROW_H);
			items.revalidateScroll();
			updateScrollbar(items);
			return;
		}

		// Tiles in race order.
		List<BingoApiClient.BoardTile> ordered = new ArrayList<>();
		for (BingoApiClient.BoardTile t : board.tiles)
		{
			if (t != null)
			{
				ordered.add(t);
			}
		}
		ordered.sort(java.util.Comparator.comparingInt(t -> t.index));

		// tileId -> race position, to fold each team's completed set into a furthest position.
		java.util.Map<Integer, Integer> indexByTile = new java.util.HashMap<>();
		for (int i = 0; i < ordered.size(); i++)
		{
			indexByTile.put(ordered.get(i).tileId, i);
		}

		// Bucket teams by the furthest race index they've reached (-1 = not started yet).
		java.util.Map<Integer, List<BingoApiClient.BoardTeam>> teamsAt = new java.util.HashMap<>();
		if (board.teams != null)
		{
			for (BingoApiClient.BoardTeam tm : board.teams)
			{
				if (tm == null)
				{
					continue;
				}
				int furthest = -1;
				if (tm.completedTileIds != null)
				{
					for (Integer tileId : tm.completedTileIds)
					{
						Integer idx = indexByTile.get(tileId);
						if (idx != null && idx > furthest)
						{
							furthest = idx;
						}
					}
				}
				teamsAt.computeIfAbsent(furthest, k -> new ArrayList<>()).add(tm);
			}
		}

		// "Start" lane for any teams that haven't completed a tile yet.
		List<BingoApiClient.BoardTeam> atStart = teamsAt.get(-1);
		if (atStart != null && !atStart.isEmpty())
		{
			Widget s = items.createChild(-1, WidgetType.TEXT);
			s.setText("<col=777777>Start</col>");
			s.setFontId(FONT_PLAIN);
			s.setTextShadowed(true);
			place(s, 8, y + 6, 60, 16);
			s.revalidate();
			renderRacePips(items, atStart, paneWidth, y);
			y += RACE_ROW_H;
		}

		for (int i = 0; i < ordered.size(); i++)
		{
			y += renderRaceRow(items, i + 1, ordered.get(i), teamsAt.get(i), y, paneWidth);
		}

		items.setScrollHeight(y + 6);
		items.revalidateScroll();
		updateScrollbar(items);
	}

	private int renderRaceRow(Widget items, int number, BingoApiClient.BoardTile t,
		List<BingoApiClient.BoardTeam> teamsHere, int y, int paneWidth)
	{
		int ty = y + 4;

		Widget num = items.createChild(-1, WidgetType.TEXT);
		num.setText("<col=999999>" + number + "</col>");
		num.setFontId(FONT_PLAIN);
		num.setTextShadowed(true);
		place(num, 6, ty, 22, 16);
		num.setXTextAlignment(ALIGN_RIGHT);
		num.revalidate();

		Widget icon = items.createChild(-1, WidgetType.GRAPHIC);
		if (t.itemId > 0)
		{
			icon.setItemId(t.itemId);
			icon.setItemQuantityMode(ItemQuantityMode.NEVER);
		}
		else
		{
			icon.setSpriteId(ClogIds.STAT_TILE_SPRITE);
		}
		place(icon, 32, ty - 2, 20, 20);
		icon.revalidate();

		Widget name = items.createChild(-1, WidgetType.TEXT);
		name.setText(t.label);
		name.setTextColor(t.complete ? (ClogIds.COMPLETE_COLOR.getRGB() & 0xFFFFFF) : 0xe0e0e0);
		name.setFontId(FONT_PLAIN);
		name.setTextShadowed(true);
		place(name, 58, ty, Math.max(40, paneWidth - 58 - 70), 16);
		name.revalidate();

		if (teamsHere != null && !teamsHere.isEmpty())
		{
			renderRacePips(items, teamsHere, paneWidth, y);
		}
		return RACE_ROW_H;
	}

	/** A right-aligned row of small team-coloured squares (one per team) on a race row. */
	private void renderRacePips(Widget items, List<BingoApiClient.BoardTeam> teams, int paneWidth, int y)
	{
		int pip = 10;
		int gap = 3;
		int count = teams.size();
		int totalW = count * pip + (count - 1) * gap;
		int startX = paneWidth - 6 - totalW;
		int py = y + 7;
		for (int i = 0; i < count; i++)
		{
			BingoApiClient.BoardTeam tm = teams.get(i);
			Widget dot = items.createChild(-1, WidgetType.RECTANGLE);
			dot.setFilled(true);
			dot.setTextColor(parseColor(tm.color, 0xffffff));
			dot.setOpacity(0);
			place(dot, startX + i * (pip + gap), py, pip, pip);
			if (tm.name != null)
			{
				dot.setName(tm.name); // hover shows the team name
			}
			dot.revalidate();
		}
	}

	// ---- board view helpers ----

	private String teamName()
	{
		PluginConfigResponse cfg = plugin.getPluginConfig();
		return (cfg != null && cfg.team != null && cfg.team.name != null) ? cfg.team.name : "";
	}

	private static int teamColorById(BingoApiClient.BoardResponse board, int teamId)
	{
		int fallback = ClogIds.COMPLETE_COLOR.getRGB() & 0xFFFFFF;
		if (board != null && board.teams != null)
		{
			for (BingoApiClient.BoardTeam t : board.teams)
			{
				if (t != null && t.teamId == teamId)
				{
					return parseColor(t.color, fallback);
				}
			}
		}
		return fallback;
	}

	/** Parse "#rrggbb" / "rrggbb" / "#rgb" to an 0xRRGGBB int; returns fallback on anything else. */
	private static int parseColor(String hex, int fallback)
	{
		if (hex == null)
		{
			return fallback;
		}
		String s = hex.trim();
		if (s.startsWith("#"))
		{
			s = s.substring(1);
		}
		if (s.length() == 3)
		{
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < 3; i++)
			{
				b.append(s.charAt(i)).append(s.charAt(i));
			}
			s = b.toString();
		}
		if (s.length() != 6)
		{
			return fallback;
		}
		try
		{
			return Integer.parseInt(s, 16) & 0xFFFFFF;
		}
		catch (NumberFormatException ex)
		{
			return fallback;
		}
	}

	/** Trim a tile label to roughly fit a grid cell of the given pixel width (no trailing-space "."). */
	private static String abbreviate(String label, int cellSize)
	{
		if (label == null)
		{
			return "";
		}
		// ~6px per glyph, less a little inset; floor at 3 so tiny cells still show something.
		int maxChars = Math.max(3, (cellSize - 6) / 6);
		if (label.length() <= maxChars)
		{
			return label;
		}
		String cut = label.substring(0, maxChars).trim();
		return cut.isEmpty() ? label.substring(0, maxChars) : cut + ".";
	}

	// ---- task data ----

	private List<ClogTaskModel.TaskRow> tasks()
	{
		// In the unenrolled points preview the tasks come from the fetched board, not your config, so
		// the accordion body (renderItems/renderBodyFilters — search + Status/Type filters + rich rows)
		// renders the previewed event's tiles instead of your own.
		if (hubView == HubView.POINTS)
		{
			return boardTaskRows(viewedBoard());
		}
		return ClogTaskModel.build(plugin.getPluginConfig());
	}

	/** Difficulty bands to filter by — from the previewed board in POINTS view, else your config. */
	private List<PluginConfigResponse.TierBand> tierBands()
	{
		List<PluginConfigResponse.TierBand> served;
		if (hubView == HubView.POINTS)
		{
			BingoApiClient.BoardResponse b = viewedBoard();
			served = b == null ? null : b.tiers;
		}
		else
		{
			PluginConfigResponse cfg = plugin.getPluginConfig();
			served = cfg == null ? null : cfg.tiers;
		}
		return ClogTaskModel.tierBandsOrDefault(served);
	}

	/** The selected tier key, or "" if it's blank or no longer a valid band (e.g. admin retuned them). */
	private String effectiveTier(List<PluginConfigResponse.TierBand> bands)
	{
		if (tierFilter.isEmpty())
		{
			return "";
		}
		for (PluginConfigResponse.TierBand b : bands)
		{
			if (b != null && tierFilter.equalsIgnoreCase(b.key))
			{
				return tierFilter;
			}
		}
		return "";
	}

	// ---- helpers ----

	private int colorFor(ClogTaskModel.Status status)
	{
		switch (status)
		{
			case COMPLETED:
				return ClogIds.COMPLETE_COLOR.getRGB() & 0xFFFFFF;
			case IN_PROGRESS:
				return ClogIds.IN_PROGRESS_COLOR.getRGB() & 0xFFFFFF;
			case NOT_STARTED:
			default:
				return ClogIds.NOT_STARTED_COLOR.getRGB() & 0xFFFFFF;
		}
	}

	private static String hex(int rgb)
	{
		return String.format("%06x", rgb & 0xFFFFFF);
	}

	private String eventName()
	{
		PluginConfigResponse cfg = plugin.getPluginConfig();
		return (cfg != null && cfg.event != null) ? cfg.event.name : null;
	}

	private boolean isEnabled()
	{
		return config.bingoClogTab();
	}

	/** Schedule-row label for a bingo-family event, by format + scoring mode. */
	private static String bingoKindLabel(String format, String scoringMode)
	{
		if ("tilerace".equalsIgnoreCase(format))
		{
			return "Tile race";
		}
		if ("points".equalsIgnoreCase(scoringMode))
		{
			return "Bingo (points)";
		}
		return "Bingo";
	}

	private static boolean isAdventureLogGroup(int groupId)
	{
		return groupId == InterfaceID.ADVENTURE_LOG;
	}

	private static String nz(String s)
	{
		return s == null ? "" : s;
	}

	private static String pretty(String enumName)
	{
		String s = enumName.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
