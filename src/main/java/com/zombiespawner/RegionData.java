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
 * Saved with the world. The map is split into square regions. Each region has two finite budgets
 * of "not yet spawned" zombies: indoor (in buildings) and open (outside, very few).
 * Spawning takes from a budget; a zombie that dies is gone for good; a zombie removed only because
 * you walked far away is given back. Also remembers which regions a player has ever stood in.
 */
public class RegionData extends SavedData {
    private static final String NAME = "zombiespawner_regions_v2";

    private final Map<Long, Integer> indoor = new HashMap<>();
    private final Map<Long, Integer> open = new HashMap<>();
    private final Set<Long> visited = new HashSet<>();

    public static RegionData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(RegionData::load, RegionData::new, NAME);
    }

    public static long key(int blockX, int blockZ, int size) {
        int rx = Math.floorDiv(blockX, size);
        int rz = Math.floorDiv(blockZ, size);
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    private static int roll(RandomSource rnd, int min, int max) {
        max = Math.max(max, min);
        return min + rnd.nextInt(max - min + 1);
    }

    /** Creates the region's budgets the first time it is needed (deterministic per world + region). */
    private void ensure(ServerLevel level, long key) {
        if (indoor.containsKey(key)) return;
        RandomSource rnd = RandomSource.create(level.getSeed() * 341873128712L + key * 132897987541L);
        indoor.put(key, roll(rnd, ZSConfig.MIN_INDOOR.get(), ZSConfig.MAX_INDOOR.get()));
        open.put(key, roll(rnd, ZSConfig.MIN_OPEN.get(), ZSConfig.MAX_OPEN.get()));
        setDirty();
    }

    public int getIndoor(ServerLevel level, long key) {
        ensure(level, key);
        return indoor.get(key);
    }

    public int getOpen(ServerLevel level, long key) {
        ensure(level, key);
        return open.get(key);
    }

    public void consume(long key, boolean isOpen) {
        Map<Long, Integer> m = isOpen ? open : indoor;
        Integer v = m.get(key);
        if (v != null && v > 0) {
            m.put(key, v - 1);
            setDirty();
        }
    }

    public void giveBack(long key, boolean isOpen) {
        Map<Long, Integer> m = isOpen ? open : indoor;
        Integer v = m.get(key);
        if (v != null) { // region was reset meanwhile: let it re-roll fresh instead
            m.put(key, v + 1);
            setDirty();
        }
    }

    public boolean isVisited(long key) {
        return visited.contains(key);
    }

    public void markVisited(long key) {
        if (visited.add(key)) setDirty();
    }

    /** Forget everything: every region gets a fresh budget and counts as unexplored again. */
    public void clearAll() {
        indoor.clear();
        open.clear();
        visited.clear();
        setDirty();
    }

    /** Forget a single region. */
    public void clearRegion(long key) {
        indoor.remove(key);
        open.remove(key);
        visited.remove(key);
        setDirty();
    }

    // ---------------------------------------------------------------- saving

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, Integer> e : indoor.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("k", e.getKey());
            c.putInt("i", e.getValue());
            c.putInt("o", open.getOrDefault(e.getKey(), 0));
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
            long k = c.getLong("k");
            d.indoor.put(k, c.getInt("i"));
            d.open.put(k, c.getInt("o"));
        }
        for (long v : tag.getLongArray("visited")) d.visited.add(v);
        return d;
    }
}
