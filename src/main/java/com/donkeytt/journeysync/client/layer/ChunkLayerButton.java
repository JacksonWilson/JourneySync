package com.donkeytt.journeysync.client.layer;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import com.donkeytt.journeysync.JourneySync;
import com.donkeytt.journeysync.client.ChunkLayerState;
import com.gtnewhorizons.navigator.api.model.SupportedMods;
import com.gtnewhorizons.navigator.api.model.buttons.ButtonManager;

public class ChunkLayerButton extends ButtonManager {

    public static final ChunkLayerButton INSTANCE = new ChunkLayerButton();

    @Override
    public ResourceLocation getIcon(SupportedMods mod, String theme) {
        return new ResourceLocation(JourneySync.MODID, "textures/icons/chunks.png");
    }

    @Override
    public String getButtonText() {
        String text = StatCollector.translateToLocal("journeysync.layer.button");
        return ChunkLayerState.serverHasMod() ? text
            : text + " " + StatCollector.translateToLocal("journeysync.layer.button.no_server");
    }
}
