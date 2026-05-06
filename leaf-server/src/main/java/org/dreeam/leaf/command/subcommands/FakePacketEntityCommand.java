package org.dreeam.leaf.command.subcommands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerPlayer;
import org.dreeam.leaf.command.LeafCommand;
import org.dreeam.leaf.command.PermissionedLeafSubcommand;
import org.dreeam.leaf.config.modules.gameplay.FakePacketEntities;
import org.dreeam.leaf.fakeentity.FakePacketEntityEngine;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.permissions.PermissionDefault;

public final class FakePacketEntityCommand extends PermissionedLeafSubcommand {

    public static final String LITERAL_ARGUMENT = "fakepacketentity";
    public static final String PERM = LeafCommand.BASE_PERM + "." + LITERAL_ARGUMENT;

    public FakePacketEntityCommand() {
        super(PERM, PermissionDefault.OP);
    }

    @Override
    public boolean execute(final CommandSender sender, final String subCommand, final String[] args) {
        if (!FakePacketEntities.enabled) {
            sender.sendMessage(Component.text("Enable gameplay-mechanisms.fake-packet-entities.enabled in Leaf config and restart.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /leaf fakepacketentity <spawn|remove|list> ...", NamedTextColor.GRAY));
            return true;
        }
        try {
            return dispatch(sender, args);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.text("Invalid number.", NamedTextColor.RED));
            return true;
        }
    }

    private boolean dispatch(CommandSender sender, String[] args) {
        String op = args[0].toLowerCase();
        switch (op) {
            case "spawn" -> {
                if (!(sender instanceof CraftPlayer craftPlayer)) {
                    sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
                    return true;
                }
                ServerPlayer player = craftPlayer.getHandle();
                double radius = 1.5;
                double speed = 1.0;
                if (args.length >= 2) {
                    radius = Double.parseDouble(args[1]);
                }
                if (args.length >= 3) {
                    speed = Double.parseDouble(args[2]);
                }
                if (radius <= 0 || speed <= 0) {
                    sender.sendMessage(Component.text("radius and speed must be positive.", NamedTextColor.RED));
                    return true;
                }
                Integer id = FakePacketEntityEngine.spawnOrbitingArmorStand(player, radius, speed);
                if (id == null) {
                    sender.sendMessage(Component.text("Could not spawn (must run on main thread).", NamedTextColor.RED));
                    return true;
                }
                sender.sendMessage(Component.text("Spawned fake armor stand id=" + id + " (orbit radius=" + radius + ", speed=" + speed + ").", NamedTextColor.GREEN));
            }
            case "remove" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /leaf fakepacketentity remove <id>", NamedTextColor.GRAY));
                    return true;
                }
                int rid = Integer.parseInt(args[1]);
                boolean ok = FakePacketEntityEngine.remove(rid);
                sender.sendMessage(Component.text(ok ? "Removed " + rid : "Unknown id " + rid, ok ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            case "list" -> sender.sendMessage(Component.text("Active fake packet entities: " + FakePacketEntityEngine.activeCount(), NamedTextColor.YELLOW));
            default -> sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        }
        return true;
    }
}
