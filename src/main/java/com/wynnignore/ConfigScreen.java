package com.wynnignore;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class ConfigScreen {

    public static Screen create(Screen parent) {
        ModConfig config = ModConfig.getInstance();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.literal("WynnIgnore Configuration"));

        ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        general.addEntry(entryBuilder.startIntField(Text.literal("War Ignore Duration (min)"), config.getWarIgnoreDurationMinutes())
            .setDefaultValue(15)
            .setMin(1)
            .setMax(60)
            .setTooltip(Text.literal("How long war-ignored players stay ignored (minutes)"))
            .setSaveConsumer(config::setWarIgnoreDurationMinutes)
            .build());

        general.addEntry(entryBuilder.startIntField(Text.literal("War Ignore Distance (blocks)"), (int) config.getWarIgnoreDistance())
            .setDefaultValue(20)
            .setMin(1)
            .setMax(50)
            .setTooltip(Text.literal("Max distance to detect nearby players for /warignore"))
            .setSaveConsumer(val -> config.setWarIgnoreDistance(val))
            .build());

        general.addEntry(entryBuilder.startIntField(Text.literal("Command Delay (ms)"), (int) config.getCommandDelayMs())
            .setDefaultValue(500)
            .setMin(100)
            .setMax(1000)
            .setTooltip(Text.literal("Delay between queued /ignore commands to avoid rate limiting"))
            .setSaveConsumer(val -> config.setCommandDelayMs(val))
            .build());

        general.addEntry(entryBuilder.startIntField(Text.literal("Max Retries"), config.getCommandMaxRetries())
            .setDefaultValue(3)
            .setMin(0)
            .setMax(5)
            .setTooltip(Text.literal("How many times to retry a command if the server doesn't respond"))
            .setSaveConsumer(config::setCommandMaxRetries)
            .build());

        return builder.build();
    }
}
