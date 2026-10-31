package com.zombiespawner;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Saved with the world. The map is split into square regions.
 * Each region has a finite number of "unspawned" zombies (its budget).
 * Spawning takes from the budget; a zombie that dies is gone for good;
 * a zombie removed only because you walked far away is given back.
 * Also remembers which regions a player has ever stood in (for frontier wanderers).
 */
public class RegionData extends SavedData {
    private static final String NAME = "zombiespawner_regions";

    private final Map<Long, Integer> unspawned = new HashMap<>();
    private final Set<Long> visited = new HashSet<>();

    public static RegionData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(RegionData::load, RegionData::new, NAME);
    }

    public static long key(int blockX, int blockZ, int size) {
        int rx = Math.floorDiv(blockX, size);
        int rz = Math.floorDiv(blockZ, size);
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /** Remaining budget of a region. Created the first time it's asked for. */
    public int getUnspawned(ServerLevel level, long key) {
        Integer v = unspawned.get(key);
        if (v == null) {
            int min = ZSConfig.MIN_PER_REGION.get();
            int max = Math.max(ZSConfig.MAX_PER_REGION.get(), min);
            // deterministic per world + region, so it can't be re-rolled
            RandomSource rnd = RandomSource.create(level.getSeed() * 341873128712L + key * 132897987541L);
            v = min + rnd.nextInt(max - min + 1);
            unspawned.put(key, v);
            setDirty();
        }
        return v;
    }

    public void consume(long key) {
        Integer v = unspawned.get(key);
        if (v != null && v > 0) {
            unspawned.put(key, v - 1);
            setDirty();
        }
    }

    public void giveBack(long key) {
        Integer v = unspawned.get(key);
        if (v != null) { // region was reset meanwhile: let it re-roll fresh instead
            unspawned.put(key, v + 1);
            setDirty();
        }
    }

    /** Forget everything: every region gets a fresh budget and counts as unexplored again. */
    public void clearAll() {
        unspawned.clear();
        visited.clear();
        setDirty();
    }

    /** Forget a single region. */
    public void clearRegion(long key) {
        unspawned.remove(key);
        visited.remove(key);
        setDirty();
    }

    public boolean isVisited(long key) {
        return visited.contains(key);
    }

    public void markVisited(long key) {
        if (visited.add(key)) setDirty();
    }

    // ---------------------------------------------------------------- saving

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, Integer> e : unspawned.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("k", e.getKey());
            c.putInt("n", e.getValue());
            list.add(c);
        }
        tag.put("regions", list);
        tag.put("visited", new LongArrayTag(visited.stream().mapToLong(Long::longValue).toArray()));
        return tag;
    }

    public static RegionData load(CompoundTag tag) {
        RegionData d = new RegionData();
        ListTag list = tag.getList("regions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            d.unspawned.put(c.getLong("k"), c.getInt("n"));
        }
        for (long v : tag.getLongArray("visited")) d.visited.add(v);
        return d;
    }
}
