package com.wynnignore.mixin;

import com.wynnignore.CommandHandler;
import com.wynnignore.IgnoreListManager;
import com.wynnignore.WynnIgnoreMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onGameMessage", at = @At("HEAD"))
    private void onGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        if (packet.content() == null) {
            return;
        }
        String message = packet.content().getString();
        if (message != null) {
            CommandHandler.onChatMessage(message);
        }
    }

    @Inject(method = "sendChatCommand", at = @At("HEAD"), cancellable = true)
    private void onSendChatCommand(String command, CallbackInfo ci) {
        // Check if this is an /ignore command
        if (!command.toLowerCase().startsWith("ignore ") && !command.equalsIgnoreCase("ignore")) {
            return;
        }

        IgnoreListManager manager = WynnIgnoreMod.getIgnoreListManager();
        MinecraftClient client = MinecraftClient.getInstance();

        if (manager == null) {
            return;
        }

        // Parse the command
        String[] parts = command.split("\\s+");

        if (parts.length == 1) {
            // Just "/ignore" with no args - let it through
            return;
        }

        String subCommand = parts[1].toLowerCase();

        // Handle "/ignore list" - intercept and show our tracked list
        if (subCommand.equals("list")) {
            ci.cancel();

            Set<String> players = manager.getIgnoredPlayers();
            if (players.isEmpty()) {
                if (client.player != null) {
                    client.player.sendMessage(
                        Text.literal("[WynnIgnore] Your tracked ignore list is empty.").formatted(Formatting.YELLOW),
                        false
                    );
                }
            } else {
                if (client.player != null) {
                    client.player.sendMessage(
                        Text.literal("[WynnIgnore] Tracked ignored players (" + players.size() + "):").formatted(Formatting.GOLD),
                        false
                    );

                    StringBuilder sb = new StringBuilder();
                    int i = 0;
                    for (String player : players) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(player);

                        // Show remaining time for timed ignores
                        long remaining = manager.getRemainingMinutes(player);
                        if (remaining >= 0) {
                            sb.append(" (").append(remaining).append("m)");
                        }
                        i++;

                        // Send in batches to avoid too long messages
                        if (i % 5 == 0) {
                            client.player.sendMessage(
                                Text.literal("  " + sb.toString()).formatted(Formatting.GRAY),
                                false
                            );
                            sb = new StringBuilder();
                        }
                    }

                    if (sb.length() > 0) {
                        client.player.sendMessage(
                            Text.literal("  " + sb.toString()).formatted(Formatting.GRAY),
                            false
                        );
                    }
                }
            }
            return;
        }

        // Handle "/ignore remove <player>" - let through but also track
        // Skip tracking if this is a queued command (confirmation handler will track it)
        if (subCommand.equals("remove") && parts.length >= 3) {
            if (!CommandHandler.isSendingQueuedCommand()) {
                String playerName = parts[2];
                manager.removePlayer(playerName);
            }
            // Let the command through to the server
            return;
        }

        // Handle "/ignore <player>" or "/ignore <player> <time>"
        // Skip tracking if this is a queued command (confirmation handler will track it)
        // This is any other subcommand that isn't "list" or "remove"
        if (parts.length >= 2) {
            String playerName = parts[1];
            // Skip common subcommands that aren't player names
            if (playerName.equalsIgnoreCase("add") || playerName.equalsIgnoreCase("help")) {
                // Let through to server
                return;
            }

            // Check if there's a time argument: /ignore <player> <time>
            if (parts.length >= 3) {
                try {
                    int durationMinutes = Integer.parseInt(parts[2]);
                    if (durationMinutes > 0) {
                        // Validate duration (max 1 week)
                        if (durationMinutes > CommandHandler.MAX_TIMED_IGNORE_MINUTES) {
                            if (client.player != null) {
                                client.player.sendMessage(
                                    Text.literal("[WynnIgnore] Duration capped to 1 week (10080 minutes).").formatted(Formatting.YELLOW),
                                    false
                                );
                            }
                        }
                        // This is a timed ignore - intercept and queue through CommandHandler
                        ci.cancel();
                        CommandHandler.queueTimedIgnore(playerName, durationMinutes);
                        return;
                    }
                } catch (NumberFormatException e) {
                    // Not a number, treat as regular ignore (let server handle the extra arg)
                }
            }

            // Regular ignore without time - track it and let through
            if (!CommandHandler.isSendingQueuedCommand()) {
                manager.addPlayer(playerName);
            }
            // Let the command through to the server
        }
    }
}
