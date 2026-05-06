package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;
import org.dreeam.leaf.config.LeafConfig;
import org.dreeam.leaf.perf.TileEntityGovernorMetrics;

import java.util.concurrent.atomic.AtomicLong;

public class TileEntityGovernor extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.PERF.getBaseKeyName() + ".tile-entity-governor";
    }

    public static boolean enabled = false;
    public static boolean autoEnableByMspt = false;
    public static double autoEnableMsptThreshold = 45.0D;
    public static int globalInterval = 1;
    public static int maxTickedPerChunk = 0;
    public static boolean adaptiveEnabled = true;
    public static double adaptiveMsptThreshold = 45.0D;
    public static int adaptiveExtraInterval = 1;
    public static int hopperInterval = 2;
    public static int furnaceInterval = 1;
    public static int brewingStandInterval = 1;
    public static int beaconInterval = 1;
    public static int spawnerInterval = 1;
    public static boolean blockAllMobSpawnsWhenActive = false;
    private static final AtomicLong TICKERS_CONSIDERED = new AtomicLong();
    private static final AtomicLong TICKERS_SKIPPED = new AtomicLong();
    private static final AtomicLong TICKERS_EXECUTED = new AtomicLong();
    private static final AtomicLong BLOCKED_MOB_SPAWNS = new AtomicLong();

    @Override
    public void onLoaded() {
        enabled = config.getBoolean(getBasePath() + ".enabled", enabled, config.pickStringRegionBased(
            """
                Adaptive tile-entity governor: rate-limit expensive block entities by type/chunk to reduce MSPT spikes.""",
            """
                自适应方块实体调速器：按类型/区块限制高开销方块实体 tick，以降低 MSPT 峰值。"""
        ));
        autoEnableByMspt = config.getBoolean(getBasePath() + ".auto-enable-by-mspt", autoEnableByMspt, config.pickStringRegionBased(
            """
                Enable governor only when average MSPT reaches threshold below.""",
            """
                当平均 MSPT 达到阈值时自动启用调速器。"""
        ));
        autoEnableMsptThreshold = Math.max(1.0D, config.getDouble(getBasePath() + ".auto-enable-mspt-threshold", autoEnableMsptThreshold));
        globalInterval = Math.max(1, config.getInt(getBasePath() + ".global-interval", globalInterval));
        maxTickedPerChunk = Math.max(0, config.getInt(getBasePath() + ".max-ticked-per-chunk", maxTickedPerChunk, config.pickStringRegionBased(
            """
                Hard cap for number of block-entity ticker executions per chunk each server tick. 0 disables cap.""",
            """
                每个区块每 tick 最多执行多少次方块实体 ticker。0 为关闭。"""
        )));
        adaptiveEnabled = config.getBoolean(getBasePath() + ".adaptive.enabled", adaptiveEnabled);
        adaptiveMsptThreshold = Math.max(1.0D, config.getDouble(getBasePath() + ".adaptive.mspt-threshold", adaptiveMsptThreshold));
        adaptiveExtraInterval = Math.max(0, config.getInt(getBasePath() + ".adaptive.extra-interval", adaptiveExtraInterval));

        hopperInterval = Math.max(1, config.getInt(getBasePath() + ".type-intervals.hopper", hopperInterval));
        furnaceInterval = Math.max(1, config.getInt(getBasePath() + ".type-intervals.furnace", furnaceInterval));
        brewingStandInterval = Math.max(1, config.getInt(getBasePath() + ".type-intervals.brewing-stand", brewingStandInterval));
        beaconInterval = Math.max(1, config.getInt(getBasePath() + ".type-intervals.beacon", beaconInterval));
        spawnerInterval = Math.max(1, config.getInt(getBasePath() + ".type-intervals.spawner", spawnerInterval));
        blockAllMobSpawnsWhenActive = config.getBoolean(getBasePath() + ".block-all-mob-spawns-when-active", blockAllMobSpawnsWhenActive, config.pickStringRegionBased(
            """
                While governor is active, block all mob additions (natural, spawner, command, plugin).""",
            """
                调速器激活时，阻止所有生物加入（自然、刷怪笼、命令、插件）。"""
        ));
    }

    public static boolean isActiveForMspt(final double avgMspt) {
        if (!enabled) {
            return false;
        }
        return !autoEnableByMspt || avgMspt >= autoEnableMsptThreshold;
    }

    public static boolean shouldBlockMobSpawns(final double avgMspt) {
        return isActiveForMspt(avgMspt) && blockAllMobSpawnsWhenActive;
    }

    public static int intervalForType(final String tickerType, final double avgMspt) {
        if (!isActiveForMspt(avgMspt)) {
            return 1;
        }
        int interval = globalInterval;
        final String lower = tickerType == null ? "" : tickerType.toLowerCase(java.util.Locale.ROOT);

        if (lower.contains("hopper")) {
            interval = Math.max(interval, hopperInterval);
        } else if (lower.contains("furnace") || lower.contains("smoker") || lower.contains("blast_furnace")) {
            interval = Math.max(interval, furnaceInterval);
        } else if (lower.contains("brewing")) {
            interval = Math.max(interval, brewingStandInterval);
        } else if (lower.contains("beacon")) {
            interval = Math.max(interval, beaconInterval);
        } else if (lower.contains("spawner") || lower.contains("trial_spawner")) {
            interval = Math.max(interval, spawnerInterval);
        }

        if (adaptiveEnabled && avgMspt >= adaptiveMsptThreshold) {
            interval += adaptiveExtraInterval;
        }
        return Math.max(1, interval);
    }

    public static void recordTickerConsidered() {
        TICKERS_CONSIDERED.incrementAndGet();
    }

    public static void recordTickerSkipped() {
        TICKERS_SKIPPED.incrementAndGet();
    }

    public static void recordTickerExecuted() {
        TICKERS_EXECUTED.incrementAndGet();
    }

    public static void recordBlockedMobSpawn() {
        BLOCKED_MOB_SPAWNS.incrementAndGet();
    }

    public static TileEntityGovernorMetrics snapshotMetrics() {
        return new TileEntityGovernorMetrics(
            TICKERS_CONSIDERED.get(),
            TICKERS_SKIPPED.get(),
            TICKERS_EXECUTED.get(),
            BLOCKED_MOB_SPAWNS.get()
        );
    }

    public static void setEnabledAndPersist(final boolean value) {
        enabled = value;
        try {
            LeafConfig.config().set(EnumConfigCategory.PERF.getBaseKeyName() + ".tile-entity-governor.enabled", value);
            LeafConfig.config().saveConfig();
        } catch (Exception e) {
            LeafConfig.LOGGER.warn("Failed to persist tile-entity-governor.enabled update", e);
        }
    }

}
