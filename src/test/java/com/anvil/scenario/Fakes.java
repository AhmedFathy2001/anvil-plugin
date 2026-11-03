package com.anvil.scenario;

import java.lang.reflect.Proxy;
import java.util.Map;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;

/**
 * Stand-ins for the client's interface types (NPC, Player, NPCComposition, Hitsplat) that a
 * scenario needs to put inside an event. Built with {@link Proxy}: the named getters answer from
 * the map, every other method returns the type's zero value (null / 0 / false), and default methods
 * (Hitsplat.isMine reads getHitsplatType) run their real bodies.
 */
final class Fakes
{
	private Fakes()
	{
	}

	static NPC npc(String name, int id, int combatLevel)
	{
		NPCComposition comp = composition(name, id, combatLevel);
		return proxy(NPC.class, Map.of(
			"getName", name,
			"getId", id,
			"getCombatLevel", combatLevel,
			"getComposition", comp,
			"getTransformedComposition", comp));
	}

	static NPCComposition composition(String name, int id, int combatLevel)
	{
		return proxy(NPCComposition.class, Map.of(
			"getName", name,
			"getId", id,
			"getCombatLevel", combatLevel));
	}

	static Player player(String name)
	{
		return proxy(Player.class, Map.of("getName", name, "getCombatLevel", 126));
	}

	/** A hitsplat the game would draw as damage dealt BY the local player ({@code isMine()}). */
	static Hitsplat myHit(int amount)
	{
		return proxy(Hitsplat.class, Map.of(
			"getHitsplatType", HitsplatID.DAMAGE_ME,
			"getAmount", amount));
	}

	@SuppressWarnings("unchecked")
	static <T> T proxy(Class<T> type, Map<String, Object> answers)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) ->
		{
			String m = method.getName();
			if (answers.containsKey(m) && (args == null || args.length == 0))
			{
				return answers.get(m);
			}
			switch (m)
			{
				case "equals":
					return self == args[0];
				case "hashCode":
					return System.identityHashCode(self);
				case "toString":
					return "Fake" + type.getSimpleName() + answers;
			}
			if (method.isDefault())
			{
				return java.lang.invoke.MethodHandles.privateLookupIn(type, java.lang.invoke.MethodHandles.lookup())
					.unreflectSpecial(method, type).bindTo(self).invokeWithArguments(args == null ? new Object[0] : args);
			}
			return zero(method.getReturnType());
		});
	}

	private static Object zero(Class<?> t)
	{
		if (!t.isPrimitive() || t == void.class)
		{
			return null;
		}
		if (t == boolean.class)
		{
			return false;
		}
		if (t == long.class)
		{
			return 0L;
		}
		if (t == float.class)
		{
			return 0f;
		}
		if (t == double.class)
		{
			return 0d;
		}
		if (t == char.class)
		{
			return (char) 0;
		}
		if (t == byte.class)
		{
			return (byte) 0;
		}
		if (t == short.class)
		{
			return (short) 0;
		}
		return 0;
	}
}
