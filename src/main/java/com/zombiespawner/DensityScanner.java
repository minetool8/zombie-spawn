package com.zombiespawner;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Measures how built-up a region is, so cities get many zombies and countryside gets few.
 *
 * It samples random columns. A column is "built" when the highest block is man-made (any non-natural block,
 * modded ones included; water doesn't count). Under every built column it counts the walkable floors, so a
 * many-floor building scores far higher than a one-room hut. Score = average floors per column, scaled to 0..1.
 */
public final class DensityScanner {
    private DensityScanner() {}

    public static final class Result {
        public double score;          // 0..1
        public double builtFraction;  // share of sampled columns that are built
        public double avgFloors;      // average walkable floors per sampled column
    }

    /** Returns null when too little of the region is loaded to judge it (try again later). */
    public static Result measure(ServerLevel level, int rx0, int rz0, int size, RandomSource rnd) {
        int samples = ZSConfig.DENSITY_SAMPLES.get();
        int loaded = 0, built = 0, floors = 0;
        int minY = level.getMinBuildHeight() + 1;

        for (int s = 0; s < samples; s++) {
            int x = rx0 + rnd.nextInt(size);
            int z = rz0 + rnd.nextInt(size);
            if (!level.hasChunkAt(new BlockPos(x, minY, z))) continue;
            loaded++;

            int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockState top = level.getBlockState(new BlockPos(x, h - 1, z));
            if (top.isAir() || !top.getFluidState().isEmpty() || BlockClassifier.isNatural(top)) continue;
            built++;

            int f = 0;
            for (int y = h - 4; y >= Math.max(minY, h - 52); y--) {
                if (Spots.isStandable(level, new BlockPos(x, y, z))) {
                    f++;
                    y -= 2;   // don't count one floor twice
                }
            }
            floors += Math.min(f, 10);
        }

        if (loaded < Math.max(8, samples / 3)) return null;

        Result r = new Result();
        r.builtFraction = (double) built / loaded;
        r.avgFloors = (double) floors / loaded;
        r.score = Math.max(0.0, Math.min(1.0, r.avgFloors / ZSConfig.DENSE_FLOORS.get()));
        return r;
    }
}
