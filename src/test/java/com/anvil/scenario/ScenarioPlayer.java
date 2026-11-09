package com.anvil.scenario;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Plays resolved scenario steps, one game tick at a time. {@link #tick} is called from GameTick on
 * the client thread: it runs every step up to the next {@code wait}, then sleeps that many ticks.
 * Counting ticks (never sleeping) keeps ordering identical to the game's — two lines in one step
 * group land in the same tick, exactly as a KC line and its pet line do.
 */
final class ScenarioPlayer
{
	interface ChatSink
	{
		/** Put a line in the chatbox the way the game would; true if the client fired ChatMessage for it. */
		boolean add(ChatMessageType type, String name, String message, String sender);
	}

	private final Client client;
	private final EventBus eventBus;
	private final ChatSink chat;
	private final Consumer<String> note;

	private Deque<JsonObject> pending = new ArrayDeque<>();
	/** Each var this run wrote, with the value it had before the run touched it ("varbit:5314" → 0). */
	private final Map<String, Integer> originals = new LinkedHashMap<>();
	private int sleep;
	private String running;

	ScenarioPlayer(Client client, EventBus eventBus, ChatSink chat, Consumer<String> note)
	{
		this.client = client;
		this.eventBus = eventBus;
		this.chat = chat;
		this.note = note;
	}

	boolean isRunning()
	{
		return running != null;
	}

	String running()
	{
		return running;
	}

	void start(String name, List<JsonObject> steps)
	{
		pending = new ArrayDeque<>(steps);
		sleep = 0;
		running = name;
		originals.clear();
	}

	void stop()
	{
		pending.clear();
		running = null;
	}

	/** {@code ::anvil stop}: abort, and put back any var the aborted run changed. Client thread. */
	void abort()
	{
		stop();
		restore();
	}

	/** Write every var this run changed back to its pre-run value (the {@code restore} step). */
	private void restore()
	{
		for (Map.Entry<String, Integer> e : originals.entrySet())
		{
			String[] kv = e.getKey().split(":");
			int id = Integer.parseInt(kv[1]);
			if ("varp".equals(kv[0]))
			{
				writeVarp(id, e.getValue());
			}
			else
			{
				writeVarbit(id, e.getValue());
			}
		}
		originals.clear();
	}

	private void writeVarbit(int id, int value)
	{
		// Same as DevTools' ::setvarb: write the client-side value (so getVarbitValue reads it)
		// and post the event the game would have fired. The server overwrites it the next time
		// it sends that varp, which for most gating varbits outside their content is never.
		client.setVarbit(id, value);
		VarbitChanged ev = new VarbitChanged();
		ev.setVarbitId(id);
		ev.setVarpId(-1);
		ev.setValue(value);
		eventBus.post(ev);
	}

	private void writeVarp(int id, int value)
	{
		client.setVarpValue(id, value);
		VarbitChanged ev = new VarbitChanged();
		ev.setVarbitId(-1);
		ev.setVarpId(id);
		ev.setValue(value);
		eventBus.post(ev);
	}

	/** Advance one tick. Returns the name of a scenario that just finished, else null. */
	String tick()
	{
		if (running == null)
		{
			return null;
		}
		if (sleep > 0 && --sleep > 0)
		{
			return null;
		}
		while (!pending.isEmpty())
		{
			JsonObject step = pending.poll();
			String kind = Scenario.kindOf(step);
			if ("wait".equals(kind))
			{
				sleep = Math.max(1, step.get("wait").getAsInt());
				return null;
			}
			try
			{
				run(kind, step.getAsJsonObject(kind));
			}
			catch (RuntimeException e)
			{
				note.accept("step " + kind + " failed: " + e.getMessage());
			}
		}
		String done = running;
		running = null;
		return done;
	}

	private void run(String kind, JsonObject s)
	{
		switch (kind)
		{
			case "chat":
			{
				ChatMessageType type = ChatMessageType.valueOf(orDefault(Scenario.string(s, "type"), "GAMEMESSAGE"));
				String name = orDefault(Scenario.string(s, "name"), "");
				String message = Scenario.string(s, "message");
				String sender = Scenario.string(s, "sender");
				if (!chat.add(type, name, message, sender))
				{
					// The client didn't echo it as a ChatMessage (logged out, or a client that doesn't) —
					// post it ourselves so the plugin still sees the line.
					eventBus.post(new ChatMessage(null, type, name, message, sender, (int) (System.currentTimeMillis() / 1000)));
				}
				break;
			}
			case "loot":
				eventBus.post(lootEvent(s));
				break;
			case "varbit":
			{
				int id = varbitId(s);
				int before = client.getVarbitValue(id);
				int base = s.has("base") ? client.getVarbitValue(ref(VarbitID.class, s, "base")) : before;
				originals.putIfAbsent("varbit:" + id, before);
				writeVarbit(id, newValue(s, base));
				break;
			}
			case "varp":
			{
				int id = varpId(s);
				int before = client.getVarpValue(id);
				int base = s.has("base") ? client.getVarpValue(ref(VarPlayerID.class, s, "base")) : before;
				originals.putIfAbsent("varp:" + id, before);
				writeVarp(id, newValue(s, base));
				break;
			}
			case "restore":
				restore();
				break;
			case "stat":
				eventBus.post(statEvent(s, client::getSkillExperience));
				break;
			case "attacked":
			{
				// What DeathAttribution reads: something targeting us, then damage landing on us. Not
				// our hitsplat type, so it never counts as a hit WE dealt.
				net.runelite.api.Actor by = Boolean.TRUE.equals(bool(s, "npc"))
					? Fakes.npc(Scenario.string(s, "by"), Scenario.integer(s, "npcId", -1), Scenario.integer(s, "combatLevel", 0))
					: Fakes.player(Scenario.string(s, "by"));
				eventBus.post(new InteractingChanged(by, client.getLocalPlayer()));
				HitsplatApplied hit = new HitsplatApplied();
				hit.setActor(client.getLocalPlayer());
				hit.setHitsplat(Fakes.hitTaken(Scenario.integer(s, "damage", 5)));
				eventBus.post(hit);
				break;
			}
			case "playerSpawn":
				eventBus.post(new PlayerSpawned(Fakes.player(Scenario.string(s, "name"))));
				break;
			case "playerLoot":
				eventBus.post(playerLootEvent(s));
				break;
			case "death":
			{
				String who = orDefault(Scenario.string(s, "who"), "self");
				if ("self".equals(who))
				{
					eventBus.post(new ActorDeath(client.getLocalPlayer()));
				}
				else if ("player".equals(who))
				{
					// Someone else dying near us, NOT by our hand (no hitsplat of ours): a party member in a
					// raid instance, which is what fails a deathless run.
					eventBus.post(new ActorDeath(Fakes.player(Scenario.string(s, "name"))));
				}
				else
				{
					eventBus.post(new ActorDeath(Fakes.npc(Scenario.string(s, "name"),
						Scenario.integer(s, "npcId", -1), Scenario.integer(s, "combatLevel", 0))));
				}
				break;
			}
			case "pvpKill":
			{
				// The plugin credits a PK off damage→death attribution, not a chat line: a hitsplat we
				// dealt to that Player, then the Player's ActorDeath inside the attribution window.
				net.runelite.api.Player victim = Fakes.player(Scenario.string(s, "victim"));
				HitsplatApplied hit = new HitsplatApplied();
				hit.setActor(victim);
				hit.setHitsplat(Fakes.myHit(Scenario.integer(s, "damage", 20)));
				eventBus.post(hit);
				eventBus.post(new ActorDeath(victim));
				break;
			}
			default:
				throw new IllegalArgumentException("unknown step " + kind);
		}
	}

	static Object lootEvent(JsonObject s)
	{
		String kind = orDefault(Scenario.string(s, "kind"), "npc");
		String name = Scenario.string(s, "name");
		int npcId = Scenario.integer(s, "npcId", -1);
		int combat = Scenario.integer(s, "combatLevel", 0);
		List<ItemStack> items = items(s.getAsJsonArray("items"));
		switch (kind)
		{
			case "npc":
				return new NpcLootReceived(Fakes.npc(name, npcId, combat), items);
			case "server":
				return new ServerNpcLoot(Fakes.composition(name, npcId, combat), items);
			case "generic":
				return new LootReceived(name, combat,
					LootRecordType.valueOf(orDefault(Scenario.string(s, "lootType"), "EVENT")),
					items, Scenario.integer(s, "amount", 1), null);
			default:
				throw new IllegalArgumentException("loot kind must be npc|server|generic: " + kind);
		}
	}

	static Object playerLootEvent(JsonObject s)
	{
		return new PlayerLootReceived(Fakes.player(Scenario.string(s, "victim")), items(s.getAsJsonArray("items")));
	}

	static StatChanged statEvent(JsonObject s)
	{
		return statEvent(s, skill -> 0);
	}

	/**
	 * {@code addXp} is relative to the skill's real XP ({@code currentXp}); the level then follows from
	 * the XP unless the step names one. Otherwise {@code xp} and {@code level} are absolute.
	 */
	static StatChanged statEvent(JsonObject s, ToIntFunction<Skill> currentXp)
	{
		Skill skill = Skill.valueOf(Scenario.string(s, "skill").toUpperCase(Locale.ROOT));
		int xp = s.has("addXp")
			? currentXp.applyAsInt(skill) + Scenario.integer(s, "addXp", 0)
			: Scenario.integer(s, "xp", 0);
		int level = s.has("level") || !s.has("addXp") ? Scenario.integer(s, "level", 1) : Experience.getLevelForXp(xp);
		return new StatChanged(skill, xp, level, Scenario.integer(s, "boostedLevel", level));
	}

	/** {@code value} as written, or {@code add} on top of {@code base} (the var's own value, or the {@code base} var's). */
	static int newValue(JsonObject s, int base)
	{
		return s.has("add") ? base + Scenario.integer(s, "add", 0) : Scenario.integer(s, "value", 0);
	}

	static int varpId(JsonObject s)
	{
		return s.has("id") ? Scenario.integer(s, "id", -1) : constant(VarPlayerID.class, Scenario.string(s, "name"));
	}

	/** A var named by {@code key}: a number (or digit string) is an id, anything else a gameval constant. */
	static int ref(Class<?> holder, JsonObject s, String key)
	{
		String v = Scenario.string(s, key);
		return v != null && v.matches("\\d+") ? Integer.parseInt(v) : constant(holder, v);
	}

	private static Boolean bool(JsonObject s, String key)
	{
		return s.has(key) && !s.get(key).isJsonNull() ? Boolean.valueOf(s.get(key).getAsString()) : null;
	}

	static List<ItemStack> items(JsonArray arr)
	{
		List<ItemStack> out = new ArrayList<>();
		if (arr == null)
		{
			return out;
		}
		for (JsonElement e : arr)
		{
			JsonObject it = e.getAsJsonObject();
			int id = it.has("id") ? Scenario.integer(it, "id", -1) : constant(ItemID.class, Scenario.string(it, "name"));
			out.add(new ItemStack(id, Scenario.integer(it, "qty", 1)));
		}
		return out;
	}

	static int varbitId(JsonObject s)
	{
		return s.has("id") ? Scenario.integer(s, "id", -1) : constant(VarbitID.class, Scenario.string(s, "name"));
	}

	/** A gameval constant by name (VarbitID.RAIDS_CLIENT_INDUNGEON, ItemID.COINS, …). */
	static int constant(Class<?> holder, String name)
	{
		if (name == null)
		{
			throw new IllegalArgumentException("need an id or a " + holder.getSimpleName() + " name");
		}
		try
		{
			Field f = holder.getField(name.toUpperCase(Locale.ROOT));
			return f.getInt(null);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalArgumentException("no " + holder.getSimpleName() + "." + name);
		}
	}

	private static String orDefault(String v, String d)
	{
		return v == null || v.isEmpty() ? d : v;
	}
}
