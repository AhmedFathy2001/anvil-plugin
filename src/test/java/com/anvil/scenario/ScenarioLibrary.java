package com.anvil.scenario;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every scenario the harness knows, keyed by name. Three sources, loaded in order so a later one
 * shadows an earlier one of the same name: bundled (src/test/resources/scenarios, listed in
 * index.txt because a classpath directory can't be enumerated reliably), local
 * (~/.runelite/anvil-scenarios/*.json, which includes recordings), then site
 * (~/.runelite/anvil-scenarios/site/*.json, written by {@code ::anvil sync}).
 */
public final class ScenarioLibrary
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ScenarioLibrary.class);

	static final String BUNDLED_INDEX = "/scenarios/index.txt";

	private final Path localDir;
	private final Map<String, Scenario> byName = new TreeMap<>();
	private final List<String> errors = new ArrayList<>();

	public ScenarioLibrary(Path localDir)
	{
		this.localDir = localDir;
	}

	public Path localDir()
	{
		return localDir;
	}

	public Path siteDir()
	{
		return localDir.resolve("site");
	}

	/** Reload everything. Returns the per-file parse errors (a bad file never hides the good ones). */
	public synchronized List<String> reload()
	{
		byName.clear();
		errors.clear();
		for (String file : bundledFiles())
		{
			try (InputStream in = ScenarioLibrary.class.getResourceAsStream("/scenarios/" + file))
			{
				if (in == null)
				{
					errors.add("bundled " + file + ": listed in index.txt but missing");
					continue;
				}
				add(Scenario.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), Scenario.Origin.BUNDLED));
			}
			catch (IOException | RuntimeException e)
			{
				errors.add("bundled " + file + ": " + e.getMessage());
			}
		}
		loadDir(localDir, Scenario.Origin.LOCAL);
		loadDir(siteDir(), Scenario.Origin.SITE);
		return new ArrayList<>(errors);
	}

	public synchronized Scenario get(String name)
	{
		return byName.get(name);
	}

	public synchronized List<Scenario> all()
	{
		return new ArrayList<>(byName.values());
	}

	private void add(Scenario s)
	{
		byName.put(s.name, s);
	}

	private void loadDir(Path dir, Scenario.Origin origin)
	{
		if (!Files.isDirectory(dir))
		{
			return;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json"))
		{
			for (Path f : files)
			{
				try
				{
					add(Scenario.parse(Files.readString(f), origin));
				}
				catch (IOException | RuntimeException e)
				{
					errors.add(f.getFileName() + ": " + e.getMessage());
				}
			}
		}
		catch (IOException e)
		{
			errors.add(dir + ": " + e.getMessage());
		}
	}

	/** File names from the bundled index — blank lines and # comments ignored. */
	public static List<String> bundledFiles()
	{
		List<String> out = new ArrayList<>();
		try (InputStream in = ScenarioLibrary.class.getResourceAsStream(BUNDLED_INDEX))
		{
			if (in == null)
			{
				return out;
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = r.readLine()) != null)
			{
				line = line.trim();
				if (!line.isEmpty() && !line.startsWith("#"))
				{
					out.add(line);
				}
			}
		}
		catch (IOException e)
		{
			log.warn("scenario index unreadable", e);
		}
		return out;
	}
}
