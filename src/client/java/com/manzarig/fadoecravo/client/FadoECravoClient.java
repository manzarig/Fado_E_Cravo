package com.manzarig.fadoecravo.client;

import com.manzarig.fadoecravo.client.debug.SeekTestCommand;
import net.fabricmc.api.ClientModInitializer;

public class FadoECravoClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Client-only setup (renderers, screens, key bindings) goes here.
		SeekTestCommand.register();
	}
}
