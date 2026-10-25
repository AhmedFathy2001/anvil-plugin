package com.anvil;

import com.google.gson.Gson;
import java.util.regex.Pattern;

/** The bundled chat-pattern defaults, for parser tests that run without the plugin's injected Gson. */
final class TestPatterns
{
	private TestPatterns() {}

	static Pattern get(String key)
	{
		ChatPatterns.loadDefaults(new Gson());
		return ChatPatterns.get(key);
	}
}
