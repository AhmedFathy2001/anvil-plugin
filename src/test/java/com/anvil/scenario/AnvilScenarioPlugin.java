package com.anvil.scenario;

import com.anvil.AnvilPlugin;
import com.anvil.BingoApiClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.loottracker.LootReceived;
import okhttp3.OkHttpClient;

/**
 * DEV CLIENT ONLY — lives in src/test, never in the hub jar. Replays scripted game events into the
 * running Anvil plugin so a tile can be tested without the grind (a 2,000-kc pet is one chat line),
 * and records real ones to build those scripts from. See src/test/resources/scenarios/README.md.
 */
@PluginDescriptor(
	name = "Anvil Scenario Lab",
	description = "DEV ONLY: replay/record game events into the Anvil plugin (::anvil help)",
	tags = {"anvil", "dev", "test"}
)
public class AnvilScenarioPlugin extends Plugin
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnvilScenarioPlugin.class);
	private static final String TAG = "<col=ff5fd7>[anvil-lab]</col> ";
	private static final String CFG_GROUP = "osrsbingo";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private ConfigManager configManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ScheduledExecutorService executor;

	private final SiteBridge bridge = new SiteBridge();
	private ScenarioLibrary library;
	private ScenarioPlayer player;
	private ScenarioRecorder recorder;
	private BingoApiClient apiClient;

	/** True while WE are emitting events, so the recorder never records its own replay. */
	private boolean injecting;
	private String echoExpected;
	private boolean echoSeen;

	static Path labDir()
	{
		return RuneLite.RUNELITE_DIR.toPath().resolve("anvil-scenarios");
	}

	@Override
	protected void startUp()
	{
		library = new ScenarioLibrary(labDir());
		player = new ScenarioPlayer(client, eventBus, this::addChatLine, this::print);
		apiClient = findApiClient();
		if (apiClient != null && bridge.install(apiClient))
		{
			String site = initialSite();
			if (site != null)
			{
				try
				{
					bridge.setSite(site);
					log.info("anvil-lab: plugin traffic re-hosted to {}", bridge.site());
				}
				catch (IllegalArgumentException e)
				{
					log.warn("anvil-lab: ignoring site override: {}", e.getMessage());
				}
			}
		}
		for (String err : library.reload())
		{
			log.warn("anvil-lab scenario: {}", err);
		}
	}

	@Override
	protected void shutDown()
	{
		if (player != null)
		{
			player.stop();
		}
		recorder = null;
	}

	// ── commands ─────────────────────────────────────────────────────────────────────────────

	@Subscribe
	public void onCommandExecuted(CommandExecuted e)
	{
		if (!"anvil".equalsIgnoreCase(e.getCommand()))
		{
			return;
		}
		String[] a = e.getArguments();
		String sub = a.length == 0 ? "help" : a[0].toLowerCase();
		switch (sub)
		{
			case "list":
				list();
				break;
			case "run":
				run(a);
				break;
			case "stop":
				player.stop();
				bridge.scenarioEnded();
				print("stopped");
				break;
			case "record":
				record(a);
				break;
			case "setvarb":
				if (a.length < 3)
				{
					print("usage: ::anvil setvarb <id|NAME> <value>");
					break;
				}
				runOneOff("varbit", varbitStep(a[1], a[2]));
				break;
			case "site":
				site(a);
				break;
			case "sync":
				sync();
				break;
			case "signin":
				signIn();
				break;
			case "reload":
				reload();
				break;
			default:
				help();
		}
	}

	private void help()
	{
		print("::anvil list | run <name> [k=v …] | stop | record on [varbits] | record off");
		print("::anvil setvarb <id|NAME> <value> | site [url|off] | signin | sync | reload");
		print("site: " + bridge.site() + (bridge.overridden() ? " (override)" : " (production!)"));
	}

	private void list()
	{
		reload();
		for (Scenario s : library.all())
		{
			StringBuilder p = new StringBuilder();
			for (Map.Entry<String, String> en : s.params.entrySet())
			{
				p.append(' ').append(en.getKey()).append('=').append(en.getValue());
			}
			print(s.name + " [" + s.origin.name().toLowerCase() + "]" + p + " — " + s.description);
		}
	}

	private void reload()
	{
		List<String> errors = library.reload();
		for (String err : errors)
		{
			print("bad scenario: " + err);
		}
		print(library.all().size() + " scenarios loaded");
	}

	private void run(String[] a)
	{
		if (a.length < 2)
		{
			print("usage: ::anvil run <name> [key=value …]");
			return;
		}
		library.reload();
		Scenario s = library.get(a[1]);
		if (s == null)
		{
			print("no scenario '" + a[1] + "' — ::anvil list");
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			print("log in first — scenarios advance on game ticks");
			return;
		}
		if (!bridge.overridden())
		{
			print("WARNING: no site override — this replay reaches anvilosrs.com (production). ::anvil site <url>");
		}
		List<JsonObject> steps;
		try
		{
			Map<String, String> args = Scenario.parseArgs(a, 2);
			// ${me}: the logged-in RSN, for lines the plugin only acts on when they name the local player.
			if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
			{
				args.putIfAbsent("me", client.getLocalPlayer().getName());
			}
			steps = s.resolve(args);
		}
		catch (IllegalArgumentException ex)
		{
			print(s.name + ": " + ex.getMessage());
			return;
		}
		bridge.scenarioStarted(s.name);
		player.start(s.name, steps);
		print("running " + s.name + " (" + steps.size() + " steps)");
	}

	private void runOneOff(String kind, JsonObject body)
	{
		JsonObject step = new JsonObject();
		step.add(kind, body);
		bridge.scenarioStarted("setvarb");
		player.start("setvarb", List.of(step));
	}

	private static JsonObject varbitStep(String idOrName, String value)
	{
		JsonObject v = new JsonObject();
		if (idOrName.chars().allMatch(Character::isDigit))
		{
			v.addProperty("id", Integer.parseInt(idOrName));
		}
		else
		{
			v.addProperty("name", idOrName);
		}
		v.addProperty("value", value);
		return v;
	}

	private void record(String[] a)
	{
		String mode = a.length > 1 ? a[1].toLowerCase() : "";
		if ("on".equals(mode))
		{
			boolean varbits = a.length > 2 && "varbits".equalsIgnoreCase(a[2]);
			recorder = new ScenarioRecorder(varbits);
			print("recording" + (varbits ? " (with varbits)" : "") + " — ::anvil record off to save");
		}
		else if ("off".equals(mode))
		{
			ScenarioRecorder r = recorder;
			recorder = null;
			if (r == null)
			{
				print("not recording");
				return;
			}
			try
			{
				Path file = r.save(library.localDir(), gson);
				print("saved " + r.size() + " steps → " + file.getFileName() + " (rename the file's \"name\" to keep it)");
			}
			catch (IOException ex)
			{
				print("save failed: " + ex.getMessage());
			}
		}
		else
		{
			print("usage: ::anvil record on [varbits] | off");
		}
	}

	private void site(String[] a)
	{
		if (a.length < 2)
		{
			print("site: " + bridge.site() + (bridge.overridden() ? " (override)" : " (production)"));
			return;
		}
		if (apiClient == null)
		{
			print("can't find the Anvil plugin's api client — override unavailable");
			return;
		}
		try
		{
			bridge.setSite(a[1]);
			Path f = labDir().resolve("site.txt");
			Files.createDirectories(f.getParent());
			if (bridge.overridden())
			{
				Files.writeString(f, bridge.site());
			}
			else
			{
				Files.deleteIfExists(f);
			}
			print("site → " + bridge.site() + ". Tokens are per site: ::anvil signin"
				+ (bridge.overridden() ? "" : " (or the side panel)"));
		}
		catch (IllegalArgumentException | IOException ex)
		{
			print(ex.getMessage());
		}
	}

	/** Device sign-in against the current site; on success the token goes where the side panel puts it. */
	private void signIn()
	{
		// Its own thread: the poll sleeps for minutes, and the shared executor belongs to every plugin.
		Thread t = new Thread(() ->
		{
			try
			{
				String token = bridge.signIn(okHttpClient, gson,
					msg -> clientThread.invokeLater(() -> print(msg)), net.runelite.client.util.LinkBrowser::browse);
				if (token != null)
				{
					// ConfigChanged → the Anvil plugin re-configures its api client with the new token.
					configManager.setConfiguration(CFG_GROUP, "playerToken", token);
					clientThread.invokeLater(() -> print("signed in to " + bridge.site()));
				}
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}, "anvil-lab-signin");
		t.setDaemon(true);
		t.start();
	}

	private void sync()
	{
		String token = configManager.getConfiguration(CFG_GROUP, "playerToken");
		String rsn = apiClient == null ? null : apiClient.getCurrentRsn();
		long hash = client.getAccountHash();
		print("syncing from " + bridge.site() + " …");
		executor.execute(() ->
		{
			SiteBridge.SyncResult r = bridge.sync(okHttpClient, gson, token, rsn, hash, library.siteDir());
			clientThread.invokeLater(() ->
			{
				if (r.error != null)
				{
					print(r.error);
				}
				print("synced " + r.count + " site scenarios");
				library.reload();
			});
		});
	}

	// ── playback ─────────────────────────────────────────────────────────────────────────────

	@Subscribe
	public void onGameTick(GameTick t)
	{
		if (player == null || !player.isRunning())
		{
			return;
		}
		injecting = true;
		try
		{
			String done = player.tick();
			if (done != null)
			{
				bridge.scenarioEnded();
				print("finished " + done);
			}
		}
		finally
		{
			injecting = false;
		}
	}

	/** ChatSink: through the client, so the line shows in the chatbox and fires ChatMessage as the game's would. */
	private boolean addChatLine(ChatMessageType type, String name, String message, String sender)
	{
		echoExpected = message;
		echoSeen = false;
		client.addChatMessage(type, name, message, sender);
		echoExpected = null;
		return echoSeen;
	}

	/**
	 * Harness output. CONSOLE is outside the plugin's credit gate; the two any-channel parsers
	 * (drop / clog broadcasts) are defused by never letting their trigger phrases through.
	 */
	private void print(String text)
	{
		String safe = text.replace("received a", "got a");
		boolean was = injecting;
		injecting = true;
		try
		{
			client.addChatMessage(ChatMessageType.CONSOLE, "", TAG + safe, null);
		}
		finally
		{
			injecting = was;
		}
	}

	// ── recording (and the chat echo check) ──────────────────────────────────────────────────

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (echoExpected != null && echoExpected.equals(e.getMessage()))
		{
			echoSeen = true;
		}
		ScenarioRecorder r = recorder;
		if (r != null && !injecting)
		{
			r.chat(client.getTickCount(), e.getType(), e.getName(), e.getMessage(), e.getSender());
		}
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived e)
	{
		ScenarioRecorder r = recorder;
		if (r != null && !injecting)
		{
			NPC npc = e.getNpc();
			r.loot(client.getTickCount(), "npc", npc.getName(), npc.getId(), npc.getCombatLevel(), null, e.getItems());
		}
	}

	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot e)
	{
		ScenarioRecorder r = recorder;
		if (r != null && !injecting && e.getComposition() != null)
		{
			r.loot(client.getTickCount(), "server", e.getComposition().getName(), e.getComposition().getId(),
				e.getComposition().getCombatLevel(), null, e.getItems());
		}
	}

	@Subscribe
	public void onLootReceived(LootReceived e)
	{
		ScenarioRecorder r = recorder;
		if (r != null && !injecting)
		{
			r.loot(client.getTickCount(), "generic", e.getName(), -1, e.getCombatLevel(),
				e.getType() == null ? null : e.getType().name(), e.getItems());
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		ScenarioRecorder r = recorder;
		if (r != null && !injecting && r.recordsVarbits())
		{
			r.varbit(client.getTickCount(), e.getVarbitId(), e.getValue());
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		ScenarioRecorder r = recorder;
		if (r != null && !injecting)
		{
			r.stat(client.getTickCount(), e.getSkill(), e.getXp(), e.getLevel(), e.getBoostedLevel());
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath e)
	{
		ScenarioRecorder r = recorder;
		if (r == null || injecting)
		{
			return;
		}
		if (e.getActor() == client.getLocalPlayer())
		{
			r.death(client.getTickCount(), true, null, -1);
		}
		else if (e.getActor() instanceof NPC)
		{
			NPC npc = (NPC) e.getActor();
			r.death(client.getTickCount(), false, npc.getName(), npc.getId());
		}
	}

	// ── wiring ───────────────────────────────────────────────────────────────────────────────

	/** ANVIL_SITE env / -Danvil.site win over the saved ::anvil site choice. */
	private static String initialSite()
	{
		String v = System.getProperty("anvil.site");
		if (v == null || v.isBlank())
		{
			v = System.getenv("ANVIL_SITE");
		}
		if (v == null || v.isBlank())
		{
			try
			{
				Path f = labDir().resolve("site.txt");
				v = Files.isRegularFile(f) ? Files.readString(f).trim() : null;
			}
			catch (IOException e)
			{
				v = null;
			}
		}
		return v == null || v.isBlank() ? null : v;
	}

	/** The running Anvil plugin's own BingoApiClient (a per-plugin-injector singleton, so not ours to inject). */
	private BingoApiClient findApiClient()
	{
		for (Plugin p : pluginManager.getPlugins())
		{
			if (p instanceof AnvilPlugin)
			{
				try
				{
					Field f = AnvilPlugin.class.getDeclaredField("apiClient");
					f.setAccessible(true);
					return (BingoApiClient) f.get(p);
				}
				catch (ReflectiveOperationException e)
				{
					log.warn("anvil-lab: AnvilPlugin.apiClient not found", e);
				}
			}
		}
		return null;
	}
}
