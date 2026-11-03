package com.anvil;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.*;
import net.runelite.client.util.Filepath;

/**
 * The few disk primitives the caches and the outbox share, all through RuneLite's {@link Filepath}.
 *
 * <p>Writes are atomic: the bytes go to a temp sibling first and are then renamed over the target, so
 * a client killed mid-write leaves the previous copy (or nothing), never half a JSON file. Every
 * method here blocks on disk — callers run them on the plugin executor, never the client thread.
 */
final class DiskFiles
{
	private DiskFiles()
	{
	}

	/** {@code dir}, created on demand; null when it can't be. */
	static Filepath ensureDir(Filepath dir)
	{
		if (dir == null)
		{
			return null;
		}
		try
		{
			if (!dir.exists())
			{
				dir.createDirectories();
			}
			return dir;
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	static void writeAtomic(Filepath target, byte[] data) throws IOException
	{
		OffThread.check("disk write");
		Filepath tmp = target.getParent().joinSegment(target.getFileName() + ".tmp");
		tmp.write(data);
		try
		{
			tmp.moveTo(target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException e)
		{
			tmp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static void writeAtomic(Filepath target, String text) throws IOException
	{
		writeAtomic(target, text.getBytes(StandardCharsets.UTF_8));
	}

	/** The file's bytes, or null when it is missing or unreadable. */
	static byte[] read(Filepath file)
	{
		OffThread.check("disk read");
		if (file == null || !file.isFile())
		{
			return null;
		}
		try (InputStream in = file.openInputStream())
		{
			return in.readAllBytes();
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	static String readText(Filepath file)
	{
		byte[] b = read(file);
		return b == null ? null : new String(b, StandardCharsets.UTF_8);
	}

	/** The file as a {@code type}, or null when it is missing, empty or not valid JSON (a truncated write). */
	static <T> T readJson(Gson gson, Filepath file, Class<T> type)
	{
		String text = readText(file);
		try
		{
			return text == null ? null : gson.fromJson(text, type);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** The files directly in {@code dir} whose names pass {@code name}; empty when there is no listing. */
	static List<Filepath> listFiles(Filepath dir, Predicate<String> name)
	{
		if (dir == null)
		{
			return new ArrayList<>();
		}
		try (Stream<Filepath> walk = dir.walk(1))
		{
			return walk.filter(f -> f.isFile() && name.test(f.getFileName())).collect(Collectors.toList());
		}
		catch (IOException | RuntimeException e)
		{
			return new ArrayList<>();
		}
	}

	static void deleteQuietly(Filepath file)
	{
		if (file == null)
		{
			return;
		}
		try
		{
			file.deleteIfExists();
		}
		catch (IOException | RuntimeException ignored)
		{
			// Best effort: a leftover file is pruned by age on the next load.
		}
	}

	/**
	 * A short, stable, filename-safe stand-in for something that must never appear on disk itself
	 * (an Account Token) or that shouldn't be readable from a directory listing (a character).
	 */
	static String fingerprint(String secret)
	{
		if (secret == null || secret.trim().isEmpty())
		{
			return "";
		}
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.trim().getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 8; i++)
			{
				sb.append(String.format("%02x", digest[i]));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}
}
