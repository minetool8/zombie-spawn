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

    /** Region scans allowed per player per spawn cycle (keeps lag away). */
    private static final int MAX_SCANS_PER_CYCLE = 1;
    /** A region's room list is refreshed after this many ticks (buildings get built and destroyed). */
    private static final long RESCAN_TICKS = 12000L;

    private int tickCounter = 0;
    private int scansThisCycle = 0;
    private static boolean warnedBadId = false;

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
        int cap = dynamicCap(data, player, size, maxD);
        if (nearby >= cap) return;

        RandomSource rnd = level.random;
        boolean frontier = ZSConfig.FRONTIER_ENABLED.get() && rnd.nextDouble() < ZSConfig.FRONTIER_CHANCE.get();

        int py = player.getBlockY();
        int vr = ZSConfig.VERTICAL_RANGE.get();
        int top = Math.min(level.getMaxBuildHeight() - 4, py + vr);
        int bottom = Math.max(level.getMinBuildHeight() + 1, py - vr);

        int spawned = 0;
        scansThisCycle = 0;
        SpawnStats.cycles++;
        int maxSpawns = ZSConfig.SPAWNS_PER_CYCLE.get();

        for (int i = 0; i < ZSConfig.ATTEMPTS.get(); i++) {
            SpawnStats.attempts++;
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
            } else {
                if (data.isCleared(key)) { SpawnStats.regionCleared++; continue; }
                if (!prepare(level, player, data, key, size, !wantOpen, rnd)) { SpawnStats.regionNotReady++; continue; }
                if ((wantOpen ? data.getOpen(key) : data.getIndoor(key)) <= 0) { SpawnStats.regionNoBudget++; continue; }
            }

            BlockPos floor = wantOpen
                    ? pickOpenSpot(level, player, x0, z0, bottom, top, rnd, minD)
                    : pickIndoorSpot(level, player, data, key, size, vr, rnd, minD, maxD, type);
            if (floor == null) { SpawnStats.regionNoSpot++; continue; }

            Vec3 feet = new Vec3(floor.getX() + 0.5, Spots.feetY(level, floor), floor.getZ() + 0.5);
            if (spawnAt(level, type, feet, rnd, key, frontier, wantOpen)) {
                if (frontier) { SpawnStats.spawnedWanderer++; return; }   // wanderers: one per cycle
                if (wantOpen) SpawnStats.spawnedOpen++; else SpawnStats.spawnedIndoor++;
                data.spawned(key, wantOpen);
                spawned++;
                if (spawned >= maxSpawns || nearby + spawned >= cap) return;
            } else {
                SpawnStats.spawnFailed++;
            }
        }
    }

    /**
     * Makes sure the region has been scanned: gives it its zombie budget the first time, and (for indoor spawns)
     * keeps its list of verified rooms fresh. Returns false when it can't be done now (not loaded / scan limit).
     */
    private boolean prepare(ServerLevel level, ServerPlayer player, RegionData data, long key, int size,
                            boolean needRooms, RandomSource rnd) {
        boolean known = data.isKnown(key);
        RegionScanner.Scan sc = data.getScan(key);
        boolean stale = sc == null || level.getGameTime() - sc.time > RESCAN_TICKS;
        if (known && (!needRooms || !stale)) return true;

        if (scansThisCycle >= MAX_SCANS_PER_CYCLE) return known && sc != null;   // use the old list for now
        scansThisCycle++;
        SpawnStats.scans++;
        RegionScanner.Scan fresh = RegionScanner.scan(level, key, size, rnd, player);
        if (fresh == null) {
            SpawnStats.scansFailedNotLoaded++;
            return known && sc != null;
        }
        data.putScan(key, fresh);
        if (!known) data.setRegion(level, key, fresh.zombies, fresh.density);
        return true;
    }

    /**
     * How many zombies may be around this player. Slides from baseZombiesPerPlayer (countryside) up to
     * maxZombiesDense (big buildings, cities) using the density of the best-scoring regions in range.
     */
    private int dynamicCap(RegionData data, ServerPlayer player, int size, int maxD) {
        int base = ZSConfig.BASE_PER_PLAYER.get();
        int dense = Math.max(ZSConfig.DENSE_PER_PLAYER.get(), base);
        if (dense == base) return base;

        int pr = Math.floorDiv(player.getBlockX(), size), pz = Math.floorDiv(player.getBlockZ(), size);
        int span = maxD / size + 1;
        double b1 = 0, b2 = 0, b3 = 0;   // three highest region scores
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                double d = data.getDensity(RegionData.regionKey(pr + dx, pz + dz));
                if (d < 0) continue;
                if (d > b1) { b3 = b2; b2 = b1; b1 = d; }
                else if (d > b2) { b3 = b2; b2 = d; }
                else if (d > b3) { b3 = d; }
            }
        }
        double score = (b1 + b2 + b3) / 3.0;
        return base + (int) Math.round((dense - base) * score);
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

    /** Picks one of the region's verified rooms: far enough, out of sight, and not on top of another zombie. */
    private BlockPos pickIndoorSpot(ServerLevel level, ServerPlayer player, RegionData data, long key, int size,
                                    int vr, RandomSource rnd, int minD, int maxD, EntityType<?> type) {
        RegionScanner.Scan sc = data.getScan(key);
        if (sc == null || sc.spots.isEmpty()) return null;

        double minSq = (double) minD * minD;
        double maxReach = maxD + size / 2.0;
        double maxSq = maxReach * maxReach;
        int py = player.getBlockY();

        for (int t = 0; t < 16; t++) {
            BlockPos c = sc.spots.get(rnd.nextInt(sc.spots.size()));
            if (Math.abs(c.getY() - py) > vr) { SpawnStats.spotVertical++; continue; }
            double dx = c.getX() + 0.5 - player.getX();
            double dz = c.getZ() + 0.5 - player.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 < minSq || d2 > maxSq) { SpawnStats.spotDistance++; continue; }
            if (!level.hasChunkAt(c)) continue;
            if (!Spots.isStandable(level, c)) { SpawnStats.spotStale++; continue; }
            if (crowded(level, c, type)) { SpawnStats.spotCrowded++; continue; }
            if (!acceptable(level, player, c, minD)) { SpawnStats.spotHidden++; continue; }
            return c;
        }
        return null;
    }

    private boolean crowded(ServerLevel level, BlockPos c, EntityType<?> type) {
        return !level.getEntitiesOfClass(Mob.class, new AABB(c).inflate(3.0),
                m -> m.isAlive() && m.getType() == type).isEmpty();
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

    static boolean spawnAt(ServerLevel level, EntityType<?> type, Vec3 pos, RandomSource rnd,
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

    static EntityType<?> resolveType() {
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
