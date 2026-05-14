package com.osrsbingo;

import com.google.gson.Gson;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Singleton
public class PendingSubmissionStore
{
	private static final File PENDING_DIR = new File(RuneLite.RUNELITE_DIR, "osrs-bingo-pending");

	// Garbage-collect pending submissions older than this. Prevents unbounded disk growth
	// when retries keep failing (e.g. site URL permanently wrong) and the user never clears.
	private static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000; // 7 days

	private final Gson gson;

	@Inject
	public PendingSubmissionStore(Gson gson)
	{
		this.gson = gson.newBuilder().setPrettyPrinting().create();
	}

	public static class PendingSubmission
	{
		public int eventId;
		public int tileId;
		public int teamId;
		public int playerId;
		public int amount;
		public String label;
		public String note;
		public String screenshotFile; // filename of PNG in pending dir
		public long timestamp;
		public Integer itemId; // specific item ID for per-item tracking (nullable)
	}

	public void init()
	{
		if (!PENDING_DIR.exists())
		{
			PENDING_DIR.mkdirs();
		}
	}

	public String save(PendingSubmission sub, byte[] pngBytes)
	{
		init();
		String id = sub.tileId + "-" + sub.timestamp;

		File pngFile = new File(PENDING_DIR, id + ".png");
		try (FileOutputStream fos = new FileOutputStream(pngFile))
		{
			fos.write(pngBytes);
		}
		catch (IOException e)
		{
			log.error("Failed to save pending screenshot: {}", e.getMessage());
			return null;
		}

		sub.screenshotFile = id + ".png";
		File jsonFile = new File(PENDING_DIR, id + ".json");
		try (Writer w = new FileWriter(jsonFile))
		{
			gson.toJson(sub, w);
		}
		catch (IOException e)
		{
			log.error("Failed to save pending submission metadata: {}", e.getMessage());
			pngFile.delete();
			return null;
		}

		log.info("Saved pending submission: {} (tile '{}')", id, sub.label);
		return id;
	}

	public List<PendingSubmission> loadAll()
	{
		init();
		List<PendingSubmission> result = new ArrayList<>();
		File[] jsonFiles = PENDING_DIR.listFiles((dir, name) -> name.endsWith(".json"));
		if (jsonFiles == null)
		{
			return result;
		}
		long now = System.currentTimeMillis();
		for (File f : jsonFiles)
		{
			try (Reader r = new FileReader(f))
			{
				PendingSubmission sub = gson.fromJson(r, PendingSubmission.class);
				if (sub != null && sub.timestamp > 0 && (now - sub.timestamp) > MAX_AGE_MS)
				{
					log.info("Pruning expired pending submission '{}' ({} days old)",
						sub.label, (now - sub.timestamp) / (24 * 60 * 60 * 1000));
					remove(sub);
					continue;
				}
				result.add(sub);
			}
			catch (Exception e)
			{
				log.warn("Failed to read pending submission {}: {}", f.getName(), e.getMessage());
			}
		}
		return result;
	}

	public byte[] readScreenshot(PendingSubmission sub)
	{
		File pngFile = new File(PENDING_DIR, sub.screenshotFile);
		try
		{
			return Files.readAllBytes(pngFile.toPath());
		}
		catch (IOException e)
		{
			log.error("Failed to read pending screenshot {}: {}", sub.screenshotFile, e.getMessage());
			return null;
		}
	}

	public void remove(PendingSubmission sub)
	{
		String baseName = sub.screenshotFile.replace(".png", "");
		new File(PENDING_DIR, baseName + ".png").delete();
		new File(PENDING_DIR, baseName + ".json").delete();
		log.info("Removed pending submission: {} (tile '{}')", baseName, sub.label);
	}
}
