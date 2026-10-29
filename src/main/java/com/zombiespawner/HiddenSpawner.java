package com.zombiespawner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.ClipContext;
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

/** Spawns zombies anywhere (no light/time checks) but only where the player can't see them. */
public class HiddenSpawner {
    public static final String TAG = "zs_spawned";

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

            if (spawnCycle) {
                for (ServerPlayer player : new ArrayList<>(level.players())) {
                    if (player.isSpectator() || !player.isAlive()) continue;
                    trySpawn(level, player, type);
                }
            }
            if (cleanupCycle && ZSConfig.OWN_DESPAWN.get()) {
                cleanup(level);
            }
        }
    }

    // ------------------------------------------------------------------ spawning

    private void trySpawn(ServerLevel level, ServerPlayer player, EntityType<?> type) {
        int minD = ZSConfig.MIN_DIST.get();
        int maxD = Math.max(ZSConfig.MAX_DIST.get(), minD + 1);

        // cap
        AABB box = player.getBoundingBox().inflate(maxD + 16);
        int nearby = level.getEntitiesOfClass(Mob.class, box,
                m -> m.isAlive() && m.getType() == type).size();
        if (nearby >= ZSConfig.MAX_PER_PLAYER.get()) return;

        RandomSource rnd = level.random;
        int py = player.getBlockY();
        int top = Math.min(level.getMaxBuildHeight() - 3, py + 24);
        int bottom = Math.max(level.getMinBuildHeight() + 1, py - 24);

        for (int i = 0; i < ZSConfig.ATTEMPTS.get(); i++) {
            double angle = rnd.nextDouble() * Math.PI * 2.0;
            double dist = minD + rnd.nextDouble() * (maxD - minD);
            int x = Mth.floor(player.getX() + Math.cos(angle) * dist);
            int z = Mth.floor(player.getZ() + Math.sin(angle) * dist);

            if (!level.hasChunkAt(new BlockPos(x, py, z))) continue;
            if (!level.getWorldBorder().isWithinBounds(x, z)) continue;

            // every valid standing spot in this column (surface, caves, interiors)
            List<BlockPos> spots = new ArrayList<>();
            for (int y = top; y >= bottom; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (isValidSpot(level, pos)) spots.add(pos);
            }
            if (spots.isEmpty()) continue;

            BlockPos chosen = spots.get(rnd.nextInt(spots.size()));
            Vec3 feet = new Vec3(chosen.getX() + 0.5, chosen.getY(), chosen.getZ() + 0.5);

            // never closer than the minimum distance in 3D either
            if (feet.distanceToSqr(player.position()) < (double) minD * minD) continue;
            if (!isHidden(level, player, feet)) continue;

            if (spawnAt(level, type, feet, rnd)) return; // one per player per cycle
        }
    }

    private boolean spawnAt(ServerLevel level, EntityType<?> type, Vec3 pos, RandomSource rnd) {
        Entity entity = type.create(level);
        if (entity == null) return false;

        entity.moveTo(pos.x, pos.y, pos.z, rnd.nextFloat() * 360.0F, 0.0F);
        if (!level.noCollision(entity)) {
            entity.discard();
            return false;
        }

        entity.getPersistentData().putBoolean(TAG, true);

        if (entity instanceof Mob mob) {
            if (ZSConfig.OWN_DESPAWN.get()) mob.setPersistenceRequired();
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                    MobSpawnType.EVENT, null, null);
        }
        level.addFreshEntityWithPassengers(entity);
        return true;
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
        if (!lineOfSight) return true;                  // blocked by terrain/walls: hidden
        if (ZSConfig.STRICT_LOS.get()) return false;    // strict: any clear LOS rejects

        // non-strict: clear LOS is fine as long as it's outside the player's view cone
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

    private void cleanup(ServerLevel level) {
        double range = ZSConfig.DESPAWN_DIST.get();
        List<Entity> toRemove = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof Mob mob)) continue;
            if (!mob.getPersistentData().getBoolean(TAG)) continue;
            mob.setPersistenceRequired(); // re-assert so vanilla never despawns it
            if (mob.hasCustomName()) continue;
            if (level.getNearestPlayer(mob, range) == null) toRemove.add(mob);
        }
        for (Entity e : toRemove) e.discard();
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
