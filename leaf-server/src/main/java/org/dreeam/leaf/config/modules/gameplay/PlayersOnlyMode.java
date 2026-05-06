package org.dreeam.leaf.config.modules.gameplay;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

/**
 * When enabled, only server players may be added to worlds.
 * All other entities (mobs, items, projectiles, vehicles, etc.) are rejected at registration time.
 * Vanilla gameplay that depends on non-player entities will not work.
 */
public class PlayersOnlyMode extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.GAMEPLAY.getBaseKeyName() + ".players-only-mode";
    }

    public static boolean enabled = false;

    @Override
    public void onLoaded() {
        enabled = config.getBoolean(getBasePath(), enabled, config.pickStringRegionBased(
            """
                When true, the server refuses to register any entity except players (ServerPlayer).
                Blocks chunk-loaded entities, mob/item spawns, projectiles, and drops. Players still join normally.
                Requires restart after toggling for predictable behavior.""",
            """
                启用后，除玩家（ServerPlayer）外任何实体都不会被注册到世界中。
                会阻止从区块加载的实体、生物/物品生成、弹射物与掉落物；玩家仍可正常加入。
                切换后建议重启服务器以获得一致行为。"""
        ));
    }
}
