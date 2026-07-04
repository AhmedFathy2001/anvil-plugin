package com.anvil;

import java.awt.Color;

/**
 * Single source of truth for every fragile collection-log interface id the plugin pokes at.
 *
 * The in-game collection log is built by Jagex CS2 scripts; the script / widget / varbit ids
 * below shift whenever those scripts are reshuffled in a game update. Keeping them all here
 * (rather than sprinkled through {@link ClogTabController}) means a post-update fix is a
 * one-file edit. Verified against RuneLite api 1.12.24 — see plugin_clog_plan.md §6.
 *
 * Named RuneLite constants we rely on elsewhere (resolved at compile time, listed for the
 * next maintainer):
 *   net.runelite.api.ScriptID.COLLECTION_DRAW_LIST            = 2731
 *   net.runelite.api.widgets.InterfaceID.COLLECTION_LOG       = 621
 *   net.runelite.api.widgets.InterfaceID.ADVENTURE_LOG        = 187
 *   net.runelite.api.widgets.ComponentID.COLLECTION_LOG_{TABS,ENTRY_HEADER,ENTRY_ITEMS}
 */
final class ClogIds
{
	private ClogIds() {}

	// Varbits tracking the player's active native tab / page inside the clog. No named
	// RuneLite constant exists in 1.12.24, so they're read raw via client.getVarbitValue().
	static final int VARBIT_ACTIVE_TAB = 6905;
	static final int VARBIT_ACTIVE_PAGE = 6906;

	// Label shown on the injected category tab. Branded "Anvil" (not "Bingo") so it can be reused
	// for other Anvil content (schedule, weekly comps) when no bingo event is running.
	static final String BINGO_TAB_NAME = "Anvil";

	// ---- Live geometry, measured from the real clog via the debug dump (RuneLite 1.12.28) ----
	// Native tab row: COLLECTION_LOG_TABS is 500 wide, 20 tall, with 5 static tabs at width 97 /
	// step 100. We re-space all of them to width 80 / step 83 to fit a 6th "Bingo" tab.
	static final int TAB_HEIGHT = 20;
	static final int TAB_STEP = 83;
	static final int TAB_WIDTH = 80;
	static final int BINGO_TAB_INDEX = 5; // 6th slot (after the 5 natives)
	// Tab background is a 3-slice sprite (left cap / stretched middle / right cap). Measured
	// from the live clog: a SELECTED tab uses 2283/2284, an UNSELECTED tab uses 2285/2286.
	static final int TAB_CAP_SELECTED = 2283;
	static final int TAB_MID_SELECTED = 2284;
	static final int TAB_CAP_UNSELECTED = 2285;
	static final int TAB_MID_UNSELECTED = 2286;
	static final int TAB_CAP_W = 20;
	// Left column (where the native category list lives) — we hide that list while the Bingo
	// tab is active and drop our filter column here instead. Container path: ROOT.s[0].s[2].
	static final int LEFT_LIST_CHILD = 1;
	static final int LEFT_COL_W = 200;

	// Item grid inside COLLECTION_LOG_ENTRY_ITEMS: 36x32 cells, 42px x-step, 36px y-step, 6 cols.
	static final int ITEM_CELL_W = 36;
	static final int ITEM_CELL_H = 32;
	static final int ITEM_STEP_X = 42;
	static final int ITEM_STEP_Y = 36;
	static final int ITEM_COLS = 6;
	// Reserve a strip at the top of the items pane for the Leagues-style filter toggles.
	static final int FILTER_ROW_H = 18;

	// Leagues-style vertical task rows: [icon] [name / "Reward: N points"].
	static final int ROW_H = 36;
	static final int ROW_ICON = 32;
	static final int ROW_TEXT_X = 40;

	// A sprite to stand in for stat tiles (which have no inventory item icon): the
	// collection-log book icon. Confirmed present as SpriteID.HISCORE_COLLECTIONS_LOGGED.
	static final int STAT_TILE_SPRITE = 6390;

	// Entry colours mirroring the real clog palette (orange title, green obtained, red missing).
	static final Color TITLE_COLOR = new Color(255, 152, 31);
	static final Color COMPLETE_COLOR = new Color(0, 255, 128);
	static final Color IN_PROGRESS_COLOR = new Color(255, 215, 0);
	static final Color NOT_STARTED_COLOR = new Color(255, 60, 60);
}
