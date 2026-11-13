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
    public static final ForgeConfigSpec.IntValue VERTICAL_RANGE;
    public static final ForgeConfigSpec.IntValue BASE_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue DENSE_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue SPAWNS_PER_CYCLE;
    public static final ForgeConfigSpec.IntValue DESPAWN_DIST;
    public static final ForgeConfigSpec.BooleanValue STRICT_LOS;
    public static final ForgeConfigSpec.IntValue VIEW_CONE;
    public static final ForgeConfigSpec.BooleanValue OWN_DESPAWN;
    public static final ForgeConfigSpec.BooleanValue ALLOW_BABIES;
    public static final ForgeConfigSpec.BooleanValue LOG_REMOVALS;

    // --- regions ---
    public static final ForgeConfigSpec.IntValue REGION_SIZE;
    public static final ForgeConfigSpec.IntValue DENSE_INDOOR;
    public static final ForgeConfigSpec.IntValue AREA_PER_ZOMBIE;
    public static final ForgeConfigSpec.IntValue SCAN_CANDIDATES;
    public static final ForgeConfigSpec.IntValue MIN_OPEN;
    public static final ForgeConfigSpec.IntValue MAX_OPEN;
    public static final ForgeConfigSpec.DoubleValue OPEN_CHANCE;
    public static final ForgeConfigSpec.BooleanValue FRONTIER_ENABLED;
    public static final ForgeConfigSpec.DoubleValue FRONTIER_CHANCE;

    // --- where zombies may stand / what counts as a building ---
    public static final ForgeConfigSpec.BooleanValue ALLOW_CAVES;
    public static final ForgeConfigSpec.DoubleValue INDOOR_MIN_SCORE;
    public static final ForgeConfigSpec.IntValue CEILING_HEIGHT;
    public static final ForgeConfigSpec.IntValue ENCLOSURE_RADIUS;
    public static final ForgeConfigSpec.IntValue MIN_ENCLOSED_RAYS;
    public static final ForgeConfigSpec.DoubleValue MIN_MAN_MADE_RATIO;
    public static final ForgeConfigSpec.BooleanValue DETECT_BY_SHAPE;
    public static final ForgeConfigSpec.IntValue MAX_NATURAL_COVER;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> NATURAL_BLOCKS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BUILDING_BLOCKS;

    // --- vanilla blocking ---
    public static final ForgeConfigSpec.BooleanValue BLOCK_VANILLA_HOSTILES;
    public static final ForgeConfigSpec.BooleanValue BLOCK_PHANTOMS;
    public static final ForgeConfigSpec.BooleanValue BLOCK_REINFORCEMENTS;

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
        ATTEMPTS = b.comment("How many random regions to try per cycle per player.")
                .defineInRange("attemptsPerCycle", 10, 1, 100);
        MIN_DIST = b.comment("Minimum spawn distance from the player (blocks).")
                .defineInRange("minDistance", 24, 8, 128);
        MAX_DIST = b.comment("Maximum spawn distance from the player (blocks).")
                .defineInRange("maxDistance", 64, 16, 128);
        VERTICAL_RANGE = b.comment("How far above/below the player (blocks) a zombie may be placed.")
                .defineInRange("verticalRange", 24, 4, 128);
        BASE_PER_PLAYER = b.comment("Max spawned zombies around one player in sparse areas (villages, countryside).")
                .defineInRange("baseZombiesPerPlayer", 10, 1, 500);
        DENSE_PER_PLAYER = b.comment("Max spawned zombies around one player in the densest areas (big multi-floor buildings, cities).",
                        "The real cap slides between baseZombiesPerPlayer and this number depending on how built-up the area around you is.")
                .defineInRange("maxZombiesDense", 25, 1, 500);
        SPAWNS_PER_CYCLE = b.comment("Max zombies spawned per player per spawn cycle (lets dense areas fill up faster).")
                .defineInRange("maxSpawnsPerCycle", 2, 1, 20);
        DESPAWN_DIST = b.comment("Zombies spawned by this mod are removed when no player is within this many blocks.")
                .defineInRange("despawnDistance", 128, 32, 512);
        OWN_DESPAWN = b.comment("Mark spawned zombies as persistent (vanilla won't random-despawn them) and use despawnDistance instead.")
                .define("useOwnDespawn", true);
        ALLOW_BABIES = b.comment("false = no baby zombies (and no chicken jockeys). Applies to zombies spawned by this mod and",
                        "to any other baby zombie that appears in the world. Existing baby zombies are turned into adults.")
                .define("allowBabyZombies", false);
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
        DENSE_INDOOR = b.comment("Zombies INSIDE BUILDINGS for the densest possible region. Every region gets a share of this depending on how",
                        "built-up it is: empty land gets 0, a village a few, a dense city or a tall many-floor building the full amount.",
                        "Killed zombies are never replaced. When every zombie of a region is dead, the region is CLEARED for good.")
                .defineInRange("denseIndoorPerRegion", 12, 1, 200);
        AREA_PER_ZOMBIE = b.comment("Indoor floor area (blocks) that earns one zombie. Each region is scanned for rooms; the zombies it gets",
                        "= verified indoor floor area / this number (every floor of a tall building counts), up to denseIndoorPerRegion.",
                        "Lower = more zombies in buildings.")
                .defineInRange("indoorAreaPerZombie", 100, 10, 5000);
        SCAN_CANDIDATES = b.comment("How many candidate rooms are tested per region scan (the scan is done once per region, then refreshed every few minutes).",
                        "Higher = more reliable in huge buildings, slightly more work during the scan.")
                .defineInRange("scanCandidates", 80, 10, 400);
        MIN_OPEN = b.comment("Zombies per region that stand OUTSIDE in the open. Keep this tiny.")
                .defineInRange("minOpenPerRegion", 0, 0, 200);
        MAX_OPEN = b.defineInRange("maxOpenPerRegion", 1, 0, 200);
        OPEN_CHANCE = b.comment("Chance that a spawn attempt targets the open instead of a building (when the region has open budget).")
                .defineInRange("openAttemptChance", 0.08, 0.0, 1.0);
        FRONTIER_ENABLED = b.comment("Wanderers: occasionally spawn a zombie in the open at the far edge of your range, but only in land",
                        "you have NEVER been near (the region and all 8 regions around it must be unvisited and not cleared).")
                .define("frontierWanderers", true);
        FRONTIER_CHANCE = b.comment("Chance per spawn cycle (per player) to try a frontier wanderer.")
                .defineInRange("frontierChance", 0.05, 0.0, 1.0);
        b.pop();

        b.push("placement");
        ALLOW_CAVES = b.comment("true = ANY roofed spot counts as indoors (caves included) and the building check below is skipped.")
                .define("allowCaves", false);
        INDOOR_MIN_SCORE = b.comment("A roofed spot counts as a building room when its score reaches this (max 8). Points come from several",
                        "independent signals: walls around it, man-made materials, regular room shape,",
                        "man-made block count, and how much of the area around it is roofed. Furniture is never used,",
                        "so empty buildings count too. Lower = more places count as buildings.")
                .defineInRange("indoorMinScore", 3.5, 0.0, 10.0);
        CEILING_HEIGHT = b.comment("A building needs a ceiling within this many blocks above the floor.")
                .defineInRange("ceilingSearchHeight", 16, 3, 64);
        ENCLOSURE_RADIUS = b.comment("Building check: walls are looked for in 8 directions up to this many blocks away.")
                .defineInRange("enclosureRadius", 10, 3, 32);
        MIN_ENCLOSED_RAYS = b.comment("Building check: how many of the 8 directions must hit a wall within enclosureRadius.",
                        "Lower = ruins and open-sided buildings count too.")
                .defineInRange("minEnclosedRays", 5, 1, 8);
        MIN_MAN_MADE_RATIO = b.comment("Building check (materials): share of the floor, ceiling and wall blocks around a spot that must NOT be natural",
                        "(stone, dirt, sand, ores, logs, leaves...). EVERY block that is not natural counts as man-made, including all modded blocks.")
                .defineInRange("minManMadeRatio", 0.5, 0.0, 1.0);
        DETECT_BY_SHAPE = b.comment("Building check (shape): a room with a flat floor, a flat ceiling and walls around it counts as a building",
                        "even if it is built from blocks that look natural (plain stone, logs, dirt, a modded stone...).")
                .define("detectByShape", true);
        MAX_NATURAL_COVER = b.comment("A room with more than this many natural blocks (rock, dirt...) above it is underground (a cave, mine,",
                        "stronghold) and is not a building, unless its walls are clearly man-made.")
                .defineInRange("maxNaturalCover", 6, 0, 64);
        NATURAL_BLOCKS = b.comment("Extra blocks that should ALWAYS count as natural terrain. Entries: \"modid:block\", \"modid:*\" (whole mod) or \"#modid:tag\".",
                        "Use this for modded stone/dirt/ore blocks that make up caves in your world.")
                .defineListAllowEmpty("naturalBlocks", List.<String>of(), o -> o instanceof String);
        BUILDING_BLOCKS = b.comment("Extra blocks that should ALWAYS count as man-made. Same entry format as naturalBlocks.",
                        "Wins over naturalBlocks and over the built-in natural list. Example: [\"mymod:*\", \"#forge:cobblestone\"]")
                .defineListAllowEmpty("buildingBlocks", List.<String>of(), o -> o instanceof String);
        b.pop();

        b.push("vanilla_blocking");
        BLOCK_VANILLA_HOSTILES = b.comment("Cancel natural + world-generation spawns of all hostile mobs (zombies included).")
                .define("blockVanillaHostiles", true);
        BLOCK_PHANTOMS = b.comment("Also block phantoms (they spawn through a special system).")
                .define("blockPhantoms", true);
        BLOCK_REINFORCEMENTS = b.comment("Stop zombies from calling in extra zombies when they are hit. Those extra zombies are not part of any",
                        "region budget, so they would appear in areas you already cleared.")
                .define("blockZombieReinforcements", true);
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
