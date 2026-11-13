package com.zombiespawner;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /zombiespawner reset         -> reset every region in every dimension
 * /zombiespawner reset here    -> reset only the region you are standing in
 * /zombiespawner inspect       -> how the mod scores the room you are standing in
 * /zombiespawner scan          -> scans the region you are in and says what it found
 * /zombiespawner stats         -> where spawn attempts succeed or fail (stats reset = start over)
 * /zombiespawner test          -> spawns one zombie in the nearest verified room (ignores budget and sight)
 * Requires op (permission level 2).
 */
public class ResetCommand {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("zombiespawner")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("reset")
                                .executes(this::resetAll)
                                .then(Commands.literal("here")
                                        .executes(this::resetHere)))
                        .then(Commands.literal("inspect").executes(this::inspect))
                        .then(Commands.literal("scan").executes(this::scan))
                        .then(Commands.literal("stats")
                                .executes(this::stats)
                                .then(Commands.literal("reset").executes(this::statsReset)))
                        .then(Commands.literal("test").executes(this::test)));
    }

    private int resetAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        int count = 0;
        for (ServerLevel level : src.getServer().getAllLevels()) {
            RegionData.get(level).clearAll();
            count++;
        }
        final int dims = count;
        src.sendSuccess(() -> Component.literal(
                "[ZombieSpawner] Reset all regions in " + dims + " dimension(s). Cleared areas will repopulate."), true);
        return 1;
    }

    private int resetHere(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 pos = src.getPosition();
        BlockPos bp = BlockPos.containing(pos);
        long key = RegionData.key(bp.getX(), bp.getZ(), ZSConfig.REGION_SIZE.get());
        RegionData.get(level).clearRegion(key);
        src.sendSuccess(() -> Component.literal(
                "[ZombieSpawner] Reset the region you're standing in."), true);
        return 1;
    }

    private void say(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }

    // ------------------------------------------------------------------ inspect

    /** Stand somewhere and run this to see exactly how the mod scores that room. */
    private int inspect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();

        BlockPos p = player.blockPosition();
        BlockPos floor = null;
        if (Spots.isStandable(level, p.below())) floor = p.below();
        else if (Spots.isStandable(level, p)) floor = p;

        long key = RegionData.key(p.getX(), p.getZ(), ZSConfig.REGION_SIZE.get());
        say(src, "[ZombieSpawner] " + RegionData.get(level).describe(key));

        if (floor == null) {
            say(src, "[ZombieSpawner] Can't find a standable floor under you (stand on solid ground with 2 blocks of air above).");
            return 0;
        }

        BuildingDetector.Result r = BuildingDetector.analyse(level, floor, player);
        say(src, "[ZombieSpawner] Floor block at " + floor.getX() + " " + floor.getY() + " " + floor.getZ());
        say(src, "  ceiling: " + (r.roofed ? r.ceilingHeight + " blocks above the floor" : "none"));
        say(src, "  walls: " + r.hits + "/8 directions  -> " + BuildingDetector.fmt(r.pWalls) + " pts");
        if (r.roofed && r.hits >= 3) {
            say(src, "  materials: " + r.manMade + "/" + r.samples + " man-made = " + String.format("%.2f", r.ratio)
                    + " (need " + ZSConfig.MIN_MAN_MADE_RATIO.get() + ")  -> " + BuildingDetector.fmt(r.pMaterial) + " pts");
            say(src, "  shape: " + r.cells + " open cells, flat floor " + r.flatFloor + ", flat ceiling "
                    + r.flatCeiling + "  -> " + BuildingDetector.fmt(r.pShape) + " pts");
            say(src, "  man-made blocks nearby: " + r.manBlocks + "  -> " + BuildingDetector.fmt(r.pVolume) + " pts");
            say(src, "  roofed area: " + r.coverRoofed + "/" + r.coverCells + " cells  -> " + BuildingDetector.fmt(r.pRoofed) + " pts");
            say(src, "  natural blocks above: " + r.naturalCover + " (limit " + ZSConfig.MAX_NATURAL_COVER.get() + ")");
            for (String s : r.surfaces) say(src, "    " + s);
            say(src, "  SCORE " + BuildingDetector.fmt(r.score) + " (need " + BuildingDetector.fmt(ZSConfig.INDOOR_MIN_SCORE.get()) + ")");
        }
        say(src, "[ZombieSpawner] Verdict: " + (r.building ? "BUILDING" : "NOT a building") + " - " + r.verdict
                + (ZSConfig.ALLOW_CAVES.get() ? "  (allowCaves is ON, so any roofed spot is accepted)" : ""));
        return 1;
    }

    // ------------------------------------------------------------------ scan

    /** Scans the region you stand in, like the spawner does, and shows what it found and why spots failed. */
    private int scan(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();
        int size = ZSConfig.REGION_SIZE.get();
        BlockPos p = player.blockPosition();
        long key = RegionData.key(p.getX(), p.getZ(), size);
        RegionData data = RegionData.get(level);

        RegionScanner.Scan sc = RegionScanner.scan(level, key, size, level.random, player);
        if (sc == null) {
            say(src, "[ZombieSpawner] Not enough of this region is loaded yet. Wait a moment or move toward its middle.");
            return 0;
        }
        data.putScan(key, sc);
        if (!data.isKnown(key)) data.setRegion(level, key, sc.zombies, sc.density);

        say(src, "[ZombieSpawner] Scan of " + data.describe(key));
        say(src, "  roofed standing spots found: " + sc.floorsFound + ", tested: " + sc.tested
                + ", verified as rooms: " + sc.verified);
        say(src, "  indoor zombies this region earns: " + String.format("%.1f", sc.zombies));
        if (!sc.reasons.isEmpty()) {
            List<Map.Entry<String, Integer>> list = new ArrayList<>(sc.reasons.entrySet());
            list.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder sb = new StringBuilder("  why the others failed: ");
            for (Map.Entry<String, Integer> e : list) sb.append(e.getKey()).append(" x").append(e.getValue()).append(", ");
            say(src, sb.toString());
        }
        if (sc.verified == 0) {
            say(src, "  Nothing here counted as a room. Stand inside a building and run /zombiespawner inspect to see the points,"
                    + " then lower indoorMinScore / minEnclosedRays or add the building's blocks to buildingBlocks.");
        }
        return 1;
    }

    // ------------------------------------------------------------------ stats

    private int stats(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        say(src, "[ZombieSpawner] Since the last reset: " + SpawnStats.cycles + " spawn cycles, " + SpawnStats.attempts + " attempts");
        say(src, "  spawned: " + SpawnStats.spawnedIndoor + " indoor, " + SpawnStats.spawnedOpen + " open, "
                + SpawnStats.spawnedWanderer + " wanderers, " + SpawnStats.spawnFailed + " failed to place");
        say(src, "  attempts lost because the region was: cleared " + SpawnStats.regionCleared
                + ", not ready/loaded " + SpawnStats.regionNotReady + ", out of budget " + SpawnStats.regionNoBudget
                + ", no usable spot " + SpawnStats.regionNoSpot);
        say(src, "  indoor spots rejected: wrong height " + SpawnStats.spotVertical + ", wrong distance " + SpawnStats.spotDistance
                + ", changed since scan " + SpawnStats.spotStale + ", zombie already there " + SpawnStats.spotCrowded
                + ", player could see it " + SpawnStats.spotHidden);
        say(src, "  region scans: " + SpawnStats.scans + " (" + SpawnStats.scansFailedNotLoaded + " postponed: chunks not loaded)");
        return 1;
    }

    private int statsReset(CommandContext<CommandSourceStack> ctx) {
        SpawnStats.reset();
        say(ctx.getSource(), "[ZombieSpawner] Stats reset.");
        return 1;
    }

    // ------------------------------------------------------------------ test

    /** Proves the chain works: spawns one zombie in the nearest verified room, ignoring budget and line of sight. */
    private int test(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();
        int size = ZSConfig.REGION_SIZE.get();
        RegionData data = RegionData.get(level);
        int pr = Math.floorDiv(player.getBlockX(), size), pz = Math.floorDiv(player.getBlockZ(), size);

        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = RegionData.regionKey(pr + dx, pz + dz);
                RegionScanner.Scan sc = data.getScan(key);
                if (sc == null) {
                    sc = RegionScanner.scan(level, key, size, level.random, player);
                    if (sc == null) continue;
                    data.putScan(key, sc);
                    if (!data.isKnown(key)) data.setRegion(level, key, sc.zombies, sc.density);
                }
                for (BlockPos c : sc.spots) {
                    double d = c.distSqr(player.blockPosition());
                    if (d < 36.0 || d >= bestD) continue;       // at least 6 blocks away
                    if (!Spots.isStandable(level, c)) continue;
                    bestD = d;
                    best = c;
                }
            }
        }
        if (best == null) {
            say(src, "[ZombieSpawner] No verified room within the 3x3 regions around you. Run /zombiespawner scan to see why.");
            return 0;
        }
        Vec3 feet = new Vec3(best.getX() + 0.5, Spots.feetY(level, best), best.getZ() + 0.5);
        // wanderer=true: not tied to any region budget
        boolean ok = HiddenSpawner.spawnAt(level, HiddenSpawner.resolveType(), feet, level.random, 0L, true, false);
        say(src, ok
                ? "[ZombieSpawner] Spawned a test zombie inside a room at " + best.getX() + " " + (best.getY() + 1) + " " + best.getZ()
                        + " (" + (int) Math.sqrt(bestD) + " blocks away)."
                : "[ZombieSpawner] Found a room at " + best.getX() + " " + best.getY() + " " + best.getZ()
                        + " but the zombie could not be placed there.");
        return ok ? 1 : 0;
    }
}
