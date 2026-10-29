package com.zombiespawner;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Stops vanilla from spawning hostile mobs, and stops vanilla from despawning ours. */
public class SpawnBlockHandler {

    @SubscribeEvent
    public void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        if (!ZSConfig.BLOCK_VANILLA_HOSTILES.get()) return;

        MobSpawnType reason = event.getSpawnType();
        if (reason != MobSpawnType.NATURAL
                && reason != MobSpawnType.CHUNK_GENERATION
                && reason != MobSpawnType.PATROL) return;

        if (event.getEntity() instanceof Enemy) {
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
        if (!ZSConfig.BLOCK_PHANTOMS.get()) return;
        if (event.getEntity() instanceof Phantom && !event.loadedFromDisk()) {
            event.setCanceled(true);
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
