package com.zombiespawner;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.common.Mod;

@Mod(ZombieSpawnerMod.MODID)
public class ZombieSpawnerMod {
    public static final String MODID = "zombiespawner";

    public ZombieSpawnerMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ZSConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(new SpawnBlockHandler());
        MinecraftForge.EVENT_BUS.register(new HiddenSpawner());
        MinecraftForge.EVENT_BUS.register(new DayNightController());
        MinecraftForge.EVENT_BUS.register(new ResetCommand());
    }
}
