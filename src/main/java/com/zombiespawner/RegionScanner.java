package com.zombiespawner;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks through a whole region for rooms, instead of poking at random columns.
 *
 * 1. Walks a grid of columns and lists every roofed standing spot (every floor of every building).
 * 2. Tests a sample of them with {@link BuildingDetector}; the ones that pass are verified indoor spots.
 * 3. Estimates the indoor floor area (all floors) and from it how many zombies the region deserves.
 *
 * The verified spots are remembered, so spawning picks from a ready list of real rooms.
 */
public final class RegionScanner {
    private RegionScanner() {}

    public static final class Scan {
        public final List<BlockPos> spots = new ArrayList<>();   // verified indoor floors
        public int floorsFound;      // roofed standing spots found on the grid
        public int tested;           // how many were analysed
        public int verified;         // how many passed
        public int maxFloors;        // floors of the tallest verified building (counted per column)
        public double area;          // estimated indoor floor area, all floors
        public double zombies;       // raw zombie budget (before rounding)
        public double density;       // 0..1, zombies / denseIndoorPerRegion
        public long time;            // game time of the scan
        public final Map<String, Integer> reasons = new HashMap<>();   // why tested spots failed
    }

    /** Returns null when too little of the region is loaded to judge it (try again later). */
    public static Scan scan(ServerLevel level, long key, int size, RandomSource rnd, Entity rayOwner) {
        int rx0 = RegionData.regionX(key) * size;
        int rz0 = RegionData.regionZ(key) * size;
        int minY = level.getMinBuildHeight() + 1;
        boolean caves = ZSConfig.ALLOW_CAVES.get();
        int stride = Math.max(3, size / 11);

        int cols = 0, loadedCols = 0;
        List<BlockPos> floors = new ArrayList<>();
        Map<Long, Integer> colFloors = new HashMap<>();
        for (int x = rx0 + 1; x < rx0 + size; x += stride) {
            for (int z = rz0 + 1; z < rz0 + size; z += stride) {
                cols++;
                if (!level.hasChunkAt(new BlockPos(x, minY, z))) continue;
                loadedCols++;

                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockState top = level.getBlockState(new BlockPos(x, h - 1, z));
                boolean manTop = !top.isAir() && top.getFluidState().isEmpty() && !BlockClassifier.isNatural(top);

                int lowest = Math.max(minY, h - 64);
                int before = floors.size();
                for (int y = h - 4; y >= lowest; y--) {
                    BlockPos f = new BlockPos(x, y, z);
                    if (!Spots.isStandable(level, f)) continue;
                    // buildings have a man-made top; anything else must be near the surface (cheap pre-filter)
                    if (caves || manTop || h - y <= 14) floors.add(f);
                    y -= 2; // a floor needs 3 blocks of height, don't count it twice
                }
                colFloors.put(((long) x << 32) | (z & 0xFFFFFFFFL), floors.size() - before);
            }
        }
        if (loadedCols < Math.max(6, cols / 3)) return null;

        Scan sc = new Scan();
        sc.time = level.getGameTime();
        sc.floorsFound = floors.size();
        Collections.shuffle(floors, new java.util.Random(rnd.nextLong()));

        int limit = Math.min(floors.size(), caves ? 200 : ZSConfig.SCAN_CANDIDATES.get());
        for (int i = 0; i < limit; i++) {
            BlockPos f = floors.get(i);
            if (caves) {
                sc.tested++;
                sc.verified++;
                sc.spots.add(f);
                continue;
            }
            BuildingDetector.Result r = BuildingDetector.analyse(level, f, rayOwner);
            if (r.incomplete) continue;              // chunk edge: no verdict either way
            sc.tested++;
            if (r.building) {
                sc.verified++;
                sc.spots.add(f);
                sc.maxFloors = Math.max(sc.maxFloors, colFloors.getOrDefault(((long) f.getX() << 32) | (f.getZ() & 0xFFFFFFFFL), 1));
            } else {
                sc.reasons.merge(r.reason, 1, Integer::sum);
            }
        }

        int minZ = ZSConfig.MIN_PER_BUILDING.get();
        int maxZ = Math.max(ZSConfig.MAX_PER_BUILDING.get(), minZ);
        if (sc.tested > 0 && sc.verified > 0) {
            double fraction = (double) sc.verified / sc.tested;
            sc.area = floors.size() * (double) stride * stride * fraction;       // indoor floor area, all floors
            // size: a small building earns about the minimum, a massive one the maximum
            double z = minZ + (maxZ - minZ) * Math.min(1.0, sc.area / ZSConfig.MASSIVE_AREA.get());
            // tall buildings: extra zombies for every floor above floorsWithoutBonus
            int free = ZSConfig.FLOORS_FREE.get();
            if (sc.maxFloors > free) z += (double) ZSConfig.PER_EXTRA_FLOOR.get() * (sc.maxFloors - free);
            sc.zombies = Math.max(minZ, Math.min(maxZ, z));
        }
        sc.density = Math.min(1.0, sc.zombies / maxZ);
        return sc;
    }
}
