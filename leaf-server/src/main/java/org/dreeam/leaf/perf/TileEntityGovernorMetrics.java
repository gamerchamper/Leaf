package org.dreeam.leaf.perf;

public record TileEntityGovernorMetrics(long considered, long skipped, long executed, long blockedMobSpawns) {
    public double skippedRatio() {
        return considered <= 0L ? 0.0D : (double) skipped / (double) considered;
    }
}
