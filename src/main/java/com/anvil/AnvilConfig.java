package com.anvil;

import net.runelite.client.config.*;

@ConfigGroup("osrsbingo")
public interface AnvilConfig extends Config {
	// ---- Sections (collapsible groups, top to bottom) ----
	// Split by purpose: Setup + Bingo are event-participation; the "Notifications:" sections are
	// clan-wide Discord posts that fire with or without an active bingo.

	@ConfigSection(
		name = "Bingo",
		description = "In-event behaviour — only matters while you're in a bingo.",
		position = 2
	)
	String bingoSection = "bingoSection";

	@ConfigSection(
		name = "Notifications",
		description = "What you post to your clan's Discord (drops, pets, deaths, levels, achievements) and which chat is hidden in your screenshots are set on the Anvil website: Settings > Announcements (a link is in the Anvil panel's settings tab).",
		position = 3
	)
	String deathsSection = "deathsSection";

	@ConfigSection(
		name = "Player lookup",
		description = "The \"Anvil\" option on player names, and where it appears.",
		position = 1
	)
	String lookupSection = "lookupSection";

	@ConfigSection(
		name = "Profile sync",
		description = "Send your collection log and best times to your Anvil profile.",
		position = 9,
		closedByDefault = true
	)
	String profileSection = "profileSection";

	@ConfigSection(
		name = "General",
		description = "Anvil's own lines in your chatbox, and Supporters' names. Chat colours are on the Anvil website: Settings > In-game chat.",
		position = 6,
		closedByDefault = true
	)
	String chatSection = "chatSection";

	@ConfigSection(
		name = "Anvil chat",
		description = "Public, clan, friends chat and private lines that Anvil players send through Anvil, for when the game doesn't show them.",
		position = 7,
		closedByDefault = true
	)
	String relaySection = "relaySection";

	@ConfigSection(
		name = "Event chat",
		description = "Your boards' event chat in the game chatbox. Which boards show here is set on the Anvil website: Settings > In-game chat.",
		position = 8,
		closedByDefault = true
	)
	String eventSection = "eventSection";

	@ConfigItem(
		keyName = "chatFeed",
		name = "Anvil messages in chat",
		description = "Print the feed in your game chatbox as it happens: big drops, pets and achievements from boards whose host turned it on and clans you guest in, your teammates' tile progress, and other teams' finished tiles. The side panel's Feed tab works either way. Pick what shows on your Anvil profile.",
		position = 1,
		section = "chatSection"
	)
	default boolean chatFeed() {
		return true;
	}

	/** Supporters' colours: off, in chat, or in chat and on right-click menus (never in PvP areas). */
	enum Names {
		Off, Chat, Chat_menus;

		@Override
		public String toString() {
			return name().replace("_", " + ");
		}
	}

	@ConfigItem(
		keyName = "supporterNames",
		name = "Supporter names",
		description = "Show Anvil Supporters' name colours and badges in chat (and on your friends list and channel member lists while they are online), and on their name when you right-click them. Never in PvP areas. The list of names is downloaded from Anvil; nothing about who you see is sent.",
		position = 2,
		section = "chatSection"
	)
	default Names supporterNames() {
		return Names.Chat_menus;
	}

	/** Anvil chat: off, see others' lines, also send your own, also your private messages. */
	enum Relay {
		Off, See_others, See_send, See_send_PMs;

		@Override
		public String toString() {
			return this == See_others ? "See others" : name().replace("_", " + ");
		}
	}

	@ConfigItem(
		keyName = "anvilChat",
		name = "Anvil chat",
		description = "See: lines other Anvil players sent through Anvil show when your game didn't show them (they carry a small anvil). Send: a copy of your own public, clan, friends chat and guest clan lines goes through Anvil too (your chat itself is never changed). PMs: your private messages as well, only to players who have Anvil chat on.",
		position = 1,
		section = "relaySection"
	)
	default Relay anvilChat() {
		return Relay.See_others;
	}

	@ConfigItem(
		keyName = "eventChatbox",
		name = "Event chat in chatbox",
		description = "Print your boards' event chat in the game chatbox too. Off, it stays in the panel's Feed tab, with a dot when something new comes in.",
		position = 1,
		section = "eventSection"
	)
	default boolean eventChatbox() {
		return true;
	}

	@ConfigItem(
		keyName = "eventChatKey",
		name = "Event chat key",
		description = "Opens a chat input for your board's event chat; press it again to switch channel. You can also right-click the Clan chat tab.",
		position = 2,
		section = "eventSection"
	)
	default Keybind eventChatKey() {
		return new Keybind(java.awt.event.KeyEvent.VK_INSERT, 0);
	}

	// ---- Setup ----

	// No Site URL: the plugin only talks to https://anvilosrs.com (BingoApiClient.CANONICAL_SITE).

	@ConfigItem(
		keyName = "playerToken",
		name = "Account Token",
		description = "Set by Sign in in the Anvil side panel.",
		position = 2,
		secret = true,
		// Not shown in settings. RuneLite only draws a plugin's settings page when it's opened, so a page
		// left open across a sign-in kept showing an empty box — and its focus-out save could write that
		// empty value back and sign the member out. The side panel owns signing in and out instead.
		hidden = true
	)
	default String playerToken() {
		return "";
	}

	@ConfigItem(
		keyName = "profileLookupMenu",
		name = "Anvil lookup on players",
		description = "Adds an \"Anvil\" option on player names that opens their profile; off hides it everywhere.",
		position = 0,
		section = "lookupSection"
	)
	default boolean profileLookupMenu() {
		return true;
	}

	@ConfigItem(
		keyName = "lookupPlayers",
		name = "Players in the world",
		description = "Right-clicking a player standing near you.",
		position = 1,
		section = "lookupSection"
	)
	default boolean lookupPlayers() {
		return true;
	}

	@ConfigItem(
		keyName = "lookupChat",
		name = "Chatbox and private messages",
		description = "Names in the chatbox and private chat; never the left-click option.",
		position = 2,
		section = "lookupSection"
	)
	default boolean lookupChat() {
		return true;
	}

	@ConfigItem(
		keyName = "lookupClan",
		name = "Clan panels",
		description = "Your clan and guest-clan member lists.",
		position = 3,
		section = "lookupSection"
	)
	default boolean lookupClan() {
		return true;
	}

	@ConfigItem(
		keyName = "lookupFriendsChat",
		name = "Friends chat",
		description = "The friends chat channel member list.",
		position = 4,
		section = "lookupSection"
	)
	default boolean lookupFriendsChat() {
		return true;
	}

	@ConfigItem(
		keyName = "lookupLists",
		name = "Friends, ignore and party lists",
		description = "Your friends and ignore lists and the raid party panel.",
		position = 5,
		section = "lookupSection"
	)
	default boolean lookupLists() {
		return true;
	}

	// ---- Bingo ----

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show Overlay",
		description = "Show the date verification overlay on screen.",
		position = 2,
		section = "bingoSection"
	)
	default boolean showOverlay() {
		return true;
	}

	@ConfigItem(
		keyName = "teamCompletionBanner",
		name = "Team completion popups",
		description = "Show a banner when anyone on your team completes a tile.",
		position = 3,
		section = "bingoSection"
	)
	default boolean teamCompletionBanner() {
		return true;
	}

	@ConfigItem(
		keyName = "selfTileBanner",
		name = "My tile popups",
		description = "Show a popup when your own drop progresses or completes a tile; off still tracks quietly.",
		position = 4,
		section = "bingoSection"
	)
	default boolean selfTileBanner() {
		return true;
	}

	@ConfigItem(
		keyName = "bannerSound",
		name = "Banner sound",
		// No longer names a folder to drop files into. The clips live in the plugin's own directory
		// now, which is not somewhere to send people rummaging — "Add sounds" copies them in, and
		// "Copy folder path" in the same panel hands over the location for anyone who wants it.
		description = "Play your banner and mission clips (added from the Anvil side panel); off silences both.",
		position = 5,
		section = "bingoSection"
	)
	default boolean bannerSound() {
		return true;
	}

	@ConfigItem(
		keyName = "bannerSoundVolume",
		name = "Banner volume",
		description = "Volume of your banner and mission clips (0–100).",
		position = 6,
		section = "bingoSection"
	)
	default int bannerSoundVolume() {
		return 70;
	}

	@ConfigItem(
		keyName = "bannerSoundClip",
		name = "Banner sound clip",
		// Hidden from the settings panel: the play-cycle allowlist is managed via the Anvil side panel's
		// sound toggles, which read/write this key. Kept as a config key so the selection persists/syncs.
		description = "Banner clips to cycle (blank = all), managed in the side panel.",
		position = 7,
		section = "bingoSection",
		hidden = true
	)
	default String bannerSoundClip() {
		return "";
	}

	@ConfigItem(
		keyName = "autoStartShot",
		name = "Auto starting shot",
		description = "Take an event's starting shot automatically once you stand on its pinned spot.",
		position = 8,
		section = "bingoSection"
	)
	default boolean autoStartShot() {
		return true;
	}

	@ConfigItem(
		keyName = "startSpotGuide",
		name = "Starting shot guide",
		description = "While a starting shot is owed: an on-screen card with its checks, a hint arrow and a world map pin to the spot. Gone once it's filed.",
		position = 9,
		section = "bingoSection"
	)
	default boolean startSpotGuide() {
		return true;
	}

	@ConfigItem(
		keyName = "trackerOnePanel",
		name = "Pinned tiles in one panel",
		description = "Show every tile pinned with \"Track on screen\" in one compact movable panel: each tile's name and your team's progress. Off: a separate, detailed widget per tile (members and other teams).",
		position = 10,
		section = "bingoSection"
	)
	default boolean trackerOnePanel() {
		return true;
	}

	@ConfigItem(
		keyName = "shareActivity",
		name = "Share clan posts",
		description = "Post your drops, pets, deaths, levels and achievements to your clan's Discord. Which ones post, and from when, is set on the Anvil website: Settings > Announcements.",
		position = 0,
		section = "deathsSection"
	)
	default boolean shareActivity() {
		return true;
	}

	@ConfigItem(
		keyName = "shareSeasonal",
		name = "Share seasonal activity",
		description = "On Leagues, Deadman and other seasonal worlds, share your progress (relics, areas, tasks, with a screenshot) to your clan. What gets posted is set by your clan on the Anvil website.",
		position = 1,
		section = "deathsSection"
	)
	default boolean shareSeasonal() {
		return true;
	}

	// ---- Profile sync ----

	@ConfigItem(
		keyName = "autoSyncClanRoster",
		name = "Keep the clan roster in sync",
		description = "Admins: push the in-game clan roster to the site when you log in.",
		position = 0,
		section = "profileSection"
	)
	default boolean autoSyncClanRoster() {
		return true;
	}

	@ConfigItem(
		keyName = "syncClanCoffer",
		name = "Sync Clan Coffer",
		description = "Send Clan Coffer deposits and withdrawals to your clan site, from your own clan chat line and the coffer screen.",
		position = 1,
		section = "profileSection"
	)
	default boolean syncClanCoffer() {
		return true;
	}

	@ConfigItem(
		keyName = "syncClog",
		name = "Sync collection log",
		description = "Send the collection-log pages you open to your profile.",
		position = 2,
		section = "profileSection"
	)
	default boolean syncClog() {
		return true;
	}

	@ConfigItem(
		keyName = "autoFullClogSync",
		name = "Sync the whole log on open",
		description = "Read your whole collection log when you open it, not just the pages you view.",
		position = 4,
		section = "profileSection"
	)
	default boolean autoFullClogSync() {
		return true;
	}

	@ConfigItem(
		keyName = "syncPersonalBests",
		name = "Sync personal bests",
		description = "Send your best boss and raid times as you set them.",
		position = 3,
		section = "profileSection"
	)
	default boolean syncPersonalBests() {
		return true;
	}

	@ConfigItem(
		keyName = "importRuneLitePbs",
		name = "Import existing bests once",
		description = "Once per account, copy the bests RuneLite already recorded locally.",
		position = 5,
		section = "profileSection"
	)
	default boolean importRuneLitePbs() {
		return true;
	}

	@ConfigItem(
		keyName = "shareMoments",
		name = "Share highlights with the clan",
		description = "Show your pets, uniques, big drops and deaths on the clan site's feed during events, and in the Anvil chat feed of players who can see them.",
		position = 6,
		section = "profileSection"
	)
	default boolean shareMoments() {
		return true;
	}
}
