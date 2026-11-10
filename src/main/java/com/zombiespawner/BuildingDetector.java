package com.zombiespawner;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a standing spot is INSIDE A BUILDING, without needing to know the blocks it is made of.
 *
 * It looks at the room instead of the materials:
 *  1. there is a ceiling above (leaves don't count)
 *  2. walls close in around the spot (8 rays at head height)
 *  3. it is not buried deep under natural rock (that would be a cave, mine or stronghold)
 *  4. EITHER most of the floor/ceiling/wall blocks are man-made (every non-natural block, modded or not)
 *     OR the room is regular: flat floor and flat ceiling (caves are never like that)
 *
 * Hand-built rooms pass on shape even when they are made of plain stone, dirt or logs.
 */
public final class BuildingDetector {
    private BuildingDetector() {}

    public static final class Result {
        public boolean building;
        public boolean roofed;
        public boolean enclosed;
        public boolean underground;
        public boolean materialOk;
        public boolean shapeOk;
        public int ceilingHeight;       // blocks from floor to ceiling block, 0 = none found
        public int hits;                // rays (of 8) that hit a wall
        public int naturalCover;        // natural blocks above the ceiling
        public int manMade;
        public int samples;
        public double ratio;
        public int cells, flatFloor, flatCeiling;
        public String verdict = "";
        public final List<String> surfaces = new ArrayList<>();
    }

    private static final int NONE = Integer.MIN_VALUE;

    public static Result analyse(ServerLevel level, BlockPos floor, Entity rayOwner) {
        Result r = new Result();
        int radius = ZSConfig.ENCLOSURE_RADIUS.get();
        int ceilSearch = ZSConfig.CEILING_HEIGHT.get();
        int reach = radius + 2;

        // never force-load chunks just to look at a room
        if (!level.hasChunksAt(floor.offset(-reach, 0, -reach), floor.offset(reach, 0, reach))) {
            r.verdict = "the chunks around this spot are not loaded";
            return r;
        }

        int fx = floor.getX(), fy = floor.getY(), fz = floor.getZ();
        BlockState floorState = level.getBlockState(floor);

        // 1. ceiling
        int ceilY = findCeiling(level, fx, fz, fy + 3, fy + 2 + ceilSearch);
        if (ceilY == NONE) {
            r.verdict = "no ceiling within " + ceilSearch + " blocks (open sky)";
            return r;
        }
        r.roofed = true;
        r.ceilingHeight = ceilY - fy;
        BlockState ceilState = level.getBlockState(new BlockPos(fx, ceilY, fz));

        // 2. walls: 8 rays at head height
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
        r.enclosed = r.hits >= ZSConfig.MIN_ENCLOSED_RAYS.get();

        // 3. natural rock/dirt above the ceiling
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fx, fz);
        int endY = Math.min(h, ceilY + 96);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = ceilY; y < endY; y++) {
            m.set(fx, y, fz);
            BlockState s = level.getBlockState(m);
            if (s.getCollisionShape(level, m).isEmpty() || s.is(BlockTags.LEAVES)) continue;
            if (BlockClassifier.isNatural(s)) r.naturalCover++;
        }

        // 4a. materials of the surfaces around the spot
        r.samples = 2 + walls.size();
        r.surfaces.add("floor   " + BlockClassifier.describe(floorState));
        r.surfaces.add("ceiling " + BlockClassifier.describe(ceilState));
        if (!BlockClassifier.isNatural(floorState)) r.manMade++;
        if (!BlockClassifier.isNatural(ceilState)) r.manMade++;
        for (BlockState w : walls) {
            r.surfaces.add("wall    " + BlockClassifier.describe(w));
            if (!BlockClassifier.isNatural(w)) r.manMade++;
        }
        r.ratio = r.samples == 0 ? 0.0 : (double) r.manMade / r.samples;
        r.materialOk = r.ratio >= ZSConfig.MIN_MAN_MADE_RATIO.get();

        // 4b. shape: flat floor + flat ceiling over the open cells around the spot (5x5)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = fx + dx, z = fz + dz;
                m.set(x, fy + 1, z);
                if (!level.getBlockState(m).getCollisionShape(level, m).isEmpty()) continue;
                m.set(x, fy + 2, z);
                if (!level.getBlockState(m).getCollisionShape(level, m).isEmpty()) continue;
                r.cells++;
                m.set(x, fy, z);
                BlockState fl = level.getBlockState(m);
                if (!fl.getCollisionShape(level, m).isEmpty() && fl.getFluidState().isEmpty()) r.flatFloor++;
                if (findCeiling(level, x, z, fy + 3, fy + 2 + ceilSearch) == ceilY) r.flatCeiling++;
            }
        }
        r.shapeOk = ZSConfig.DETECT_BY_SHAPE.get() && r.cells >= 4
                && r.flatFloor >= r.cells * 0.85 && r.flatCeiling >= r.cells * 0.8;

        // verdict
        boolean clearlyBuilt = r.ratio >= 0.75;
        r.underground = r.naturalCover > ZSConfig.MAX_NATURAL_COVER.get() && !clearlyBuilt;

        if (!r.enclosed) {
            r.verdict = "not enclosed: only " + r.hits + "/8 directions have a wall within " + radius
                    + " blocks (need " + ZSConfig.MIN_ENCLOSED_RAYS.get() + ")";
        } else if (r.underground) {
            r.verdict = "underground: " + r.naturalCover + " natural blocks above it (limit "
                    + ZSConfig.MAX_NATURAL_COVER.get() + ") and the walls are not clearly man-made";
        } else if (r.materialOk || r.shapeOk) {
            r.building = true;
            r.verdict = r.materialOk ? "man-made materials" : "regular room shape (flat floor and ceiling)";
        } else {
            r.verdict = "looks natural: only " + r.manMade + "/" + r.samples + " man-made surfaces and the room shape is irregular";
        }
        return r;
    }

    /** First block above that you would bump your head on (leaves are ignored). */
    private static int findCeiling(ServerLevel level, int x, int z, int fromY, int toY) {
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
