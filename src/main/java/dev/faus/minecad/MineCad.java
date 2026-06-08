package dev.faus.minecad;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.cadoodlecad.manifold.ManifoldBindings;

public class MineCad implements ModInitializer {
	public static final String MOD_ID = "minecad";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static ManifoldBindings mb;

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
		mb = new ManifoldBindings();
		if (mb == null) {
			throw new Exception("Failed to initialize ManifoldBindings");
		}
		if (!ManifoldBindings.isNativeLibraryLoaded()) {
			throw new Exception("ManifoldBindings native library failed to load");
		}
		LOGGER.info("ManifoldBindings initialized successfully");
	}
}
