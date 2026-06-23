package dev.faus.minecad.client;

import net.fabricmc.api.ClientModInitializer;

public class MineCadClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		SketchBoundaryRenderer.initialize();
	}
}
