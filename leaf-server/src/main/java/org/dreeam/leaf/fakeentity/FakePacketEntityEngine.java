package org.dreeam.leaf.fakeentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.dreeam.leaf.config.LeafConfig;
import org.dreeam.leaf.config.modules.gameplay.FakePacketEntities;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.LoggerFactory;

/**
 * Client-visible stand-ins using outbound packets only (no registered {@link net.minecraft.world.entity.Entity}).
 * Kinematics and movement flush run on a dedicated worker pool (teleports no longer enqueue onto the dedicated server tick thread).
 * {@link net.minecraft.network.Connection} marshals sends onto each connection's Netty event loop when needed.
 * {@link net.minecraft.server.players.PlayerList#players} is snapshot-safe ({@link java.util.concurrent.CopyOnWriteArrayList}) for iteration here.
 * {@link #bindMainThreadExecutor} runs once {@link org.bukkit.craftbukkit.CraftServer} exists to start the worker pool when enabled.
 */
public final class FakePacketEntityEngine {

    private static final AtomicInteger NEXT_ID = new AtomicInteger(-4096);
    private static final org.slf4j.Logger STORE_LOGGER = LoggerFactory.getLogger("LeafFakePacketEntities");

    private static volatile @Nullable ScheduledExecutorService scheduler;
    private static volatile @Nullable ScheduledFuture<?> tickFuture;
    private static final Map<Integer, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Map<Integer, MotionState> LAST_SENT = new ConcurrentHashMap<>();
    private static final Map<Integer, List<SynchedEntityData.DataValue<?>>> LAST_META = new ConcurrentHashMap<>();
    private static final Path STORE_PATH = Path.of("config", "leaf-fake-packet-entities.dat");
    private static volatile boolean restoredFromDisk;

    private FakePacketEntityEngine() {
    }

    /**
     * Called once from {@link org.bukkit.craftbukkit.CraftServer} after the dedicated server exists so the worker pool can start when fakes are enabled.
     */
    public static void bindMainThreadExecutor(@SuppressWarnings("unused") final Executor executor) {
        if (FakePacketEntities.enabled && scheduler == null) {
            restoreFromDiskIfNeeded();
            start();
        }
    }

    public static void start() {
        if (scheduler != null) {
            return;
        }
        int threads = FakePacketEntities.workerThreads;
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "Leaf FakePacketEntity Worker");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((th, ex) -> LeafConfig.LOGGER.error("Uncaught in {}", th.getName(), ex));
            return t;
        };
        scheduler = threads <= 1
            ? Executors.newSingleThreadScheduledExecutor(factory)
            : Executors.newScheduledThreadPool(threads, factory);

        long period = FakePacketEntities.tickIntervalMs;
        tickFuture = scheduler.scheduleAtFixedRate(FakePacketEntityEngine::workerTick, period, period, TimeUnit.MILLISECONDS);
        LeafConfig.LOGGER.info("Fake packet entities: {} worker thread(s), {} ms tick interval", threads, period);
    }

    public static void shutdown() {
        ScheduledFuture<?> f = tickFuture;
        tickFuture = null;
        if (f != null) {
            f.cancel(false);
        }
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) {
            s.shutdownNow();
        }
        ENTRIES.clear();
        LAST_SENT.clear();
        LAST_META.clear();
    }

    /**
     * Persist UUID-backed packet-only compat entities so plugins that only store UUIDs can resolve them after a restart.
     * File: {@code config/leaf-fake-packet-entities.dat}
     */
    public static void savePersistentStore() {
        if (!FakePacketEntities.enabled) {
            return;
        }
        try {
            Files.createDirectories(STORE_PATH.getParent());
            CompoundTag root = new CompoundTag();
            ListTag list = new ListTag();

            MinecraftServer server = MinecraftServer.getServer();
            for (Entry e : ENTRIES.values()) {
                if (e.sourceEntity == null) {
                    continue;
                }
                // Only persist UUID-backed compat entities (plugin holograms, displays, etc.)
                CompoundTag record = new CompoundTag();
                record.putString("dim", e.dimension.identifier().toString());
                record.putString("type", EntityType.getKey(e.type).toString());

                CompoundTag nbt;
                try (ProblemReporter.ScopedCollector scoped = new ProblemReporter.ScopedCollector(e.sourceEntity.problemPath(), STORE_LOGGER)) {
                    TagValueOutput out = TagValueOutput.createWithContext(scoped, e.sourceEntity.registryAccess());
                    e.sourceEntity.saveWithoutId(out);
                    nbt = out.buildResult();
                }
                record.put("nbt", nbt);
                list.add(record);
            }
            root.put("entities", list);

            Path tmp = STORE_PATH.resolveSibling(STORE_PATH.getFileName().toString() + ".tmp");
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(64 * 1024);
            NbtIo.writeCompressed(root, baos);
            Files.write(tmp, baos.toByteArray());
            Files.move(tmp, STORE_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            LeafConfig.LOGGER.warn("Failed to save fake packet entity store", ex);
        }
    }

    private static void restoreFromDiskIfNeeded() {
        if (restoredFromDisk) {
            return;
        }
        restoredFromDisk = true;
        if (!Files.isRegularFile(STORE_PATH)) {
            return;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(STORE_PATH, NbtAccounter.unlimitedHeap());
            ListTag list = root.getListOrEmpty("entities");
            if (list.isEmpty()) {
                return;
            }
            MinecraftServer server = MinecraftServer.getServer();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag record = list.getCompoundOrEmpty(i);
                String dimId = record.getStringOr("dim", "");
                String typeId = record.getStringOr("type", "");
                CompoundTag nbt = record.getCompoundOrEmpty("nbt");
                if (dimId.isEmpty() || typeId.isEmpty() || nbt.isEmpty()) {
                    continue;
                }
                ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimId));
                ServerLevel level = server.getLevel(dimKey);
                if (level == null) {
                    continue;
                }
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(typeId)).orElse(null);
                if (type == null) {
                    continue;
                }

                Entity entity = type.create(level, EntitySpawnReason.COMMAND);
                if (entity == null) {
                    continue;
                }
                try (ProblemReporter.ScopedCollector scoped = new ProblemReporter.ScopedCollector(entity.problemPath(), STORE_LOGGER)) {
                    entity.load(TagValueInput.create(scoped, level.registryAccess(), nbt));
                }
                // Register as stationary, UUID-backed compat entry (packet-only)
                int id = NEXT_ID.getAndDecrement();
                Vec3 pos = entity.position();
                Entry entry = new Entry(id, entity.getUUID(), dimKey, type, pos, 0.0, 0.0, 0.0, true, entity);
                MotionState initial = new MotionState(pos, entity.getYRot(), entity.getXRot());
                entry.motion.set(initial);
                ENTRIES.put(id, entry);
                LAST_SENT.remove(id);
                List<SynchedEntityData.DataValue<?>> raw = entity.getEntityData().getNonDefaultValues();
                List<SynchedEntityData.DataValue<?>> meta = raw == null ? Collections.emptyList() : List.copyOf(raw);
                LAST_META.put(id, meta);
            }
            LeafConfig.LOGGER.info("Restored {} fake packet entity record(s) from disk", list.size());
        } catch (Exception ex) {
            LeafConfig.LOGGER.warn("Failed to restore fake packet entity store", ex);
        }
    }

    /**
     * After login, send spawn+metadata for all restored/compat packet-only entities in range.
     */
    public static void resyncFor(final ServerPlayer player) {
        if (!FakePacketEntities.enabled || ENTRIES.isEmpty() || player.connection == null) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server.isStopped()) {
            return;
        }
        double distSq = FakePacketEntities.broadcastDistanceBlocks * FakePacketEntities.broadcastDistanceBlocks;
        ResourceKey<Level> dim = player.level().dimension();
        Vec3 p = player.position();
        for (Entry e : ENTRIES.values()) {
            if (!e.dimension.equals(dim)) {
                continue;
            }
            MotionState at = e.motion.get();
            if (player.distanceToSqr(at.pos.x, at.pos.y, at.pos.z) > distSq) {
                continue;
            }
            List<SynchedEntityData.DataValue<?>> raw = e.sourceEntity != null ? e.sourceEntity.getEntityData().getNonDefaultValues() : null;
            List<SynchedEntityData.DataValue<?>> meta = raw == null ? Collections.emptyList() : List.copyOf(raw);
            ClientboundAddEntityPacket add = new ClientboundAddEntityPacket(
                e.id, e.uuid,
                at.pos.x, at.pos.y, at.pos.z,
                at.xRot, at.yRot,
                e.type, 0, Vec3.ZERO, at.yRot
            );
            ClientboundSetEntityDataPacket data = new ClientboundSetEntityDataPacket(e.id, meta);
            player.connection.send(new ClientboundBundlePacket(List.of(add, data)));
        }
    }

    private static void workerTick() {
        if (!FakePacketEntities.enabled || scheduler == null || ENTRIES.isEmpty()) {
            return;
        }
        double t = System.nanoTime() * 1.0e-9;
        boolean needsMovementFlush = false;
        boolean needsMetadataFlush = false;
        List<Integer> removedIds = new ArrayList<>();
        for (Entry e : ENTRIES.values()) {
            if (e.sourceEntity != null) {
                if (e.sourceEntity.isRemoved()) {
                    removedIds.add(e.id);
                    continue;
                }
                MotionState linked = new MotionState(e.sourceEntity.position(), e.sourceEntity.getYRot(), e.sourceEntity.getXRot());
                MotionState oldLinked = e.motion.get();
                if (!linked.equals(oldLinked)) {
                    e.motion.set(linked);
                }
                List<SynchedEntityData.DataValue<?>> rawData = e.sourceEntity.getEntityData().getNonDefaultValues();
                List<SynchedEntityData.DataValue<?>> nowMeta = rawData == null ? Collections.emptyList() : List.copyOf(rawData);
                List<SynchedEntityData.DataValue<?>> prevMeta = LAST_META.get(e.id);
                if (prevMeta == null || !prevMeta.equals(nowMeta)) {
                    e.pendingMeta.set(nowMeta);
                    needsMetadataFlush = true;
                }
            } else if (!e.stationary) {
                double angle = t * e.speed + e.phase;
                double x = e.orbitCenter.x + Math.sin(angle) * e.radius;
                double z = e.orbitCenter.z + Math.cos(angle) * e.radius;
                double y = e.orbitCenter.y;
                e.motion.set(new MotionState(new Vec3(x, y, z), 0.0F, 0.0F));
            }
            MotionState now = e.motion.get();
            MotionState prev = LAST_SENT.get(e.id);
            if (prev == null || !prev.equals(now)) {
                needsMovementFlush = true;
            }
        }
        if (needsMovementFlush) {
            flushMovementPacketsFromWorker();
        }
        if (needsMetadataFlush) {
            flushMetadataPacketsFromWorker();
        }
        for (int id : removedIds) {
            remove(id);
        }
    }

    /**
     * Runs on the fake-entity worker thread(s). Does not enqueue work onto the dedicated server tick thread.
     */
    private static void flushMovementPacketsFromWorker() {
        if (!FakePacketEntities.enabled) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server.isStopped()) {
            return;
        }
        double distSq = FakePacketEntities.broadcastDistanceBlocks * FakePacketEntities.broadcastDistanceBlocks;
        Map<ResourceKey<Level>, List<ServerPlayer>> byDimension = playersByDimension(server);
        for (Entry e : ENTRIES.values()) {
            MotionState now = e.motion.get();
            MotionState prev = LAST_SENT.get(e.id);
            if (prev != null && prev.equals(now)) {
                continue;
            }
            List<ServerPlayer> inDim = byDimension.get(e.dimension);
            if (inDim == null || inDim.isEmpty()) {
                LAST_SENT.put(e.id, now);
                continue;
            }
            PositionMoveRotation pmr = new PositionMoveRotation(now.pos(), Vec3.ZERO, now.yRot(), now.xRot());
            ClientboundTeleportEntityPacket teleport = ClientboundTeleportEntityPacket.teleport(e.id, pmr, Set.of(), true);
            for (ServerPlayer player : inDim) {
                if (player.distanceToSqr(now.pos.x, now.pos.y, now.pos.z) <= distSq) {
                    player.connection.send(teleport);
                }
            }
            LAST_SENT.put(e.id, now);
        }
    }

    private static void flushMetadataPacketsFromWorker() {
        if (!FakePacketEntities.enabled) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server.isStopped()) {
            return;
        }
        double distSq = FakePacketEntities.broadcastDistanceBlocks * FakePacketEntities.broadcastDistanceBlocks;
        Map<ResourceKey<Level>, List<ServerPlayer>> byDimension = playersByDimension(server);
        for (Entry e : ENTRIES.values()) {
            List<SynchedEntityData.DataValue<?>> pending = e.pendingMeta.getAndSet(null);
            if (pending == null) {
                continue;
            }
            List<ServerPlayer> inDim = byDimension.get(e.dimension);
            if (inDim == null || inDim.isEmpty()) {
                LAST_META.put(e.id, pending);
                continue;
            }
            MotionState at = e.motion.get();
            ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(e.id, pending);
            for (ServerPlayer player : inDim) {
                if (player.distanceToSqr(at.pos.x, at.pos.y, at.pos.z) <= distSq) {
                    player.connection.send(packet);
                }
            }
            LAST_META.put(e.id, pending);
        }
    }

    /** Snapshot-safe: backed by {@link net.minecraft.server.players.PlayerList#players}. */
    private static Map<ResourceKey<Level>, List<ServerPlayer>> playersByDimension(final MinecraftServer server) {
        Map<ResourceKey<Level>, List<ServerPlayer>> map = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().players) {
            if (player.level() instanceof ServerLevel sl) {
                map.computeIfAbsent(sl.dimension(), k -> new ArrayList<>(4)).add(player);
            }
        }
        return map;
    }

    /**
     * Spawn a cosmetic armor stand orbiting a fixed point; main thread only.
     */
    public static @Nullable Integer spawnOrbitingArmorStand(ServerPlayer creator, double radius, double speed) {
        if (!FakePacketEntities.enabled) {
            return null;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (!server.isSameThread()) {
            return null;
        }
        ServerLevel level = creator.level();
        int id = NEXT_ID.getAndDecrement();
        UUID uuid = UUID.randomUUID();
        Vec3 center = creator.position().add(0.0, 1.0, 0.0);
        ResourceKey<Level> dim = level.dimension();
        Entry entry = new Entry(id, uuid, dim, EntityType.ARMOR_STAND, center, radius, speed, creator.getRandom().nextDouble() * Math.PI * 2, false, null);
        MotionState initial = new MotionState(center, creator.getYRot(), creator.getXRot());
        entry.motion.set(initial);
        ENTRIES.put(id, entry);
        LAST_SENT.remove(id);
        LAST_META.put(id, Collections.emptyList());
        broadcastSpawn(server, entry, initial);
        LAST_SENT.put(id, initial);
        return id;
    }

    /**
     * Players-only mode compatibility: convert a blocked real entity to a stationary packet clone (main thread only).
     */
    public static boolean spawnPlayersOnlyEntityAppearance(final ServerLevel level, final Entity entity) {
        if (!FakePacketEntities.enabled) {
            return false;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (!server.isSameThread()) {
            return false;
        }
        List<SynchedEntityData.DataValue<?>> rawData = entity.getEntityData().getNonDefaultValues();
        List<SynchedEntityData.DataValue<?>> meta = rawData == null ? Collections.emptyList() : List.copyOf(rawData);
        Vec3 pos = entity.position();
        float yRot = entity.getYRot();
        float xRot = entity.getXRot();
        EntityType<?> type = entity.getType();
        UUID uuid = entity.getUUID();
        ResourceKey<Level> dim = level.dimension();

        int id = NEXT_ID.getAndDecrement();
        Entry entry = new Entry(id, uuid, dim, type, pos, 0.0, 0.0, 0.0, true, entity);
        MotionState initial = new MotionState(pos, yRot, xRot);
        entry.motion.set(initial);
        ENTRIES.put(id, entry);
        LAST_SENT.remove(id);
        LAST_META.put(id, meta);
        broadcastSpawn(level.getServer(), entry, initial, meta);
        LAST_SENT.put(id, initial);
        return true;
    }

    /**
     * Convert already-registered real entities into packet-only compat entities (legacy hologram migration).
     */
    public static int convertExistingEntities(@Nullable final ServerLevel onlyWorld, final EntityType<?> type) {
        if (!FakePacketEntities.enabled) {
            return 0;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (!server.isSameThread()) {
            return 0;
        }
        List<ServerLevel> worlds = new ArrayList<>();
        if (onlyWorld != null) {
            worlds.add(onlyWorld);
        } else {
            for (ServerLevel level : server.getAllLevels()) {
                worlds.add(level);
            }
        }
        int converted = 0;
        for (ServerLevel world : worlds) {
            List<Entity> snapshot = new ArrayList<>();
            world.getEntities().getAll().forEach(snapshot::add);
            for (Entity entity : snapshot) {
                if (entity.getType() != type || entity instanceof ServerPlayer || entity.isRemoved()) {
                    continue;
                }
                if (convertRegisteredEntityToPacketCompat(world, entity)) {
                    converted++;
                }
            }
        }
        return converted;
    }

    /**
     * Re-materialize packet-only compat entities as real server entities (legacy rollback / plugin recovery).
     */
    public static int unconvertExistingEntities(@Nullable final ServerLevel onlyWorld, final EntityType<?> type) {
        if (!FakePacketEntities.enabled) {
            return 0;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (!server.isSameThread()) {
            return 0;
        }
        List<Entry> snapshot = new ArrayList<>(ENTRIES.values());
        int restored = 0;
        for (Entry e : snapshot) {
            if (e.type != type || e.sourceEntity == null) {
                continue;
            }
            ServerLevel level = server.getLevel(e.dimension);
            if (level == null || (onlyWorld != null && level != onlyWorld)) {
                continue;
            }
            Entity rebuilt = cloneDetachedEntity(e.sourceEntity, level);
            if (rebuilt == null) {
                continue;
            }
            remove(e.id);
            level.addFreshEntity(rebuilt, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
            restored++;
        }
        return restored;
    }

    private static boolean convertRegisteredEntityToPacketCompat(final ServerLevel level, final Entity real) {
        EntityType<?> type = real.getType();
        Entity source = cloneDetachedEntity(real, level);
        if (source == null) {
            return false;
        }

        real.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DISCARD);

        int id = NEXT_ID.getAndDecrement();
        Vec3 pos = source.position();
        ResourceKey<Level> dim = level.dimension();
        Entry entry = new Entry(id, source.getUUID(), dim, type, pos, 0.0, 0.0, 0.0, true, source);
        MotionState initial = new MotionState(pos, source.getYRot(), source.getXRot());
        entry.motion.set(initial);
        ENTRIES.put(id, entry);
        LAST_SENT.remove(id);
        List<SynchedEntityData.DataValue<?>> raw = source.getEntityData().getNonDefaultValues();
        List<SynchedEntityData.DataValue<?>> meta = raw == null ? Collections.emptyList() : List.copyOf(raw);
        LAST_META.put(id, meta);
        broadcastSpawn(level.getServer(), entry, initial, meta);
        LAST_SENT.put(id, initial);
        return true;
    }

    private static @Nullable Entity cloneDetachedEntity(final Entity source, final ServerLevel level) {
        EntityType<?> type = source.getType();
        Entity clone = type.create(level, EntitySpawnReason.COMMAND);
        if (clone == null) {
            return null;
        }
        try (ProblemReporter.ScopedCollector scoped = new ProblemReporter.ScopedCollector(clone.problemPath(), STORE_LOGGER)) {
            TagValueOutput out = TagValueOutput.createWithContext(scoped, source.registryAccess());
            source.saveWithoutId(out);
            CompoundTag nbt = out.buildResult();
            clone.load(TagValueInput.create(scoped, level.registryAccess(), nbt));
            return clone;
        } catch (Exception ex) {
            LeafConfig.LOGGER.warn("Failed to clone detached entity {}", EntityType.getKey(type), ex);
            return null;
        }
    }

    public static boolean remove(int id) {
        if (!FakePacketEntities.enabled) {
            return false;
        }
        Entry removed = ENTRIES.remove(id);
        LAST_SENT.remove(id);
        LAST_META.remove(id);
        if (removed == null) {
            return false;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server.isStopped()) {
            return true;
        }
        ClientboundRemoveEntitiesPacket pkt = new ClientboundRemoveEntitiesPacket(id);
        double distSq = FakePacketEntities.broadcastDistanceBlocks * FakePacketEntities.broadcastDistanceBlocks;
        Vec3 ref = removed.motion.get().pos;
        List<ServerPlayer> inDim = playersByDimension(server).get(removed.dimension);
        if (inDim != null) {
            for (ServerPlayer player : inDim) {
                if (player.distanceToSqr(ref.x, ref.y, ref.z) <= distSq) {
                    player.connection.send(pkt);
                }
            }
        }
        return true;
    }

    private static void broadcastSpawn(MinecraftServer server, Entry e, MotionState state) {
        broadcastSpawn(server, e, state, Collections.emptyList());
    }

    private static void broadcastSpawn(MinecraftServer server, Entry e, MotionState state, List<SynchedEntityData.DataValue<?>> entityData) {
        ClientboundAddEntityPacket add = new ClientboundAddEntityPacket(
            e.id,
            e.uuid,
            state.pos.x,
            state.pos.y,
            state.pos.z,
            state.xRot,
            state.yRot,
            e.type,
            0,
            Vec3.ZERO,
            state.yRot
        );
        ClientboundSetEntityDataPacket meta = new ClientboundSetEntityDataPacket(e.id, entityData);
        ClientboundBundlePacket bundle = new ClientboundBundlePacket(List.of(add, meta));
        double distSq = FakePacketEntities.broadcastDistanceBlocks * FakePacketEntities.broadcastDistanceBlocks;
        List<ServerPlayer> inDim = playersByDimension(server).get(e.dimension);
        if (inDim != null) {
            for (ServerPlayer player : inDim) {
                if (player.distanceToSqr(state.pos.x, state.pos.y, state.pos.z) <= distSq) {
                    player.connection.send(bundle);
                }
            }
        }
    }

    /**
     * {@code /paper entity list}: counts packet-only fakes in {@code world}'s dimension toward the same tallies as server entities.
     * Each fake is treated as non-ticking (not registered in the chunk entity manager).
     */
    public static void forPaperEntityList(
        final ServerLevel world,
        final Set<Identifier> matchingTypes,
        final BiConsumer<Identifier, ChunkPos> sink
    ) {
        if (!FakePacketEntities.enabled || ENTRIES.isEmpty()) {
            return;
        }
        ResourceKey<Level> dim = world.dimension();
        for (Entry e : ENTRIES.values()) {
            if (!e.dimension.equals(dim)) {
                continue;
            }
            Identifier key = EntityType.getKey(e.type);
            if (!matchingTypes.contains(key)) {
                continue;
            }
            Vec3 pos = e.motion.get().pos;
            ChunkPos chunk = new ChunkPos(BlockPos.containing(pos.x, pos.y, pos.z));
            sink.accept(key, chunk);
        }
    }

    public static void forPaperEntityListArmorStandKinds(
        final ServerLevel world,
        final BiConsumer<Boolean, ChunkPos> sink
    ) {
        if (!FakePacketEntities.enabled || ENTRIES.isEmpty()) {
            return;
        }
        final ResourceKey<Level> dim = world.dimension();
        for (Entry e : ENTRIES.values()) {
            if (!e.dimension.equals(dim) || e.type != EntityType.ARMOR_STAND) {
                continue;
            }
            final Vec3 pos = e.motion.get().pos;
            final ChunkPos chunk = new ChunkPos(BlockPos.containing(pos.x, pos.y, pos.z));
            sink.accept(isLikelyHologramArmorStand(e.sourceEntity), chunk);
        }
    }

    public static boolean isLikelyHologramArmorStand(final @Nullable Entity entity) {
        if (!(entity instanceof ArmorStand stand)) {
            return false;
        }
        return stand.isMarker()
            || (stand.isInvisible() && stand.isNoGravity() && stand.getCustomName() != null);
    }

    public static int activeCount() {
        return ENTRIES.size();
    }

    /**
     * Bukkit/API compatibility: resolve converted packet-only entities by UUID for {@code Bukkit.getEntity(uuid)}.
     * Returns a Bukkit handle only for entries backed by a source entity object.
     */
    public static org.bukkit.entity.@Nullable Entity findBukkitEntityByUuid(final @Nullable UUID uuid) {
        if (!FakePacketEntities.enabled || uuid == null || ENTRIES.isEmpty()) {
            return null;
        }
        for (Entry e : ENTRIES.values()) {
            if (!uuid.equals(e.uuid) || e.sourceEntity == null) {
                continue;
            }
            return e.sourceEntity.getBukkitEntity();
        }
        return null;
    }

    record MotionState(Vec3 pos, float yRot, float xRot) {
    }

    static final class Entry {
        final int id;
        final UUID uuid;
        final ResourceKey<Level> dimension;
        final EntityType<?> type;
        final Vec3 orbitCenter;
        final double radius;
        final double phase;
        final double speed;
        /** When true, worker skips kinematics (spawn-egg cosmetic clones). */
        final boolean stationary;
        final @Nullable Entity sourceEntity;
        final AtomicReference<MotionState> motion = new AtomicReference<>();
        final AtomicReference<@Nullable List<SynchedEntityData.DataValue<?>>> pendingMeta = new AtomicReference<>();

        Entry(int id, UUID uuid, ResourceKey<Level> dimension, EntityType<?> type, Vec3 orbitCenter, double radius, double speed, double phase, boolean stationary, @Nullable Entity sourceEntity) {
            this.id = id;
            this.uuid = uuid;
            this.dimension = dimension;
            this.type = type;
            this.orbitCenter = orbitCenter;
            this.radius = radius;
            this.phase = phase;
            this.speed = speed;
            this.stationary = stationary;
            this.sourceEntity = sourceEntity;
        }
    }
}
