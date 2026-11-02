package com.donkeytt.journeysync.mixins.late.journeymap;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.donkeytt.journeysync.client.HolderAccess;
import com.donkeytt.journeysync.client.JmFiles;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import journeymap.client.model.ImageHolder;
import journeymap.client.render.texture.TextureImpl;

/**
 * JourneyMap's in-memory image of one map type of one region. Loading it is wrapped in JourneySync's lock for the
 * file, and the holder is registered while loaded, so received cells go into memory instead of into a file JourneyMap
 * would overwrite. See {@link JmFiles}.
 */
@Mixin(value = ImageHolder.class, remap = false)
public abstract class MixinImageHolder implements HolderAccess {

    @Shadow
    @Final
    Path imagePath;
    @Shadow
    @Final
    ReentrantLock writeLock;
    @Shadow
    private volatile TextureImpl texture;

    @Shadow
    abstract void partialImageUpdate(BufferedImage imagePart, int x, int y);

    @Shadow
    abstract void finishPartialImageUpdates();

    @WrapMethod(method = "getTexture")
    private TextureImpl journeysync$loadUnderLock(Operation<TextureImpl> original) {
        File file = imagePath.toFile();
        synchronized (JmFiles.lock(file)) {
            TextureImpl result = original.call();
            JmFiles.register(file, this);
            return result;
        }
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void journeysync$unregister(CallbackInfo ci) {
        File file = imagePath.toFile();
        synchronized (JmFiles.lock(file)) {
            JmFiles.unregister(file, this);
        }
    }

    @Override
    public void journeysync$drawCells(int[] indices, int[][] cells) {
        BufferedImage cell = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < indices.length; i++) {
            cell.setRGB(0, 0, 16, 16, cells[i], 0, 16);
            // Same call JourneyMap's renderer ends with: source-over, so transparent pixels keep what's there.
            partialImageUpdate(cell, (indices[i] & 31) * 16, (indices[i] >> 5) * 16);
        }
        finishPartialImageUpdates();
    }

    @Override
    public int[] journeysync$pixels() {
        writeLock.lock();
        try {
            TextureImpl t = texture;
            if (t == null || !t.hasImage()) return null;
            BufferedImage image = t.getImage();
            return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        } finally {
            writeLock.unlock();
        }
    }
}
