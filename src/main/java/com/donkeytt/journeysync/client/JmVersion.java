package com.donkeytt.journeysync.client;

import cpw.mods.fml.common.Loader;

/** Whether JourneyMap 5 is installed. Has no JourneyMap imports, so it's safe to call without JourneyMap. */
public final class JmVersion {

    private static Boolean supported;

    private JmVersion() {}

    /** JourneyMap is installed and is the 5.x line; 6.x has different classes. */
    public static boolean isSupported() {
        if (supported == null) {
            supported = Loader.isModLoaded("journeymap") && isJourneyMap5(JmVersion.class.getClassLoader());
        }
        return supported;
    }

    /** Same check as Navigator's {@code JourneyMapVersion}: 5.x has GridRenderer and no v2 API. */
    public static boolean isJourneyMap5(ClassLoader cl) {
        return cl.getResource("journeymap/client/render/map/GridRenderer.class") != null
            && cl.getResource("journeymap/api/v2/client/IClientAPI.class") == null;
    }
}
