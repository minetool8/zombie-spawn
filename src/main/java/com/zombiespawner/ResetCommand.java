package com.zombiespawner;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * /zombiespawner reset         -> reset every region in every dimension
 * /zombiespawner reset here    -> reset only the region you are standing in
 * Requires op (permission level 2).
 */
public class ResetCommand {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("zombiespawner")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("reset")
                                .executes(this::resetAll)
                                .then(Commands.literal("here")
                                        .executes(this::resetHere))));
    }

    private int resetAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        int count = 0;
        for (ServerLevel level : src.getServer().getAllLevels()) {
            RegionData.get(level).clearAll();
            count++;
        }
        final int dims = count;
        src.sendSuccess(() -> Component.literal(
                "[ZombieSpawner] Reset all regions in " + dims + " dimension(s). Cleared areas will repopulate."), true);
        return 1;
    }

    private int resetHere(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 pos = src.getPosition();
        BlockPos bp = BlockPos.containing(pos);
        long key = RegionData.key(bp.getX(), bp.getZ(), ZSConfig.REGION_SIZE.get());
        RegionData.get(level).clearRegion(key);
        src.sendSuccess(() -> Component.literal(
                "[ZombieSpawner] Reset the region you're standing in."), true);
        return 1;
    }
}
