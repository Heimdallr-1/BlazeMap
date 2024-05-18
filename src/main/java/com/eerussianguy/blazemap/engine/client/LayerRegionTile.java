package com.eerussianguy.blazemap.engine.client;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

import net.minecraft.world.level.ChunkPos;

import com.eerussianguy.blazemap.BlazeMap;
import com.eerussianguy.blazemap.api.BlazeRegistry;
import com.eerussianguy.blazemap.api.maps.Layer;
import com.eerussianguy.blazemap.api.maps.TileResolution;
import com.eerussianguy.blazemap.api.util.RegionPos;
import com.eerussianguy.blazemap.engine.BlazeMapAsync;
import com.eerussianguy.blazemap.engine.StorageAccess;
import com.eerussianguy.blazemap.engine.async.AsyncAwaiter;
import com.eerussianguy.blazemap.engine.async.PriorityLock;
import com.mojang.blaze3d.platform.NativeImage;

public class LayerRegionTile {
    private static final Object MUTEX = new Object();
    private static int instances = 0, loaded = 0;

    private final PriorityLock lock = new PriorityLock();
    private final AsyncAwaiter load = new AsyncAwaiter(1);
    private final File file, buffer;
    private NativeImage image;
    private final TileResolution resolution;
    private boolean isEmpty = true;
    private boolean isDirty = false;
    private boolean destroyed = false;
    private boolean isLoading = false;

    public LayerRegionTile(StorageAccess.Internal storage, BlazeRegistry.Key<Layer> layer, RegionPos region, TileResolution resolution) {
        this.file = storage.getMipmap(layer.location, region + ".png", resolution);
        this.buffer = storage.getMipmap(layer.location, region + ".buffer", resolution);
        this.resolution = resolution;
    }

    public void tryLoad(Runnable onLoad) {
        if(file.exists()) {
            lock.lockPriority();
            isLoading = true;
            BlazeMapAsync.instance().clientChain.runOnDataThread(() -> {
                try {
                    image = NativeImage.read(Files.newInputStream(file.toPath()));
                    isEmpty = false;
                    onFill();
                }
                catch(IOException e) {
                    // FIXME: this needs to hook into a reporting mechanism AND possibly automated LRT regeneration
                    BlazeMap.LOGGER.error("Error loading LayerRegionTile: {}", file, e);
                }
                isLoading = false;
                load.done();
                if(!isEmpty) {
                    onLoad.run();
                }
            });
            lock.unlock();
        }
        else {
            file.getParentFile().mkdirs();
        }
        onCreate();
    }

    public void save() {
        if(isEmpty || isLoading || !isDirty) return;

        // Save image into buffer
        lock.lock();
        try {
            image.writeToFile(buffer);
            isDirty = false;
        }
        catch(IOException e) {
            e.printStackTrace();
            // FIXME: this needs to hook into a reporting mechanism
            BlazeMap.LOGGER.error("Error saving LayerRegionTile buffer: {}", buffer, e);
        }
        finally {
            lock.unlock();
        }

        // Move buffer to real image path
        try {
            Files.move(buffer.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            buffer.delete();
        }
        catch(IOException e) {
            e.printStackTrace();
            // FIXME: this needs to hook into a reporting mechanism
            BlazeMap.LOGGER.error("Error moving LayerRegionTile buffer to image: {} {}", buffer, file, e);
        }
    }

    public void updateTile(NativeImage tile, ChunkPos chunk) {
        int xOffset = (chunk.getRegionLocalX() << 4) / resolution.pixelWidth;
        int zOffset = (chunk.getRegionLocalZ() << 4) / resolution.pixelWidth;
        boolean wasEmpty = isEmpty;

        lock.lock();
        try {
            if(isLoading) {
                load.await();
            }
            if(isEmpty) {
                image = new NativeImage(NativeImage.Format.RGBA, resolution.regionWidth, resolution.regionWidth, true);
                isEmpty = false;
            }

            for(int x = 0; x < resolution.chunkWidth; x++) {
                for(int z = 0; z < resolution.chunkWidth; z++) {
                    int old = image.getPixelRGBA(xOffset + x, zOffset + z);
                    int pixel = tile.getPixelRGBA(x, z);
                    if(pixel != old) {
                        image.setPixelRGBA(xOffset + x, zOffset + z, pixel);
                        isDirty = true;
                    }
                }
            }

        }
        finally {
            lock.unlock();
        }

        if(wasEmpty && !isEmpty) {
            onFill();
        }
    }

    public boolean isDirty() {
        return isDirty;
    }

    public void consumeSync(Consumer<NativeImage> consumer) {
        if(isLoading) load.await();
        if(isEmpty) return;
        lock.lockPriority();
        try {
            consumer.accept(image);
        }
        finally {
            lock.unlock();
        }
    }

    public void consumeAsync(Consumer<NativeImage> consumer) {
        if(isEmpty || isLoading) return;
        lock.lockPriority();
        try {
            consumer.accept(image);
        }
        finally {
            lock.unlock();
        }
    }

    private void onCreate() {
        synchronized(MUTEX) {
            instances++;
            if(!isEmpty) {
                loaded += resolution.regionSizeKb;
            }
        }
    }

    private void onFill() {
        synchronized(MUTEX) {
            loaded += resolution.regionSizeKb;
        }
    }

    public void destroy() {
        if(!destroyed) {
            synchronized(MUTEX) {
                instances--;
                if(!isEmpty) {
                    loaded -= resolution.regionSizeKb;
                }
            }
        }
        else return;

        lock.lockPriority();
        try {
            save();
            if(image != null) {
                image.close();
                image = null;
            }
            isDirty = false;
            isEmpty = true;
            destroyed = true;
        }
        finally {
            lock.unlock();
        }
    }

    public static int getInstances() {
        synchronized(MUTEX) {
            return instances;
        }
    }

    public static int getLoadedKb() {
        synchronized(MUTEX) {
            return loaded;
        }
    }
}
