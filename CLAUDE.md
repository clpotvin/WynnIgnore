# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

WynnIgnore is a client-side Fabric mod for Minecraft 1.21.11 that enhances the `/ignore` system on the Wynncraft server. It tracks ignored players locally, adds timed ignores (auto-unignore after a duration), and provides a `/warignore` command to bulk-ignore nearby players during wars.

## Build Commands

- **Build:** `./gradlew build` (output jar in `build/libs/`)
- **Clean build:** `./gradlew clean build`
- **Run client:** `./gradlew runClient`

## Tech Stack

- Java 21, Fabric Loader 0.18.1, Fabric API 0.141.3, Minecraft 1.21.11
- Yarn mappings 1.21.11+build.4
- Fabric Loom 1.14 (Gradle plugin), Gradle 9.3.1
- Mod Menu integration (Terraformers modmenu 17.0.0-beta.2)
- SpongePowered Mixin for hooking into Minecraft internals

## Architecture

All source is in `src/main/java/com/wynnignore/`. This is a client-only mod (`"environment": "client"` in fabric.mod.json).

**Entry point:** `WynnIgnoreMod` — implements `ClientModInitializer`. Initializes the ignore list manager, registers commands, and sets up tick/join event handlers for timed ignore expiry checking.

**Core flow — command interception via Mixin:**
- `ClientPlayNetworkHandlerMixin` intercepts outgoing `/ignore` commands (via `sendChatCommand`) and incoming chat messages (via `onGameMessage`)
- Outgoing: intercepts `/ignore list` to show local tracked list, intercepts `/ignore <player> <minutes>` for timed ignores, and tracks add/remove operations
- Incoming: watches for server confirmation messages ("has been added/removed from your ignore list") to confirm operations

**Command queue system (`CommandHandler`):**
- All ignore/unignore operations go through a sequential queue that sends one command at a time and waits for server confirmation before proceeding
- This is necessary because Wynncraft rate-limits `/ignore` commands
- Configurable delay between commands (default 500ms) and retry count (default 3) to handle server rate limiting
- War commands use a shorter 1-second confirmation timeout; regular commands use 10 seconds
- Registers two client commands: `/unignore <player|all>` and `/warignore`

**Persistence:**
- `IgnoreListManager` — stores ignored players and timed ignore expiry timestamps in `config/wynnignore.json` (versioned format, v2 current, with v1 migration support)
- `ModConfig` — stores settings (war ignore duration, war ignore distance, command delay, max retries) in `config/wynnignore_config.json`. Singleton with double-checked locking.

**Config UI:** `ConfigScreen` provides a Mod Menu-accessible settings screen for war ignore duration (1-60 min), distance (1-50 blocks), command delay (100-1000ms), and max retries (0-5).

## Key Design Details

- All player names are stored lowercase for case-insensitive matching
- The mixin uses `isSendingQueuedCommand()` flag to avoid double-tracking when the command queue sends commands programmatically
- Timed ignore expiry is checked once per second (every 20 ticks) plus on world join
- `/warignore` ignores all players within configured distance using timed ignores
