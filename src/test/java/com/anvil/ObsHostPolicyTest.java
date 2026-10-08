package com.anvil;

import org.junit.Test;

import static org.junit.Assert.*;

public class ObsHostPolicyTest
{
	@Test
	public void loopbackHostsMayManageTheFolder()
	{
		assertTrue(ObsHostPolicy.canManage(true, "localhost"));
		assertTrue(ObsHostPolicy.canManage(true, "127.0.0.1"));
		assertTrue(ObsHostPolicy.canManage(true, "::1"));
		assertTrue(ObsHostPolicy.canManage(true, "[::1]"));
	}

	@Test
	public void remoteHostsCanNeverReceiveThisMachinesPath()
	{
		assertFalse(ObsHostPolicy.canManage(true, "203.0.113.1"));
		assertFalse(ObsHostPolicy.canManage(true, "remote.invalid"));
		assertFalse(ObsHostPolicy.canManage(false, "localhost"));
	}
}
