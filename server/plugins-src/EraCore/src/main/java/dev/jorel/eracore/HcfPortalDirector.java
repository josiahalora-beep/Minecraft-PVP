package dev.jorel.eracore;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Faction-built physical portals are the fast route.  They land at the server's
 * configured Nether/End hubs while command warps retain a warmup.
 */
final class HcfPortalDirector implements Listener {
    private final WarpManager warps;

    HcfPortalDirector(WarpManager warps) {
        this.warps=warps;
    }

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void onPortal(PlayerPortalEvent e) {
        if(e.getFrom()==null || e.getFrom().getWorld()==null) return;
        PlayerTeleportEvent.TeleportCause cause=e.getCause();

        Location target=null;
        World.Environment env=e.getFrom().getWorld().getEnvironment();

        if(cause==PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            target=env==World.Environment.NETHER?warps.getSpawn():warps.getWarp("nether");
        } else if(cause==PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            target=env==World.Environment.THE_END?warps.getSpawn():warps.getWarp("end");
        }

        if(target!=null) e.setTo(target);
    }
}
