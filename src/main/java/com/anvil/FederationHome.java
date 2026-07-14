package com.anvil;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/**
 * One <em>extra</em> Anvil home the plugin is federated to, beyond the single primary site in
 * {@link AnvilConfig#apiUrl()}/{@link AnvilConfig#playerToken()} — a {@code {baseUrl, token}} pair
 * (Layer 0 manual multi-home; see {@code docs/FEDERATION.md} and {@code FEDERATION_WIRE.md} §4).
 *
 * <p><b>Additive by construction.</b> The plugin's primary connection (connection #0) is <em>not</em>
 * a {@link FederationHome} — it stays the existing single config poll/token so default behaviour is
 * byte-for-byte unchanged. These homes are opt-in extras parsed from
 * {@link AnvilConfig#federationHomes()}; an empty field yields an empty list ⇒ exactly one connection
 * (today's behaviour).</p>
 *
 * <p>Deliberately RuneLite-free and immutable so it (and its parser) are fully unit-testable. The
 * parser accepts two shapes so a member can paste whichever their site hands them:</p>
 * <ul>
 *   <li><b>Line/CSV form</b> — one home per line (or comma-separated), each
 *       {@code <baseUrl> <token>} split on whitespace or a {@code |} pipe, e.g.
 *       {@code https://clan-b.example.com  tok_abc123}. An optional third field is a label.</li>
 *   <li><b>JSON form</b> — a {@code [{"baseUrl":"…","token":"…","label":"…"}]} array (label optional).</li>
 * </ul>
 *
 * <p>Every {@code baseUrl} is passed through {@link BingoApiClient#normalizeBaseUrl} (same HTTPS/dev
 * gate the primary uses, so a plaintext host that would leak the bearer token is dropped, not sent).
 * Entries missing a usable baseUrl <em>or</em> token are skipped — a half-typed line never becomes a
 * live connection.</p>
 */
public final class FederationHome
{
	public final String baseUrl;
	public final String token;
	/** Optional human label for the clan filter; falls back to the polled clan name when blank. */
	public final String label;

	public FederationHome(String baseUrl, String token, String label)
	{
		this.baseUrl = baseUrl == null ? "" : baseUrl;
		this.token = token == null ? "" : token;
		this.label = label == null ? "" : label.trim();
	}

	/** True once the pair is usable — a normalized (HTTPS/dev) base URL and a non-blank token. */
	public boolean isUsable()
	{
		return !baseUrl.isEmpty() && !token.isEmpty();
	}

	/**
	 * Parse the opt-in multi-home config field into extra homes. Never returns {@code null}; a blank,
	 * whitespace-only, or entirely-unusable field returns an empty list (⇒ single-home default). Junk
	 * lines are skipped rather than throwing, so one bad paste never breaks the plugin's own polling.
	 *
	 * @param gson shared Gson (used only for the JSON shape)
	 * @param raw  the raw {@link AnvilConfig#federationHomes()} value
	 */
	public static List<FederationHome> parse(Gson gson, String raw)
	{
		List<FederationHome> out = new ArrayList<>();
		if (raw == null)
		{
			return out;
		}
		String trimmed = raw.trim();
		if (trimmed.isEmpty())
		{
			return out;
		}
		if (trimmed.startsWith("[") || trimmed.startsWith("{"))
		{
			parseJson(trimmed, out);
		}
		else
		{
			parseLines(trimmed, out);
		}
		return out;
	}

	private static void parseJson(String trimmed, List<FederationHome> out)
	{
		try
		{
			JsonElement root = new JsonParser().parse(trimmed);
			JsonArray arr;
			if (root.isJsonArray())
			{
				arr = root.getAsJsonArray();
			}
			else
			{
				arr = new JsonArray();
				arr.add(root); // tolerate a single bare object
			}
			for (JsonElement el : arr)
			{
				if (el == null || !el.isJsonObject())
				{
					continue;
				}
				JsonObject o = el.getAsJsonObject();
				String base = str(o, "baseUrl");
				if (base.isEmpty())
				{
					base = str(o, "url"); // tolerate the {url,token} shape too
				}
				add(out, base, str(o, "token"), str(o, "label"));
			}
		}
		catch (RuntimeException ignored)
		{
			// Malformed JSON — leave whatever we parsed; a bad field must not break polling.
		}
	}

	private static void parseLines(String trimmed, List<FederationHome> out)
	{
		// One home per line; also split on commas so a single-line comma-separated paste works.
		for (String line : trimmed.split("[\\r\\n,]+"))
		{
			String s = line.trim();
			if (s.isEmpty())
			{
				continue;
			}
			// Split base/token/label on a pipe or run of whitespace.
			String[] parts = s.split("\\s*\\|\\s*|\\s+", 3);
			String base = parts.length > 0 ? parts[0] : "";
			String token = parts.length > 1 ? parts[1] : "";
			String label = parts.length > 2 ? parts[2] : "";
			add(out, base, token, label);
		}
	}

	private static void add(List<FederationHome> out, String base, String token, String label)
	{
		String norm = BingoApiClient.normalizeBaseUrl(base);
		FederationHome home = new FederationHome(norm, token == null ? "" : token.trim(), label);
		if (home.isUsable())
		{
			out.add(home);
		}
	}

	private static String str(JsonObject o, String key)
	{
		JsonElement el = o.get(key);
		return el != null && el.isJsonPrimitive() ? el.getAsString().trim() : "";
	}
}
