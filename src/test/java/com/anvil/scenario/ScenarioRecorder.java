package com.anvil.scenario;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemStack;

/**
 * Turns real events into scenario steps while recording is on. Each event is stamped with the
 * client tick it arrived on, and the gap since the previous event becomes a {@code wait} step — so a
 * replay keeps the game's own ordering and spacing.
 *
 * <p>Deliberately not recorded: player-authored chat (it is someone else's text, and the plugin never
 * credits off it), varbits unless asked for (hundreds change a minute), and StatChanged events that
 * didn't move XP (boost/drain ticks).</p>
 */
final class ScenarioRecorder
{
	private static final java.util.Set<ChatMessageType> SKIP_CHAT = java.util.EnumSet.of(
		ChatMessageType.PUBLICCHAT, ChatMessageType.PRIVATECHAT, ChatMessageType.PRIVATECHATOUT,
		ChatMessageType.MODCHAT, ChatMessageType.MODPRIVATECHAT, ChatMessageType.AUTOTYPER,
		ChatMessageType.MODAUTOTYPER, ChatMessageType.FRIENDSCHAT, ChatMessageType.CLAN_CHAT,
		ChatMessageType.CLAN_GUEST_CHAT, ChatMessageType.CLAN_GIM_CHAT, ChatMessageType.CONSOLE);

	private final JsonArray steps = new JsonArray();
	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	private final boolean varbits;
	private int lastTick = -1;

	ScenarioRecorder(boolean varbits)
	{
		this.varbits = varbits;
	}

	boolean recordsVarbits()
	{
		return varbits;
	}

	int size()
	{
		return steps.size();
	}

	void chat(int tick, ChatMessageType type, String name, String message, String sender)
	{
		if (type == null || SKIP_CHAT.contains(type))
		{
			return;
		}
		JsonObject c = new JsonObject();
		c.addProperty("type", type.name());
		if (name != null && !name.isEmpty())
		{
			c.addProperty("name", name);
		}
		if (sender != null && !sender.isEmpty())
		{
			c.addProperty("sender", sender);
		}
		c.addProperty("message", message);
		add(tick, "chat", c);
	}

	void loot(int tick, String kind, String name, int npcId, int combatLevel, String lootType, Collection<ItemStack> items)
	{
		JsonObject l = new JsonObject();
		l.addProperty("kind", kind);
		l.addProperty("name", name);
		if (npcId >= 0)
		{
			l.addProperty("npcId", npcId);
		}
		if (combatLevel > 0)
		{
			l.addProperty("combatLevel", combatLevel);
		}
		if (lootType != null)
		{
			l.addProperty("lootType", lootType);
		}
		JsonArray arr = new JsonArray();
		for (ItemStack it : items)
		{
			JsonObject o = new JsonObject();
			o.addProperty("id", it.getId());
			o.addProperty("qty", it.getQuantity());
			arr.add(o);
		}
		l.add("items", arr);
		add(tick, "loot", l);
	}

	void varbit(int tick, int id, int value)
	{
		if (!varbits || id < 0)
		{
			return;
		}
		JsonObject v = new JsonObject();
		v.addProperty("id", id);
		v.addProperty("value", value);
		add(tick, "varbit", v);
	}

	void stat(int tick, Skill skill, int xp, int level, int boosted)
	{
		Integer prev = lastXp.put(skill, xp);
		if (prev == null || prev == xp)
		{
			return; // first sighting is the login burst; unchanged XP is a boost tick
		}
		JsonObject s = new JsonObject();
		s.addProperty("skill", skill.name());
		s.addProperty("xp", xp);
		s.addProperty("level", level);
		s.addProperty("boostedLevel", boosted);
		add(tick, "stat", s);
	}

	void death(int tick, boolean self, String npcName, int npcId)
	{
		JsonObject d = new JsonObject();
		d.addProperty("who", self ? "self" : "npc");
		if (!self)
		{
			d.addProperty("name", npcName);
			d.addProperty("npcId", npcId);
		}
		add(tick, "death", d);
	}

	private void add(int tick, String kind, JsonObject body)
	{
		if (lastTick >= 0 && tick > lastTick)
		{
			JsonObject w = new JsonObject();
			w.addProperty("wait", tick - lastTick);
			steps.add(w);
		}
		lastTick = tick;
		JsonObject step = new JsonObject();
		step.add(kind, body);
		steps.add(step);
	}

	/** Write the recording as a scenario file; returns its path. */
	Path save(Path dir, Gson gson) throws IOException
	{
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		String name = "recorded-" + stamp;
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("description", "Recorded " + stamp + " (" + steps.size() + " steps)");
		o.add("params", new JsonObject());
		o.add("steps", steps);
		Files.createDirectories(dir);
		Path file = dir.resolve(name + ".json");
		Files.writeString(file, gson.newBuilder().setPrettyPrinting().create().toJson(o));
		return file;
	}
}
