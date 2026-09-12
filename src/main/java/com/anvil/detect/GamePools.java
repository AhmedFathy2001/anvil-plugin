package com.anvil.detect;

import com.anvil.AnvilPlugin;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Skill;

/**
 * The word lists the plugin matches game text against, and the lines it says back.
 *
 * <p>All of it is data: no state, no client, no config. It lived in the middle of
 * {@code AnvilPlugin} because that is where the code using it lived, and three hundred lines of
 * quest names and taunts between two methods is three hundred lines you scroll past every time you
 * look for anything else.</p>
 *
 * <p><b>These are fallbacks, mostly.</b> The server sends its own fun-death lines and its own
 * always-notify item list, and when it does, its version wins. What is here is what a plugin talking
 * to a site that predates the field — or to no site at all — still has to work with. The quest tiers
 * are the exception: those are facts about the game, and the plugin is the only thing that knows
 * them.</p>
 */
public final class GamePools
{
    private GamePools()
    {
    }

    // Fallback fun-death lines used only when the server pool (pluginConfig.funDeathMessages) is
    // empty/unavailable. {name} is replaced with the RSN.
    public static final List<String> FUN_DEATHS_FALLBACK = Arrays.asList(
            "{name} has been sent to Lumbridge to think about their choices.",
            "{name} forgot to flick Protect from Magic. Classic.",
            "{name} died doing what they loved: not eating.",
            "Press F for {name}.",
            "{name} just speedran a trip to Lumbridge."
    );

    // Short reaction lines appended to every death post.
    public static final List<String> DEATH_TAUNTS = Arrays.asList(
            "Sit. 🪑", "L + ratio.", "Skill issue.", "Couldn't be me.", "GG go next.",
            "Have you tried eating?", "That's gotta hurt.", "Prayer was off, wasn't it?", "Get good. 🤡"
    );

    // Reaction lines appended to a notably lucky (rare / high-value) drop.
    public static final List<String> SPOON_TAUNTS = Arrays.asList(
            "SPOONED. 🥄", "Way under rate — absolute spoon.", "RNG said \"here you go champ\".",
            "Some of us are 5x dry. Disgusting.", "No skill, all luck. Congrats. 🥄",
            "Hand it over — someone drier deserved that."
    );

    // Prestige items always posted to the rare-drops channel regardless of value/rarity — they're
    // usually untradeable or cheap but a big deal. Matched as case-insensitive substrings, so
    // "Blessed dizana's quiver" still matches "dizana's quiver". The server list
    // (pluginConfig.alwaysNotifyItems) extends this without a plugin update.
    public static final List<String> ALWAYS_NOTIFY_FALLBACK = Arrays.asList(
            // Prestige capes / quivers (awarded, untradeable).
            "infernal cape",
            "dizana's quiver",
            "purifying sigil",
            // Raid ornament / colour kits + dusts (untradeable — ToB / CoX / ToA).
            "ancient blood ornament kit",
            "sanguine ornament kit",
            "holy ornament kit",
            "sanguine dust",
            "metamorphic dust",
            "twisted ancestral colour kit",
            // ToA reward-chest cosmetics (untradeable, no-death at a high invocation): the Menaphite
            // ornament kit (Elidinis' ward), the Cursed phalanx (Osmumten's fang), and the Masori
            // crafting kit (Ava's assembler → Masori assembler).
            "menaphite ornament kit",
            "cursed phalanx",
            "masori crafting kit",
            // DT2 (Forgotten Four) untradeable uniques: the ring vestiges (the actual boss
            // drops — Ultor/Magus/Bellator/Venator vestige, all caught by "vestige"), the
            // chromium-ingot quartz, and the four Soulreaper axe pieces. Substring-matched.
            "vestige",
            "quartz",
            "executioner's axe head",
            "eye of the duke",
            "leviathan's lure",
            "siren's staff",
            // Boss collection-log jars (untradeable) + Champions' Challenge scroll/cape.
            "jar of",
            "champion scroll",
            "champion's cape",
            // Enhanced crystal seeds — the Gauntlet weapon seed and the Elf-pickpocket
            // teleport seed (both untradeable). Substring covers both.
            "enhanced crystal",
            // Vyrewatch Sentinel blood shard (tradeable, but GE price dips below the value
            // floor so we always want it) + the Fight Caves fire cape milestone.
            "blood shard",
            "fire cape"
    );

    // Quest difficulty tiers for completion announcements — verified against the OSRS Wiki
    // quest list (2026-07). Only the top tiers are listed: any quest absent from both sets
    // counts as below Master, so it only posts on the "All quests" setting. Lowercase,
    // matched against the parsed scroll name. Update when Jagex ships new Master+ quests.
    public static final Set<String> GRANDMASTER_QUESTS = new LinkedHashSet<>(Arrays.asList(
            "desert treasure ii - the fallen empire",
            "desert treasure ii", // scroll may omit the subtitle
            "dragon slayer ii",
            "monkey madness ii",
            "song of the elves",
            "the blood moon rises",
            "while guthix sleeps"));
    public static final Set<String> MASTER_QUESTS = new LinkedHashSet<>(Arrays.asList(
            "a night at the theatre",
            "beneath cursed sands",
            "desert treasure i",
            "desert treasure", // pre-DT2 scroll name, in case the numeral is omitted
            "dream mentor",
            "grim tales",
            "legends' quest",
            "making friends with my arm",
            "monkey madness i",
            "monkey madness", // pre-MM2 scroll name
            "mourning's end part i",
            "mourning's end part ii",
            "perilous moons",
            // The wiki tiers RFD as "Special"; defeating the Culinaromancer is the de-facto
            // final completion (Barrows gloves), so announce it with the Masters.
            "recipe for disaster - culinaromancer",
            "secrets of the north",
            "sins of the father",
            "swan song",
            "the curse of arrav",
            "the final dawn",
            "the fremennik exiles"));

    public static final List<String> RFD_TAGS = Arrays.asList("Another Cook", "freed", "defeated", "saved");

    public static final List<String> WORD_QUEST_IN_NAME_TAGS = Arrays.asList(
            "Another Cook", "Doric", "Heroes", "Legends", "Observatory", "Olaf", "Waterfall");

    // A drop this rare (or this valuable) earns a spoon reaction line.
    public static final long SPOON_VALUE = 50_000_000L;
}
