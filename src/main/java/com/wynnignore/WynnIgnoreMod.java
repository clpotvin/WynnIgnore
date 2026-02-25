package com.wynnignore;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.network.ServerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WynnIgnoreMod implements ClientModInitializer {
    public static final String MOD_ID = "wynnignore";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static IgnoreListManager ignoreListManager;
    private static boolean checkedOnJoin = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("WynnIgnore initializing...");

        // Initialize the ignore list manager and load saved data
        ignoreListManager = new IgnoreListManager();
        ignoreListManager.load();

        // Register commands
        CommandHandler.register();

        // Register world join event to detect server and check for expired timed ignores
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            checkedOnJoin = false;

            // Detect server type and switch ignore list
            ServerInfo serverInfo = handler.getServerInfo();
            String address = serverInfo != null ? serverInfo.address : "";
            String serverType = isBetaServer(address) ? "beta" : "main";
            ignoreListManager.setServer(serverType);
        });

        // Register tick event for timed ignore expiry checking
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world != null && client.player != null) {
                // On first tick after joining, check for any ignores that expired while offline
                if (!checkedOnJoin) {
                    checkedOnJoin = true;
                    LOGGER.info("Checking for expired timed ignores...");
                    ignoreListManager.checkTimedIgnoreExpiry();
                }

                // Check for confirmation timeout and process delayed commands every tick
                CommandHandler.checkConfirmationTimeout();
                CommandHandler.tickProcessQueue();

                // Regular expiry check (once per second to reduce overhead)
                if (client.world.getTime() % 20 == 0) {
                    ignoreListManager.checkTimedIgnoreExpiry();
                }
            }
        });

        LOGGER.info("WynnIgnore initialized!");
    }

    public static IgnoreListManager getIgnoreListManager() {
        return ignoreListManager;
    }

    private static boolean isBetaServer(String address) {
        String lower = address.toLowerCase();
        return lower.contains("beta") && lower.contains("wynncraft");
    }
}
