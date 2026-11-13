package com.zombiespawner;

/** Counters for /zombiespawner stats: shows where spawn attempts die. */
public final class SpawnStats {
    private SpawnStats() {}

    public static int cycles, attempts;
    public static int regionCleared, regionNotReady, regionNoBudget, regionNoSpot;
    public static int scans, scansFailedNotLoaded;
    public static int spotVertical, spotDistance, spotStale, spotCrowded, spotHidden;
    public static int spawnedIndoor, spawnedOpen, spawnedWanderer, spawnFailed;

    public static void reset() {
        cycles = attempts = 0;
        regionCleared = regionNotReady = regionNoBudget = regionNoSpot = 0;
        scans = scansFailedNotLoaded = 0;
        spotVertical = spotDistance = spotStale = spotCrowded = spotHidden = 0;
        spawnedIndoor = spawnedOpen = spawnedWanderer = spawnFailed = 0;
    }
}
