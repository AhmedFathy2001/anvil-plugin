package com.anvil;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;
import javax.inject.Provider;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * No collaborator may depend on itself, however long the way round.
 *
 * <p>Everything the plugin owns is constructor-injected, and Guice refuses a constructor cycle at
 * injection time — which is to say, when a player enables the plugin, not when anyone builds it.
 * There is no Guice-level test to catch that here (the graph needs a live client), so this walks the
 * {@code @Inject} constructors by reflection instead and looks for a cycle directly.</p>
 *
 * <p>The break for a genuine mutual dependency is {@link Provider}: a {@code Provider<Foo>}
 * parameter resolves when it is first asked, long after the graph is built, so it is not an edge
 * here either. Three pairs need it today — the board's store and the trackers that ask it to
 * refresh, the moments feed and the two services that report into it, and the PvP and party
 * trackers, which each know something the other needs.</p>
 */
public class InjectionGraphTest
{
	@Test
	public void noConstructorDependencyCycles() throws Exception
	{
		Map<Class<?>, List<Class<?>>> edges = new HashMap<>();
		for (Class<?> c : anvilClasses())
		{
			Constructor<?> ctor = injectConstructor(c);
			if (ctor == null)
			{
				continue;
			}
			List<Class<?>> deps = new ArrayList<>();
			Type[] generic = ctor.getGenericParameterTypes();
			for (int i = 0; i < generic.length; i++)
			{
				Class<?> raw = ctor.getParameterTypes()[i];
				if (Provider.class.isAssignableFrom(raw) || raw.getName().equals("com.google.inject.Provider"))
				{
					continue; // resolved on demand — deliberately not an edge
				}
				Class<?> dep = raw;
				if (generic[i] instanceof ParameterizedType && !raw.getName().startsWith("com.anvil"))
				{
					continue; // Supplier<…>, Optional<…> and friends are provided, not constructed
				}
				if (dep.getName().startsWith("com.anvil"))
				{
					deps.add(dep);
				}
			}
			edges.put(c, deps);
		}

		assertTrue("found no @Inject constructors at all — the scan is broken", edges.size() > 20);

		Set<Class<?>> done = new HashSet<>();
		for (Class<?> start : edges.keySet())
		{
			List<Class<?>> cycle = findCycle(start, edges, done);
			if (cycle != null)
			{
				StringBuilder sb = new StringBuilder("constructor dependency cycle:\n");
				for (Class<?> c : cycle)
				{
					sb.append("    ").append(c.getSimpleName()).append(" ->\n");
				}
				sb.append("    ").append(cycle.get(0).getSimpleName())
					.append("\n\nBreak it with a javax.inject.Provider<…> on one of the edges.");
				fail(sb.toString());
			}
		}
	}

	/** Depth-first, carrying the path so a hit can name the whole loop rather than just a class. */
	private static List<Class<?>> findCycle(Class<?> start, Map<Class<?>, List<Class<?>>> edges,
		Set<Class<?>> settled)
	{
		if (settled.contains(start))
		{
			return null;
		}
		Set<Class<?>> onPath = new LinkedHashSet<>();
		Deque<Class<?>> stack = new ArrayDeque<>();
		List<Class<?>> found = walk(start, edges, settled, onPath, stack);
		return found;
	}

	private static List<Class<?>> walk(Class<?> node, Map<Class<?>, List<Class<?>>> edges,
		Set<Class<?>> settled, Set<Class<?>> onPath, Deque<Class<?>> stack)
	{
		if (onPath.contains(node))
		{
			List<Class<?>> cycle = new ArrayList<>();
			boolean started = false;
			for (Class<?> c : onPath)
			{
				started |= c.equals(node);
				if (started)
				{
					cycle.add(c);
				}
			}
			return cycle;
		}
		if (settled.contains(node))
		{
			return null;
		}
		onPath.add(node);
		for (Class<?> dep : edges.getOrDefault(node, java.util.Collections.emptyList()))
		{
			List<Class<?>> hit = walk(dep, edges, settled, onPath, stack);
			if (hit != null)
			{
				return hit;
			}
		}
		onPath.remove(node);
		settled.add(node);
		return null;
	}

	private static Constructor<?> injectConstructor(Class<?> c)
	{
		for (Constructor<?> ctor : c.getDeclaredConstructors())
		{
			if (ctor.isAnnotationPresent(Inject.class)
				|| ctor.isAnnotationPresent(com.google.inject.Inject.class))
			{
				return ctor;
			}
		}
		return null;
	}

	/** Every compiled class under com.anvil, read off wherever the main output actually landed. */
	private static List<Class<?>> anvilClasses() throws Exception
	{
		URL src = AnvilPlugin.class.getProtectionDomain().getCodeSource().getLocation();
		File dir = new File(new File(src.toURI()), "com/anvil");
		assertTrue("main classes not found at " + dir, dir.isDirectory());
		Set<String> names = new TreeSet<>();
		collect(dir, "com.anvil", names);
		List<Class<?>> out = new ArrayList<>();
		for (String n : names)
		{
			try
			{
				out.add(Class.forName(n, false, InjectionGraphTest.class.getClassLoader()));
			}
			catch (Throwable ignored)
			{
				// a class the test classpath cannot load says nothing about the injection graph
			}
		}
		return out;
	}

	private static void collect(File dir, String pkg, Set<String> out)
	{
		File[] kids = dir.listFiles();
		if (kids == null)
		{
			return;
		}
		for (File f : kids)
		{
			if (f.isDirectory())
			{
				collect(f, pkg + "." + f.getName(), out);
			}
			else if (f.getName().endsWith(".class") && !f.getName().contains("$"))
			{
				out.add(pkg + "." + f.getName().substring(0, f.getName().length() - 6));
			}
		}
	}
}
