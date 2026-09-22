package dev.jorel.eracore;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.*;
import java.util.*;

/**
 * HCF presentation + safety layer:
 * - persistent spawn safezones per dimension
 * - 60s combat tag; tagged players cannot enter/teleport into safezones
 * - visible safezone borders
 * - lightweight faction + DTR line above physical players
 */
final class HcfZoneDisplayDirector implements Listener {
    private static final class Zone {
        String world;
        double x,y,z;
        int radius;
    }

    private final EraCore plugin;
    private final WarpManager warps;
    private final File file;
    private final YamlConfiguration data;
    private final Map<String,Zone> zones = new HashMap<String,Zone>();
    private final Map<String,Long> combatTags = new HashMap<String,Long>();
    private final Map<String,Boolean> lastSafe = new HashMap<String,Boolean>();
    private final Map<UUID,ArmorStand> dtrLabels = new HashMap<UUID,ArmorStand>();
    private final Set<UUID> labelIds = new HashSet<UUID>();
    private BukkitTask labelTask;
    private BukkitTask tagTask;

    HcfZoneDisplayDirector(EraCore plugin, WarpManager warps) {
        this.plugin=plugin;
        this.warps=warps;
        this.file=new File(plugin.getDataFolder(),"safezones.yml");
        this.data=YamlConfiguration.loadConfiguration(file);
        load();
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        bootstrapLoadedWorlds();
        cleanupStaleDtrLabels();

        if(plugin.getConfig().getBoolean("presentation.faction-dtr-overhead",false)) {
            labelTask=plugin.getServer().getScheduler().runTaskTimer(plugin,new Runnable() {
                public void run() { updateLabels(); }
            },20L,10L);
        }

        tagTask=plugin.getServer().getScheduler().runTaskTimer(plugin,new Runnable() {
            public void run() { expireTags(); }
        },20L,20L);

        if(plugin.getConfig().getBoolean("safezones.auto-build-borders",false)) {
            new BukkitTaskStarter(plugin,this).startLater(80L);
        }
    }

    void stop() {
        if(labelTask!=null) labelTask.cancel();
        if(tagTask!=null) tagTask.cancel();
        labelTask=null; tagTask=null;
        for(ArmorStand a:dtrLabels.values()) if(a!=null&&!a.isDead()) a.remove();
        dtrLabels.clear();
        labelIds.clear();
        save();
    }

    private static final class BukkitTaskStarter implements Runnable {
        private final EraCore plugin;
        private final HcfZoneDisplayDirector owner;
        BukkitTaskStarter(EraCore plugin,HcfZoneDisplayDirector owner){this.plugin=plugin;this.owner=owner;}
        void startLater(long ticks){plugin.getServer().getScheduler().runTaskLater(plugin,this,ticks);}
        public void run(){owner.buildAllBorders();}
    }

    boolean handleDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if(!(e.getEntity() instanceof Player)) return false;
        Player victim=(Player)e.getEntity();
        Player attacker=attacker(e.getDamager());

        if(isSafe(victim.getLocation()) || (attacker!=null && isSafe(attacker.getLocation()))) {
            e.setCancelled(true);
            return true;
        }

        if(attacker==null || attacker.equals(victim)) return false;
        if(plugin.sameFactionForClasses(attacker.getName(),victim.getName())) {
            e.setCancelled(true);
            return true;
        }

        if(plugin.simWorldProtectionActive()) {
            e.setCancelled(true);
            return true;
        }

        tag(attacker);
        tag(victim);
        return false;
    }

    private Player attacker(Entity damager) {
        if(damager instanceof Player) return (Player)damager;
        if(damager instanceof Projectile) {
            ProjectileSource source=((Projectile)damager).getShooter();
            if(source instanceof Player) return (Player)source;
        }
        return null;
    }

    void tag(Player p) {
        if(p==null || plugin.isOwnerPlayer(p)) return;
        long now=System.currentTimeMillis();
        long until=now+Math.max(5,plugin.getConfig().getInt("safezones.combat-tag-seconds",60))*1000L;
        Long old=combatTags.put(key(p.getName()),until);
        if(old==null || old<=now) {
            p.sendMessage(EraCore.colorText("&cCombat tagged &7for &f"+tagSeconds(p)+"s&7. You cannot enter a Safezone."));
        }
    }

    boolean isTagged(Player p) {
        Long until=combatTags.get(key(p.getName()));
        return until!=null && until>System.currentTimeMillis();
    }

    long tagSeconds(Player p) {
        Long until=combatTags.get(key(p.getName()));
        if(until==null) return 0L;
        return Math.max(0L,(long)Math.ceil((until-System.currentTimeMillis())/1000.0));
    }

    boolean isSafe(Location loc) {
        if(loc==null || loc.getWorld()==null) return false;
        Zone z=zone(loc.getWorld());
        if(z==null) return false;
        return Math.abs(loc.getX()-z.x)<=z.radius && Math.abs(loc.getZ()-z.z)<=z.radius;
    }

    boolean blocksTeleport(Player p,Location to) {
        return p!=null && to!=null && !plugin.isOwnerPlayer(p) && isTagged(p) && isSafe(to) && !isSafe(p.getLocation());
    }

    void syncDimensionZone(Location loc) {
        if(loc==null || loc.getWorld()==null) return;
        Zone z=new Zone();
        z.world=loc.getWorld().getName();
        z.x=loc.getX(); z.y=loc.getY(); z.z=loc.getZ();
        z.radius=defaultRadius(loc.getWorld());
        zones.put(key(z.world),z);
        save();
    }

    void syncMainSpawn(Location loc) {
        if(loc==null || loc.getWorld()==null) return;
        Zone z=zone(loc.getWorld());
        if(z==null) z=new Zone();
        z.world=loc.getWorld().getName();
        z.x=loc.getX(); z.y=loc.getY(); z.z=loc.getZ();
        z.radius=plugin.getConfig().getInt("safezones.overworld-radius",60);
        zones.put(key(z.world),z);
        save();
    }

    boolean command(Player p,String[] args) {
        if(!plugin.isOwnerPlayer(p)) {
            p.sendMessage(EraCore.colorText("&cOwner only."));
            return true;
        }

        if(args.length==0 || args[0].equalsIgnoreCase("info")) {
            Zone z=zone(p.getWorld());
            p.sendMessage(EraCore.colorText("&aSafezone &7world=&f"+p.getWorld().getName()+
                " &7center=&f"+(int)z.x+","+(int)z.y+","+(int)z.z+
                " &7radius=&f"+z.radius+
                " &7tag=&f"+plugin.getConfig().getInt("safezones.combat-tag-seconds",60)+"s"));
            return true;
        }

        if(args[0].equalsIgnoreCase("set")) {
            int r=defaultRadius(p.getWorld());
            if(args.length>=2) {
                try { r=Math.max(16,Math.min(160,Integer.parseInt(args[1]))); }
                catch(Exception ignored){}
            }
            Zone z=new Zone();
            z.world=p.getWorld().getName();
            z.x=p.getLocation().getX();
            z.y=p.getLocation().getY();
            z.z=p.getLocation().getZ();
            z.radius=r;
            zones.put(key(z.world),z);
            p.getWorld().setSpawnLocation((int)Math.floor(z.x),(int)Math.floor(z.y),(int)Math.floor(z.z));
            if(p.getWorld().equals(Bukkit.getWorlds().get(0))) {
                warps.setSpawn(p.getLocation());
                warps.setWarp("spawn",p.getLocation());
            }
            save();
            p.sendMessage(EraCore.colorText("&aSafezone set. &7Use &f/safezone build &7to redraw the visible border."));
            return true;
        }

        if(args[0].equalsIgnoreCase("build")) {
            buildBorder(p.getWorld());
            p.sendMessage(EraCore.colorText("&aSafezone border queued."));
            return true;
        }

        p.sendMessage("/safezone <info|set [radius]|build>");
        return true;
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onMove(PlayerMoveEvent e) {
        if(e.getTo()==null || e.getFrom().getWorld()!=e.getTo().getWorld()) return;
        Player p=e.getPlayer();
        boolean from=isSafe(e.getFrom());
        boolean to=isSafe(e.getTo());

        if(!from && to && isTagged(p) && !plugin.isOwnerPlayer(p)) {
            e.setTo(e.getFrom());
            Vector v=e.getFrom().toVector().subtract(e.getTo().toVector());
            if(v.lengthSquared()<0.01) {
                Zone z=zone(p.getWorld());
                v=p.getLocation().toVector().subtract(new Vector(z.x,p.getLocation().getY(),z.z));
            }
            if(v.lengthSquared()>0.01) p.setVelocity(v.setY(0).normalize().multiply(0.55).setY(0.16));
            p.sendMessage(EraCore.colorText("&cYou are combat tagged for &f"+tagSeconds(p)+"s&c and cannot enter Safezone."));
            return;
        }

        Boolean prior=lastSafe.put(key(p.getName()),to);
        if(prior!=null && prior.booleanValue()!=to) {
            if(to) p.sendMessage(EraCore.colorText("&aEntering Safezone"));
            else p.sendMessage(EraCore.colorText("&cEntering Warzone"));
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onTeleport(PlayerTeleportEvent e) {
        if(blocksTeleport(e.getPlayer(),e.getTo())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(EraCore.colorText("&cYou cannot enter Safezone while combat tagged. &7"+tagSeconds(e.getPlayer())+"s remaining."));
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onLabelDamage(EntityDamageEvent e) {
        if(labelIds.contains(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        combatTags.remove(key(e.getPlayer().getName()));
        lastSafe.remove(key(e.getPlayer().getName()));
    }

    @EventHandler public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        combatTags.remove(key(e.getEntity().getName()));
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        removeLabel(e.getPlayer());
        lastSafe.remove(key(e.getPlayer().getName()));
    }

    @EventHandler public void onWorldChange(PlayerChangedWorldEvent e) {
        removeLabel(e.getPlayer());
        lastSafe.remove(key(e.getPlayer().getName()));
    }

    private void bootstrapLoadedWorlds() {
        for(World w:Bukkit.getWorlds()) {
            if(zones.containsKey(key(w.getName()))) continue;
            Location s;
            if(w.equals(Bukkit.getWorlds().get(0))) s=warps.getSpawn();
            else s=w.getSpawnLocation();
            Zone z=new Zone();
            z.world=w.getName(); z.x=s.getX(); z.y=s.getY(); z.z=s.getZ();
            z.radius=defaultRadius(w);
            zones.put(key(z.world),z);
        }
        save();
    }

    private int defaultRadius(World w) {
        if(w.getEnvironment()==World.Environment.NETHER)
            return plugin.getConfig().getInt("safezones.nether-radius",42);
        if(w.getEnvironment()==World.Environment.THE_END)
            return plugin.getConfig().getInt("safezones.end-radius",42);
        return plugin.getConfig().getInt("safezones.overworld-radius",60);
    }

    private Zone zone(World w) {
        Zone z=zones.get(key(w.getName()));
        if(z!=null) return z;
        Location s=w.getSpawnLocation();
        z=new Zone();
        z.world=w.getName();z.x=s.getX();z.y=s.getY();z.z=s.getZ();z.radius=defaultRadius(w);
        zones.put(key(z.world),z);
        return z;
    }

    private void expireTags() {
        long now=System.currentTimeMillis();
        Iterator<Map.Entry<String,Long>> it=combatTags.entrySet().iterator();
        while(it.hasNext()) if(it.next().getValue()<=now) it.remove();
    }

    private void cleanupStaleDtrLabels() {
        int removed=0;
        for(World w:Bukkit.getWorlds()) {
            for(ArmorStand a:new ArrayList<ArmorStand>(w.getEntitiesByClass(ArmorStand.class))) {
                if(a==null || a.isDead() || !a.isCustomNameVisible()) continue;
                String raw=a.getCustomName();
                if(raw==null || raw.isEmpty()) continue;
                String plain=ChatColor.stripColor(raw);
                if(plain==null) continue;
                String upper=plain.toUpperCase(Locale.ENGLISH);
                if(upper.contains(" DTR]") || upper.contains("[RAIDABLE]")) {
                    a.remove();
                    removed++;
                }
            }
        }
        if(removed>0) plugin.getLogger().info("Removed "+removed+" stale faction/DTR overhead labels.");
    }

    private void updateLabels() {
        Set<UUID> seen=new HashSet<UUID>();
        for(Player p:Bukkit.getOnlinePlayers()) {
            String line=plugin.factionDtrDisplay(p.getName());
            if(line==null || line.isEmpty()) {
                removeLabel(p);
                continue;
            }
            seen.add(p.getUniqueId());
            ArmorStand stand=dtrLabels.get(p.getUniqueId());
            if(stand==null || stand.isDead() || !stand.getWorld().equals(p.getWorld())) {
                removeLabel(p);
                try {
                    stand=p.getWorld().spawn(p.getLocation().clone().add(0,2.45,0),ArmorStand.class);
                    stand.setVisible(false);
                    stand.setGravity(false);
                    stand.setSmall(true);
                    stand.setBasePlate(false);
                    stand.setCustomNameVisible(true);
                    dtrLabels.put(p.getUniqueId(),stand);
                    labelIds.add(stand.getUniqueId());
                } catch(Throwable t) {
                    plugin.getLogger().warning("Could not create faction DTR label: "+t.getMessage());
                    continue;
                }
            }
            stand.setCustomName(EraCore.colorText(line));
            Location loc=p.getLocation().clone().add(0,2.45,0);
            stand.teleport(loc);
        }

        for(UUID id:new ArrayList<UUID>(dtrLabels.keySet())) {
            if(!seen.contains(id)) {
                ArmorStand a=dtrLabels.remove(id);
                if(a!=null) {labelIds.remove(a.getUniqueId()); if(!a.isDead())a.remove();}
            }
        }
    }

    private void removeLabel(Player p) {
        ArmorStand a=dtrLabels.remove(p.getUniqueId());
        if(a!=null) {
            labelIds.remove(a.getUniqueId());
            if(!a.isDead()) a.remove();
        }
    }

    private void buildAllBorders() {
        for(World w:Bukkit.getWorlds()) buildBorder(w);
    }

    private void buildBorder(final World w) {
        final Zone z=zone(w);
        final ArrayDeque<int[]> q=new ArrayDeque<int[]>();
        int cx=(int)Math.floor(z.x), cz=(int)Math.floor(z.z), r=z.radius;
        for(int x=cx-r;x<=cx+r;x++) {
            q.add(new int[]{x,cz-r});
            q.add(new int[]{x,cz+r});
        }
        for(int zz=cz-r+1;zz<cz+r;zz++) {
            q.add(new int[]{cx-r,zz});
            q.add(new int[]{cx+r,zz});
        }

        new org.bukkit.scheduler.BukkitRunnable() {
            public void run() {
                int n=0;
                while(!q.isEmpty() && n++<24) {
                    int[] pos=q.removeFirst();
                    int chunkX=pos[0] >> 4, chunkZ=pos[1] >> 4;
                    if(!w.isChunkLoaded(chunkX,chunkZ)) continue;
                    int y=Math.max(1,w.getHighestBlockYAt(pos[0],pos[1]));
                    Block b=w.getBlockAt(pos[0],y,pos[1]);
                    if(b.getType()==Material.AIR) b=w.getBlockAt(pos[0],Math.max(1,y-1),pos[1]);
                    int dx=Math.abs(pos[0]-(int)Math.floor(z.x));
                    int dz=Math.abs(pos[1]-(int)Math.floor(z.z));
                    if((dx+dz)%12==0) {
                        b.setType(Material.GLOWSTONE);
                    } else {
                        b.setType(Material.STAINED_CLAY);
                        b.setData((byte)5);
                    }
                }
                if(q.isEmpty()) cancel();
            }
        }.runTaskTimer(plugin,1L,1L);
    }

    private void load() {
        if(data.isConfigurationSection("zones")) {
            for(String k:data.getConfigurationSection("zones").getKeys(false)) {
                String b="zones."+k;
                Zone z=new Zone();
                z.world=data.getString(b+".world",k);
                z.x=data.getDouble(b+".x");
                z.y=data.getDouble(b+".y",64);
                z.z=data.getDouble(b+".z");
                z.radius=data.getInt(b+".radius",60);
                zones.put(key(z.world),z);
            }
        }
    }

    private void save() {
        data.set("zones",null);
        for(Zone z:zones.values()) {
            String b="zones."+key(z.world);
            data.set(b+".world",z.world);
            data.set(b+".x",z.x);
            data.set(b+".y",z.y);
            data.set(b+".z",z.z);
            data.set(b+".radius",z.radius);
        }
        try {data.save(file);}
        catch(IOException e){plugin.getLogger().warning("Could not save safezones.yml: "+e.getMessage());}
    }

    private String key(String s){return s==null?"":s.toLowerCase(Locale.ENGLISH);}
}
