package de.servergrid;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;

public class ServerGridClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof MultiplayerScreen mp) {
                new GridView(client, mp, w, h).init();
            }
        });
    }
}
