package com.donkeytt.journeysync;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.donkeytt.journeysync.client.JmVersion;
import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

import cpw.mods.fml.relauncher.FMLLaunchHandler;

/** Applies the JourneyMap Mixins on clients that have JourneyMap 5. */
@LateMixin
public class JourneySyncLateMixins implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.journeysync.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        if (!FMLLaunchHandler.side()
            .isClient() || !loadedMods.contains("journeymap")
            || !JmVersion.isJourneyMap5(JourneySyncLateMixins.class.getClassLoader())) {
            JourneySync.LOG.info("JourneyMap 5 not found; JourneySync won't sync maps on this side");
            return Collections.emptyList();
        }
        return Arrays.asList("journeymap.MixinImageHolder", "journeymap.MixinChunkRenderController");
    }
}
