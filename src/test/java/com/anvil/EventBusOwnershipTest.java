package com.anvil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every {@code @Subscribe} lives on {@link AnvilPlugin}, and nowhere else.
 *
 * <h2>Why this is a rule and not a preference</h2>
 *
 * <p>RuneLite registers the Plugin on the event bus. It does not register the plugin's collaborators,
 * so a {@code @Subscribe} on one of them compiles, reads correctly, and never fires — which is
 * exactly what happened to {@code onChatMessage} when the chat routing moved out: the annotation went
 * with it, the delegator did not, and every chat-driven credit (kill counts, collection-log unlocks,
 * personal bests, diaries, combat achievements, drop attribution, pets) silently stopped.</p>
 *
 * <p>Keeping every handler on the plugin as a thin delegator also preserves ORDER by construction.
 * Four orderings are load-bearing — most of all the kill-count branch running before the
 * collection-log branch, since the unlock is stamped with the count the KC line just recorded.
 * Separate listeners would put that at the mercy of registration order.</p>
 */
public class EventBusOwnershipTest
{
	@Test
	public void everySubscribeLivesOnThePlugin() throws IOException
	{
		List<String> strays = new ArrayList<>();
		int onPlugin = 0;
		for (Path f : mainSources())
		{
			String body = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
			int count = countOutsideComments(body);
			if (count == 0)
			{
				continue;
			}
			if (f.getFileName().toString().equals("AnvilPlugin.java"))
			{
				onPlugin = count;
			}
			else
			{
				strays.add(f.getFileName() + " has " + count);
			}
		}
		assertEquals("@Subscribe outside AnvilPlugin never fires — RuneLite registers the Plugin, "
			+ "not its collaborators. Put a thin delegator on AnvilPlugin instead: " + strays,
			0, strays.size());
		assertTrue("no @Subscribe found on AnvilPlugin at all — the scan is broken", onPlugin > 10);
	}

	/**
	 * The annotation in a line that is not a {@code //} comment or a javadoc body — written plain or
	 * fully qualified, since {@code @net.runelite.client.eventbus.Subscribe} is the same annotation
	 * and just as invisible to a reader skimming for the word.
	 */
	private static final java.util.regex.Pattern SUBSCRIBE =
		java.util.regex.Pattern.compile("^@(?:[\\w.]+\\.)?Subscribe\\b");

	private static int countOutsideComments(String body)
	{
		int n = 0;
		for (String line : body.split("\n"))
		{
			String t = line.trim();
			if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))
			{
				continue;
			}
			if (SUBSCRIBE.matcher(t).find())
			{
				n++;
			}
		}
		return n;
	}

	private static List<Path> mainSources() throws IOException
	{
		Path root = Paths.get("src", "main", "java", "com", "anvil");
		if (!Files.isDirectory(root))
		{
			// Gradle may run tests from the project root or the module dir; try one level up.
			root = Paths.get("..", "src", "main", "java", "com", "anvil");
		}
		assertTrue("main sources not found at " + root.toAbsolutePath(), Files.isDirectory(root));
		try (Stream<Path> walk = Files.walk(root))
		{
			List<Path> out = new ArrayList<>();
			walk.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
			assertTrue("found no sources under " + root, out.size() > 50);
			return out;
		}
	}
}
