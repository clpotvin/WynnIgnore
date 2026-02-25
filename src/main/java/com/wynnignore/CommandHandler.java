package com.wynnignore;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class CommandHandler {

    // Maximum timed ignore duration: 1 week in minutes
    public static final int MAX_TIMED_IGNORE_MINUTES = 7 * 24 * 60; // 10080 minutes

    // Timeout for waiting for server confirmation (10 seconds)
    private static final long CONFIRMATION_TIMEOUT_MS = 10000;

    private static long getCommandDelayMs() {
        return ModConfig.getInstance().getCommandDelayMs();
    }

    private static int getMaxRetries() {
        return ModConfig.getInstance().getCommandMaxRetries();
    }

    private static final Queue<QueuedCommand> commandQueue = new LinkedList<>();
    private static final Object QUEUE_LOCK = new Object();

    private static volatile boolean waitingForConfirmation = false;
    private static volatile String pendingPlayer = null;
    private static volatile boolean pendingIsAdd = false;
    private static volatile int pendingDurationMinutes = 0;
    private static volatile boolean pendingIsTimedUnignore = false;
    private static volatile boolean sendingQueuedCommand = false;
    private static volatile long confirmationStartTime = 0;
    private static volatile boolean pendingIsWarCommand = false;
    private static volatile long nextCommandReadyTime = 0;
    private static volatile int retryCount = 0;

    // Shorter timeout for war commands (time-sensitive)
    private static final long WAR_CONFIRMATION_TIMEOUT_MS = 1000;

    private static class QueuedCommand {
        final String playerName;
        final boolean isAdd;
        final int durationMinutes; // -1 = permanent, 0 = use default, >0 = specific duration
        final boolean isTimedUnignore;
        final boolean isWarCommand;

        QueuedCommand(String playerName, boolean isAdd) {
            this(playerName, isAdd, -1, false, false);
        }

        QueuedCommand(String playerName, boolean isAdd, int durationMinutes, boolean isTimedUnignore) {
            this(playerName, isAdd, durationMinutes, isTimedUnignore, false);
        }

        QueuedCommand(String playerName, boolean isAdd, int durationMinutes, boolean isTimedUnignore, boolean isWarCommand) {
            this.playerName = playerName;
            this.isAdd = isAdd;
            this.durationMinutes = durationMinutes;
            this.isTimedUnignore = isTimedUnignore;
            this.isWarCommand = isWarCommand;
        }
    }

    private static final SuggestionProvider<FabricClientCommandSource> UNIGNORE_SUGGESTIONS = (context, builder) -> {
        IgnoreListManager manager = WynnIgnoreMod.getIgnoreListManager();
        if (manager == null) {
            return builder.buildFuture();
        }
        Set<String> ignoredPlayers = manager.getIgnoredPlayers();

        List<String> suggestions = new ArrayList<>(ignoredPlayers);
        suggestions.add("all");

        return CommandSource.suggestMatching(suggestions, builder);
    };

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // /unignore <player|all>
            dispatcher.register(ClientCommandManager.literal("unignore")
                .then(ClientCommandManager.argument("target", StringArgumentType.word())
                    .suggests(UNIGNORE_SUGGESTIONS)
                    .executes(context -> {
                        String target = StringArgumentType.getString(context, "target");
                        return handleUnignore(target);
                    })
                )
                .executes(context -> {
                    sendMessage(Text.literal("Usage: /unignore <player|all>").formatted(Formatting.RED));
                    return 0;
                })
            );

            // /warignore
            dispatcher.register(ClientCommandManager.literal("warignore")
                .executes(context -> {
                    return handleWarIgnore();
                })
            );
        });
    }

    private static int handleUnignore(String target) {
        MinecraftClient client = MinecraftClient.getInstance();
        IgnoreListManager manager = WynnIgnoreMod.getIgnoreListManager();

        if (client.player == null || manager == null) {
            return 0;
        }

        if (target.equalsIgnoreCase("all")) {
            Set<String> players = manager.getIgnoredPlayers();
            if (players.isEmpty()) {
                sendMessage(Text.literal("[WynnIgnore] No players in ignore list.").formatted(Formatting.YELLOW));
                return 1;
            }

            int count = players.size();
            List<String> toUnignore = new ArrayList<>(players);

            synchronized (QUEUE_LOCK) {
                for (String player : toUnignore) {
                    commandQueue.add(new QueuedCommand(player, false, 0, false));
                }
            }

            sendMessage(Text.literal("[WynnIgnore] Unignoring " + count + " players...").formatted(Formatting.GREEN));
            processNextCommand();
            return 1;
        } else {
            if (!manager.isIgnored(target)) {
                sendMessage(Text.literal("[WynnIgnore] ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(target).formatted(Formatting.YELLOW))
                    .append(Text.literal(" is not in your tracked ignore list.").formatted(Formatting.GRAY)));
                return 0;
            }

            // Use queue for consistency with confirmation system
            synchronized (QUEUE_LOCK) {
                commandQueue.add(new QueuedCommand(target, false, 0, false));
            }
            processNextCommand();
            return 1;
        }
    }

    private static int handleWarIgnore() {
        MinecraftClient client = MinecraftClient.getInstance();
        IgnoreListManager manager = WynnIgnoreMod.getIgnoreListManager();

        if (client.player == null || client.world == null || manager == null) {
            return 0;
        }

        double maxDistance = ModConfig.getInstance().getWarIgnoreDistance();
        List<AbstractClientPlayerEntity> nearbyPlayers = new ArrayList<>();

        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (player == client.player) {
                continue;
            }

            double distance = client.player.distanceTo(player);
            if (distance <= maxDistance) {
                nearbyPlayers.add(player);
            }
        }

        if (nearbyPlayers.isEmpty()) {
            sendMessage(Text.literal("[WynnIgnore] No players within " + (int) maxDistance + " blocks.").formatted(Formatting.YELLOW));
            return 1;
        }

        List<String> toIgnore = new ArrayList<>();
        for (AbstractClientPlayerEntity player : nearbyPlayers) {
            String name = player.getName().getString();

            if (manager.isIgnored(name)) {
                continue;
            }

            toIgnore.add(name);
        }

        if (toIgnore.isEmpty()) {
            sendMessage(Text.literal("[WynnIgnore] All nearby players are already ignored.").formatted(Formatting.YELLOW));
            return 1;
        }

        int minutes = ModConfig.getInstance().getWarIgnoreDurationMinutes();

        synchronized (QUEUE_LOCK) {
            for (String name : toIgnore) {
                commandQueue.add(new QueuedCommand(name, true, minutes, false, true));
            }
        }

        sendMessage(Text.literal("[WynnIgnore] War-ignoring " + toIgnore.size() + " players for " + minutes + " min: ")
            .formatted(Formatting.GREEN)
            .append(Text.literal(String.join(", ", toIgnore)).formatted(Formatting.YELLOW)));
        processNextCommand();

        return 1;
    }

    /**
     * Queue timed unignores (called by IgnoreListManager when timed ignores expire).
     */
    public static void queueTimedUnignores(List<String> players) {
        synchronized (QUEUE_LOCK) {
            for (String player : players) {
                commandQueue.add(new QueuedCommand(player, false, 0, true));
            }
        }
        processNextCommand();
    }

    /**
     * Queue a timed ignore command.
     */
    public static void queueTimedIgnore(String playerName, int durationMinutes) {
        // Validate duration (max 1 week)
        int validDuration = Math.max(1, Math.min(MAX_TIMED_IGNORE_MINUTES, durationMinutes));

        synchronized (QUEUE_LOCK) {
            commandQueue.add(new QueuedCommand(playerName, true, validDuration, false));
        }
        processNextCommand();
    }

    /**
     * Called from tick event to process delayed commands waiting for the inter-command delay.
     */
    public static void tickProcessQueue() {
        if (!waitingForConfirmation && nextCommandReadyTime > 0 && System.currentTimeMillis() >= nextCommandReadyTime) {
            processNextCommand();
        }
    }

    /**
     * Check for confirmation timeout and process next command if timed out.
     * Called from tick event.
     */
    public static void checkConfirmationTimeout() {
        if (waitingForConfirmation && confirmationStartTime > 0) {
            long elapsed = System.currentTimeMillis() - confirmationStartTime;
            long timeout = pendingIsWarCommand ? WAR_CONFIRMATION_TIMEOUT_MS : CONFIRMATION_TIMEOUT_MS;
            if (elapsed > timeout) {
                retryCount++;
                if (retryCount <= getMaxRetries()) {
                    WynnIgnoreMod.LOGGER.warn("Confirmation timeout for player: {} (retry {}/{})", pendingPlayer, retryCount, getMaxRetries());
                    sendMessage(Text.literal("[WynnIgnore] No response for ")
                        .formatted(Formatting.YELLOW)
                        .append(Text.literal(pendingPlayer != null ? pendingPlayer : "unknown").formatted(Formatting.GOLD))
                        .append(Text.literal(", retrying (" + retryCount + "/" + getMaxRetries() + ")...").formatted(Formatting.YELLOW)));

                    // Retry: resend the same command after a short delay
                    waitingForConfirmation = false;
                    nextCommandReadyTime = System.currentTimeMillis() + getCommandDelayMs();
                    // Re-add the command to the front of the queue
                    synchronized (QUEUE_LOCK) {
                        ((LinkedList<QueuedCommand>) commandQueue).addFirst(
                            new QueuedCommand(pendingPlayer, pendingIsAdd, pendingDurationMinutes, pendingIsTimedUnignore, pendingIsWarCommand));
                    }
                    confirmationStartTime = 0;
                } else {
                    WynnIgnoreMod.LOGGER.warn("Confirmation timeout for player: {} (max retries reached, skipping)", pendingPlayer);
                    sendMessage(Text.literal("[WynnIgnore] Failed to process ")
                        .formatted(Formatting.RED)
                        .append(Text.literal(pendingPlayer != null ? pendingPlayer : "unknown").formatted(Formatting.YELLOW))
                        .append(Text.literal(" after " + getMaxRetries() + " retries. Skipping.").formatted(Formatting.RED)));

                    // Reset state and move to next command
                    waitingForConfirmation = false;
                    pendingPlayer = null;
                    confirmationStartTime = 0;
                    retryCount = 0;
                    nextCommandReadyTime = System.currentTimeMillis() + getCommandDelayMs();
                }
            }
        }
    }

    private static void processNextCommand() {
        if (waitingForConfirmation) {
            return;
        }

        // Respect delay between commands
        if (nextCommandReadyTime > 0 && System.currentTimeMillis() < nextCommandReadyTime) {
            return;
        }
        nextCommandReadyTime = 0;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        QueuedCommand cmd;
        synchronized (QUEUE_LOCK) {
            cmd = commandQueue.poll();
        }

        if (cmd != null) {
            // Reset retry count when starting a genuinely new player (not a retry)
            if (!cmd.playerName.equalsIgnoreCase(pendingPlayer)) {
                retryCount = 0;
            }

            waitingForConfirmation = true;
            pendingPlayer = cmd.playerName;
            pendingIsAdd = cmd.isAdd;
            pendingDurationMinutes = cmd.durationMinutes;
            pendingIsTimedUnignore = cmd.isTimedUnignore;
            pendingIsWarCommand = cmd.isWarCommand;
            confirmationStartTime = System.currentTimeMillis();

            sendingQueuedCommand = true;
            if (cmd.isAdd) {
                client.player.networkHandler.sendChatCommand("ignore " + cmd.playerName);
            } else {
                client.player.networkHandler.sendChatCommand("ignore remove " + cmd.playerName);
            }
            sendingQueuedCommand = false;
        }
    }

    /**
     * Returns true if we're currently sending a queued command.
     */
    public static boolean isSendingQueuedCommand() {
        return sendingQueuedCommand;
    }

    /**
     * Strips formatting codes, PUA characters, and other special characters from a message.
     */
    private static String stripSpecialCharacters(String message) {
        if (message == null) {
            return "";
        }
        String stripped = message.replaceAll("§.", "");
        stripped = stripped.replaceAll("[\\uE000-\\uF8FF]", "");
        stripped = stripped.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        return stripped;
    }

    /**
     * Called when a chat message is received.
     */
    public static boolean onChatMessage(String message) {
        if (!waitingForConfirmation || pendingPlayer == null) {
            return false;
        }

        String cleanedMessage = stripSpecialCharacters(message);
        String lowerMessage = cleanedMessage.toLowerCase();
        String lowerPlayer = pendingPlayer.toLowerCase();

        // Check for add confirmation
        if (pendingIsAdd && lowerMessage.contains(lowerPlayer) && lowerMessage.contains("has been added to your ignore list")) {
            IgnoreListManager manager = WynnIgnoreMod.getIgnoreListManager();
            if (manager == null) {
                resetPendingState();
                return true;
            }

            int duration = pendingDurationMinutes;
            if (duration > 0) {
                manager.addTimedIgnore(pendingPlayer, duration);
                sendMessage(Text.literal("[WynnIgnore] Ignored ")
                    .formatted(Formatting.GREEN)
                    .append(Text.literal(pendingPlayer).formatted(Formatting.YELLOW))
                    .append(Text.literal(" for " + formatDuration(duration) + ".").formatted(Formatting.GREEN)));
            } else {
                // Permanent ignore
                manager.addPlayer(pendingPlayer);
                sendMessage(Text.literal("[WynnIgnore] Ignored ")
                    .formatted(Formatting.GREEN)
                    .append(Text.literal(pendingPlayer).formatted(Formatting.YELLOW))
                    .append(Text.literal(" permanently.").formatted(Formatting.GREEN)));
            }

            resetPendingState();
            nextCommandReadyTime = System.currentTimeMillis() + getCommandDelayMs();
            return true;
        }

        // Check for remove confirmation
        if (!pendingIsAdd && lowerMessage.contains(lowerPlayer) && lowerMessage.contains("has been removed from your ignore list")) {
            IgnoreListManager manager = WynnIgnoreMod.getIgnoreListManager();
            if (manager == null) {
                resetPendingState();
                return true;
            }

            if (pendingIsTimedUnignore) {
                manager.onTimedUnignoreComplete(pendingPlayer);
                sendMessage(Text.literal("[WynnIgnore] Auto-unignored ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(pendingPlayer).formatted(Formatting.YELLOW))
                    .append(Text.literal(" (timed ignore expired).").formatted(Formatting.GRAY)));
            } else {
                manager.removePlayer(pendingPlayer);
                sendMessage(Text.literal("[WynnIgnore] Unignored ")
                    .formatted(Formatting.GREEN)
                    .append(Text.literal(pendingPlayer).formatted(Formatting.YELLOW))
                    .append(Text.literal(".").formatted(Formatting.GREEN)));
            }

            resetPendingState();
            nextCommandReadyTime = System.currentTimeMillis() + getCommandDelayMs();
            return true;
        }

        // Check for "not being ignored" response (player already unignored on server)
        if (!pendingIsAdd && lowerMessage.contains(lowerPlayer) && lowerMessage.contains("is not being ignored")) {
            IgnoreListManager manager = WynnIgnoreMod.getIgnoreListManager();
            if (manager == null) {
                resetPendingState();
                return true;
            }

            manager.removePlayer(pendingPlayer);
            sendMessage(Text.literal("[WynnIgnore] ")
                .formatted(Formatting.GRAY)
                .append(Text.literal(pendingPlayer).formatted(Formatting.YELLOW))
                .append(Text.literal(" was not ignored on the server. Removed from local list.").formatted(Formatting.GRAY)));

            resetPendingState();
            nextCommandReadyTime = System.currentTimeMillis() + getCommandDelayMs();
            return true;
        }

        return false;
    }

    private static void resetPendingState() {
        waitingForConfirmation = false;
        pendingPlayer = null;
        confirmationStartTime = 0;
        retryCount = 0;
    }

    private static String formatDuration(int minutes) {
        if (minutes < 60) {
            return minutes + " minute" + (minutes == 1 ? "" : "s");
        } else if (minutes < 24 * 60) {
            int hours = minutes / 60;
            int mins = minutes % 60;
            if (mins == 0) {
                return hours + " hour" + (hours == 1 ? "" : "s");
            }
            return hours + "h " + mins + "m";
        } else {
            int days = minutes / (24 * 60);
            int hours = (minutes % (24 * 60)) / 60;
            if (hours == 0) {
                return days + " day" + (days == 1 ? "" : "s");
            }
            return days + "d " + hours + "h";
        }
    }

    private static void sendMessage(Text message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(message, false);
        }
    }
}
