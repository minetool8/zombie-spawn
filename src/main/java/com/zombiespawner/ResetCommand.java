package com.zombiespawner;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * /zombiespawner reset         -> reset every region in every dimension
 * /zombiespawner reset here    -> reset only the region you are standing in
 * /zombiespawner inspect       -> explains how the mod sees the spot you are standing on
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
                                        .executes(this::resetHere)))
                        .then(Commands.literal("inspect")
                                .executes(this::inspect)));
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

    private void say(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }

    /** Stand somewhere and run this to see exactly why the mod does or doesn't call it a building. */
    private int inspect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();

        BlockPos p = player.blockPosition();
        BlockPos floor = null;
        if (Spots.isStandable(level, p.below())) floor = p.below();
        else if (Spots.isStandable(level, p)) floor = p;

        long key = RegionData.key(p.getX(), p.getZ(), ZSConfig.REGION_SIZE.get());
        say(src, "[ZombieSpawner] " + RegionData.get(level).describe(level, key));

        if (floor == null) {
            say(src, "[ZombieSpawner] Can't find a standable floor under you (stand on solid ground with 2 blocks of air above).");
            return 0;
        }

        BuildingDetector.Result r = BuildingDetector.analyse(level, floor, player);
        say(src, "[ZombieSpawner] Floor block at " + floor.getX() + " " + floor.getY() + " " + floor.getZ());
        say(src, "  ceiling: " + (r.roofed ? r.ceilingHeight + " blocks above the floor" : "none"));
        say(src, "  walls: " + r.hits + "/8 directions (need " + ZSConfig.MIN_ENCLOSED_RAYS.get() + ")");
        say(src, "  natural blocks above: " + r.naturalCover + " (limit " + ZSConfig.MAX_NATURAL_COVER.get() + ")");
        say(src, "  man-made surfaces: " + r.manMade + "/" + r.samples + " = " + String.format("%.2f", r.ratio)
                + " (need " + ZSConfig.MIN_MAN_MADE_RATIO.get() + ") -> " + (r.materialOk ? "pass" : "fail"));
        say(src, "  room shape: " + r.cells + " open cells, flat floor " + r.flatFloor + ", flat ceiling "
                + r.flatCeiling + " -> " + (r.shapeOk ? "regular" : "irregular"));
        for (String s : r.surfaces) say(src, "    " + s);
        say(src, "[ZombieSpawner] Verdict: " + (r.building ? "BUILDING" : "NOT a building") + " - " + r.verdict
                + (ZSConfig.ALLOW_CAVES.get() ? "  (allowCaves is ON, so any roofed spot is accepted)" : ""));
        return 1;
    }
}
