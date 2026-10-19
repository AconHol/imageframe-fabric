package com.imageframe;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the server-side Fabric reimplementation of ImageFrame.
 *
 * <p>Everything is server side: vanilla clients see the images through the normal
 * filled map protocol, no client mod is required.</p>
 */
public final class ImageFrameFabric implements ModInitializer {

    public static final String MOD_ID = "imageframe";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ImageFrameMod.get().init();
    }
}
