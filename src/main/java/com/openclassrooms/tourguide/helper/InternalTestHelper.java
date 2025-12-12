package com.openclassrooms.tourguide.helper;

public class InternalTestHelper {

	// Set this default up to 100,000 for testing
	private static int internalUserNumber = 100000;
	private static boolean disableTracking = false;

	public static void setInternalUserNumber(int internalUserNumber) {
		InternalTestHelper.internalUserNumber = internalUserNumber;
	}
	
	public static int getInternalUserNumber() {
		return internalUserNumber;
	}

	public static boolean trackingDisabled() {
		return disableTracking;
	}

	public static void setDisableTracking(boolean disableTracking) {
		InternalTestHelper.disableTracking = disableTracking;
	}
}
