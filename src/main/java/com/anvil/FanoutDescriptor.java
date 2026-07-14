package com.anvil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The plugin-declared {@code fanout} descriptor attached to every federated event submission
 * (see {@code FEDERATION_WIRE.md} §5). It tells each receiving instance how many connected clans
 * this same game event was submitted to, and which:
 *
 * <pre>"fanout": { "count": &lt;int&gt;, "instanceIds": ["&lt;uuid&gt;", ...] }</pre>
 *
 * <p>An instance whose {@code sharedCredit} policy is {@code exclusive} declines to credit when
 * {@code count > 1} (responding {@code 200 {credited:false, reason:"exclusive"}}); the default
 * {@code accept} policy credits regardless. The descriptor is trusted exactly as far as the drop
 * report itself — it rides the same auth boundary.</p>
 *
 * <p><b>Single-home invariant:</b> when only the primary connection matches a game event, the plugin
 * does not attach a descriptor at all (the submit body stays byte-for-byte what it is today). A
 * descriptor is emitted only once two or more connections are credited for the same event, so its
 * {@code count} is always ≥ 2 on the wire in practice.</p>
 *
 * <p>Immutable and RuneLite-free so it is unit-testable.</p>
 */
public final class FanoutDescriptor
{
	/** Number of connections this same game event was submitted to (including the primary). */
	public final int count;

	/** The instanceIds of those connections, display order. Never {@code null}. */
	public final List<String> instanceIds;

	public FanoutDescriptor(int count, List<String> instanceIds)
	{
		this.count = Math.max(0, count);
		this.instanceIds = instanceIds == null
			? Collections.emptyList()
			: Collections.unmodifiableList(new ArrayList<>(instanceIds));
	}

	/** {@code {"count":N,"instanceIds":[...]}} — merged into a submission body under the {@code fanout} key. */
	public JsonObject toJson()
	{
		JsonObject o = new JsonObject();
		o.addProperty("count", count);
		JsonArray ids = new JsonArray();
		for (String id : instanceIds)
		{
			if (id != null && !id.isEmpty())
			{
				ids.add(id);
			}
		}
		o.add("instanceIds", ids);
		return o;
	}
}
