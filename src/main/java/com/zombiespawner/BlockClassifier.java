package com.zombiespawner;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a block is NATURAL terrain (stone, dirt, sand, ores, leaves...) or MAN-MADE.
 *
 * Only blocks that are known to be natural count as natural. EVERYTHING ELSE (all modded blocks included)
 * counts as man-made. Any block that is misclassified can be fixed in the config with naturalBlocks / buildingBlocks.
 */
public final class BlockClassifier {
    private BlockClassifier() {}

    private static TagKey<Block> tag(String ns, String path) {
        return TagKey.create(Registries.BLOCK, new ResourceLocation(ns, path));
    }

    private static final List<TagKey<Block>> NATURAL_TAGS = List.of(
            BlockTags.BASE_STONE_OVERWORLD, BlockTags.BASE_STONE_NETHER,
            BlockTags.DIRT, BlockTags.SAND, BlockTags.LEAVES, BlockTags.LOGS,
            BlockTags.ICE, BlockTags.SNOW, BlockTags.FLOWERS,
            BlockTags.COAL_ORES, BlockTags.IRON_ORES, BlockTags.COPPER_ORES, BlockTags.GOLD_ORES,
            BlockTags.REDSTONE_ORES, BlockTags.LAPIS_ORES, BlockTags.DIAMOND_ORES, BlockTags.EMERALD_ORES,
            // tags many mods use for their own terrain blocks
            tag("forge", "ores"), tag("forge", "gravel"), tag("forge", "sand"),
            tag("forge", "netherrack"), tag("forge", "end_stones"),
            tag("forge", "ore_bearing_ground/stone"), tag("forge", "ore_bearing_ground/deepslate"),
            tag("forge", "ore_bearing_ground/netherrack")
    );

    private static final Set<Block> NATURAL_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.BEDROCK, Blocks.GRAVEL, Blocks.CLAY, Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE,
            Blocks.CALCITE, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_CLUSTER,
            Blocks.LARGE_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD,
            Blocks.GLOW_LICHEN, Blocks.VINE, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT, Blocks.HANGING_ROOTS,
            Blocks.MOSS_CARPET, Blocks.SMALL_DRIPLEAF, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM,
            Blocks.SPORE_BLOSSOM, Blocks.GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.COBWEB, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW, Blocks.TERRACOTTA, Blocks.MAGMA_BLOCK,
            Blocks.OBSIDIAN, Blocks.SCULK, Blocks.SCULK_VEIN
    ));

    // ------------------------------------------------------------ user rules from the config

    private static final class Rules {
        final Set<ResourceLocation> blocks = new HashSet<>();
        final Set<String> namespaces = new HashSet<>();
        final List<TagKey<Block>> tags = new ArrayList<>();

        static Rules parse(List<? extends String> entries) {
            Rules r = new Rules();
            for (String raw : entries) {
                if (raw == null) continue;
                String e = raw.trim();
                if (e.isEmpty()) continue;
                if (e.startsWith("#")) {
                    ResourceLocation id = ResourceLocation.tryParse(e.substring(1));
                    if (id != null) r.tags.add(TagKey.create(Registries.BLOCK, id));
                } else if (e.endsWith(":*")) {
                    r.namespaces.add(e.substring(0, e.length() - 2));
                } else {
                    ResourceLocation id = ResourceLocation.tryParse(e);
                    if (id != null) r.blocks.add(id);
                }
            }
            return r;
        }

        boolean matches(ResourceLocation id, BlockState s) {
            if (id != null) {
                if (blocks.contains(id)) return true;
                if (namespaces.contains(id.getNamespace())) return true;
            }
            for (TagKey<Block> t : tags) {
                if (s.is(t)) return true;
            }
            return false;
        }
    }

    private static List<String> lastNatural = null;
    private static List<String> lastBuilding = null;
    private static Rules naturalRules = new Rules();
    private static Rules buildingRules = new Rules();

    private static void refresh() {
        List<? extends String> n = ZSConfig.NATURAL_BLOCKS.get();
        if (lastNatural == null || !lastNatural.equals(n)) {
            lastNatural = new ArrayList<>(n);
            naturalRules = Rules.parse(n);
        }
        List<? extends String> b = ZSConfig.BUILDING_BLOCKS.get();
        if (lastBuilding == null || !lastBuilding.equals(b)) {
            lastBuilding = new ArrayList<>(b);
            buildingRules = Rules.parse(b);
        }
    }

    /** True if this (non-air) block is natural terrain. False = man-made. */
    public static boolean isNatural(BlockState s) {
        refresh();
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(s.getBlock());
        if (buildingRules.matches(id, s)) return false;     // config: always man-made
        if (naturalRules.matches(id, s)) return true;       // config: always natural
        if (NATURAL_BLOCKS.contains(s.getBlock())) return true;
        for (TagKey<Block> t : NATURAL_TAGS) {
            if (s.is(t)) return true;
        }
        return false;
    }

    public static String idOf(BlockState s) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(s.getBlock());
        return id == null ? "?" : id.toString();
    }

    public static String describe(BlockState s) {
        return idOf(s) + (isNatural(s) ? " [natural]" : " [man-made]");
    }
}
