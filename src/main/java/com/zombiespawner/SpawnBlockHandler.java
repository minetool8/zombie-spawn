package com.zombiespawner;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.entity.living.ZombieEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** Stops vanilla from spawning hostile mobs, stops baby zombies, and stops vanilla from despawning ours. */
public class SpawnBlockHandler {

    /** Is this mob type on the allowedMobs list (or the custom zombie this mod spawns)? */
    static boolean isAllowed(EntityType<?> type) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
        if (id == null) return false;
        String name = id.toString();
        if (name.equals(ZSConfig.ENTITY_ID.get())) return true;
        for (String allowed : ZSConfig.ALLOWED_MOBS.get()) {
            if (name.equals(allowed.trim())) return true;
        }
        return false;
    }

    @SubscribeEvent
    public void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        MobSpawnType reason = event.getSpawnType();
        if (reason != MobSpawnType.NATURAL
                && reason != MobSpawnType.CHUNK_GENERATION
                && reason != MobSpawnType.STRUCTURE
                && reason != MobSpawnType.PATROL) return;

        Mob mob = event.getEntity();
        boolean deny = false;
        if (ZSConfig.ONLY_ALLOWED.get() && !isAllowed(mob.getType())) {
            deny = true;                                              // not on the whitelist
        } else if (ZSConfig.BLOCK_VANILLA_HOSTILES.get() && mob instanceof Enemy) {
            deny = true;                                              // zombies come only from this mod
        }
        if (deny) event.setResult(Event.Result.DENY);
    }

    /** A hit zombie normally calls in more zombies. Those belong to no region, so they would "respawn" cleared areas. */
    @SubscribeEvent
    public void onSummonAid(ZombieEvent.SummonAidEvent event) {
        if (ZSConfig.BLOCK_REINFORCEMENTS.get()) {
            event.setResult(Event.Result.DENY);
        }
    }

    /** Zombies spawned by this mod are only ever removed by our own distance cleanup. */
    @SubscribeEvent
    public void onAllowDespawn(MobSpawnEvent.AllowDespawn event) {
        if (!ZSConfig.OWN_DESPAWN.get()) return;
        if (event.getEntity().getPersistentData().getBoolean(HiddenSpawner.TAG)) {
            event.setResult(Event.Result.DENY);
        }
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();

        if (ZSConfig.BLOCK_PHANTOMS.get() && entity instanceof Phantom && !event.loadedFromDisk()) {
            event.setCanceled(true);
            return;
        }

        // whitelist: nothing else may be newly added to the world (existing mobs loaded from disk are untouched)
        if (ZSConfig.ONLY_ALLOWED.get() && entity instanceof Mob mob && !event.loadedFromDisk()
                && !mob.getPersistentData().getBoolean(HiddenSpawner.TAG) && !isAllowed(mob.getType())) {
            event.setCanceled(true);
            return;
        }

        // no baby zombies: catches anything that slipped through (other mods, old saves of this mod)
        if (!ZSConfig.ALLOW_BABIES.get() && entity instanceof Zombie zombie && zombie.isBaby()) {
            boolean ours = zombie.getPersistentData().getBoolean(HiddenSpawner.TAG);
            if (!event.loadedFromDisk() || ours) {
                zombie.setBaby(false);
            }
        }
    }

    /** Debug aid: set logRemovals=true in the config to see why a zombie disappeared. */
    @SubscribeEvent
    public void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!ZSConfig.LOG_REMOVALS.get()) return;
        Entity e = event.getEntity();
        if (!e.getPersistentData().getBoolean(HiddenSpawner.TAG)) return;
        System.out.println("[zombiespawner] zombie left level: reason=" + e.getRemovalReason()
                + " pos=" + e.blockPosition() + " type=" + e.getType());
    }
}
