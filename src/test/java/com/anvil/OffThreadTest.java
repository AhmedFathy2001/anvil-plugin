package com.anvil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Test;

public class OffThreadTest
{
	@After
	public void unbind()
	{
		OffThread.bindClientThread(null);
	}

	@Test
	public void aBackgroundThreadMayBlock()
	{
		assertNull(OffThread.where());
	}

	@Test
	public void theEdtMayNot() throws Exception
	{
		String[] seen = new String[1];
		SwingUtilities.invokeAndWait(() -> seen[0] = OffThread.where());
		assertNotNull(seen[0]);
	}

	@Test
	public void theClientThreadMayNot()
	{
		OffThread.bindClientThread(() -> true);
		assertEquals("client thread (game)", OffThread.where());
		OffThread.bindClientThread(() ->
		{
			throw new IllegalStateException("client gone");
		});
		assertNull(OffThread.where());
	}
}
