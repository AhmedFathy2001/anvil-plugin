package com.anvil.scenario;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Every bundled scenario parses, resolves, and turns into the events it claims to — no client needed. */
public class ScenarioFormatTest
{
	private static final Map<String, String> ME = Map.of("me", "Tester");

	@Test
	public void indexListsEveryBundledFile() throws IOException
	{
		Set<String> onDisk = new HashSet<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get("src/test/resources/scenarios"), "*.json"))
		{
			for (Path p : ds)
			{
				onDisk.add(p.getFileName().toString());
			}
		}
		assertEquals(onDisk, new HashSet<>(ScenarioLibrary.bundledFiles()));
	}

	@Test
	public void bundledScenariosLoadCleanly() throws IOException
	{
		Path empty = Files.createTempDirectory("anvil-scenarios");
		ScenarioLibrary lib = new ScenarioLibrary(empty);
		List<String> errors = lib.reload();
		assertTrue(errors.toString(), errors.isEmpty());
		assertEquals(ScenarioLibrary.bundledFiles().size(), lib.all().size());
		for (Scenario s : lib.all())
		{
			assertEquals(Scenario.Origin.BUNDLED, s.origin);
			assertTrue(s.name + " needs a description", !s.description.isEmpty());
		}
	}

	/** Each step resolves (no dangling ${…}) and builds its event — catches bad enums, ids and names. */
	@Test
	public void everyStepBuildsItsEvent() throws IOException
	{
		ScenarioLibrary lib = new ScenarioLibrary(Files.createTempDirectory("anvil-scenarios"));
		lib.reload();
		for (Scenario s : lib.all())
		{
			for (JsonObject step : s.resolve(ME))
			{
				String kind = Scenario.kindOf(step);
				try
				{
					JsonObject body = "wait".equals(kind) ? null : step.getAsJsonObject(kind);
					switch (kind)
					{
						case "wait":
							assertTrue(step.get("wait").getAsInt() > 0);
							break;
						case "chat":
							ChatMessageType.valueOf(body.has("type") ? body.get("type").getAsString() : "GAMEMESSAGE");
							assertTrue(!body.get("message").getAsString().contains("${"));
							break;
						case "loot":
							assertNotNull(ScenarioPlayer.lootEvent(body));
							break;
						case "varbit":
							assertTrue(ScenarioPlayer.varbitId(body) > 0);
							if (body.has("base"))
							{
								assertTrue(ScenarioPlayer.ref(net.runelite.api.gameval.VarbitID.class, body, "base") > 0);
							}
							assertTrue(body.has("value") || body.has("add"));
							ScenarioPlayer.newValue(body, 0);
							break;
						case "varp":
							assertTrue(ScenarioPlayer.varpId(body) > 0);
							assertTrue(body.has("value") || body.has("add"));
							ScenarioPlayer.newValue(body, 0);
							break;
						case "stat":
							assertNotNull(ScenarioPlayer.statEvent(body));
							break;
						case "death":
						{
							String who = body.get("who").getAsString();
							assertTrue(who, "self".equals(who) || body.has("name"));
							break;
						}
						case "attacked":
							assertNotNull(body.get("by").getAsString());
							break;
						case "playerSpawn":
							assertNotNull(body.get("name").getAsString());
							break;
						case "playerLoot":
							assertNotNull(ScenarioPlayer.playerLootEvent(body));
							break;
						default:
							break;
					}
				}
				catch (RuntimeException e)
				{
					fail(s.name + " step " + step + ": " + e);
				}
			}
		}
	}

	@Test
	public void argsOverrideDefaultsAndKeepSpaces()
	{
		Map<String, String> args = Scenario.parseArgs(new String[]{"run", "boss-kc", "boss=Phantom", "Muspah", "kc=7"}, 2);
		assertEquals("Phantom Muspah", args.get("boss"));
		assertEquals("7", args.get("kc"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNamesThatArentFileSafe()
	{
		Scenario.parse("{\"name\":\"../evil\",\"steps\":[]}", Scenario.Origin.SITE);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsStepsWithTwoKinds()
	{
		Scenario.parse("{\"name\":\"x\",\"steps\":[{\"wait\":1,\"chat\":{\"message\":\"hi\"}}]}", Scenario.Origin.LOCAL);
	}

	@Test
	public void lootKindsBuildTheRightEvents()
	{
		JsonObject l = new JsonObject();
		l.addProperty("name", "Zulrah");
		l.addProperty("npcId", 2042);
		com.google.gson.JsonArray items = new com.google.gson.JsonArray();
		JsonObject it = new JsonObject();
		it.addProperty("id", "12922");
		items.add(it);
		l.add("items", items);

		l.addProperty("kind", "server");
		ServerNpcLoot server = (ServerNpcLoot) ScenarioPlayer.lootEvent(l);
		assertEquals("Zulrah", server.getComposition().getName());
		assertEquals(12922, server.getItems().iterator().next().getId());

		l.addProperty("kind", "npc");
		NpcLootReceived npc = (NpcLootReceived) ScenarioPlayer.lootEvent(l);
		assertEquals("Zulrah", npc.getNpc().getName());
		assertEquals(2042, npc.getNpc().getId());

		l.addProperty("kind", "generic");
		l.addProperty("lootType", "EVENT");
		LootReceived generic = (LootReceived) ScenarioPlayer.lootEvent(l);
		assertEquals(LootRecordType.EVENT, generic.getType());
	}

	@Test
	public void fakeHitsplatIsOurs()
	{
		assertTrue(Fakes.myHit(25).isMine());
		assertEquals(25, Fakes.myHit(25).getAmount());
	}

	@Test
	public void gamevalNamesResolve()
	{
		JsonObject v = new JsonObject();
		v.addProperty("name", "raids_client_indungeon");
		assertEquals(5432, ScenarioPlayer.varbitId(v));
	}

	/** The player keeps the game's tick grouping: everything before a wait lands in one tick. */
	@Test
	public void playbackGroupsStepsByTick() throws IOException
	{
		ScenarioLibrary lib = new ScenarioLibrary(Files.createTempDirectory("anvil-scenarios"));
		lib.reload();
		List<Object> seen = new ArrayList<>();
		EventBus bus = new EventBus();
		bus.register(ChatMessage.class, seen::add, 0);
		bus.register(ActorDeath.class, seen::add, 0);
		Client client = Fakes.proxy(Client.class, Map.of());
		ScenarioPlayer player = new ScenarioPlayer(client, bus, (t, n, m, s) -> false, msg -> fail(msg));

		player.start("boss-pet", lib.get("boss-pet").resolve(Map.of("kc", "1999")));
		assertEquals(null, player.tick());
		assertEquals(3, seen.size()); // NPC death, KC line, pet line — same tick
		assertTrue(seen.get(0) instanceof ActorDeath);
		assertEquals("Your Scurrius kill count is: 1999.", ((ChatMessage) seen.get(1)).getMessage());
		assertEquals("boss-pet", player.tick()); // wait 1 elapses → the clog line, then done
		assertEquals("New item added to your collection log: Scurry", ((ChatMessage) seen.get(3)).getMessage());
		assertTrue(!player.isRunning());
	}

	@Test
	public void pvpKillIsHitThenDeathOfTheSamePlayer() throws IOException
	{
		ScenarioLibrary lib = new ScenarioLibrary(Files.createTempDirectory("anvil-scenarios"));
		lib.reload();
		List<Object> seen = new ArrayList<>();
		EventBus bus = new EventBus();
		bus.register(HitsplatApplied.class, seen::add, 0);
		bus.register(ActorDeath.class, seen::add, 0);
		Client client = Fakes.proxy(Client.class, Map.of());
		ScenarioPlayer player = new ScenarioPlayer(client, bus, (t, n, m, s) -> false, msg -> fail(msg));
		player.start("pvp-kill", lib.get("pvp-kill").resolve(Map.of()));
		for (int i = 0; i < 6 && player.isRunning(); i++)
		{
			player.tick();
		}
		assertEquals(2, seen.size());
		HitsplatApplied hit = (HitsplatApplied) seen.get(0);
		assertTrue(hit.getHitsplat().isMine());
		assertTrue(hit.getActor() instanceof net.runelite.api.Player);
		assertTrue(hit.getActor() == ((ActorDeath) seen.get(1)).getActor());
		assertEquals("Some Pker", hit.getActor().getName());
	}

	/** A run's var writes are undone by a restore step, back to what they were before the run. */
	@Test
	public void restorePutsVarsBack()
	{
		Map<Integer, Integer> varbits = new java.util.HashMap<>(Map.of(4069, 200, 14812, 1200));
		Map<Integer, Integer> varps = new java.util.HashMap<>();
		Client client = (Client) java.lang.reflect.Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(self, m, args) ->
			{
				switch (m.getName())
				{
					case "getVarbitValue":
						return varbits.getOrDefault((Integer) args[0], 0);
					case "setVarbit":
						varbits.put((Integer) args[0], (Integer) args[1]);
						return null;
					case "getVarpValue":
						return varps.getOrDefault((Integer) args[0], 0);
					case "setVarpValue":
						varps.put((Integer) args[0], (Integer) args[1]);
						return null;
					default:
						return null;
				}
			});
		List<Object> seen = new ArrayList<>();
		EventBus bus = new EventBus();
		bus.register(net.runelite.api.events.VarbitChanged.class, seen::add, 0);
		ScenarioPlayer player = new ScenarioPlayer(client, bus, (t, n, m, s) -> false, msg -> fail(msg));
		player.start("t", List.of(
			Scenario.parse("{\"name\":\"t\",\"steps\":[{\"varbit\":{\"name\":\"SLAYER_TASKS_COMPLETED\",\"add\":1}}]}", Scenario.Origin.LOCAL).steps.get(0),
			Scenario.parse("{\"name\":\"t\",\"steps\":[{\"varbit\":{\"name\":\"CA_POINTS\",\"base\":\"CA_THRESHOLD_ELITE\",\"add\":0}}]}", Scenario.Origin.LOCAL).steps.get(0),
			Scenario.parse("{\"name\":\"t\",\"steps\":[{\"varp\":{\"name\":\"DOM_LEVEL_3_COMPLETIONS\",\"add\":\"2\"}}]}", Scenario.Origin.LOCAL).steps.get(0),
			Scenario.parse("{\"name\":\"t\",\"steps\":[{\"wait\":1}]}", Scenario.Origin.LOCAL).steps.get(0),
			Scenario.parse("{\"name\":\"t\",\"steps\":[{\"restore\":{}}]}", Scenario.Origin.LOCAL).steps.get(0)));
		player.tick();
		assertEquals(201, (int) varbits.get(4069));
		assertEquals(1200, (int) varbits.get(14815));
		assertEquals(2, (int) varps.get(4810));
		assertEquals(3, seen.size());
		assertEquals("t", player.tick());
		assertEquals(200, (int) varbits.get(4069));
		assertEquals(0, (int) varbits.get(14815));
		assertEquals(0, (int) varps.get(4810));
		net.runelite.api.events.VarbitChanged last = (net.runelite.api.events.VarbitChanged) seen.get(seen.size() - 1);
		assertEquals(4810, last.getVarpId());
	}

	@Test
	public void addXpStacksOnRealXpAndDerivesTheLevel()
	{
		JsonObject s = new JsonObject();
		s.addProperty("skill", "fishing");
		s.addProperty("addXp", "5,000");
		StatChanged ev = ScenarioPlayer.statEvent(s, skill -> 1_000_000);
		assertEquals(1_005_000, ev.getXp());
		assertEquals(73, ev.getLevel());
	}

	@Test
	public void playerLootNamesTheVictim()
	{
		JsonObject s = new JsonObject();
		s.addProperty("victim", "Some Pker");
		com.google.gson.JsonArray items = new com.google.gson.JsonArray();
		JsonObject it = new JsonObject();
		it.addProperty("id", 13652);
		items.add(it);
		s.add("items", items);
		net.runelite.client.events.PlayerLootReceived ev = (net.runelite.client.events.PlayerLootReceived) ScenarioPlayer.playerLootEvent(s);
		assertEquals("Some Pker", ev.getPlayer().getName());
		assertEquals(13652, ev.getItems().iterator().next().getId());
	}

	@Test
	public void fakeHitTakenIsNotOurs()
	{
		assertTrue(!Fakes.hitTaken(30).isMine());
	}

	@Test
	public void statStepUppercasesSkill()
	{
		JsonObject s = new JsonObject();
		s.addProperty("skill", "Cooking");
		s.addProperty("xp", "13,034,431");
		s.addProperty("level", 99);
		StatChanged ev = ScenarioPlayer.statEvent(s);
		assertEquals(Skill.COOKING, ev.getSkill());
		assertEquals(13034431, ev.getXp());
		assertEquals(99, ev.getBoostedLevel());
	}
}
