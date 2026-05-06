package org.dreeam.leaf.perf;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Player;
import org.dreeam.leaf.config.modules.opt.TileEntityGovernor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class CatchupBossbarService {
    private static BossBar bar;
    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> task;
    private static boolean enabled;

    private CatchupBossbarService() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(final boolean value) {
        enabled = value;
        if (!value) {
            stop();
            return;
        }
        start();
    }

    private static void start() {
        if (bar == null) {
            bar = Bukkit.createBossBar("Catchup", BarColor.YELLOW, BarStyle.SOLID);
        }
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Leaf-CatchupBossbar");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
        }

        task = scheduler.scheduleAtFixedRate(() -> {
            if (!(Bukkit.getServer() instanceof CraftServer craftServer)) {
                return;
            }
            craftServer.getServer().execute(() -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (!bar.getPlayers().contains(player)) {
                        bar.addPlayer(player);
                    }
                }
                for (Player tracked : java.util.List.copyOf(bar.getPlayers())) {
                    if (!tracked.isOnline()) {
                        bar.removePlayer(tracked);
                    }
                }

                final TileEntityGovernorMetrics m = TileEntityGovernor.snapshotMetrics();
                final double ratio = m.skippedRatio();
                final double mspt = getMspt();
                final boolean active = TileEntityGovernor.isActiveForMspt(mspt);
                final String state = active ? "ON" : "OFF";
                bar.setTitle("§6Catchup " + state
                    + " §7| MSPT §f" + String.format(java.util.Locale.ROOT, "%.2f", mspt)
                    + " §7| TE skip §f" + String.format(java.util.Locale.ROOT, "%.1f%%", ratio * 100.0D)
                    + " §7| blocked mobs §f" + m.blockedMobSpawns());
                bar.setProgress(Math.max(0.0D, Math.min(1.0D, ratio)));
                bar.setColor(active ? BarColor.GREEN : BarColor.RED);
            });
        }, 0L, 1L, TimeUnit.SECONDS);
    }

    private static void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (bar != null) {
            if (Bukkit.getServer() instanceof CraftServer craftServer) {
                craftServer.getServer().execute(bar::removeAll);
            } else {
                bar.removeAll();
            }
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private static double getMspt() {
        if (Bukkit.getServer() instanceof org.bukkit.craftbukkit.CraftServer craftServer) {
            return craftServer.getServer().getAverageTickTimeNanos() / 1_000_000.0D;
        }
        return 0.0D;
    }
}
