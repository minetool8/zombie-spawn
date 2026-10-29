package com.zombiespawner;

import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Stops vanilla from spawning hostile mobs on its own. */
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

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!ZSConfig.BLOCK_PHANTOMS.get()) return;
        if (event.getEntity() instanceof Phantom && !event.loadedFromDisk()) {
            event.setCanceled(true);
        }
    }
}
