package com.anvil;

import com.google.gson.Gson;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class PendingSubmissionStore
{
	// Garbage-collect pending submissions older than this. Prevents unbounded disk growth
	// when retries keep failing (e.g. site URL permanently wrong) and the user never clears.
	private static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000; // 7 days

	/**
	 * The plugin's own directory, handed over by AnvilPlugin — the only class allowed to ask for it.
	 *
	 * THE ROOT ITSELF, not a subfolder, and that is the migration's doing rather than a preference:
	 * `legacyDataDirectory` MOVES `.runelite/osrs-bingo-pending` to BE the plugin directory, so
	 * everything that folder held arrives at its root. Reading from anywhere else would mean not
	 * finding the very files the migration exists to carry across.
	 *
	 * Null until startUp resolves it, and null forever if that failed. Every method treats that as
	 * "no store": nothing queues, nothing loads, and the caller's own error path takes over — which
	 * is the same thing that happened when the disk was full.
	 */
	private volatile Filepath root;

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
		public Integer durationSeconds; // timed-tile clear time in seconds (nullable; non-null routes to submitTimed)
		public String capturedRsn; // character the drop was obtained on; only re-submitted while on it
		// A proof the plugin can't auto-submit to a specific tile (pet drop, duplicate Champion's
		// scroll — the "would have received" line names no item), captured so the player has a ready
		// screenshot to attach when they submit by hand on the site. The retry loop skips these.
		public boolean manual;
	}

	public void setRoot(Filepath dir)
	{
		this.root = dir;
	}

	/** The directory, created on demand, or null when there is nowhere to write. */
	private Filepath dir()
	{
		Filepath d = root;
		if (d == null)
		{
			return null;
		}
		try
		{
			if (!d.exists())
			{
				d.createDirectories();
			}
			return d;
		}
		catch (IOException e)
		{
			log.warn("Anvil: cannot create the pending-submission directory: {}", e.getMessage());
			return null;
		}
	}

	public String save(PendingSubmission sub, byte[] pngBytes)
	{
		Filepath dir = dir();
		if (dir == null)
		{
			return null;
		}
		String id = sub.tileId + "-" + sub.timestamp;

		Filepath png = dir.joinSegment(id + ".png");
		try
		{
			png.write(pngBytes);
		}
		catch (IOException e)
		{
			log.error("Failed to save pending screenshot: {}", e.getMessage());
			return null;
		}

		sub.screenshotFile = id + ".png";
		try (Writer w = dir.joinSegment(id + ".json").openWriter())
		{
			gson.toJson(sub, w);
		}
		catch (IOException e)
		{
			log.error("Failed to save pending submission metadata: {}", e.getMessage());
			// The PNG without its metadata is an orphan nothing will ever read.
			try
			{
				png.deleteIfExists();
			}
			catch (IOException ignored)
			{
				// Already logged the real failure; a leftover file is the lesser problem.
			}
			return null;
		}

		log.info("Saved pending submission: {} (tile '{}')", id, sub.label);
		return id;
	}

	public List<PendingSubmission> loadAll()
	{
		List<PendingSubmission> result = new ArrayList<>();
		List<Filepath> jsonFiles = listJson();
		long now = System.currentTimeMillis();
		for (Filepath f : jsonFiles)
		{
			try (Reader r = f.openReader())
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
				log.warn("Failed to read pending submission {}: {}", f.getFileName(), e.getMessage());
			}
		}
		return result;
	}

	/**
	 * The queued metadata files, one per waiting submission.
	 *
	 * Depth 1 rather than a full walk: the migration drops these at the root of the plugin directory
	 * alongside `sounds/` and `debug/`, and descending would read a banner clip's neighbours looking
	 * for JSON.
	 */
	private List<Filepath> listJson()
	{
		Filepath dir = dir();
		if (dir == null)
		{
			return new ArrayList<>();
		}
		try (java.util.stream.Stream<Filepath> walk = dir.walk(1))
		{
			return walk
				.filter(f -> f.isFile() && f.getFileName().endsWith(".json"))
				.collect(Collectors.toList());
		}
		catch (IOException e)
		{
			log.warn("Failed to list pending submissions: {}", e.getMessage());
			return new ArrayList<>();
		}
	}

	public byte[] readScreenshot(PendingSubmission sub)
	{
		Filepath dir = dir();
		if (dir == null || sub.screenshotFile == null)
		{
			return null;
		}
		// joinSegment, not join: it refuses a separator, so a screenshotFile read back out of a JSON
		// file cannot walk anywhere with "../" in it.
		try (InputStream in = dir.joinSegment(sub.screenshotFile).openInputStream())
		{
			return readAll(in);
		}
		catch (IOException | RuntimeException e)
		{
			log.error("Failed to read pending screenshot {}: {}", sub.screenshotFile, e.getMessage());
			return null;
		}
	}

	private static byte[] readAll(InputStream in) throws IOException
	{
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) > 0)
		{
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	public void remove(PendingSubmission sub)
	{
		Filepath dir = dir();
		if (dir == null || sub.screenshotFile == null)
		{
			return;
		}
		String baseName = sub.screenshotFile.replace(".png", "");
		try
		{
			dir.joinSegment(baseName + ".png").deleteIfExists();
			dir.joinSegment(baseName + ".json").deleteIfExists();
			log.info("Removed pending submission: {} (tile '{}')", baseName, sub.label);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Failed to remove pending submission {}: {}", baseName, e.getMessage());
		}
	}

	/** Number of submissions still waiting to upload. */
	public int count()
	{
		return listJson().size();
	}

	/** Puts the pending-proofs folder on the clipboard, off the calling thread. */
	public void copyFolderPath()
	{
		Filepath dir = dir();
		if (dir == null)
		{
			return;
		}
		// LinkBrowser::open is restricted for hub releases, so the path goes on the clipboard and the
		// player pastes it wherever they were going to open it.
		Clipboards.copy(dir.toString());
	}
}
