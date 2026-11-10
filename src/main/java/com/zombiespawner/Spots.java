package com.zombiespawner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where can a zombie stand? Works for any block shape (carpets, slabs, paths, modded floors...),
 * not only full blocks.
 *
 * A spot is identified by its FLOOR block position. The zombie stands on top of it.
 */
public final class Spots {
    private Spots() {}

    /** Floor block is solid enough to stand on and there is room for a 2-block-tall zombie above it. */
    public static boolean isStandable(ServerLevel level, BlockPos floor) {
        BlockState fs = level.getBlockState(floor);
        if (!fs.getFluidState().isEmpty()) return false;
        VoxelShape shape = fs.getCollisionShape(level, floor);
        if (shape.isEmpty()) return false;
        double top = shape.max(Direction.Axis.Y);
        if (top > 1.0 + 1.0E-6 || top < 0.05) return false;      // fences, walls, nothing
        if (isHazardFloor(fs)) return false;
        return isFree(level, floor.above()) && isFree(level, floor.above(2));
    }

    /** Y coordinate of the floor's walking surface. */
    public static double feetY(ServerLevel level, BlockPos floor) {
        BlockState fs = level.getBlockState(floor);
        return floor.getY() + fs.getCollisionShape(level, floor).max(Direction.Axis.Y);
    }

    /** A block a zombie can stand inside: no collision, no fluid, nothing harmful. */
    public static boolean isFree(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!s.getCollisionShape(level, pos).isEmpty()) return false;
        if (!s.getFluidState().isEmpty()) return false;
        Block b = s.getBlock();
        return !(s.is(BlockTags.FIRE) || s.is(BlockTags.CAMPFIRES) || b == Blocks.SWEET_BERRY_BUSH
                || b == Blocks.WITHER_ROSE || b == Blocks.COBWEB || b == Blocks.POWDER_SNOW);
    }

    private static boolean isHazardFloor(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.MAGMA_BLOCK || b == Blocks.CACTUS || s.is(BlockTags.CAMPFIRES);
    }
}
