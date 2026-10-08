package com.anvil;

import org.junit.Test;

import static org.junit.Assert.*;

public class ObsPathPolicyTest
{
	@Test
	public void linuxNeverRestoresAMacOrWindowsPath()
	{
		assertTrue(ObsPathPolicy.isCompatible("/home/ahmed/Videos", "Linux"));
		assertFalse(ObsPathPolicy.isCompatible("/Users/ahmed/Videos", "Linux"));
		assertFalse(ObsPathPolicy.isCompatible("C:\\Users\\ahmed\\Videos", "Linux"));
	}

	@Test
	public void macNeverRestoresALinuxOrWindowsPath()
	{
		assertTrue(ObsPathPolicy.isCompatible("/Users/ahmed/Videos", "Mac OS X"));
		assertFalse(ObsPathPolicy.isCompatible("/home/ahmed/Videos", "Mac OS X"));
		assertFalse(ObsPathPolicy.isCompatible("C:\\Users\\ahmed\\Videos", "Mac OS X"));
	}

	@Test
	public void windowsRequiresAWindowsPath()
	{
		assertTrue(ObsPathPolicy.isCompatible("C:\\Users\\ahmed\\Videos", "Windows 11"));
		assertFalse(ObsPathPolicy.isCompatible("/Users/ahmed/Videos", "Windows 11"));
	}
}
