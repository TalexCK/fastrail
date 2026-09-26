package com.example.sandstone_fast_rails;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class SandstoneFastRailsMod implements ModInitializer {
    public static final String MOD_ID = "sandstone_fast_rails";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    @Override
    public void onInitialize() {
        LOGGER.info("Sandstone Fast Rails mod loaded.");
    }
}
