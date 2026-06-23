package dev.faus.minecad;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MineCad implements ModInitializer {
	public static final String MOD_ID = "minecad";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		SketchItems.initialize();
		try {
			initBindings();
		} catch (Exception e) {
			LOGGER.error("Failed to initialize ManifoldBindings: {}", e.getMessage());
			e.printStackTrace();
			return;
		}
		LOGGER.info("MineCad initialized");
	}

	static void initBindings() throws Exception {
		LOGGER.info("ManifoldBindings initialized successfully");
	}
}
