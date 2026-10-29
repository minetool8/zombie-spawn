package com.zombiespawner;

import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Slows the day/night cycle.
 * In-game ticks 0..12000 = "day half", 12000..24000 = "night half".
 * Vanilla runs 1 time-tick per real tick; we run (12000 / (minutes * 1200)) per real tick.
 * 25 min => 0.4 time-ticks per real tick.
 */
public class DayNightController {
    private double accumulator = 0.0;

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        if (!ZSConfig.MANAGE_TIME.get()) return;

        // we drive time ourselves, so vanilla's own advance must be off
        GameRules.BooleanValue rule = level.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
        if (rule.get()) rule.set(false, level.getServer());

        long time = level.getDayTime();
        long phase = Math.floorMod(time, 24000L);
        double minutes = phase < 12000L ? ZSConfig.DAY_MINUTES.get() : ZSConfig.NIGHT_MINUTES.get();
        double speed = 12000.0 / (minutes * 1200.0);

        accumulator += speed;
        long add = (long) accumulator;
        accumulator -= add;
        if (add > 0) {
            level.setDayTime(time + add);
        }

        // vanilla only syncs time every 20 ticks and the client won't advance it itself
        // while the gamerule is off, so push updates often to keep the sun smooth.
        if (level.getGameTime() % 4 == 0) {
            ClientboundSetTimePacket packet =
                    new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(), false);
            for (ServerPlayer p : level.players()) {
                p.connection.send(packet);
            }
        }
    }
}
