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


	@ConfigItem(
		keyName = "apiUrl",
		name = "Site URL",
		description = "The URL of your bingo site (e.g. https://yourbingo.vercel.app)",
		position = 1
	)
	default String apiUrl()
	{
		return "";
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

	@ConfigItem(
		keyName = "adminLinkedRsn",
		name = "Linked RSN",
		description = "The RSN that was linked when the admin token was issued. Managed automatically.",
		position = 13,
		section = "adminSection"
	)
	default String adminLinkedRsn()
	{
		return "";
	}
}
