package com.anvil;

import java.awt.Color;
import net.runelite.client.config.*;

@ConfigGroup("osrsbingo")
public interface AnvilConfig extends Config
{
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
		name = "Notifications: Deaths & kills",
		description = "Clan Discord posts for your deaths and PvP kills.",
		position = 3
	)
	String deathsSection = "deathsSection";

	@ConfigSection(
		name = "Notifications: Drops & pets",
		description = "Clan Discord posts for valuable or rare drops and pets.",
		position = 4
	)
	String dropsSection = "dropsSection";

	@ConfigSection(
		name = "Notifications: Combat achievements",
		description = "Clan Discord posts for achievements, levels, quests and log slots.",
		position = 5,
		closedByDefault = true
	)
	String caSection = "caSection";

	@ConfigSection(
		name = "Player lookup",
		description = "The \"Anvil\" option on player names, and where it appears.",
		position = 1
	)
	String lookupSection = "lookupSection";

	@ConfigSection(
		name = "Profile sync",
		description = "Send your collection log and best times to your Anvil profile.",
		position = 7,
		closedByDefault = true
	)
	String profileSection = "profileSection";

	@ConfigSection(
		name = "Chat messages",
		description = "How Anvil's own lines look in your chatbox.",
		position = 6,
		closedByDefault = true
	)
	String chatSection = "chatSection";

	@ConfigItem(
		keyName = "chatPrefixColor",
		name = "[Anvil] tag colour",
		description = "Colour of the [Anvil] tag at the start of each line.",
		position = 1,
		section = "chatSection"
	)
	default Color chatPrefixColor()
	{
		return new Color(0xFFD700);
	}

	@ConfigItem(
		keyName = "chatTextColor",
		name = "Message colour",
		description = "Colour of the message after the tag. Pick something dark on an opaque chatbox.",
		position = 2,
		section = "chatSection"
	)
	default Color chatTextColor()
	{
		return Color.WHITE;
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
	default String playerToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "profileLookupMenu",
		name = "Anvil lookup on players",
		description = "Adds an \"Anvil\" option on player names that opens their profile; off hides it everywhere.",
		position = 0,
		section = "lookupSection"
	)
	default boolean profileLookupMenu()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lookupPlayers",
		name = "Players in the world",
		description = "Right-clicking a player standing near you.",
		position = 1,
		section = "lookupSection"
	)
	default boolean lookupPlayers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lookupChat",
		name = "Chatbox and private messages",
		description = "Names in the chatbox and private chat; never the left-click option.",
		position = 2,
		section = "lookupSection"
	)
	default boolean lookupChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lookupClan",
		name = "Clan panels",
		description = "Your clan and guest-clan member lists.",
		position = 3,
		section = "lookupSection"
	)
	default boolean lookupClan()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lookupFriendsChat",
		name = "Friends chat",
		description = "The friends chat channel member list.",
		position = 4,
		section = "lookupSection"
	)
	default boolean lookupFriendsChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lookupLists",
		name = "Friends, ignore and party lists",
		description = "Your friends and ignore lists and the raid party panel.",
		position = 5,
		section = "lookupSection"
	)
	default boolean lookupLists()
	{
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
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "teamCompletionBanner",
		name = "Team completion popups",
		description = "Show a banner when anyone on your team completes a tile.",
		position = 3,
		section = "bingoSection"
	)
	default boolean teamCompletionBanner()
	{
		return true;
	}

	@ConfigItem(
		keyName = "selfTileBanner",
		name = "My tile popups",
		description = "Show a popup when your own drop progresses or completes a tile; off still tracks quietly.",
		position = 4,
		section = "bingoSection"
	)
	default boolean selfTileBanner()
	{
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
	default boolean bannerSound()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bannerSoundVolume",
		name = "Banner volume",
		description = "Volume of your banner and mission clips (0–100).",
		position = 6,
		section = "bingoSection"
	)
	default int bannerSoundVolume()
	{
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
	default String bannerSoundClip()
	{
		return "";
	}

	@ConfigItem(
		keyName = "autoStartShot",
		name = "Auto starting shot",
		description = "Take an event's starting shot automatically once you stand on its pinned spot.",
		position = 8,
		section = "bingoSection"
	)
	default boolean autoStartShot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "startSpotGuide",
		name = "Starting shot guide",
		description = "While a starting shot is owed: an on-screen card with its checks, a hint arrow and a world map pin to the spot. Gone once it's filed.",
		position = 9,
		section = "bingoSection"
	)
	default boolean startSpotGuide()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackerOnePanel",
		name = "Pinned tiles in one panel",
		description = "Show every tile pinned with \"Track on screen\" in one compact movable panel: each tile's name and your team's progress. Off: a separate, detailed widget per tile (members and other teams).",
		position = 10,
		section = "bingoSection"
	)
	default boolean trackerOnePanel()
	{
		return true;
	}


	// ---- Notifications: Deaths & kills ----

	@ConfigItem(
		keyName = "notifyDeaths",
		name = "Notify on death",
		description = "Post a screenshot to the clan deaths channel when you die.",
		position = 1,
		section = "deathsSection"
	)
	default boolean notifyDeaths()
	{
		return true;
	}

	@ConfigItem(
		keyName = "deathMessage",
		name = "Death message",
		description = "Your death message. Use {name} for your RSN.",
		position = 2,
		section = "deathsSection"
	)
	default String deathMessage()
	{
		return "{name} just died!";
	}

	@ConfigItem(
		keyName = "notifyPvpKills",
		name = "Notify on PvP kill",
		description = "Post a screenshot to the clan PvP channel when you kill a player.",
		position = 3,
		section = "deathsSection"
	)
	default boolean notifyPvpKills()
	{
		return false;
	}

	// ---- Notifications: Drops & pets ----


	@ConfigItem(
		keyName = "notifyRareDrops",
		name = "Notify on rare drops",
		description = "Post valuable or very rare drops to the clan drops channel.",
		position = 1,
		section = "dropsSection"
	)
	default boolean notifyRareDrops()
	{
		return true;
	}

	@ConfigItem(
		keyName = "rareDropMinValue",
		name = "Min drop value",
		description = "Post drops worth at least this (min 1,000,000; 0 = off).",
		position = 2,
		section = "dropsSection"
	)
	default int rareDropMinValue()
	{
		return 5_000_000;
	}

	@ConfigItem(
		keyName = "rareDropMinRarity",
		name = "Min drop rarity (1 in N)",
		description = "Also post drops rarer than 1 in N whatever their value (min 1000; 0 = off).",
		position = 3,
		section = "dropsSection"
	)
	default int rareDropMinRarity()
	{
		// 1/10,000. Anything looser turns the channel into herb and seed rolls — at 1/2000 a normal
		// slayer task posts several times. Raised from 1/5000; see AnvilPlugin.migrateConfigDefaults.
		return 10_000;
	}


	@ConfigItem(
		keyName = "lootKeyMinValue",
		name = "Loot key value",
		description = "Post a loot key whose contents total at least this (0 = off).",
		position = 5,
		section = "dropsSection"
	)
	default int lootKeyMinValue()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "notifyPets",
		name = "Notify on pets",
		description = "Post to the clan drops channel when you get a pet.",
		position = 6,
		section = "dropsSection"
	)
	default boolean notifyPets()
	{
		return true;
	}


	// ---- Notifications: Combat achievements ----

	@ConfigItem(
		keyName = "caMinTaskTier",
		name = "CA post tier",
		description = "Post combat tasks at or above this tier, plus tier clears; Off posts none. Bingo tiles still count.",
		position = 2,
		section = "caSection"
	)
	default CaAnnounceTier caMinTaskTier()
	{
		return CaAnnounceTier.MASTER;
	}


	@ConfigItem(
		keyName = "notifyClogSlots",
		name = "Notify on collection log slots",
		description = "Post every new collection-log slot to the clan achievements channel.",
		position = 4,
		section = "caSection"
	)
	default boolean notifyClogSlots()
	{
		return true;
	}


	@ConfigItem(
		keyName = "notifyLevelUps",
		name = "Notify on level milestones",
		description = "Post level milestones (your clan's rules; 99s and high totals by default) to the clan and your own webhooks.",
		position = 6,
		section = "caSection"
	)
	default boolean notifyLevelUps()
	{
		return true;
	}


	@ConfigItem(
		keyName = "notifyDiaries",
		name = "Notify on diary completions",
		description = "Post achievement-diary tier completions to the clan, with a screenshot.",
		position = 8,
		section = "caSection"
	)
	default boolean notifyDiaries()
	{
		return true;
	}

	@ConfigItem(
		keyName = "questAnnounce",
		name = "Quest post tier",
		description = "Post quest completions at or above this difficulty to the clan; Off posts none. Bingo tiles still count.",
		position = 9,
		section = "caSection"
	)
	default QuestAnnounceTier questAnnounce()
	{
		return QuestAnnounceTier.MASTER;
	}

	// ---- Profile sync ----

	@ConfigItem(
		keyName = "autoSyncClanRoster",
		name = "Keep the clan roster in sync",
		description = "Admins: push the in-game clan roster to the site when you log in.",
		position = 0,
		section = "profileSection"
	)
	default boolean autoSyncClanRoster()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncClanCoffer",
		name = "Sync Clan Coffer",
		description = "Send Clan Coffer deposits and withdrawals to your clan site, from the clan chat line and the coffer screen.",
		position = 1,
		section = "profileSection"
	)
	default boolean syncClanCoffer()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncClog",
		name = "Sync collection log",
		description = "Send the collection-log pages you open to your profile.",
		position = 2,
		section = "profileSection"
	)
	default boolean syncClog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "autoFullClogSync",
		name = "Sync the whole log on open",
		description = "Read your whole collection log when you open it, not just the pages you view.",
		position = 4,
		section = "profileSection"
	)
	default boolean autoFullClogSync()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncPersonalBests",
		name = "Sync personal bests",
		description = "Send your best boss and raid times as you set them.",
		position = 3,
		section = "profileSection"
	)
	default boolean syncPersonalBests()
	{
		return true;
	}

	@ConfigItem(
		keyName = "importRuneLitePbs",
		name = "Import existing bests once",
		description = "Once per account, copy the bests RuneLite already recorded locally.",
		position = 5,
		section = "profileSection"
	)
	default boolean importRuneLitePbs()
	{
		return true;
	}

	@ConfigItem(
		keyName = "shareMoments",
		name = "Share highlights with the clan",
		description = "Show your pets, uniques, big drops and deaths on the clan site's feed during events.",
		position = 6,
		section = "profileSection"
	)
	default boolean shareMoments()
	{
		return true;
	}
}
