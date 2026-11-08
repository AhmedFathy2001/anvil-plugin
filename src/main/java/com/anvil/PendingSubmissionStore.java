package com.anvil;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

import java.io.*;
import java.util.*;

@Slf4j
@Singleton
public class PendingSubmissionStore {
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
	public PendingSubmissionStore(Gson gson) {
		this.gson = gson.newBuilder().setPrettyPrinting().create();
	}

	public static class PendingSubmission {
		public int eventId;
		public int tileId;
		public int teamId;
		public int playerId;
		public int amount;
		public String label;
		public String note;
		public String screenshotFile;
		public long timestamp;
		// itemId / durationSeconds / inferred / note: read from proofs older builds saved (legacyFacts).
		public Integer itemId;
		public Integer durationSeconds;
		public String capturedRsn; // character the drop was obtained on; only re-submitted while on it
		public String inferred; // the item was worked out, not seen (DuplicateInference); the site flags it for review
		// Shared kills/runs: teammates seen + party size at the time (lib/coopRuns on the site). Null
		// when the tile doesn't ask; kept on disk so a retried proof still says who was there.
		public List<String> coopGroup;
		public int coopPartySize;
		// A proof the plugin can't auto-submit to a specific tile (pet drop, duplicate Champion's
		// scroll — the "would have received" line names no item), captured so the player has a ready
		// screenshot to attach when they submit by hand on the site. The retry loop skips these.
		public boolean manual;
		// What the site draws the proof's banner from (lib/proofStamp); null on an older capture.
		public JsonObject proof;
		// Another board's clan slug (TrackedTile.board), null for the active board: where a retry posts.
		public String clan;
		// The facts this screenshot proves, posted to /api/plugin/facts (the site
		// picks the tiles) instead of a submission for one tile.
		public JsonArray facts;
	}

	/**
	 * A proof an older build saved for one tile (no facts) as the same submission in a `tile` fact:
	 * its note marks it as one, and the site keeps that note and the saved banner.
	 */
	static JsonArray legacyFacts(PendingSubmission p) {
		JsonObject f = new JsonObject();
		f.addProperty("k", "tile");
		f.addProperty("t", p.tileId);
		f.addProperty("n", Math.max(1, p.amount));
		if (p.durationSeconds != null) f.addProperty("sec", p.durationSeconds);
		if (p.itemId != null) f.addProperty("item", p.itemId);
		if (p.inferred != null) f.addProperty("inf", p.inferred);
		f.addProperty("note", p.note == null ? "" : p.note);
		JsonArray a = new JsonArray();
		a.add(f);
		return a;
	}

	public void setRoot(Filepath dir) {
		this.root = dir;
	}

	/** The directory, created on demand, or null when there is nowhere to write. */
	private Filepath dir() {
		return DiskFiles.ensureDir(root);
	}

	public String save(PendingSubmission sub, byte[] pngBytes) {
		Filepath dir = dir();
		if (dir == null) return null;
		String id = sub.tileId + "-" + sub.timestamp;

		// Both atomic: a client killed mid-write leaves no half file for the retry loop to choke on.
		String ext = BingoApiClient.isJpeg(pngBytes) ? ".jpg" : ".png";
		Filepath png = dir.joinSegment(id + ext);
		try {
			DiskFiles.writeAtomic(png, pngBytes);
		} catch (IOException e) {
			log.error("Failed to save pending screenshot: {}", e.getMessage());
			return null;
		}

		sub.screenshotFile = id + ext;
		try {
			DiskFiles.writeAtomic(dir.joinSegment(id + ".json"), gson.toJson(sub));
		} catch (IOException e) {
			log.error("Failed to save pending submission metadata: {}", e.getMessage());
			// The PNG without its metadata is an orphan nothing will ever read.
			DiskFiles.deleteQuietly(png);
			return null;
		}

		log.info("Saved pending submission: {} (tile '{}')", id, sub.label);
		return id;
	}

	/**
	 * Every queued submission. Depth 1 rather than a full walk: the migration drops these at the root
	 * of the plugin directory alongside `sounds/`, and descending would read a banner clip's
	 * neighbours looking for JSON. An empty or truncated file reads as nothing and is skipped.
	 */
	public List<PendingSubmission> loadAll() {
		List<PendingSubmission> result = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Filepath f : DiskFiles.listFiles(dir(), n -> n.endsWith(".json"))) {
			PendingSubmission sub = DiskFiles.readJson(gson, f, PendingSubmission.class);
			if (sub == null) {
				log.warn("Failed to read pending submission {}", f.getFileName());
				continue;
			}
			if (sub.timestamp > 0 && (now - sub.timestamp) > MAX_AGE_MS) {
				log.info("Pruning expired pending submission '{}' ({} days old)",
					sub.label, (now - sub.timestamp) / (24 * 60 * 60 * 1000));
				remove(sub);
				continue;
			}
			if (sub.facts == null && !sub.manual) sub.facts = legacyFacts(sub);
			result.add(sub);
		}
		return result;
	}

	public byte[] readScreenshot(PendingSubmission sub) {
		Filepath dir = dir();
		if (dir == null || sub.screenshotFile == null) return null;
		// joinSegment, not join: it refuses a separator, so a screenshotFile read back out of a JSON
		// file cannot walk anywhere with "../" in it.
		try (InputStream in = dir.joinSegment(sub.screenshotFile).openInputStream()) {
			return in.readAllBytes();
		} catch (IOException | RuntimeException e) {
			log.error("Failed to read pending screenshot {}: {}", sub.screenshotFile, e.getMessage());
			return null;
		}
	}

	public void remove(PendingSubmission sub) {
		Filepath dir = dir();
		if (dir == null || sub.screenshotFile == null) return;
		String baseName = sub.screenshotFile.replaceAll("\\.(png|jpg)$", "");
		try {
			dir.joinSegment(sub.screenshotFile).deleteIfExists();
			dir.joinSegment(baseName + ".json").deleteIfExists();
			log.info("Removed pending submission: {} (tile '{}')", baseName, sub.label);
		} catch (IOException | RuntimeException e) {
			log.warn("Failed to remove pending submission {}: {}", baseName, e.getMessage());
		}
	}

}
