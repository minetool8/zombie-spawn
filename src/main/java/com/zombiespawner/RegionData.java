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
 *
 * - Spawning takes from a budget.
 * - A zombie that DIES is gone for good.
 * - A zombie that was only removed because you walked far away is given back (it is still alive).
 * - When a region's budgets are empty and every zombie it ever spawned is dead, the region is CLEARED.
 *   A cleared region never gets a zombie again (until /zombiespawner reset).
 * - Also remembers which regions a player has ever stood in.
 */
public class RegionData extends SavedData {
    // same name as before on purpose: existing progress (cleared areas) is kept
    private static final String NAME = "zombiespawner_regions_v2";
    /** 3 = budgets come from the room scan. Older saves are re-measured (cleared regions stay cleared). */
    private static final int FORMAT = 3;

    private final Map<Long, Integer> indoor = new HashMap<>();
    private final Map<Long, Integer> open = new HashMap<>();
    private final Map<Long, Integer> alive = new HashMap<>();
    private final Map<Long, Double> density = new HashMap<>();
    private final Set<Long> visited = new HashSet<>();
    private final Set<Long> cleared = new HashSet<>();
    /** verified indoor spots per region: kept in memory only, rebuilt by scanning */
    private final Map<Long, RegionScanner.Scan> scans = new HashMap<>();

    public static RegionData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(RegionData::load, RegionData::new, NAME);
    }

    public static long regionKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    public static long key(int blockX, int blockZ, int size) {
        return regionKey(Math.floorDiv(blockX, size), Math.floorDiv(blockZ, size));
    }

    public static int regionX(long key) {
        return (int) (key >> 32);
    }

    public static int regionZ(long key) {
        return (int) key;
    }

    /** Has this region been measured and given its budget yet? */
    public boolean isKnown(long key) {
        return indoor.containsKey(key);
    }

    /** Gives a region its budget: zombies is the raw indoor amount (from the room scan), dens is 0..1. */
    public void setRegion(ServerLevel level, long key, double zombies, double dens) {
        RandomSource rnd = RandomSource.create(level.getSeed() * 341873128712L + key * 132897987541L);
        int in = (int) Math.floor(zombies) + (rnd.nextDouble() < zombies - Math.floor(zombies) ? 1 : 0);
        in = Math.min(in, ZSConfig.DENSE_INDOOR.get());
        int mn = ZSConfig.MIN_OPEN.get(), mx = Math.max(ZSConfig.MAX_OPEN.get(), mn);
        indoor.put(key, in);
        open.put(key, mn + rnd.nextInt(mx - mn + 1));
        density.put(key, dens);
        setDirty();
    }

    public RegionScanner.Scan getScan(long key) {
        return scans.get(key);
    }

    public void putScan(long key, RegionScanner.Scan scan) {
        scans.put(key, scan);
    }

    /** 0..1 how built-up the region is, or -1 if it has not been measured. */
    public double getDensity(long key) {
        return density.getOrDefault(key, -1.0);
    }

    public int getIndoor(long key) {
        return cleared.contains(key) ? 0 : indoor.getOrDefault(key, 0);
    }

    public int getOpen(long key) {
        return cleared.contains(key) ? 0 : open.getOrDefault(key, 0);
    }

    public int getAlive(long key) {
        return alive.getOrDefault(key, 0);
    }

    /** A zombie from this region was just spawned: take it out of the budget and count it as alive. */
    public void spawned(long key, boolean isOpen) {
        Map<Long, Integer> m = isOpen ? open : indoor;
        Integer v = m.get(key);
        if (v != null && v > 0) m.put(key, v - 1);
        alive.merge(key, 1, Integer::sum);
        setDirty();
    }

    /** A zombie from this region was KILLED. When nothing is left to spawn or alive, the region is cleared. */
    public void killed(long key) {
        int a = Math.max(0, alive.getOrDefault(key, 0) - 1);
        alive.put(key, a);
        if (a == 0 && indoor.getOrDefault(key, 1) == 0 && open.getOrDefault(key, 1) == 0) {
            cleared.add(key);
        }
        setDirty();
    }

    /** A zombie was only unloaded/removed for distance (not killed): it goes back into its region. */
    public void giveBack(long key, boolean isOpen) {
        if (cleared.contains(key)) return;           // a cleared region never gets zombies back
        Map<Long, Integer> m = isOpen ? open : indoor;
        Integer v = m.get(key);
        if (v != null) { // region was reset meanwhile: let it re-roll fresh instead
            m.put(key, v + 1);
            alive.put(key, Math.max(0, alive.getOrDefault(key, 0) - 1));
            setDirty();
        }
    }

    public boolean isCleared(long key) {
        return cleared.contains(key);
    }

    /** True if this region or any of the 8 around it is cleared. */
    public boolean nearCleared(long key) {
        return anyAround(cleared, key);
    }

    public boolean isVisited(long key) {
        return visited.contains(key);
    }

    /** True if this region or any of the 8 around it has ever had a player in it. */
    public boolean nearVisited(long key) {
        return anyAround(visited, key);
    }

    public void markVisited(long key) {
        if (visited.add(key)) setDirty();
    }

    private static boolean anyAround(Set<Long> set, long key) {
        int rx = regionX(key), rz = regionZ(key);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (set.contains(regionKey(rx + dx, rz + dz))) return true;
            }
        }
        return false;
    }

    /** Forget everything: every region gets a fresh budget and counts as unexplored again. */
    public void clearAll() {
        indoor.clear();
        open.clear();
        alive.clear();
        density.clear();
        scans.clear();
        visited.clear();
        cleared.clear();
        setDirty();
    }

    /** Forget a single region. */
    public void clearRegion(long key) {
        indoor.remove(key);
        open.remove(key);
        alive.remove(key);
        density.remove(key);
        scans.remove(key);
        visited.remove(key);
        cleared.remove(key);
        setDirty();
    }

    public String describe(long key) {
        return "region " + regionX(key) + "," + regionZ(key)
                + ": density=" + (density.containsKey(key) ? String.format("%.2f", density.get(key)) : "not measured yet")
                + ", rooms found=" + (scans.containsKey(key) ? scans.get(key).spots.size() : 0)
                + ", indoor left=" + indoor.getOrDefault(key, 0)
                + ", open left=" + open.getOrDefault(key, 0)
                + ", alive=" + getAlive(key)
                + ", cleared=" + (cleared.contains(key) ? "YES" : "no")
                + ", visited=" + (visited.contains(key) ? "yes" : "no");
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
            c.putInt("a", alive.getOrDefault(e.getKey(), 0));
            c.putDouble("d", density.getOrDefault(e.getKey(), 0.0));
            list.add(c);
        }
        tag.put("regions", list);
        tag.put("visited", new LongArrayTag(visited.stream().mapToLong(Long::longValue).toArray()));
        tag.put("cleared", new LongArrayTag(cleared.stream().mapToLong(Long::longValue).toArray()));
        tag.putInt("ver", FORMAT);
        return tag;
    }

    public static RegionData load(CompoundTag tag) {
        RegionData d = new RegionData();
        boolean legacy = tag.getInt("ver") < FORMAT;
        for (long v : tag.getLongArray("cleared")) d.cleared.add(v);
        for (long v : tag.getLongArray("visited")) d.visited.add(v);
        ListTag list = tag.getList("regions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            long k = c.getLong("k");
            // budgets from older versions are dropped so they get re-measured by the room scan (cleared ones stay cleared)
            if ((legacy || !c.contains("d")) && !d.cleared.contains(k)) continue;
            d.indoor.put(k, c.getInt("i"));
            d.open.put(k, c.getInt("o"));
            d.alive.put(k, c.getInt("a"));
            d.density.put(k, c.getDouble("d"));
        }
        return d;
    }
}
