package org.dreeam.leaf.config.modules.gameplay;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;
import org.dreeam.leaf.config.LeafConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FakePacketEntities extends ConfigModules {
    public static final String BASE_PATH = EnumConfigCategory.GAMEPLAY.getBaseKeyName() + ".fake-packet-entities";

    public String getBasePath() {
        return BASE_PATH;
    }

    public static boolean enabled = false;
    /**
     * When {@code gameplay-mechanisms.players-only-mode} is on, spawn eggs still show a client-side mob (packet-only)
     * for {@link org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason#EGG} /
     * {@link org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason#SPAWNER_EGG} /
     * {@link org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason#DISPENSE_EGG}.
     */
    public static boolean playersOnlyEggFakes = true;
    /**
     * Packet-only visuals for {@code /summon} and similar ({@link org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason#COMMAND} /
     * {@link org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason#CUSTOM}) under players-only-mode.
     */
    public static boolean playersOnlySummonFakes = true;
    /**
     * Compatibility layer for players-only-mode: convert blocked real entities (plugin/default spawns) to packet-only
     * stand-ins when possible (living entities and armor stands), instead of silently dropping them.
     */
    public static boolean playersOnlyCompatibilityLayer = true;
    /**
     * Restrict compatibility-layer conversions to plugin/manual spawn paths (CUSTOM/DEFAULT/COMMAND + egg reasons),
     * excluding natural mob ecosystem reasons so mobcap-style behavior is not mirrored as packet spam.
     */
    public static boolean playersOnlyCompatibilityPluginSpawnsOnly = true;
    public static boolean convertWhitelistedEvenWithoutPlayersOnly = false;
    /**
     * For armor_stand conversions, only treat likely hologram/plugin utility stands as packet candidates.
     * This avoids converting normal decorative/interactive armor stands by default.
     */
    public static boolean armorStandHologramHeuristicOnly = true;
    public static boolean playersOnlyCompatibilityWhitelistOnly = false;
    public static Set<String> playersOnlyCompatibilityTypeList = new LinkedHashSet<>(List.of(
        "minecraft:armor_stand",
        "minecraft:text_display",
        "minecraft:item_display",
        "minecraft:block_display",
        "minecraft:interaction",
        "minecraft:marker"
    ));
    public static int workerThreads = 1;
    public static int tickIntervalMs = 50;
    public static double broadcastDistanceBlocks = 128.0;

    private static boolean initialized;

    @Override
    public void onLoaded() {
        if (initialized) {
            config.getConfigSection(getBasePath());
            return;
        }
        initialized = true;

        workerThreads = config.getInt(getBasePath() + ".worker-threads", workerThreads);
        tickIntervalMs = config.getInt(getBasePath() + ".tick-interval-ms", tickIntervalMs);
        broadcastDistanceBlocks = config.getDouble(getBasePath() + ".broadcast-distance-blocks", broadcastDistanceBlocks);

        enabled = config.getBoolean(getBasePath() + ".enabled", enabled, config.pickStringRegionBased(
            """
                Packet-only fake entities: clients see spawn/move/remove packets without ServerLevel entities.
                Simulation runs on dedicated worker thread(s); packets are sent on the main server thread.
                Not a full entity replacement — no collisions, AI, or plugin Entity API.""",
            """
                基于数据包的伪实体：不向 ServerLevel 注册实体，仅对客户端发送生成/移动/移除包。
                逻辑在独立工作线程计算；发包在主线程执行。
                不能替代完整实体（无碰撞、AI、插件 Entity API）。"""
        ));

        playersOnlyEggFakes = config.getBoolean(getBasePath() + ".players-only-spawn-egg-fakes", playersOnlyEggFakes, config.pickStringRegionBased(
            """
                With players-only-mode, spawn eggs (use / dispenser / spawner egg) spawn a packet-only mob visible to nearby players
                instead of failing silently. No AI or server-side entity.""",
            """
                在 players-only-mode 下，生成蛋（使用 / 发射器 / 刷怪笼蛋）会生成仅数据包可见的生物，供附近玩家看到，
                而不是静默失败；无 AI、无服务端实体。"""
        ));

        playersOnlySummonFakes = config.getBoolean(getBasePath() + ".players-only-summon-fakes", playersOnlySummonFakes, config.pickStringRegionBased(
            """
                With players-only-mode, /summon (COMMAND/CUSTOM spawn reasons) shows a packet-only living mob for nearby players.""",
            """
                在 players-only-mode 下，/summon（COMMAND/CUSTOM 生成原因）为附近玩家显示仅数据包的生物外观。"""
        ));

        playersOnlyCompatibilityLayer = config.getBoolean(getBasePath() + ".players-only-compatibility-layer", playersOnlyCompatibilityLayer, config.pickStringRegionBased(
            """
                Compatibility layer for players-only-mode: convert blocked real entity spawns from plugins/default paths
                (especially armor stand holograms) into packet-only visuals when supported.""",
            """
                players-only-mode 兼容层：将插件/默认路径被拦截的真实实体生成（尤其是盔甲架全息）转换为可见的数据包伪实体。"""
        ));

        playersOnlyCompatibilityPluginSpawnsOnly = config.getBoolean(getBasePath() + ".players-only-compatibility-plugin-spawns-only", playersOnlyCompatibilityPluginSpawnsOnly, config.pickStringRegionBased(
            """
                Compatibility layer safety: only convert plugin/manual spawn reasons (CUSTOM/DEFAULT/COMMAND + eggs),
                not natural/spawner/raid/chunk-gen ecosystems. Prevents huge packet-only populations.""",
            """
                兼容层安全开关：仅转换插件/手动生成原因（CUSTOM/DEFAULT/COMMAND + 生成蛋），
                不转换自然/刷怪笼/袭击/区块生成生态，避免海量数据包伪实体。"""
        ));
        convertWhitelistedEvenWithoutPlayersOnly = config.getBoolean(getBasePath() + ".convert-whitelisted-even-without-players-only", convertWhitelistedEvenWithoutPlayersOnly, config.pickStringRegionBased(
            """
                Convert supported+whitelisted entities to packet-only even when players-only-mode is disabled.""",
            """
                即使 players-only-mode 关闭，也将受支持且在白名单中的实体转换为数据包伪实体。"""
        ));
        armorStandHologramHeuristicOnly = config.getBoolean(getBasePath() + ".armor-stand-hologram-heuristic-only", armorStandHologramHeuristicOnly, config.pickStringRegionBased(
            """
                For armor_stand, only convert likely hologram-style stands (marker OR invisible+no-gravity+custom-name).""",
            """
                对 armor_stand 仅转换“疑似全息”样式（marker 或 invisible+no-gravity+custom-name）。"""
        ));

        playersOnlyCompatibilityWhitelistOnly = config.getBoolean(getBasePath() + ".players-only-compatibility-whitelist-only", playersOnlyCompatibilityWhitelistOnly, config.pickStringRegionBased(
            """
                Restrict compatibility conversions to the explicit type list below. Useful for hologram-only migrations.""",
            """
                仅对下方类型白名单执行兼容转换，适合仅迁移全息/展示实体。"""
        ));
        playersOnlyCompatibilityTypeList = normalizeTypeList(config.getList(getBasePath() + ".players-only-compatibility-types", new ArrayList<>(playersOnlyCompatibilityTypeList), config.pickStringRegionBased(
            """
                Type allowlist for compatibility conversion (minecraft:id). Used when players-only-compatibility-whitelist-only=true.""",
            """
                兼容转换类型白名单（minecraft:id），当 players-only-compatibility-whitelist-only=true 时生效。"""
        )));

        if (workerThreads < 1) {
            workerThreads = 1;
        }
        if (tickIntervalMs < 5) {
            tickIntervalMs = 5;
        }
        if (broadcastDistanceBlocks < 16.0) {
            broadcastDistanceBlocks = 16.0;
        }

        // Worker scheduling starts in FakePacketEntityEngine.bindMainThreadExecutor when CraftServer is constructed — Leaf config loads
        // before DedicatedServer exists; starting workers earlier could observe MinecraftServer before the dedicated server is ready.
    }

    public static boolean isTypeAllowed(final String typeId) {
        if (!playersOnlyCompatibilityWhitelistOnly) {
            return true;
        }
        return playersOnlyCompatibilityTypeList.contains(typeId.toLowerCase(Locale.ROOT));
    }

    public static void setBooleanOption(final String key, final boolean value) {
        switch (key) {
            case "enabled" -> enabled = value;
            case "compatibility-layer" -> playersOnlyCompatibilityLayer = value;
            case "plugin-spawns-only" -> playersOnlyCompatibilityPluginSpawnsOnly = value;
            case "always-convert-whitelist" -> convertWhitelistedEvenWithoutPlayersOnly = value;
            case "armor-stand-heuristic-only" -> armorStandHologramHeuristicOnly = value;
            case "whitelist-only" -> playersOnlyCompatibilityWhitelistOnly = value;
            case "spawn-egg-fakes" -> playersOnlyEggFakes = value;
            case "summon-fakes" -> playersOnlySummonFakes = value;
            default -> throw new IllegalArgumentException("Unknown option: " + key);
        }
        persistCurrentState();
    }

    public static boolean addType(final String typeId) {
        boolean changed = playersOnlyCompatibilityTypeList.add(typeId.toLowerCase(Locale.ROOT));
        if (changed) {
            persistCurrentState();
        }
        return changed;
    }

    public static boolean removeType(final String typeId) {
        boolean changed = playersOnlyCompatibilityTypeList.remove(typeId.toLowerCase(Locale.ROOT));
        if (changed) {
            persistCurrentState();
        }
        return changed;
    }

    private static Set<String> normalizeTypeList(final List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s != null && !s.isBlank()) {
                out.add(s.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static void persistCurrentState() {
        try {
            LeafConfig.config().set(BASE_PATH + ".enabled", enabled);
            LeafConfig.config().set(BASE_PATH + ".players-only-spawn-egg-fakes", playersOnlyEggFakes);
            LeafConfig.config().set(BASE_PATH + ".players-only-summon-fakes", playersOnlySummonFakes);
            LeafConfig.config().set(BASE_PATH + ".players-only-compatibility-layer", playersOnlyCompatibilityLayer);
            LeafConfig.config().set(BASE_PATH + ".players-only-compatibility-plugin-spawns-only", playersOnlyCompatibilityPluginSpawnsOnly);
            LeafConfig.config().set(BASE_PATH + ".convert-whitelisted-even-without-players-only", convertWhitelistedEvenWithoutPlayersOnly);
            LeafConfig.config().set(BASE_PATH + ".armor-stand-hologram-heuristic-only", armorStandHologramHeuristicOnly);
            LeafConfig.config().set(BASE_PATH + ".players-only-compatibility-whitelist-only", playersOnlyCompatibilityWhitelistOnly);
            LeafConfig.config().set(BASE_PATH + ".players-only-compatibility-types", new ArrayList<>(playersOnlyCompatibilityTypeList));
            LeafConfig.config().saveConfig();
        } catch (Exception e) {
            LeafConfig.LOGGER.warn("Failed to persist fake-packet-entities config runtime update", e);
        }
    }
}
