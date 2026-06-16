package com.osrsbingo;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("osrsbingo")
public interface OsrsBingoConfig extends Config
{
	@ConfigSection(
		name = "Admin link",
		description = "Link this plugin to your admin account on the bingo site for roster sync.",
		position = 10,
		closedByDefault = true
	)
	String adminSection = "adminSection";

	@ConfigSection(
		name = "Notifications",
		description = "Post your deaths and rare drops to the clan Discord. Channels are managed on the bingo site; choose what you share here.",
		position = 6
	)
	String notifSection = "notifSection";


	@ConfigItem(
		keyName = "apiUrl",
		name = "Site URL",
		description = "The URL of your bingo site. Defaults to the official site — only change this if you self-host.",
		position = 1
	)
	default String apiUrl()
	{
		return "https://theafkspot-bingo.vercel.app";
	}

	@ConfigItem(
		keyName = "playerToken",
		name = "Player Token",
		description = "Your player token UUID from the bingo site",
		position = 2,
		secret = true
	)
	default String playerToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "autoSubmit",
		name = "Auto Submit Drops",
		description = "Automatically screenshot and submit when a tracked drop is received",
		position = 3
	)
	default boolean autoSubmit()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show Overlay",
		description = "Show the codeword and date verification overlay on screen",
		position = 4
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "autoEnrollWeekly",
		name = "Auto-enroll weekly comp",
		description = "On login, automatically enroll in the active weekly competition if one is running.",
		position = 5
	)
	default boolean autoEnrollWeekly()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bingoClogTab",
		name = "Bingo tab in Collection Log",
		description = "Show your bingo tasks as a custom tab inside the in-game Collection Log. "
			+ "Experimental — injects into the collection log interface and may need updating after game updates.",
		position = 6
	)
	default boolean bingoClogTab()
	{
		return false;
	}

	@ConfigItem(
		keyName = "notifyDeaths",
		name = "Notify on death",
		description = "Post a message to the clan deaths channel when you die.",
		position = 20,
		section = "notifSection"
	)
	default boolean notifyDeaths()
	{
		return true;
	}

	@ConfigItem(
		keyName = "deathMessage",
		name = "Death message",
		description = "Your death message. Use {name} for your RSN. (There's a small chance a random clan one-liner is used instead.)",
		position = 21,
		section = "notifSection"
	)
	default String deathMessage()
	{
		return "{name} just died!";
	}

	@ConfigItem(
		keyName = "deathScreenshot",
		name = "Screenshot deaths",
		description = "Attach a screenshot of the moment you died, like the Death Notifier plugin.",
		position = 22,
		section = "notifSection"
	)
	default boolean deathScreenshot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyRareDrops",
		name = "Notify on rare drops",
		description = "Post valuable drops to the clan rare-drops channel.",
		position = 23,
		section = "notifSection"
	)
	default boolean notifyRareDrops()
	{
		return true;
	}

	@ConfigItem(
		keyName = "rareDropMinValue",
		name = "Min drop value",
		description = "Post any drop worth at least this much (the higher of GE and high-alch value). 0 disables value-based posts.",
		position = 24,
		section = "notifSection"
	)
	default int rareDropMinValue()
	{
		return 5_000_000;
	}

	@ConfigItem(
		keyName = "rareDropScreenshot",
		name = "Screenshot rare drops",
		description = "Attach a screenshot to rare-drop posts.",
		position = 25,
		section = "notifSection"
	)
	default boolean rareDropScreenshot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyPets",
		name = "Notify on pets",
		description = "Post to the clan rare-drops channel when you receive a pet.",
		position = 26,
		section = "notifSection"
	)
	default boolean notifyPets()
	{
		return true;
	}

	@ConfigItem(
		keyName = "rareDropMinRarity",
		name = "Min drop rarity (1 in N)",
		description = "Also post extremely rare NPC/pickpocket drops regardless of value (e.g. 1000 = rarer than 1/1000). Catches cheap-but-rare uniques. 0 disables rarity-based posts.",
		position = 27,
		section = "notifSection"
	)
	default int rareDropMinRarity()
	{
		return 1000;
	}

	@ConfigItem(
		keyName = "lootKeyMinValue",
		name = "Loot key value",
		description = "Post a loot key as one notification when its contents total at least this much. Applies to loot keys only — regular drops use 'Min drop value'. 0 disables loot-key posts.",
		position = 28,
		section = "notifSection"
	)
	default int lootKeyMinValue()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "funnyNotifications",
		name = "Funny lines",
		description = "Add a cheeky one-liner to death and rare-drop posts (e.g. \"Sit.\" on a death, \"SPOONED.\" on a lucky drop).",
		position = 29,
		section = "notifSection"
	)
	default boolean funnyNotifications()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyCombatAchievements",
		name = "Notify on combat achievements",
		description = "Post to the clan combat-achievements channel when you clear a CA tier (and high-tier individual tasks).",
		position = 30,
		section = "notifSection"
	)
	default boolean notifyCombatAchievements()
	{
		return true;
	}

	@ConfigItem(
		keyName = "caMinTaskTier",
		name = "CA task min tier",
		description = "Post individual combat-task completions at or above this tier. Tier clears always post regardless. Set to Grandmaster to post only the rarest tasks.",
		position = 31,
		section = "notifSection"
	)
	default CombatAchievementTier caMinTaskTier()
	{
		return CombatAchievementTier.MASTER;
	}

	@ConfigItem(
		keyName = "adminModeEnabled",
		name = "Use admin features",
		description = "When off, the plugin behaves as a regular player even if an admin token is stored. Useful for previewing the non-admin view without unlinking.",
		position = 10,
		section = "adminSection"
	)
	default boolean adminModeEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "adminLinkCode",
		name = "Admin link code",
		description = "Paste the 6-character code from the bingo site's admin settings, then click Link in the side panel.",
		position = 11,
		section = "adminSection"
	)
	default String adminLinkCode()
	{
		return "";
	}

	@ConfigItem(
		keyName = "adminPluginToken",
		name = "Admin plugin token",
		description = "Long-lived admin token returned by the site after linking. Managed automatically — do not edit.",
		position = 12,
		secret = true,
		section = "adminSection"
	)
	default String adminPluginToken()
	{
		return "";
	}

}
