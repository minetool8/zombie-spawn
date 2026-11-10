package com.zombiespawner;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;

/**
 * Spawns zombies out of the player's sight, from per-region budgets saved in the world,
 * plus rare "frontier wanderers" from land the player has never been near.
 */
public class HiddenSpawner {
    public static final String TAG = "zs_spawned";
    public static final String REGION_TAG = "zs_region";
    public static final String WANDER_TAG = "zs_wander";
    public static final String OPEN_TAG = "zs_open";

    /** Most building analyses done for one spawn attempt (keeps lag away). */
    private static final int MAX_ANALYSES_PER_ATTEMPT = 8;

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

    /** A zombie of ours was killed: that is what clears a region for good. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level)) return;
        CompoundTag pd = e.getPersistentData();
        if (!pd.getBoolean(TAG) || pd.getBoolean(WANDER_TAG) || !pd.contains(REGION_TAG)) return;
        RegionData.get(level).killed(pd.getLong(REGION_TAG));
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
        int vr = ZSConfig.VERTICAL_RANGE.get();
        int top = Math.min(level.getMaxBuildHeight() - 4, py + vr);
        int bottom = Math.max(level.getMinBuildHeight() + 1, py - vr);

        for (int i = 0; i < ZSConfig.ATTEMPTS.get(); i++) {
            double angle = rnd.nextDouble() * Math.PI * 2.0;
            double dist = frontier
                    ? Math.max(minD, maxD - rnd.nextDouble() * 8.0)      // far edge of range
                    : minD + rnd.nextDouble() * (maxD - minD);
            int x0 = Mth.floor(player.getX() + Math.cos(angle) * dist);
            int z0 = Mth.floor(player.getZ() + Math.sin(angle) * dist);
            long key = RegionData.key(x0, z0, size);

            // what is this attempt looking for: a building (usual) or the open (rare)
            boolean wantOpen = frontier || rnd.nextDouble() < ZSConfig.OPEN_CHANCE.get();
            if (frontier) {
                // only truly untouched land: never near anywhere the player has been or has cleared
                if (data.nearVisited(key) || data.nearCleared(key)) continue;
            } else if (wantOpen) {
                if (data.isCleared(key) || data.getOpen(level, key) <= 0) continue;
            } else {
                if (data.isCleared(key) || data.getIndoor(level, key) <= 0) continue;
            }

            BlockPos floor = wantOpen
                    ? pickOpenSpot(level, player, x0, z0, bottom, top, rnd, minD)
                    : pickIndoorSpot(level, player, x0, z0, size, bottom, top, rnd, minD, maxD);
            if (floor == null) continue;

            Vec3 feet = new Vec3(floor.getX() + 0.5, Spots.feetY(level, floor), floor.getZ() + 0.5);
            if (spawnAt(level, type, feet, rnd, key, frontier, wantOpen)) {
                if (!frontier) data.spawned(key, wantOpen);
                return; // one per player per cycle
            }
        }
    }

    /** One random column in the open air. */
    private BlockPos pickOpenSpot(ServerLevel level, ServerPlayer player, int x, int z,
                                  int bottom, int top, RandomSource rnd, int minD) {
        if (!columnUsable(level, player, x, z)) return null;
        List<BlockPos> roofed = new ArrayList<>();
        List<BlockPos> open = new ArrayList<>();
        scanColumn(level, x, z, bottom, top, roofed, open);
        for (int t = 0; t < 3 && !open.isEmpty(); t++) {
            BlockPos c = open.remove(rnd.nextInt(open.size()));
            if (acceptable(level, player, c, minD)) return c;
        }
        return null;
    }

    /**
     * Searches the WHOLE region the attempt landed in (not a single column) for the inside of a building.
     * Buildings are small, so probing one random column almost never finds one.
     */
    private BlockPos pickIndoorSpot(ServerLevel level, ServerPlayer player, int x0, int z0, int size,
                                    int bottom, int top, RandomSource rnd, int minD, int maxD) {
        int rx0 = Math.floorDiv(x0, size) * size;
        int rz0 = Math.floorDiv(z0, size) * size;
        double minSq = (double) minD * minD;
        double maxReach = maxD + size / 2.0;
        double maxSq = maxReach * maxReach;
        boolean anyRoof = ZSConfig.ALLOW_CAVES.get();
        int analyses = 0;

        for (int s = 0; s < ZSConfig.COLUMN_SAMPLES.get(); s++) {
            int x = rx0 + rnd.nextInt(size);
            int z = rz0 + rnd.nextInt(size);
            double dx = x + 0.5 - player.getX();
            double dz = z + 0.5 - player.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 < minSq || d2 > maxSq) continue;
            if (!columnUsable(level, player, x, z)) continue;

            List<BlockPos> roofed = new ArrayList<>();
            List<BlockPos> open = new ArrayList<>();
            scanColumn(level, x, z, bottom, top, roofed, open);

            for (int t = 0; t < 2 && !roofed.isEmpty(); t++) {
                BlockPos c = roofed.remove(rnd.nextInt(roofed.size()));
                if (!acceptable(level, player, c, minD)) continue;
                if (anyRoof) return c;
                if (++analyses > MAX_ANALYSES_PER_ATTEMPT) return null;
                if (BuildingDetector.analyse(level, c, player).building) return c;
            }
        }
        return null;
    }

    private boolean columnUsable(ServerLevel level, ServerPlayer player, int x, int z) {
        if (!level.hasChunkAt(new BlockPos(x, player.getBlockY(), z))) return false;
        return level.getWorldBorder().isWithinBounds(x, z);
    }

    /** Splits the standing spots of a column into "under a roof" and "open sky". */
    private void scanColumn(ServerLevel level, int x, int z, int bottom, int top,
                            List<BlockPos> roofed, List<BlockPos> open) {
        // trees don't count as a roof (leaves ignored); a building's own roof-top counts as open
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int y = top; y >= bottom; y--) {
            BlockPos floor = new BlockPos(x, y, z);
            if (!Spots.isStandable(level, floor)) continue;
            if (h >= y + 4) roofed.add(floor); else open.add(floor);
        }
    }

    /** Far enough from the player and out of their sight. */
    private boolean acceptable(ServerLevel level, ServerPlayer player, BlockPos floor, int minD) {
        Vec3 feet = new Vec3(floor.getX() + 0.5, Spots.feetY(level, floor), floor.getZ() + 0.5);
        if (feet.distanceToSqr(player.position()) < (double) minD * minD) return false;
        return isHidden(level, player, feet);
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

        boolean noBabies = !ZSConfig.ALLOW_BABIES.get();
        if (entity instanceof Mob mob) {
            if (ZSConfig.OWN_DESPAWN.get()) mob.setPersistenceRequired();
            // handing over ready-made group data (adult, no chicken jockey) stops vanilla from rolling a baby
            SpawnGroupData group = (noBabies && entity instanceof Zombie)
                    ? new Zombie.ZombieGroupData(false, false) : null;
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                    MobSpawnType.EVENT, group, null);
        }
        if (noBabies && entity instanceof Zombie z && z.isBaby()) z.setBaby(false);

        level.addFreshEntityWithPassengers(entity);
        return true;
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
