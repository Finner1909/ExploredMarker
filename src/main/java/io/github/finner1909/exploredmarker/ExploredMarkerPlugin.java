package io.github.finner1909.exploredmarker;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Marks the ground a player can SEE as explored, so a live map draws it.
 *
 * <h2>The problem it solves</h2>
 *
 * BlueMap (and any renderer with an equivalent option) can be told to render
 * only chunks whose {@code inhabitedTime} exceeds {@code min-inhabited-time}.
 * Setting that above 0 is how you keep unvisited, pre-generated world off a
 * public map. That behaviour is correct and worth keeping.
 *
 * <p>The catch is that {@code inhabitedTime} does not mean "a player has seen
 * this". In {@code ServerChunkCache.tickChunks}, {@code incrementInhabitedTime}
 * is called only for chunks in the ticking list, and ticking status is governed
 * by {@code simulation-distance} — NOT {@code view-distance}. With a typical
 * tuning of {@code simulation-distance: 6} and {@code view-distance: 20}, a
 * player sees 640 blocks across while only a ~192-block-wide corridor ever
 * accrues inhabited time. Everything else stays black at every zoom level, and
 * no amount of re-rendering fixes it, because the renderer is behaving exactly
 * as configured.
 *
 * <h2>What it does</h2>
 *
 * Every {@link #PERIOD_TICKS} it stamps {@code inhabitedTime = 1} on LOADED
 * chunks within the server's view distance of each online player, but only
 * where it is still 0. One tick of "somebody has been near here" is all the
 * renderer needs, and it is negligible against the millions of ticks that drive
 * local difficulty.
 *
 * <h2>Why it is safe</h2>
 *
 * <ul>
 *   <li><b>Loaded chunks only.</b> It never calls {@code getChunkAt} on an
 *       unloaded chunk, so it cannot generate or load terrain. Ground no player
 *       has been near keeps {@code inhabitedTime} 0 and can never reach the map.
 *       The privacy guarantee of {@code min-inhabited-time} is preserved; this
 *       widens the definition of "visited" from the ticking radius to the view
 *       radius, it does not remove it.</li>
 *   <li><b>Write-once.</b> Chunks already above 0 are skipped, so a stationary
 *       player costs one comparison per chunk and no writes.</li>
 *   <li><b>Never lowers a value</b>, so real play time cannot be erased.</li>
 * </ul>
 *
 * It does NOT backfill: ground explored before this is installed stays black
 * until somebody goes near it again. Retrofitting would mean loading those
 * chunks, which is the one thing this refuses to do.
 */
public final class ExploredMarkerPlugin extends JavaPlugin {

    /** 10 seconds. A player crossing chunks faster than this is in a boat or an
     *  elytra, and the next pass catches up - the radius is far wider than the gap. */
    private static final long PERIOD_TICKS = 200L;

    private BukkitTask task;

    @Override
    public void onEnable() {
        int radius = Bukkit.getViewDistance();
        if (radius <= 0) {
            getLogger().warning("view-distance reports " + radius + " - not marking anything");
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(this, this::pass, PERIOD_TICKS, PERIOD_TICKS);
        getLogger().info("Explored-area marking active - the map will cover what players "
                + "can see (" + radius + " chunks)");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** One sweep over everyone online. Main thread: Bukkit chunk access requires it. */
    private void pass() {
        int radius = Bukkit.getViewDistance();
        if (radius <= 0) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            mark(p, radius);
        }
    }

    private void mark(Player player, int radius) {
        World world = player.getWorld();
        Chunk origin = player.getLocation().getChunk();
        int cx = origin.getX(), cz = origin.getZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = cx + dx, z = cz + dz;
                // isChunkLoaded FIRST. This is the guard that stops us loading or
                // generating anything: if the player cannot see it, it is not
                // explored and must not reach the map.
                if (!world.isChunkLoaded(x, z)) continue;
                try {
                    Chunk c = world.getChunkAt(x, z);   // safe: already loaded
                    if (c.getInhabitedTime() <= 0) {
                        c.setInhabitedTime(1L);
                    }
                } catch (Exception e) {
                    // A chunk unloading underneath us is normal, not an error.
                }
            }
        }
    }
}
