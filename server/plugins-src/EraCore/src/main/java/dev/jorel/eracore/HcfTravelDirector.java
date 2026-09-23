package dev.jorel.eracore;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HCF-style delayed command travel. Normal players use this for /f home and
 * /f stuck; owner-only diagnostics may also route through it. Warmups are
 * cancelled by meaningful movement or damage so commands cannot become an
 * instant combat escape. Physical portals remain the dimension route.
 */
final class HcfTravelDirector implements Listener {
    static final class Pending {
        UUID id;
        Location start;
        Location target;
        String label;
        long finishAt;
        BukkitTask task;
    }

    private final EraCore plugin;
    private final Map<UUID,Pending> pending=new HashMap<UUID,Pending>();

    HcfTravelDirector(EraCore plugin) {
        this.plugin=plugin;
    }

    boolean request(final Player p,final Location target,String label) {
        return request(p,target,label,Math.max(1,plugin.getConfig().getInt("travel.warmup-seconds",10)));
    }

    boolean request(final Player p,final Location target,String label,int requestedSeconds) {
        if(p==null || target==null || target.getWorld()==null) return false;
        cancel(p,false);

        final int seconds=Math.max(1,requestedSeconds);
        final Pending q=new Pending();
        q.id=p.getUniqueId();
        q.start=p.getLocation().clone();
        q.target=target.clone();
        q.label=label==null?"destination":label;
        q.finishAt=System.currentTimeMillis()+seconds*1000L;
        pending.put(q.id,q);

        p.sendMessage(EraCore.colorText("&eTeleporting to &f"+q.label+"&e in &f"+seconds+
            "s&e. Do not move or take damage."));

        q.task=new BukkitRunnable() {
            public void run() {
                Pending live=pending.get(q.id);
                if(live!=q || !p.isOnline()) {
                    cancel();
                    return;
                }
                long left=q.finishAt-System.currentTimeMillis();
                if(left<=0L) {
                    pending.remove(q.id);
                    p.leaveVehicle();
                    p.setFallDistance(0f);
                    p.teleport(q.target);
                    p.sendMessage(EraCore.colorText("&7Teleported to &f"+q.label+"&7."));
                    cancel();
                }
            }
        }.runTaskTimer(plugin,20L,10L);
        return true;
    }

    boolean isPending(Player p) {
        return p!=null && pending.containsKey(p.getUniqueId());
    }

    void cancel(Player p,boolean tell) {
        if(p==null) return;
        Pending q=pending.remove(p.getUniqueId());
        if(q==null) return;
        if(q.task!=null) q.task.cancel();
        if(tell && p.isOnline()) p.sendMessage(EraCore.colorText("&cTeleport cancelled."));
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onMove(PlayerMoveEvent e) {
        Pending q=pending.get(e.getPlayer().getUniqueId());
        if(q==null || e.getTo()==null) return;
        Location to=e.getTo();
        if(to.getWorld()!=q.start.getWorld() ||
           Math.abs(to.getX()-q.start.getX())>0.18 ||
           Math.abs(to.getY()-q.start.getY())>0.18 ||
           Math.abs(to.getZ()-q.start.getZ())>0.18) {
            cancel(e.getPlayer(),true);
        }
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onDamage(EntityDamageEvent e) {
        Entity entity=e.getEntity();
        if(entity instanceof Player) cancel((Player)entity,true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancel(e.getPlayer(),false);
    }
}
