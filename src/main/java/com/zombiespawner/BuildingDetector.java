package com.zombiespawner;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a standing spot is INSIDE A BUILDING by combining several independent signals into a score.
 * No single check can reject a room on its own, a real room just needs enough evidence.
 * Furniture is NOT used: empty buildings count exactly like furnished ones.
 *
 * Required: a ceiling above, and at least 3 of 8 directions with a wall nearby.
 * Points (max 8):
 *   walls      0.5-2  how many of 8 directions hit a wall
 *   material   0-2    share of floor/ceiling/wall blocks that are man-made (all non-natural blocks, modded included)
 *   shape      0-2    flat floor + flat ceiling over the open cells around (caves never are)
 *   volume     0-1    how many man-made blocks surround the spot
 *   roofed     0-1    how much of the floor area around the spot has a ceiling
 * A room counts when the score reaches indoorMinScore. Rooms buried under lots of natural rock are caves/mines
 * and are rejected unless their walls are clearly man-made.
 */
public final class BuildingDetector {
    private BuildingDetector() {}

    public static final class Result {
        public boolean building, roofed, enclosed, underground, materialOk, shapeOk, incomplete;
        public int ceilingHeight, hits, naturalCover, manMade, samples;
        public int cells, flatFloor, flatCeiling, coverCells, coverRoofed;
        public int manBlocks;
        public double ratio, score;
        public double pWalls, pMaterial, pShape, pVolume, pRoofed;
        public String verdict = "";
        /** short, stable reason used for statistics */
        public String reason = "";
        public final List<String> surfaces = new ArrayList<>();
    }

    private static final int NONE = Integer.MIN_VALUE;
    private static final int MIN_WALL_HITS = 3;

    public static Result analyse(ServerLevel level, BlockPos floor, Entity rayOwner) {
        Result r = new Result();
        int radius = ZSConfig.ENCLOSURE_RADIUS.get();
        int ceilSearch = ZSConfig.CEILING_HEIGHT.get();
        int reach = Math.max(radius, 5) + 2;

        // never force-load chunks just to look at a room
        if (!level.hasChunksAt(floor.offset(-reach, 0, -reach), floor.offset(reach, 0, reach))) {
            r.incomplete = true;
            r.reason = "chunks not loaded";
            r.verdict = "the chunks around this spot are not loaded";
            return r;
        }

        int fx = floor.getX(), fy = floor.getY(), fz = floor.getZ();
        BlockState floorState = level.getBlockState(floor);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        // ---- 1. ceiling (required)
        int ceilY = findCeiling(level, fx, fz, fy + 3, fy + 2 + ceilSearch);
        if (ceilY == NONE) {
            r.reason = "open sky / no ceiling";
            r.verdict = "no ceiling within " + ceilSearch + " blocks (open sky)";
            return r;
        }
        r.roofed = true;
        r.ceilingHeight = ceilY - fy;
        BlockState ceilState = level.getBlockState(new BlockPos(fx, ceilY, fz));

        // ---- 2. walls: 8 rays at head height (at least 3 required)
        double cx = fx + 0.5, cz = fz + 0.5, cy = Spots.feetY(level, floor) + 1.5;
        List<BlockState> walls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            Vec3 from = new Vec3(cx, cy, cz);
            Vec3 to = from.add(Math.cos(a) * radius, 0.0, Math.sin(a) * radius);
            BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, rayOwner));
            if (hit.getType() == HitResult.Type.BLOCK) {
                r.hits++;
                walls.add(level.getBlockState(hit.getBlockPos()));
            }
        }
        int minWalls = ZSConfig.MIN_ENCLOSED_RAYS.get();
        r.enclosed = r.hits >= minWalls;
        if (r.hits < MIN_WALL_HITS) {
            r.reason = "too few walls";
            r.verdict = "only " + r.hits + "/8 directions have a wall within " + radius + " blocks (need at least " + MIN_WALL_HITS + ")";
            return r;
        }
        r.pWalls = r.enclosed ? 2.0 : (r.hits >= Math.max(MIN_WALL_HITS, minWalls - 2) ? 1.0 : 0.5);

        // ---- 3. natural rock/dirt above the ceiling
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fx, fz);
        int endY = Math.min(h, ceilY + 96);
        for (int y = ceilY; y < endY; y++) {
            m.set(fx, y, fz);
            BlockState s = level.getBlockState(m);
            if (s.getCollisionShape(level, m).isEmpty() || s.is(BlockTags.LEAVES)) continue;
            if (BlockClassifier.isNatural(s)) r.naturalCover++;
        }

        // ---- 4. materials of floor, ceiling and walls
        r.samples = 2 + walls.size();
        r.surfaces.add("floor   " + BlockClassifier.describe(floorState));
        r.surfaces.add("ceiling " + BlockClassifier.describe(ceilState));
        if (!BlockClassifier.isNatural(floorState)) r.manMade++;
        if (!BlockClassifier.isNatural(ceilState)) r.manMade++;
        for (BlockState w : walls) {
            r.surfaces.add("wall    " + BlockClassifier.describe(w));
            if (!BlockClassifier.isNatural(w)) r.manMade++;
        }
        r.ratio = (double) r.manMade / r.samples;
        double minRatio = ZSConfig.MIN_MAN_MADE_RATIO.get();
        r.materialOk = r.ratio >= minRatio;
        r.pMaterial = r.materialOk ? 2.0 : (r.ratio >= minRatio * 0.6 ? 1.0 : 0.0);

        // ---- 5. shape: flat floor + flat ceiling over the open cells around the spot (5x5)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = fx + dx, z = fz + dz;
                if (!openCell(level, m, x, fy, z)) continue;
                r.cells++;
                m.set(x, fy, z);
                BlockState fl = level.getBlockState(m);
                if (!fl.getCollisionShape(level, m).isEmpty() && fl.getFluidState().isEmpty()) r.flatFloor++;
                if (findCeiling(level, x, z, fy + 3, fy + 2 + ceilSearch) == ceilY) r.flatCeiling++;
            }
        }
        r.shapeOk = ZSConfig.DETECT_BY_SHAPE.get() && r.cells >= 4
                && r.flatFloor >= r.cells * 0.85 && r.flatCeiling >= r.cells * 0.8;
        r.pShape = r.shapeOk ? 2.0 : 0.0;

        // ---- 6. how much of the floor area around the spot is roofed (9x9)
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int x = fx + dx, z = fz + dz;
                if (!openCell(level, m, x, fy, z)) continue;
                r.coverCells++;
                if (findCeiling(level, x, z, fy + 3, fy + 2 + ceilSearch) != NONE) r.coverRoofed++;
            }
        }
        r.pRoofed = (r.coverCells >= 6 && r.coverRoofed >= r.coverCells * 0.85) ? 1.0 : 0.0;

        // ---- 7. man-made volume within 5 blocks (11x11x4)
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    m.set(fx + dx, fy + dy, fz + dz);
                    BlockState s = level.getBlockState(m);
                    if (s.isAir() || !s.getFluidState().isEmpty()) continue;
                    if (!BlockClassifier.isNatural(s)) r.manBlocks++;
                }
            }
        }
        r.pVolume = r.manBlocks >= 40 ? 1.0 : (r.manBlocks >= 20 ? 0.5 : 0.0);

        r.score = r.pWalls + r.pMaterial + r.pShape + r.pVolume + r.pRoofed;

        // ---- verdict
        boolean clearlyBuilt = r.ratio >= 0.75;
        r.underground = r.naturalCover > ZSConfig.MAX_NATURAL_COVER.get() && !clearlyBuilt;
        double need = ZSConfig.INDOOR_MIN_SCORE.get();

        if (r.underground) {
            r.reason = "underground";
            r.verdict = "underground: " + r.naturalCover + " natural blocks above it (limit "
                    + ZSConfig.MAX_NATURAL_COVER.get() + ") and it is not clearly built";
        } else if (r.score >= need) {
            r.building = true;
            r.reason = "building";
            r.verdict = "score " + fmt(r.score) + " >= " + fmt(need);
        } else {
            r.reason = "score too low";
            r.verdict = "score " + fmt(r.score) + " is below " + fmt(need) + " (looks natural or too bare)";
        }
        return r;
    }

    public static String fmt(double d) {
        return String.format("%.1f", d);
    }

    /** feet and head blocks at this column are free: an open cell of the room at the floor's height. */
    private static boolean openCell(ServerLevel level, BlockPos.MutableBlockPos m, int x, int fy, int z) {
        m.set(x, fy + 1, z);
        if (!level.getBlockState(m).getCollisionShape(level, m).isEmpty()) return false;
        m.set(x, fy + 2, z);
        return level.getBlockState(m).getCollisionShape(level, m).isEmpty();
    }

    /** First block above that you would bump your head on (leaves are ignored). */
    static int findCeiling(ServerLevel level, int x, int z, int fromY, int toY) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int max = Math.min(toY, level.getMaxBuildHeight() - 1);
        for (int y = fromY; y <= max; y++) {
            m.set(x, y, z);
            BlockState s = level.getBlockState(m);
            if (s.is(BlockTags.LEAVES)) continue;
            if (!s.getCollisionShape(level, m).isEmpty()) return y;
        }
        return NONE;
    }
}
