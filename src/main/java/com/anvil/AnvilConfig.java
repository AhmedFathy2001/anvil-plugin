package com.anvil;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("osrsbingo")
public interface AnvilConfig extends Config
{
	// ---- Sections (collapsible groups, top to bottom) ----
	// Split by purpose: Setup + Bingo are event-participation; the "Notifications:" sections are
	// clan-wide Discord posts that fire with or without an active bingo.

	@ConfigSection(
		name = "General",
		description = "Anvil across the client, with or without an event.",
		position = 1
	)
	String generalSection = "generalSection";

	@ConfigSection(
		name = "Bingo",
		description = "In-event behaviour — only matters while you're in a bingo.",
		position = 2
	)
	String bingoSection = "bingoSection";

	@ConfigSection(
		name = "Notifications: Deaths & kills",
		description = "Clan Discord posts (with screenshot) for your deaths and PvP kills. Independent of bingo.",
		position = 3
	)
	String deathsSection = "deathsSection";

	@ConfigSection(
		name = "Notifications: Drops & pets",
		description = "Clan Discord posts for valuable / rare drops and pets. Independent of bingo.",
		position = 4
	)
	String dropsSection = "dropsSection";

	@ConfigSection(
		name = "Notifications: Combat achievements",
		description = "Clan Discord posts for CA tier clears and high-tier tasks. Independent of bingo.",
		position = 5,
		closedByDefault = true
	)
	String caSection = "caSection";

	@ConfigSection(
		name = "Profile sync",
		description = "Send your collection log and best times to your clan site, so your profile there shows them.",
		position = 7,
		closedByDefault = true
	)
	String profileSection = "profileSection";

	// ---- Setup ----

	// No Site URL: the plugin only talks to https://anvilosrs.com (BingoApiClient.CANONICAL_SITE).

	@ConfigItem(
		keyName = "playerToken",
		name = "Account Token",
		description = "Set by Sign in (or Paste a token) in the Anvil side panel. One token works for every clan and event you're in.",
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
		description = "Adds \"Anvil\" to the right-click menu on players, friends, clan and chat names — opens their Anvil profile in your browser.",
		position = 1,
		section = "generalSection"
	)
	default boolean profileLookupMenu()
	{
		return true;
	}

	// ---- Bingo ----



	@ConfigItem(
		keyName = "showOverlay",
		name = "Show Overlay",
		description = "Show the codeword and date verification overlay on screen",
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
		description = "Show a banner when your team completes a tile — anyone on the team. If several complete "
			+ "at once, the hardest shows as a banner and the rest as chat messages.",
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
		description = "Show the collection-log style popup (and its sound) when YOUR drop progresses or completes a "
			+ "bingo tile. Turn off to keep tracking quietly — the tile still counts, and your team's popup for "
			+ "a tile you completed stays off too.",
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
		description = "Play a sound when the bingo banner fires, and when a mission drops. Add your own .wav files "
			+ "with the 'Banner sounds' and 'Mission sounds' buttons in the Anvil side panel — they go in a folder "
			+ "each, and which folder a clip is in is what makes it a banner clip or a mission clip. No banner "
			+ "clips means no banner sound; no mission clips means missions play one of the game\u2019s own short "
			+ "sounds, so they never sound like a finished tile. Master switch: off is silence for both.",
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
		description = "Comma-separated allowlist of banner clips to cycle (blank = all). Managed in the Anvil side panel.",
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
		description = "When an event asks for a starting shot at a pinned spot, take and send it automatically once you're standing there on a fresh session. The side panel button still works either way.",
		position = 8,
		section = "bingoSection"
	)
	default boolean autoStartShot()
	{
		return true;
	}


	// ---- Notifications: Deaths & kills ----

	@ConfigItem(
		keyName = "notifyDeaths",
		name = "Notify on death",
		description = "Post to the clan deaths channel — with a screenshot of the moment you died — when you die.",
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
		description = "Post to the clan PvP-kills channel — with a screenshot of the tick your target hits 0 HP — when you kill a player you've damaged.",
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
		description = "Post valuable or very rare drops to the clan rare-drops channel, with a screenshot of the drop.",
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
		description = "Post any drop worth at least this much (the higher of GE and high-alch value). Minimum enforced: 1,000,000. 0 disables value-based posts.",
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
		description = "Also post extremely rare NPC/pickpocket drops regardless of value (e.g. 10000 = rarer than 1/10000). Your clan can enforce a higher floor. Minimum enforced: 1/1000. 0 disables rarity-based posts.",
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
		description = "Post a loot key as one notification when its contents total at least this much. Applies to loot keys only — regular drops use 'Min drop value'. 0 disables loot-key posts.",
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
		description = "Post to the clan rare-drops channel, with a screenshot, when you receive a pet.",
		position = 6,
		section = "dropsSection"
	)
	default boolean notifyPets()
	{
		return true;
	}


	// ---- Notifications: Combat achievements ----

	@ConfigItem(
		keyName = "notifyCombatAchievements",
		name = "Notify on combat achievements",
		description = "Post to the clan combat-achievements channel, with a screenshot, when you clear a CA tier (and high-tier individual tasks).",
		position = 1,
		section = "caSection"
	)
	default boolean notifyCombatAchievements()
	{
		return true;
	}

	@ConfigItem(
		keyName = "caMinTaskTier",
		name = "CA task min tier",
		description = "Post individual combat-task completions at or above this tier. Tier clears always post regardless. Set to Grandmaster to post only the rarest tasks.",
		position = 2,
		section = "caSection"
	)
	default CombatAchievementTier caMinTaskTier()
	{
		return CombatAchievementTier.MASTER;
	}


	@ConfigItem(
		keyName = "notifyClogSlots",
		name = "Notify on collection log slots",
		description = "Post every NEW collection-log slot to the clan achievements channel, with a screenshot. Prestige items (Infernal cape, quivers, …) still go to the drops channel instead, so nothing posts twice.",
		position = 4,
		section = "caSection"
	)
	default boolean notifyClogSlots()
	{
		return true;
	}


	@ConfigItem(
		keyName = "notifyLevelUps",
		name = "Notify on 99s & high totals",
		description = "Post to the clan combat-achievements channel, with a screenshot, when you reach level 99 in a skill, hit a high total-level milestone (every 100 from 1800 up), or max.",
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
		description = "Post to the clan achievements channel when you complete an achievement-diary tier.",
		position = 8,
		section = "caSection"
	)
	default boolean notifyDiaries()
	{
		return true;
	}

	@ConfigItem(
		keyName = "questAnnounce",
		name = "Announce quest completions",
		description = "Post quest completions to the clan achievements channel at or above this difficulty. "
			+ "\"Master & up\" covers Master and Grandmaster quests.",
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
		description = "Admins only: push the in-game clan roster to the site automatically when you log in, so joins and leaves don't wait for someone to remember. The manual button in the Anvil tab still works.",
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
		description = "While you have the in-game Clan Coffer open, send its coin balance changes to your clan site so deposits and withdrawals update the audited coffer automatically. The first reading is only a baseline and is never credited as a donation.",
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
		description = "Send the collection-log pages you open to your clan site, so your profile there shows them. Only pages you actually open can be read — the game doesn't hand the client the rest.",
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
		description = "Ask the game for your ENTIRE collection log the moment you open it, instead of only the pages you click through. Turn it off if you'd rather press \"Sync profile\" in the Anvil side panel yourself.",
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
		description = "Send your best boss and raid times to your clan site as you set them.",
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
		description = "On first login, copy the personal bests RuneLite's own chat commands plugin has already recorded for this account, so your profile starts complete instead of empty. Reads local config only; runs once per account.",
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
		description = "Send the pets, uniques, big drops and deaths that happen while your clan's competition week or bingo is running, so they appear on the site's feed. Separate from the Discord channels above — turning those off doesn't turn this off, and vice versa. Nothing sent here scores anything.",
		position = 6,
		section = "profileSection"
	)
	default boolean shareMoments()
	{
		return true;
	}
}
