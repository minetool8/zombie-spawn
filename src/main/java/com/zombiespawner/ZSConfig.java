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

    // --- regions ---
    public static final ForgeConfigSpec.IntValue REGION_SIZE;
    public static final ForgeConfigSpec.IntValue MIN_INDOOR;
    public static final ForgeConfigSpec.IntValue MAX_INDOOR;
    public static final ForgeConfigSpec.IntValue MIN_OPEN;
    public static final ForgeConfigSpec.IntValue MAX_OPEN;
    public static final ForgeConfigSpec.DoubleValue OPEN_CHANCE;
    public static final ForgeConfigSpec.BooleanValue FRONTIER_ENABLED;
    public static final ForgeConfigSpec.DoubleValue FRONTIER_CHANCE;

    // --- where zombies may stand ---
    public static final ForgeConfigSpec.BooleanValue ALLOW_CAVES;
    public static final ForgeConfigSpec.IntValue BUILDING_MAX_DEPTH;
    public static final ForgeConfigSpec.IntValue BUILDING_MIN_BLOCKS;

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
                .defineInRange("spawnIntervalTicks", 100, 1, 24000);
        ATTEMPTS = b.comment("How many random positions to try per cycle per player.")
                .defineInRange("attemptsPerCycle", 10, 1, 100);
        MIN_DIST = b.comment("Minimum spawn distance from the player (blocks).")
                .defineInRange("minDistance", 24, 8, 128);
        MAX_DIST = b.comment("Maximum spawn distance from the player (blocks).")
                .defineInRange("maxDistance", 64, 16, 128);
        MAX_PER_PLAYER = b.comment("Max spawned zombies within range of one player. Spawning pauses at this cap.")
                .defineInRange("maxZombiesPerPlayer", 5, 1, 500);
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

        b.push("regions");
        REGION_SIZE = b.comment("Side length (blocks) of a square region. Each region owns a fixed zombie budget.")
                .defineInRange("regionSize", 32, 8, 256);
        MIN_INDOOR = b.comment("Zombies per region that live INSIDE BUILDINGS. Random between min and max, rolled once per region.",
                        "Killed zombies are never replaced. 0 left = cleared forever.")
                .defineInRange("minIndoorPerRegion", 1, 0, 200);
        MAX_INDOOR = b.defineInRange("maxIndoorPerRegion", 3, 0, 200);
        MIN_OPEN = b.comment("Zombies per region that stand OUTSIDE in the open. Keep this tiny.")
                .defineInRange("minOpenPerRegion", 0, 0, 200);
        MAX_OPEN = b.defineInRange("maxOpenPerRegion", 1, 0, 200);
        OPEN_CHANCE = b.comment("Chance that a spawn attempt targets the open instead of a building (when the region has open budget).")
                .defineInRange("openSpawnChance", 0.15, 0.0, 1.0);
        FRONTIER_ENABLED = b.comment("Wanderers: occasionally spawn a zombie in the open at the far edge of your range, but only in regions",
                        "you have NEVER been inside. They never touch regions you have visited/cleared.")
                .define("frontierWanderers", true);
        FRONTIER_CHANCE = b.comment("Chance per spawn cycle (per player) to try a frontier wanderer.")
                .defineInRange("frontierChance", 0.05, 0.0, 1.0);
        b.pop();

        b.push("placement");
        ALLOW_CAVES = b.comment("Allow spawning in caves / underground spaces that are not buildings.")
                .define("allowCaves", false);
        BUILDING_MAX_DEPTH = b.comment("A roofed spot is only a 'building' if the surface is at most this many blocks above it.",
                        "Anything deeper counts as underground/cave.")
                .defineInRange("buildingMaxDepth", 12, 3, 64);
        BUILDING_MIN_BLOCKS = b.comment("A roofed spot is only a 'building' if at least this many man-made blocks (planks, bricks, glass, doors,",
                        "furniture...) are within 3 blocks. Lower = more places count as buildings, higher = stricter.")
                .defineInRange("buildingMinBlocks", 10, 1, 150);
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
