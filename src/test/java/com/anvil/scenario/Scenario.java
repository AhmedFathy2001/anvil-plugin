package com.anvil.scenario;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One scripted sequence of fake game events. The on-disk shape is the contract the site's Tile Lab
 * mirrors (see scenarios/README.md), so parsing is strict about structure and lenient only where the
 * spec says so (numbers may be written as strings, which is what lets a placeholder stand in for one).
 */
public final class Scenario
{
	/** Where a scenario came from — shown in {@code ::anvil list}. A later origin shadows an earlier one. */
	public enum Origin
	{
		BUNDLED, LOCAL, SITE
	}

	static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");
	static final List<String> STEP_KINDS = List.of("wait", "chat", "loot", "varbit", "stat", "death", "pvpKill");

	public final String name;
	public final String description;
	public final Map<String, String> params;
	/** Raw step objects; placeholders are resolved per run by {@link #resolve}. */
	public final List<JsonObject> steps;
	public final Origin origin;

	private Scenario(String name, String description, Map<String, String> params, List<JsonObject> steps, Origin origin)
	{
		this.name = name;
		this.description = description;
		this.params = params;
		this.steps = steps;
		this.origin = origin;
	}

	public static Scenario parse(String json, Origin origin)
	{
		return fromJson(new JsonParser().parse(json).getAsJsonObject(), origin);
	}

	public static Scenario fromJson(JsonObject o, Origin origin)
	{
		String name = string(o, "name");
		if (name == null || !NAME.matcher(name).matches())
		{
			throw new IllegalArgumentException("scenario name must match " + NAME.pattern() + ": " + name);
		}
		String description = string(o, "description");
		Map<String, String> params = new LinkedHashMap<>();
		if (o.has("params") && o.get("params").isJsonObject())
		{
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("params").entrySet())
			{
				params.put(e.getKey(), e.getValue().getAsString());
			}
		}
		if (!o.has("steps") || !o.get("steps").isJsonArray())
		{
			throw new IllegalArgumentException(name + ": missing steps array");
		}
		List<JsonObject> steps = new ArrayList<>();
		JsonArray arr = o.getAsJsonArray("steps");
		for (int i = 0; i < arr.size(); i++)
		{
			if (!arr.get(i).isJsonObject())
			{
				throw new IllegalArgumentException(name + ": step " + i + " is not an object");
			}
			JsonObject step = arr.get(i).getAsJsonObject();
			if (step.size() != 1 || !STEP_KINDS.contains(kindOf(step)))
			{
				throw new IllegalArgumentException(name + ": step " + i + " must have exactly one of " + STEP_KINDS);
			}
			steps.add(step);
		}
		return new Scenario(name, description == null ? "" : description,
			Collections.unmodifiableMap(params), Collections.unmodifiableList(steps), origin);
	}

	public static String kindOf(JsonObject step)
	{
		return step.keySet().iterator().next();
	}

	/** The steps with every {@code ${param}} filled from the defaults overlaid with {@code args}. */
	public List<JsonObject> resolve(Map<String, String> args)
	{
		Map<String, String> values = new LinkedHashMap<>(params);
		values.putAll(args);
		List<JsonObject> out = new ArrayList<>(steps.size());
		for (JsonObject step : steps)
		{
			out.add(substitute(step, values).getAsJsonObject());
		}
		return out;
	}

	private static JsonElement substitute(JsonElement e, Map<String, String> values)
	{
		if (e.isJsonObject())
		{
			JsonObject copy = new JsonObject();
			for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet())
			{
				copy.add(en.getKey(), substitute(en.getValue(), values));
			}
			return copy;
		}
		if (e.isJsonArray())
		{
			JsonArray copy = new JsonArray();
			for (JsonElement child : e.getAsJsonArray())
			{
				copy.add(substitute(child, values));
			}
			return copy;
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString())
		{
			Matcher m = PLACEHOLDER.matcher(e.getAsString());
			StringBuffer sb = new StringBuffer();
			while (m.find())
			{
				String v = values.get(m.group(1));
				if (v == null)
				{
					throw new IllegalArgumentException("no value for ${" + m.group(1) + "}");
				}
				m.appendReplacement(sb, Matcher.quoteReplacement(v));
			}
			m.appendTail(sb);
			return new JsonPrimitive(sb.toString());
		}
		return e;
	}

	/**
	 * {@code key=value} pairs from a command line. RuneLite splits {@code ::} commands on spaces, so a
	 * token without {@code =} continues the previous value: {@code boss=Phantom Muspah} survives.
	 */
	public static Map<String, String> parseArgs(String[] tokens, int from)
	{
		Map<String, String> args = new LinkedHashMap<>();
		String key = null;
		for (int i = from; i < tokens.length; i++)
		{
			String t = tokens[i];
			int eq = t.indexOf('=');
			if (eq > 0)
			{
				key = t.substring(0, eq);
				args.put(key, t.substring(eq + 1));
			}
			else if (key != null)
			{
				args.put(key, args.get(key) + " " + t);
			}
		}
		return args;
	}

	static String string(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	/** An int field that may be written as a number or a (placeholder-resolved) string. */
	static int integer(JsonObject o, String key, int fallback)
	{
		if (!o.has(key) || o.get(key).isJsonNull())
		{
			return fallback;
		}
		return Integer.parseInt(o.get(key).getAsString().replace(",", "").trim());
	}
}
