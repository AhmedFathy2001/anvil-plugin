package com.anvil;

import static org.junit.Assert.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Regression: Supporter flair once went onto the MessageNode's name, and the chatbox built the line's
 * right-click options from it — "Add friend ★Name" on a friend, and add / remove / message acting on
 * the tagged name. Flair is display-only (widget text). Nothing in the plugin may write a message's
 * name, sender or text.
 */
public class ChatFlairNoMessageEditTest
{
	private static final Pattern WRITES = Pattern.compile(
		"(?:getMessageNode\\(\\)|MessageNode\\b[^;]*)\\s*\\.\\s*set(?:Name|Sender|Value|RuneLiteFormatMessage)\\s*\\(");

	@Test
	public void noMessageNodeIsEverRewritten() throws Exception
	{
		try (Stream<Path> files = Files.walk(Paths.get("src/main/java")))
		{
			files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
				try
				{
					String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
					assertFalse(p + " writes a MessageNode (flair must paint widget text only)", WRITES.matcher(src).find());
				}
				catch (java.io.IOException e)
				{
					throw new RuntimeException(e);
				}
			});
		}
	}
}
