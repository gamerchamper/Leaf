package org.dreeam.leaf.fakeentity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.dreeam.leaf.config.modules.gameplay.FakePacketEntities;
import org.jetbrains.annotations.Nullable;

/**
 * Bridges {@code gameplay-mechanisms.players-only-mode} with {@code fake-packet-entities} so blocked spawns
 * (spawn eggs, {@code /summon}, plugin hologram armor stands, etc.) can still produce client-visible packet stand-ins.
 */
public final class FakePacketPlayersOnlyHooks {

    private FakePacketPlayersOnlyHooks() {
    }

    public static boolean maybeSpawnFakeEggMob(
        final ServerLevel level,
        final Entity entity,
        final @Nullable CreatureSpawnEvent.SpawnReason spawnReason
    ) {
        if (!FakePacketEntities.enabled) {
            return false;
        }
        if (!supportsPacketCompat(entity)) {
            return false;
        }
        if (entity instanceof net.minecraft.server.level.ServerPlayer) {
            return false;
        }
        if (!allowsPlayersOnlyFakeVisual(spawnReason)) {
            return false;
        }
        return FakePacketEntityEngine.spawnPlayersOnlyEntityAppearance(level, entity);
    }

    public static boolean maybeSpawnFakeOutsidePlayersOnly(
        final ServerLevel level,
        final Entity entity,
        final @Nullable CreatureSpawnEvent.SpawnReason spawnReason
    ) {
        if (!FakePacketEntities.enabled || !FakePacketEntities.convertWhitelistedEvenWithoutPlayersOnly) {
            return false;
        }
        if (!supportsPacketCompat(entity)) {
            return false;
        }
        if (entity instanceof net.minecraft.server.level.ServerPlayer) {
            return false;
        }
        // Reuse same spawn-reason guard so operators can keep natural ecosystem untouched.
        if (!allowsCompatibilityReason(spawnReason)) {
            return false;
        }
        return FakePacketEntityEngine.spawnPlayersOnlyEntityAppearance(level, entity);
    }

    private static boolean supportsPacketCompat(final Entity entity) {
        EntityType<?> type = entity.getType();
        boolean builtinSupported = entity instanceof LivingEntity
            || (type == EntityType.ARMOR_STAND && shouldConvertArmorStand(entity))
            || type == EntityType.TEXT_DISPLAY
            || type == EntityType.ITEM_DISPLAY
            || type == EntityType.BLOCK_DISPLAY
            || type == EntityType.INTERACTION
            || type == EntityType.MARKER;
        if (!builtinSupported) {
            return false;
        }
        return FakePacketEntities.isTypeAllowed(EntityType.getKey(type).toString());
    }

    private static boolean shouldConvertArmorStand(final Entity entity) {
        if (entity.getType() != EntityType.ARMOR_STAND) {
            return false;
        }
        if (!FakePacketEntities.armorStandHologramHeuristicOnly) {
            return true;
        }
        return FakePacketEntityEngine.isLikelyHologramArmorStand(entity);
    }

    private static boolean allowsPlayersOnlyFakeVisual(final @Nullable CreatureSpawnEvent.SpawnReason spawnReason) {
        if (FakePacketEntities.playersOnlyCompatibilityLayer) {
            return allowsCompatibilityReason(spawnReason);
        }
        if (spawnReason == null) {
            return false;
        }
        if (spawnReason == CreatureSpawnEvent.SpawnReason.EGG
            || spawnReason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
            || spawnReason == CreatureSpawnEvent.SpawnReason.DISPENSE_EGG) {
            return FakePacketEntities.playersOnlyEggFakes;
        }
        if (spawnReason == CreatureSpawnEvent.SpawnReason.COMMAND
            || spawnReason == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            return FakePacketEntities.playersOnlySummonFakes;
        }
        return false;
    }

    private static boolean allowsCompatibilityReason(final @Nullable CreatureSpawnEvent.SpawnReason spawnReason) {
        if (spawnReason == null) {
            return false;
        }
        if (!FakePacketEntities.playersOnlyCompatibilityPluginSpawnsOnly) {
            return true;
        }
        return switch (spawnReason) {
            // plugin/manual and command-driven reasons
            case CUSTOM, DEFAULT, COMMAND -> true;
            // explicit toggles should still gate egg/summon compatibility conversions
            case EGG, SPAWNER_EGG, DISPENSE_EGG -> FakePacketEntities.playersOnlyEggFakes;
            default -> false;
        };
    }
}
