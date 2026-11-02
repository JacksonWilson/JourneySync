package com.donkeytt.journeysync.mixins.late.journeymap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.donkeytt.journeysync.client.CellCapture;

import journeymap.client.cartography.ChunkRenderController;
import journeymap.client.model.ChunkMD;
import journeymap.client.model.MapType;
import journeymap.client.model.RegionCoord;

/**
 * JourneyMap paints a chunk into a reusable 16x16 buffer and only copies it into the region's in-memory image in the
 * {@code finally} block of {@code renderChunk}, after {@code setChunkImage} has already run. So the chunk's final
 * pixels exist only once {@code renderChunk} returns; that's where JourneySync captures what to upload.
 */
@Mixin(value = ChunkRenderController.class, remap = false)
public abstract class MixinChunkRenderController {

    @Inject(method = "renderChunk", at = @At("RETURN"))
    private void journeysync$capture(RegionCoord rCoord, MapType mapType, ChunkMD chunkMd,
        CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) CellCapture.captureRendered(rCoord, mapType, chunkMd);
    }
}
