package com.zombiespawner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;

/**
 * Spawns zombies out of the player's sight, from per-region budgets saved in the world,
 * plus rare "frontier wanderers" from regions the player has never visited.
 */
public class HiddenSpawner {
    public static final String TAG = "zs_spawned";
    public static final String REGION_TAG = "zs_region";
    public static final String WANDER_TAG = "zs_wander";
    public static final String OPEN_TAG = "zs_open";

    private int tickCounter = 0;
    private boolean warnedBadId = false;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ZSConfig.ENABLED.get()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        tickCounter++;
        boolean spawnCycle = tickCounter % ZSConfig.INTERVAL_TICKS.get() == 0;
        boolean cleanupCycle = tickCounter % 100 == 0;
        if (!spawnCycle && !cleanupCycle) return;

        List<? extends String> dims = ZSConfig.DIMENSIONS.get();
        EntityType<?> type = resolveType();

        for (ServerLevel level : server.getAllLevels()) {
            if (!dims.contains(level.dimension().location().toString())) continue;
            RegionData data = RegionData.get(level);

            if (spawnCycle) {
                for (ServerPlayer player : new ArrayList<>(level.players())) {
                    if (player.isSpectator() || !player.isAlive()) continue;
                    trySpawn(level, player, type, data);
                }
            }
            if (cleanupCycle && ZSConfig.OWN_DESPAWN.get()) {
                cleanup(level, data);
            }
        }
    }

    // ------------------------------------------------------------------ spawning

    private void trySpawn(ServerLevel level, ServerPlayer player, EntityType<?> type, RegionData data) {
        int size = ZSConfig.REGION_SIZE.get();
        data.markVisited(RegionData.key(player.getBlockX(), player.getBlockZ(), size));

        int minD = ZSConfig.MIN_DIST.get();
        int maxD = Math.max(ZSConfig.MAX_DIST.get(), minD + 1);

        // cap on zombies awake around this player
        AABB box = player.getBoundingBox().inflate(maxD + 16);
        int nearby = level.getEntitiesOfClass(Mob.class, box,
                m -> m.isAlive() && m.getType() == type).size();
        if (nearby >= ZSConfig.MAX_PER_PLAYER.get()) return;

        RandomSource rnd = level.random;
        boolean frontier = ZSConfig.FRONTIER_ENABLED.get() && rnd.nextDouble() < ZSConfig.FRONTIER_CHANCE.get();

        int py = player.getBlockY();
        int top = Math.min(level.getMaxBuildHeight() - 3, py + 24);
        int bottom = Math.max(level.getMinBuildHeight() + 1, py - 24);

        for (int i = 0; i < ZSConfig.ATTEMPTS.get(); i++) {
            double angle = rnd.nextDouble() * Math.PI * 2.0;
            double dist = frontier
                    ? Math.max(minD, maxD - rnd.nextDouble() * 8.0)      // far edge of range
                    : minD + rnd.nextDouble() * (maxD - minD);
            int x = Mth.floor(player.getX() + Math.cos(angle) * dist);
            int z = Mth.floor(player.getZ() + Math.sin(angle) * dist);

            long key = RegionData.key(x, z, size);

            // decide what this attempt is looking for: a building (usual) or the open (rare)
            boolean wantOpen = frontier || rnd.nextDouble() < ZSConfig.OPEN_CHANCE.get();
            if (frontier) {
                if (data.isVisited(key)) continue;                    // never in explored/cleared land
            } else if (wantOpen) {
                if (data.getOpen(level, key) <= 0) continue;
            } else {
                if (data.getIndoor(level, key) <= 0) continue;
            }

            if (!level.hasChunkAt(new BlockPos(x, py, z))) continue;
            if (!level.getWorldBorder().isWithinBounds(x, z)) continue;

            // standing spots in this column, split into "under a roof" and "open sky"
            List<BlockPos> roofed = new ArrayList<>();
            List<BlockPos> openSpots = new ArrayList<>();
            for (int y = top; y >= bottom; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!isValidSpot(level, pos)) continue;
                if (isRoofed(level, pos)) roofed.add(pos); else openSpots.add(pos);
            }

            BlockPos chosen = null;
            if (wantOpen) {
                if (!openSpots.isEmpty()) chosen = openSpots.get(rnd.nextInt(openSpots.size()));
            } else if (!roofed.isEmpty()) {
                for (int t = 0; t < 3 && chosen == null; t++) {
                    BlockPos c = roofed.get(rnd.nextInt(roofed.size()));
                    if (ZSConfig.ALLOW_CAVES.get() || looksLikeBuilding(level, c)) chosen = c;
                }
            }
            if (chosen == null) continue;

            Vec3 feet = new Vec3(chosen.getX() + 0.5, chosen.getY(), chosen.getZ() + 0.5);
            if (feet.distanceToSqr(player.position()) < (double) minD * minD) continue;
            if (!isHidden(level, player, feet)) continue;

            if (spawnAt(level, type, feet, rnd, key, frontier, wantOpen)) {
                if (!frontier) data.consume(key, wantOpen);
                return; // one per player per cycle
            }
        }
    }

    private boolean spawnAt(ServerLevel level, EntityType<?> type, Vec3 pos, RandomSource rnd,
                            long regionKey, boolean wanderer, boolean isOpen) {
        Entity entity = type.create(level);
        if (entity == null) return false;

        entity.moveTo(pos.x, pos.y, pos.z, rnd.nextFloat() * 360.0F, 0.0F);
        if (!level.noCollision(entity)) {
            entity.discard();
            return false;
        }

        CompoundTag pd = entity.getPersistentData();
        pd.putBoolean(TAG, true);
        if (wanderer) {
            pd.putBoolean(WANDER_TAG, true);
        } else {
            pd.putLong(REGION_TAG, regionKey);
            pd.putBoolean(OPEN_TAG, isOpen);
        }

        if (entity instanceof Mob mob) {
            if (ZSConfig.OWN_DESPAWN.get()) mob.setPersistenceRequired();
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                    MobSpawnType.EVENT, null, null);
        }
        level.addFreshEntityWithPassengers(entity);
        return true;
    }

    // ------------------------------------------------------------------ where am I?

    /** Solid roof above (trees don't count, leaves are ignored). A building's own roof-top counts as open. */
    private boolean isRoofed(ServerLevel level, BlockPos feet) {
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
        return h > feet.getY() + 2;
    }

    /**
     * Roofed + not too deep below the surface + surrounded by man-made blocks.
     * Natural caves fail this (too deep or only stone/dirt/ores around).
     */
    private boolean looksLikeBuilding(ServerLevel level, BlockPos feet) {
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
        if (h - feet.getY() > ZSConfig.BUILDING_MAX_DEPTH.get()) return false;

        if (!level.hasChunksAt(feet.offset(-3, 0, -3), feet.offset(3, 0, 3))) return false;

        int needed = ZSConfig.BUILDING_MIN_BLOCKS.get();
        int found = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    m.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (isManMade(level.getBlockState(m)) && ++found >= needed) return true;
                }
            }
        }
        return false;
    }

    private boolean isManMade(BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND)
                || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.ICE)
                || s.is(BlockTags.SNOW) || s.is(BlockTags.FLOWERS)
                || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES)
                || s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES)
                || s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.EMERALD_ORES)) return false;
        Block b = s.getBlock();
        return !(b == Blocks.GRAVEL || b == Blocks.CLAY || b == Blocks.MUD || b == Blocks.MOSS_BLOCK
                || b == Blocks.MOSS_CARPET || b == Blocks.DRIPSTONE_BLOCK || b == Blocks.POINTED_DRIPSTONE
                || b == Blocks.CALCITE || b == Blocks.GLOW_LICHEN || b == Blocks.VINE
                || b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT || b == Blocks.HANGING_ROOTS
                || b == Blocks.SMALL_DRIPLEAF || b == Blocks.BIG_DRIPLEAF || b == Blocks.BIG_DRIPLEAF_STEM
                || b == Blocks.SPORE_BLOSSOM || b == Blocks.AMETHYST_BLOCK || b == Blocks.BUDDING_AMETHYST
                || b == Blocks.AMETHYST_CLUSTER || b == Blocks.GRASS || b == Blocks.TALL_GRASS
                || b == Blocks.FERN || b == Blocks.LARGE_FERN || b == Blocks.SANDSTONE
                || b == Blocks.RED_SANDSTONE || b == Blocks.TERRACOTTA || b == Blocks.COBWEB);
    }

    private boolean isValidSpot(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        BlockState belowState = level.getBlockState(below);
        if (!belowState.isFaceSturdy(level, below, Direction.UP)) return false;
        if (!level.getFluidState(below).isEmpty()) return false;
        return isPassable(level, feet) && isPassable(level, feet.above());
    }

    private boolean isPassable(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty();
    }

    // ------------------------------------------------------------------ visibility

    private boolean isHidden(ServerLevel level, ServerPlayer player, Vec3 feet) {
        Vec3 eye = player.getEyePosition();
        Vec3 head = feet.add(0, 1.7, 0);
        Vec3 body = feet.add(0, 0.9, 0);

        boolean lineOfSight = hasLineOfSight(level, player, eye, head)
                || hasLineOfSight(level, player, eye, body);
        if (!lineOfSight) return true;
        if (ZSConfig.STRICT_LOS.get()) return false;

        Vec3 toTarget = body.subtract(eye).normalize();
        double dot = player.getLookAngle().dot(toTarget);
        double halfAngle = Math.toRadians(ZSConfig.VIEW_CONE.get() / 2.0);
        return dot < Math.cos(halfAngle);
    }

    private boolean hasLineOfSight(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------ cleanup

    /**
     * Removes our zombies when no player is near. A zombie that was removed only because
     * you walked away goes back into its region's budget (it's still "alive" in the world).
     * Zombies that were killed never come back.
     */
    private void cleanup(ServerLevel level, RegionData data) {
        double range = ZSConfig.DESPAWN_DIST.get();
        List<Entity> toRemove = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof Mob mob)) continue;
            if (!mob.getPersistentData().getBoolean(TAG)) continue;
            mob.setPersistenceRequired(); // keep vanilla from despawning it
            if (mob.hasCustomName()) continue;
            if (level.getNearestPlayer(mob, range) == null) toRemove.add(mob);
        }
        for (Entity e : toRemove) {
            CompoundTag pd = e.getPersistentData();
            if (pd.contains(REGION_TAG) && !pd.getBoolean(WANDER_TAG)) {
                data.giveBack(pd.getLong(REGION_TAG), pd.getBoolean(OPEN_TAG));
            }
            e.discard();
        }
    }

    // ------------------------------------------------------------------ misc

    private EntityType<?> resolveType() {
        ResourceLocation id = ResourceLocation.tryParse(ZSConfig.ENTITY_ID.get());
        if (id != null && ForgeRegistries.ENTITY_TYPES.containsKey(id)) {
            return ForgeRegistries.ENTITY_TYPES.getValue(id);
        }
        if (!warnedBadId) {
            warnedBadId = true;
            System.err.println("[zombiespawner] Unknown entityId '" + ZSConfig.ENTITY_ID.get()
                    + "', falling back to minecraft:zombie");
        }
        return EntityType.ZOMBIE;
    }
}
