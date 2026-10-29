package com.zombiespawner;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/** Generated at config/zombiespawner-common.toml */
public class ZSConfig {
    public static final ForgeConfigSpec SPEC;

    // --- spawner ---
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> ENTITY_ID;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DIMENSIONS;
    public static final ForgeConfigSpec.IntValue INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue ATTEMPTS;
    public static final ForgeConfigSpec.IntValue MIN_DIST;
    public static final ForgeConfigSpec.IntValue MAX_DIST;
    public static final ForgeConfigSpec.IntValue MAX_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue DESPAWN_DIST;
    public static final ForgeConfigSpec.BooleanValue STRICT_LOS;
    public static final ForgeConfigSpec.IntValue VIEW_CONE;
    public static final ForgeConfigSpec.BooleanValue OWN_DESPAWN;
    public static final ForgeConfigSpec.BooleanValue LOG_REMOVALS;

    // --- vanilla blocking ---
    public static final ForgeConfigSpec.BooleanValue BLOCK_VANILLA_HOSTILES;
    public static final ForgeConfigSpec.BooleanValue BLOCK_PHANTOMS;

    // --- time ---
    public static final ForgeConfigSpec.BooleanValue MANAGE_TIME;
    public static final ForgeConfigSpec.DoubleValue DAY_MINUTES;
    public static final ForgeConfigSpec.DoubleValue NIGHT_MINUTES;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("spawner");
        ENABLED = b.comment("Master switch for the custom zombie spawner.")
                .define("enabled", true);
        ENTITY_ID = b.comment("Entity registry ID to spawn. Put your custom zombie here, e.g. \"mymod:custom_zombie\".")
                .define("entityId", "minecraft:zombie");
        DIMENSIONS = b.comment("Dimensions where the spawner runs.")
                .defineListAllowEmpty("dimensions", List.of("minecraft:overworld"), o -> o instanceof String);
        INTERVAL_TICKS = b.comment("Ticks between spawn cycles (20 = 1 second). Each cycle spawns at most 1 zombie per player.")
                .defineInRange("spawnIntervalTicks", 40, 1, 24000);
        ATTEMPTS = b.comment("How many random positions to try per cycle per player.")
                .defineInRange("attemptsPerCycle", 10, 1, 100);
        MIN_DIST = b.comment("Minimum spawn distance from the player (blocks).")
                .defineInRange("minDistance", 24, 8, 128);
        MAX_DIST = b.comment("Maximum spawn distance from the player (blocks). Keep it inside your simulation distance (default 10 chunks = 160 blocks, but 64 is safe).")
                .defineInRange("maxDistance", 64, 16, 128);
        MAX_PER_PLAYER = b.comment("Max spawned zombies within range of one player. Spawning pauses at this cap.")
                .defineInRange("maxZombiesPerPlayer", 15, 1, 500);
        DESPAWN_DIST = b.comment("Zombies spawned by this mod are removed when no player is within this many blocks.")
                .defineInRange("despawnDistance", 128, 32, 512);
        OWN_DESPAWN = b.comment("Mark spawned zombies as persistent (vanilla won't random-despawn them) and use despawnDistance instead.")
                .define("useOwnDespawn", true);
        STRICT_LOS = b.comment("true = reject any spot with a clear line of sight to the player, even behind them.",
                        "false = only reject spots the player could be looking at (inside the view cone).")
                .define("strictLineOfSight", false);
        VIEW_CONE = b.comment("Full width in degrees of the 'player can see this' cone (non-strict mode).")
                .defineInRange("viewConeDegrees", 150, 60, 360);
        LOG_REMOVALS = b.comment("Debug: log to the console whenever a zombie spawned by this mod is removed, with the reason and position.")
                .define("logRemovals", false);
        b.pop();

        b.push("vanilla_blocking");
        BLOCK_VANILLA_HOSTILES = b.comment("Cancel natural + world-generation spawns of all hostile mobs (zombies included).")
                .define("blockVanillaHostiles", true);
        BLOCK_PHANTOMS = b.comment("Also block phantoms (they spawn through a special system).")
                .define("blockPhantoms", true);
        b.pop();

        b.push("time");
        MANAGE_TIME = b.comment("Take over the day/night cycle. This turns the doDaylightCycle gamerule OFF and the mod advances time itself.",
                        "Side effect: sleeping no longer skips the night.")
                .define("manageTime", true);
        DAY_MINUTES = b.comment("Real minutes for the day half (vanilla = 10 min of day, plus 3 min dusk/dawn).")
                .defineInRange("dayMinutes", 25.0, 0.5, 600.0);
        NIGHT_MINUTES = b.comment("Real minutes for the night half.")
                .defineInRange("nightMinutes", 25.0, 0.5, 600.0);
        b.pop();

        SPEC = b.build();
    }
}
