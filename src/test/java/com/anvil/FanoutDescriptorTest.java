package com.anvil;

import com.google.gson.JsonObject;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** The {@code fanout:{count,instanceIds[]}} wire shape (FEDERATION_WIRE.md §5). */
public class FanoutDescriptorTest
{
	@Test
	public void serializesCountAndIds()
	{
		FanoutDescriptor d = new FanoutDescriptor(2, Arrays.asList("local", "uuid-b"));
		JsonObject o = d.toJson();
		assertEquals(2, o.get("count").getAsInt());
		assertEquals(2, o.getAsJsonArray("instanceIds").size());
		assertEquals("local", o.getAsJsonArray("instanceIds").get(0).getAsString());
		assertEquals("uuid-b", o.getAsJsonArray("instanceIds").get(1).getAsString());
	}

	@Test
	public void dropsBlankIdsAndClampsNegativeCount()
	{
		FanoutDescriptor d = new FanoutDescriptor(-5, Arrays.asList("a", "", null, "b"));
		JsonObject o = d.toJson();
		assertEquals(0, o.get("count").getAsInt());
		assertEquals(2, o.getAsJsonArray("instanceIds").size());
	}
}
