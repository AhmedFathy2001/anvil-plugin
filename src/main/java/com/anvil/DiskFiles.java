package com.anvil;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import net.runelite.client.util.*;

/**
 * The few disk primitives the caches and the outbox share, all through RuneLite's {@link Filepath}.
 *
 * <p>Writes are atomic: the bytes go to a temp sibling first and are then renamed over the target, so
 * a client killed mid-write leaves the previous copy (or nothing), never half a JSON file. Every
 * method here blocks on disk — callers run them on the plugin executor, never the client thread.
 */
class DiskFiles {
	/**
	 * A fixed table the plugin ships as a resource beside this class: the JSON file's {@code rows},
	 * each a list of strings (the file's {@code _about} says what the table is for). Empty when it
	 * can't be read.
	 */
	static List<List<String>> table(String resource) {
		var out = new ArrayList<List<String>>();
		try (var r = new InputStreamReader(DiskFiles.class.getResourceAsStream(resource), StandardCharsets.UTF_8)) {
			for (var row : new JsonParser().parse(r).getAsJsonObject().getAsJsonArray("rows")) {
				var l = new ArrayList<String>();
				for (var c : row.getAsJsonArray()) l.add(c.getAsString());
				out.add(l);
			}
		} catch (Exception e) {
		}
		return out;
	}

	/** {@code dir}, created on demand; null when it can't be. */
	static Filepath ensureDir(Filepath dir) {
		if (dir == null) return null;
		try {
			if (!dir.exists()) dir.createDirectories();
			return dir;
		} catch (Exception e) {
			return null;
		}
	}

	static void writeAtomic(Filepath target, byte[] data) throws IOException {
		OffThread.check("disk write");
		var tmp = target.getParent().joinSegment(target.getFileName() + ".tmp");
		tmp.write(data);
		try {
			tmp.moveTo(target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			tmp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static void writeAtomic(Filepath target, String text) throws IOException {
		writeAtomic(target, text.getBytes(StandardCharsets.UTF_8));
	}

	/** The file's bytes, or null when it is missing or unreadable. */
	static byte[] read(Filepath file) {
		OffThread.check("disk read");
		if (file == null || !file.isFile()) return null;
		try (var in = file.openInputStream()) {
			return in.readAllBytes();
		} catch (Exception e) {
			return null;
		}
	}

	/** The file as a {@code type}, or null when it is missing, empty or not valid JSON (a truncated write). */
	static <T> T readJson(Gson gson, Filepath file, Class<T> type) {
		var b = read(file);
		try {
			return b == null ? null : gson.fromJson(new String(b, StandardCharsets.UTF_8), type);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The files directly in {@code dir} whose names pass {@code name}; empty when there is no listing. */
	static List<Filepath> listFiles(Filepath dir, Predicate<String> name) {
		if (dir == null) return List.of();
		try (var walk = dir.walk(1)) {
			return walk.filter(f -> f.isFile() && name.test(f.getFileName())).collect(Collectors.toList());
		} catch (Exception e) {
			return List.of();
		}
	}

	static void deleteQuietly(Filepath file) {
		if (file == null) return;
		try {
			file.deleteIfExists();
		} catch (Exception ignored) {
			// Best effort: a leftover file is pruned by age on the next load.
		}
	}

	/**
	 * A short, stable, filename-safe stand-in for something that must never appear on disk itself
	 * (an Account Token) or that shouldn't be readable from a directory listing (a character).
	 */
	static String fingerprint(String secret) {
		if (secret == null || secret.trim().isEmpty()) return "";
		try {
			// The first 8 bytes as 16 hex digits.
			return String.format("%016x", new java.math.BigInteger(1, Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(secret.trim().getBytes(StandardCharsets.UTF_8)), 8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
