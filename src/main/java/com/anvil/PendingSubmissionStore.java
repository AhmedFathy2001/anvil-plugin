package com.anvil;

import lombok.Setter;
import com.google.gson.*;
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
	static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000; // 7 days

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
	@Setter volatile Filepath root;

	final Gson gson;

	@Inject
	PendingSubmissionStore(Gson gson) {
		this.gson = gson.newBuilder().setPrettyPrinting().create();
	}

	static class PendingSubmission {
		int eventId;
		int tileId;
		int teamId;
		int amount;
		String label;
		String note;
		String screenshotFile;
		long timestamp;
		// itemId / durationSeconds / inferred / note: read from proofs older builds saved (legacyFacts).
		Integer itemId;
		Integer durationSeconds;
		String capturedRsn; // character the drop was obtained on; only re-submitted while on it
		String inferred; // the item was worked out, not seen (DuplicateInference); the site flags it for review
		// Shared kills/runs: teammates seen + party size at the time (lib/coopRuns on the site). Null
		// when the tile doesn't ask; kept on disk so a retried proof still says who was there.
		List<String> coopGroup;
		int coopPartySize;
		// A proof the plugin can't auto-submit to a specific tile (pet drop, duplicate Champion's
		// scroll — the "would have received" line names no item), captured so the player has a ready
		// screenshot to attach when they submit by hand on the site. The retry loop skips these.
		boolean manual;
		// What the site draws the proof's banner from (lib/proofStamp); null on an older capture.
		JsonObject proof;
		// Another board's clan slug (TrackedTile.board), null for the active board: where a retry posts.
		String clan;
		// The facts this screenshot proves still to credit (posted image-less to /api/plugin/facts; the
		// site picks the tiles), and each credit's proof key → its tiles' labels, the screenshot still owed.
		JsonArray facts;
		JsonObject keys = new JsonObject();
		long keyAt; // when the last key came: the site reverses a credit a minute after it
	}

	/**
	 * A proof an older build saved for one tile (no facts) as the same submission in a `tile` fact:
	 * its note marks it as one, and the site keeps that note and the saved banner.
	 */
	static JsonArray legacyFacts(PendingSubmission p) {
		var f = new JsonObject();
		f.addProperty("k", "tile");
		f.addProperty("t", p.tileId);
		f.addProperty("n", Math.max(1, p.amount));
		if (p.durationSeconds != null) f.addProperty("sec", p.durationSeconds);
		if (p.itemId != null) f.addProperty("item", p.itemId);
		if (p.inferred != null) f.addProperty("inf", p.inferred);
		f.addProperty("note", p.note == null ? "" : p.note);
		var a = new JsonArray();
		a.add(f);
		return a;
	}

	/** The directory, created on demand, or null when there is nowhere to write. */
	Filepath dir() {
		return DiskFiles.ensureDir(root);
	}

	String save(PendingSubmission sub, byte[] pngBytes) {
		var dir = dir();
		if (dir == null) return null;
		var id = sub.tileId + "-" + sub.timestamp;

		// Both atomic: a client killed mid-write leaves no half file for the retry loop to choke on.
		var ext = BingoApiClient.isJpeg(pngBytes) ? ".jpg" : ".png";
		var png = dir.joinSegment(id + ext);
		try {
			DiskFiles.writeAtomic(png, pngBytes);
		} catch (IOException e) {
			log.error("Proof image save failed: {}", e.getMessage());
			return null;
		}

		sub.screenshotFile = id + ext;
		try {
			DiskFiles.writeAtomic(dir.joinSegment(id + ".json"), gson.toJson(sub));
		} catch (IOException e) {
			log.error("Proof save failed: {}", e.getMessage());
			// The PNG without its metadata is an orphan nothing will ever read.
			DiskFiles.deleteQuietly(png);
			return null;
		}

		log.info("Saved proof {}", id);
		return id;
	}

	/**
	 * Every queued submission. Depth 1 rather than a full walk: the migration drops these at the root
	 * of the plugin directory alongside `sounds/`, and descending would read a banner clip's
	 * neighbours looking for JSON. An empty or truncated file reads as nothing and is skipped.
	 */
	List<PendingSubmission> loadAll() {
		List<PendingSubmission> result = new ArrayList<>();
		var now = System.currentTimeMillis();
		for (Filepath f : DiskFiles.listFiles(dir(), n -> n.endsWith(".json"))) {
			var sub = DiskFiles.readJson(gson, f, PendingSubmission.class);
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

	/** The facts the site's answer says to send again (their filing failed for a passing reason), or null. */
	static JsonArray retries(JsonObject reply) {
		var a = reply != null && reply.has("retry") && reply.get("retry").isJsonArray() ? reply.getAsJsonArray("retry") : null;
		return a == null || a.size() == 0 ? null : a;
	}

	/** Rewrite a saved proof's metadata (what is still to credit and upload); its screenshot stays. */
	void update(PendingSubmission sub) {
		var dir = dir();
		if (dir == null || sub.screenshotFile == null) return;
		try {
			DiskFiles.writeAtomic(dir.joinSegment(sub.screenshotFile.replaceAll("\\.(png|jpg)$", "") + ".json"), gson.toJson(sub));
		} catch (IOException e) {
			log.error("Proof update failed: {}", e.getMessage());
		}
	}

	byte[] readScreenshot(PendingSubmission sub) {
		var dir = dir();
		if (dir == null || sub.screenshotFile == null) return null;
		// joinSegment, not join: it refuses a separator, so a screenshotFile read back out of a JSON
		// file cannot walk anywhere with "../" in it.
		try (InputStream in = dir.joinSegment(sub.screenshotFile).openInputStream()) {
			return in.readAllBytes();
		} catch (IOException | RuntimeException e) {
			log.error("Unreadable proof {}: {}", sub.screenshotFile, e.getMessage());
			return null;
		}
	}

	void remove(PendingSubmission sub) {
		var dir = dir();
		if (dir == null || sub.screenshotFile == null) return;
		var baseName = sub.screenshotFile.replaceAll("\\.(png|jpg)$", "");
		try {
			dir.joinSegment(sub.screenshotFile).deleteIfExists();
			dir.joinSegment(baseName + ".json").deleteIfExists();
			log.info("Removed proof {}", baseName);
		} catch (IOException | RuntimeException e) {
			log.warn("Can't remove proof {}: {}", baseName, e.getMessage());
		}
	}

}
