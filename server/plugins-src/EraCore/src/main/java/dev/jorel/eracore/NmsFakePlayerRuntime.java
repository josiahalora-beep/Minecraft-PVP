package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Experimental Spigot 1.8.8 combat-body runtime.
 *
 * This class intentionally uses reflection so EraCore still compiles in CI
 * against spigot-api only. Production use stays disabled until the one-body
 * PlayerDeathEvent -> existing EraCore DTR authority probe passes locally.
 */
final class NmsFakePlayerRuntime implements Listener {
    static final class BodySnapshot {
        final String name;
        final UUID uuid;
        final Location location;
        final double health;
        final boolean deathEventSeen;
        BodySnapshot(String name,UUID uuid,Location location,double health,boolean deathEventSeen) {
            this.name=name;this.uuid=uuid;this.location=location==null?null:location.clone();
            this.health=health;this.deathEventSeen=deathEventSeen;
        }
    }

    static final class DropProbeSnapshot {
        final String marker;
        final int expectedStacks;
        final int eventStacks;
        final int worldStacks;
        final boolean deathEventSeen;
        final boolean worldScanComplete;

        DropProbeSnapshot(String marker,int expectedStacks,int eventStacks,int worldStacks,
                          boolean deathEventSeen,boolean worldScanComplete) {
            this.marker=marker;this.expectedStacks=expectedStacks;
            this.eventStacks=eventStacks;this.worldStacks=worldStacks;
            this.deathEventSeen=deathEventSeen;this.worldScanComplete=worldScanComplete;
        }

        boolean pass() {
            return deathEventSeen && worldScanComplete &&
                eventStacks>=expectedStacks && worldStacks>=expectedStacks;
        }

        String summary() {
            return "eventDrops="+eventStacks+"/"+expectedStacks+
                " worldDrops="+worldStacks+"/"+expectedStacks+
                " deathEvent="+deathEventSeen+
                " scanComplete="+worldScanComplete;
        }
    }

    private static final class DropProbe {
        String actor;
        String marker;
        int expectedStacks=2;
        int eventStacks;
        int worldStacks;
        boolean deathEventSeen;
        boolean worldScanComplete;
        Location deathLocation;
    }

    private static final class PendingPearl {
        String actor;
        Location launch;
        double healthAtLaunch;
    }

    private static final class PendingKohiHit {
        UUID victim;
        Vector preMotion;
        Vector kohiMotion;
        long createdAt;
    }

    private static final class Body {
        String name;
        UUID uuid;
        Object handle;
        Object worldHandle;
        Object networkManager;
        Object playerConnection;
        Player bukkit;
        boolean deathEventSeen;
        long spawnedAt;
        int kohiVelocityApplications;
        double lastKohiHorizontal;
        double lastKohiVertical;
        boolean lastKohiMotionApplied;
    }

    private static final class DormantState {
        String name;
        String world;
        double x,y,z;
        float yaw,pitch;
        double health=20.0;
        int food=20;
        int heldSlot=0;
        ItemStack[] contents=new ItemStack[36];
        ItemStack[] armor=new ItemStack[4];

        Location location() {
            org.bukkit.World w=Bukkit.getWorld(world);
            return w==null?null:new Location(w,x,y,z,yaw,pitch);
        }
    }

    private final EraCore plugin;
    private final Map<String,Body> bodies=new LinkedHashMap<String,Body>();
    private final Map<String,DropProbe> dropProbes=new LinkedHashMap<String,DropProbe>();
    private final Map<UUID,PendingPearl> pendingPearls=new LinkedHashMap<UUID,PendingPearl>();
    private final Map<UUID,PendingKohiHit> pendingKohiHits=new LinkedHashMap<UUID,PendingKohiHit>();
    private final Map<String,DormantState> dormantStates=new LinkedHashMap<String,DormantState>();
    private final File dormantFile;
    private final YamlConfiguration dormantData;

    NmsFakePlayerRuntime(EraCore plugin){
        this.plugin=plugin;
        if(!plugin.getDataFolder().isDirectory()) plugin.getDataFolder().mkdirs();
        dormantFile=new File(plugin.getDataFolder(),"combatbody-state.yml");
        dormantData=YamlConfiguration.loadConfiguration(dormantFile);
        loadDormantStates();
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("actors.fake-player.enabled",false);
    }

    boolean probeAllowed() {
        return plugin.getConfig().getBoolean("actors.fake-player.allow-probe",true);
    }

    boolean supported() {
        return Bukkit.getServer().getClass().getPackage().getName().endsWith(".v1_8_R3");
    }

    String supportSummary() {
        return "server="+Bukkit.getServer().getClass().getPackage().getName()+
            " v1_8_R3="+supported()+
            " enabled="+enabled()+
            " probe="+probeAllowed()+
            " bodies="+bodies.size();
    }

    boolean hasBody(String name){return bodies.containsKey(key(name));}

    Player player(String name) {
        Body b=bodies.get(key(name));
        return b==null?null:b.bukkit;
    }

    int combatKohiVelocityApplications(String name) {
        Body b=bodies.get(key(name));
        return b==null?0:b.kohiVelocityApplications;
    }

    double combatLastKohiHorizontal(String name) {
        Body b=bodies.get(key(name));
        return b==null?0.0:b.lastKohiHorizontal;
    }

    double combatLastKohiVertical(String name) {
        Body b=bodies.get(key(name));
        return b==null?0.0:b.lastKohiVertical;
    }

    boolean combatLastKohiMotionApplied(String name) {
        Body b=bodies.get(key(name));
        return b!=null && b.lastKohiMotionApplied;
    }

    BodySnapshot snapshot(String name) {
        Body b=bodies.get(key(name));
        if(b==null) return null;
        Location loc=null;
        double health=0.0;
        try {loc=b.bukkit.getLocation();} catch(Exception ignored){}
        try {health=b.bukkit.getHealth();} catch(Exception ignored){}
        return new BodySnapshot(b.name,b.uuid,loc,health,b.deathEventSeen);
    }

    Player spawn(String requested,Location at,boolean probe) throws Exception {
        if(!supported()) throw new IllegalStateException("CombatBody requires Spigot/CraftBukkit v1_8_R3.");
        if(!enabled() && !(probe && probeAllowed()))
            throw new IllegalStateException("actors.fake-player.enabled=false (probe mode remains separately gated).");
        if(requested==null || requested.trim().isEmpty()) throw new IllegalArgumentException("Missing actor name.");
        String name=requested.trim();
        if(name.length()>16) throw new IllegalArgumentException("1.8 player names are limited to 16 characters.");
        if(at==null || at.getWorld()==null) throw new IllegalArgumentException("Missing spawn world/location.");
        if(hasBody(name)) return bodies.get(key(name)).bukkit;

        int maxBodies=probe?Math.max(1,Math.min(32,
            plugin.getConfig().getInt("actors.fake-player.probe-max-bodies",2))):
            Math.max(1,Math.min(64,
            plugin.getConfig().getInt("actors.fake-player.max-bodies",8)));
        if(bodies.size()>=maxBodies)
            throw new IllegalStateException("CombatBody cap reached ("+maxBodies+").");

        Player connected=Bukkit.getPlayerExact(name);
        if(connected!=null) throw new IllegalStateException(name+" already has a connected Minecraft client.");

        DormantState dormant=probe?null:dormantStates.get(key(name));
        Location spawnAt=at;
        if(dormant!=null) {
            Location remembered=dormant.location();
            if(remembered!=null) spawnAt=remembered;
        }

        Class<?> craftServer=Class.forName("org.bukkit.craftbukkit.v1_8_R3.CraftServer");
        Class<?> craftWorld=Class.forName("org.bukkit.craftbukkit.v1_8_R3.CraftWorld");
        Class<?> gameProfile=Class.forName("com.mojang.authlib.GameProfile");
        Class<?> pim=Class.forName("net.minecraft.server.v1_8_R3.PlayerInteractManager");
        Class<?> entityPlayer=Class.forName("net.minecraft.server.v1_8_R3.EntityPlayer");
        Class<?> networkManager=Class.forName("net.minecraft.server.v1_8_R3.NetworkManager");
        Class<?> direction=Class.forName("net.minecraft.server.v1_8_R3.EnumProtocolDirection");
        Class<?> playerConnection=Class.forName("net.minecraft.server.v1_8_R3.PlayerConnection");

        Object mcServer=invoke(craftServer.cast(Bukkit.getServer()),"getServer");
        Object worldServer=invoke(craftWorld.cast(spawnAt.getWorld()),"getHandle");
        UUID uuid=ActorDirectory.stableOfflineUuid(name);
        Object profile=newInstance(gameProfile,uuid,name);
        applyPrestigeCape(profile,name,uuid);
        Object interact=newInstance(pim,worldServer);
        Object ep=newInstance(entityPlayer,mcServer,worldServer,profile,interact);

        // A real EntityPlayer expects playerConnection to exist in several tick,
        // damage and message paths. For the probe we give it a disconnected
        // NetworkManager sink instead of leaving the field null.
        @SuppressWarnings({"rawtypes","unchecked"})
        Object serverbound=Enum.valueOf((Class)direction.asSubclass(Enum.class),"SERVERBOUND");
        Object nm=newInstance(networkManager,serverbound);
        Object pc=newInstance(playerConnection,mcServer,nm,ep);

        invoke(ep,"setPositionRotation",
            spawnAt.getX(),spawnAt.getY(),spawnAt.getZ(),spawnAt.getYaw(),spawnAt.getPitch());

        // EntityPlayer starts with login/spawn invulnerability. A CombatBody is
        // not a newly logged-in player: it is the physical materialization of an
        // already-active simulated identity. Keeping fresh-login immunity here
        // makes the first several seconds of PvP/damage fake and prevented the
        // normal PlayerDeathEvent/drop pipeline from being exercised at all.
        setIntField(ep,"invulnerableTicks",0);
        setIntField(ep,"ping",Math.max(0,Math.min(999,
            plugin.getConfig().getInt("actors.fake-player.default-ping",55))));

        Object added=invoke(worldServer,"addEntity",ep);
        if(added instanceof Boolean && !((Boolean)added))
            throw new IllegalStateException("WorldServer.addEntity rejected fake player.");

        Object wrapper=invoke(ep,"getBukkitEntity");
        if(!(wrapper instanceof Player)) {
            try {invoke(worldServer,"removeEntity",ep);} catch(Exception ignored){}
            throw new IllegalStateException("NMS EntityPlayer did not expose a Bukkit Player wrapper.");
        }

        Body b=new Body();
        b.name=name;b.uuid=uuid;b.handle=ep;b.worldHandle=worldServer;
        b.networkManager=nm;b.playerConnection=pc;b.bukkit=(Player)wrapper;
        b.spawnedAt=System.currentTimeMillis();
        bodies.put(key(name),b);

        if(dormant!=null) restoreDormantState(b,dormant);
        else {
            try {b.bukkit.setHealth(20.0);} catch(Exception ignored){}
            try {b.bukkit.setFoodLevel(20);} catch(Exception ignored){}
        }
        showToOnlinePlayers(b);
        plugin.getLogger().info("[CombatBody] spawned "+name+" uuid="+uuid+
            " at "+spawnAt.getWorld().getName()+" "+spawnAt.getBlockX()+","+spawnAt.getBlockY()+","+spawnAt.getBlockZ()+
            (dormant==null?"":" restored=true"));
        return b.bukkit;
    }

    boolean prepareDropProbe(String name) {
        Body b=bodies.get(key(name));
        if(b==null || b.bukkit==null) return false;

        String marker="GATE2-"+Long.toHexString(System.nanoTime())+"-"+Integer.toHexString(name.hashCode());
        PlayerInventory inv=b.bukkit.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);

        ItemStack sword=marked(new ItemStack(Material.DIAMOND_SWORD,1),marker+"-SWORD");
        ItemStack pearls=marked(new ItemStack(Material.ENDER_PEARL,3),marker+"-PEARLS");
        inv.setItem(0,sword);
        inv.setItem(1,pearls);
        try { b.bukkit.updateInventory(); } catch(Throwable ignored){}

        DropProbe probe=new DropProbe();
        probe.actor=b.name;
        probe.marker=marker;
        dropProbes.put(key(name),probe);
        plugin.getLogger().info("[CombatBody Gate2] prepared actor="+b.name+
            " marker="+marker+" expectedStacks="+probe.expectedStacks);
        return true;
    }

    DropProbeSnapshot dropProbeSnapshot(String name) {
        DropProbe p=dropProbes.get(key(name));
        if(p==null) return null;
        return new DropProbeSnapshot(p.marker,p.expectedStacks,p.eventStacks,p.worldStacks,
            p.deathEventSeen,p.worldScanComplete);
    }

    private ItemStack marked(ItemStack stack,String label) {
        ItemMeta meta=stack.getItemMeta();
        if(meta!=null) {
            meta.setDisplayName(label);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private boolean hasMarker(ItemStack stack,String marker) {
        if(stack==null || marker==null || !stack.hasItemMeta()) return false;
        ItemMeta meta=stack.getItemMeta();
        return meta!=null && meta.hasDisplayName() && meta.getDisplayName().startsWith(marker);
    }

    private int countMarked(List<ItemStack> drops,String marker) {
        int n=0;
        if(drops==null) return 0;
        for(ItemStack stack:drops) if(hasMarker(stack,marker)) n++;
        return n;
    }

    private void scanWorldDrops(final DropProbe probe) {
        if(probe==null) return;
        Location at=probe.deathLocation;
        if(at==null || at.getWorld()==null) {
            probe.worldScanComplete=true;
            probe.worldStacks=0;
            return;
        }

        int found=0;
        for(org.bukkit.entity.Entity entity:at.getWorld().getEntities()) {
            if(!(entity instanceof Item)) continue;
            if(entity.getLocation().distanceSquared(at)>36.0) continue;
            Item drop=(Item)entity;
            if(!hasMarker(drop.getItemStack(),probe.marker)) continue;
            found++;
            drop.remove();
        }
        probe.worldStacks=found;
        probe.worldScanComplete=true;
        boolean pass=probe.deathEventSeen &&
            probe.eventStacks>=probe.expectedStacks &&
            probe.worldStacks>=probe.expectedStacks;
        plugin.getLogger().info("[CombatBody Gate2] actor="+probe.actor+" "+
            (pass?"PASS":"FAIL")+" eventDrops="+probe.eventStacks+"/"+probe.expectedStacks+
            " worldDrops="+probe.worldStacks+"/"+probe.expectedStacks);
    }

    int combatHealPotionCount(String name) {
        Body b=bodies.get(key(name));
        if(b==null || b.bukkit==null) return 0;
        int total=0;
        for(ItemStack item:b.bukkit.getInventory().getContents()) {
            if(item!=null && item.getType()==Material.POTION &&
               item.getDurability()==(short)16421) total+=item.getAmount();
        }
        return total;
    }

    boolean combatMoveToward(String name,Location target,double forward,double strafe) {
        Body b=bodies.get(key(name));
        if(b==null || b.bukkit==null || target==null || target.getWorld()==null) return false;
        if(!b.bukkit.getWorld().equals(target.getWorld())) return false;
        try {
            Location here=b.bukkit.getLocation();
            double dx=target.getX()-here.getX();
            double dz=target.getZ()-here.getZ();
            double mag=Math.sqrt(dx*dx+dz*dz);
            if(mag<0.001) return false;
            dx/=mag; dz/=mag;
            double sideX=-dz,sideZ=dx;

            // A connected player's movement packets drive EntityPlayer position.
            // A clientless CombatBody has no inbound movement packets, so Bukkit
            // velocity alone is not authoritative enough to move it. Advance the
            // real NMS EntityPlayer in small physical steps instead. This keeps
            // the same server entity, hitbox, collision/death pipeline and tracker
            // visibility while making movement independent of a network client.
            double stepX=dx*forward+sideX*strafe;
            double stepZ=dz*forward+sideZ*strafe;
            double nx=here.getX()+stepX;
            double nz=here.getZ()+stepZ;

            // Do not step into a solid body-height column. The Gate-3 arena is
            // flat, but this makes the primitive safe enough for later embodied
            // coordination tests instead of blindly teleporting through walls.
            int feetY=here.getBlockY();
            Material feet=here.getWorld().getBlockAt(
                (int)Math.floor(nx),feetY,(int)Math.floor(nz)).getType();
            Material head=here.getWorld().getBlockAt(
                (int)Math.floor(nx),feetY+1,(int)Math.floor(nz)).getType();
            if(feet.isSolid() || head.isSolid()) return false;

            face(b,target);
            b.bukkit.setSprinting(true);
            invoke(b.handle,"setPositionRotation",
                nx,here.getY(),nz,b.bukkit.getLocation().getYaw(),b.bukkit.getLocation().getPitch());
            return true;
        } catch(Throwable t) {
            plugin.getLogger().warning("[CombatBody Gate3] movement failed for "+b.name+": "+root(t));
            return false;
        }
    }

    boolean combatAttack(String attacker,String target,boolean wTap) {
        final Body a=bodies.get(key(attacker));
        Body t=bodies.get(key(target));
        if(a==null || t==null || a.bukkit==null || t.bukkit==null) return false;
        try {
            face(a,t.bukkit.getLocation().add(0.0,1.0,0.0));
            a.bukkit.setSprinting(true);
            double before=t.bukkit.getHealth();
            invoke(a.handle,"attack",t.handle);
            boolean landed=t.bukkit.getHealth()<before;
            if(wTap) {
                a.bukkit.setSprinting(false);
                Bukkit.getScheduler().runTaskLater(plugin,new Runnable(){
                    public void run() {
                        Body live=bodies.get(key(a.name));
                        if(live!=null && live.bukkit!=null) {
                            try {live.bukkit.setSprinting(true);} catch(Throwable ignored){}
                        }
                    }
                },1L);
            }
            return landed;
        } catch(Throwable ex) {
            plugin.getLogger().warning("[CombatBody Gate3] attack failed "+attacker+" -> "+target+": "+root(ex));
            return false;
        }
    }

    int combatPearlCount(String name) {
        Body b=bodies.get(key(name));
        if(b==null || b.bukkit==null) return 0;
        int total=0;
        for(ItemStack item:b.bukkit.getInventory().getContents()) {
            if(item!=null && item.getType()==Material.ENDER_PEARL) total+=item.getAmount();
        }
        return total;
    }

    boolean combatSetPosition(String name,Location at) {
        Body b=bodies.get(key(name));
        if(b==null || b.bukkit==null || at==null || at.getWorld()==null) return false;
        if(!b.bukkit.getWorld().equals(at.getWorld())) return false;
        try {
            invoke(b.handle,"setPositionRotation",
                at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch());
            return true;
        } catch(Throwable ex) {
            plugin.getLogger().warning("[CombatBody Raid] reset failed for "+name+": "+root(ex));
            return false;
        }
    }

    boolean combatThrowPearl(String name,Location target) {
        Body b=bodies.get(key(name));
        if(b==null || b.bukkit==null || target==null || target.getWorld()==null) return false;
        if(!b.bukkit.getWorld().equals(target.getWorld())) return false;

        PlayerInventory inv=b.bukkit.getInventory();
        int slot=-1;
        ItemStack source=null;
        for(int i=0;i<inv.getSize();i++) {
            ItemStack item=inv.getItem(i);
            if(item!=null && item.getType()==Material.ENDER_PEARL && item.getAmount()>0) {
                slot=i;source=item;break;
            }
        }
        if(slot<0 || source==null) return false;

        try {
            // Use CraftLivingEntity's native projectile factory. In Spigot 1.8
            // World#spawn(EnderPearl.class) followed by setShooter(fakePlayer)
            // can dereference connection state that a clientless EntityPlayer
            // intentionally does not have. launchProjectile constructs the
            // EntityEnderPearl with this EntityPlayer as its shooter from the
            // start, which is the same server path used by living entities.
            Location eye=b.bukkit.getEyeLocation().clone();
            Location aim=target.clone().add(0.0,0.8,0.0);
            face(b,aim);
            Vector velocity=aim.toVector().subtract(eye.toVector());
            if(velocity.lengthSquared()<0.001) return false;
            velocity.normalize().multiply(1.55);

            EnderPearl pearl=b.bukkit.launchProjectile(EnderPearl.class,velocity);
            if(pearl==null || !pearl.isValid()) {
                if(pearl!=null) pearl.remove();
                return false;
            }

            PendingPearl pending=new PendingPearl();
            pending.actor=b.name;
            pending.launch=b.bukkit.getLocation().clone();
            pending.healthAtLaunch=b.bukkit.getHealth();
            pendingPearls.put(pearl.getUniqueId(),pending);

            // Inventory mutation is transactional: a failed projectile launch
            // never silently destroys the actor's pearl.
            ItemStack live=inv.getItem(slot);
            if(live==null || live.getType()!=Material.ENDER_PEARL || live.getAmount()<=0) {
                pendingPearls.remove(pearl.getUniqueId());
                pearl.remove();
                return false;
            }
            if(live.getAmount()<=1) inv.setItem(slot,null);
            else {
                live.setAmount(live.getAmount()-1);
                inv.setItem(slot,live);
            }
            b.bukkit.updateInventory();
            return true;
        } catch(Throwable ex) {
            plugin.getLogger().warning("[CombatBody Raid] pearl failed for "+name+
                ": "+ex.getClass().getSimpleName()+": "+root(ex));
            return false;
        }
    }

    boolean combatSplashHealAtFeet(String name) {
        Body b=bodies.get(key(name));
        if(b==null || b.bukkit==null) return false;
        PlayerInventory inv=b.bukkit.getInventory();
        int slot=-1;
        ItemStack source=null;
        for(int i=0;i<inv.getSize();i++) {
            ItemStack item=inv.getItem(i);
            if(item!=null && item.getType()==Material.POTION &&
               item.getDurability()==(short)16421) {
                slot=i;source=item;break;
            }
        }
        if(slot<0 || source==null) return false;
        try {
            if(source.getAmount()<=1) inv.setItem(slot,null);
            else {
                source.setAmount(source.getAmount()-1);
                inv.setItem(slot,source);
            }
            Location eye=b.bukkit.getEyeLocation().clone();
            ThrownPotion potion=eye.getWorld().spawn(eye,ThrownPotion.class);
            potion.setShooter(b.bukkit);
            potion.setItem(new ItemStack(Material.POTION,1,(short)16421));
            potion.setVelocity(new Vector(0.0,-1.15,0.0));
            b.bukkit.updateInventory();
            return true;
        } catch(Throwable ex) {
            plugin.getLogger().warning("[CombatBody Gate3] pot failed for "+name+": "+root(ex));
            return false;
        }
    }

    /**
     * Kohi/SportBukkit velocity compatibility for stock v1_8_R3.
     *
     * Stock Spigot saves the victim's pre-hit motion, applies knockback, fires
     * PlayerVelocityEvent, sends PacketPlayOutEntityVelocity, then restores the
     * saved server motion. Its event branch can reuse the saved vector instead
     * of a modified event vector. We preserve that proven immediate-packet path,
     * but write the canonical Kohi vector directly into NMS before serialization.
     */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onKohiDamage(EntityDamageByEntityEvent event) {
        if(!kohiEnabled() || event==null ||
           !(event.getEntity() instanceof Player) ||
           !(event.getDamager() instanceof Player) ||
           event.getFinalDamage()<=0.0) return;

        Player victim=(Player)event.getEntity();
        Player attacker=(Player)event.getDamager();
        try {
            Object handle=invoke(victim,"getHandle");
            Vector pre=motion(handle);
            Body fakeVictim=bodies.get(key(victim.getName()));
            Vector calculationPre=fakeVictim==null
                ? pre
                : new Vector(0.0,0.0,0.0);

            double dx=attacker.getLocation().getX()-victim.getLocation().getX();
            double dz=attacker.getLocation().getZ()-victim.getLocation().getZ();
            double mag=Math.sqrt(dx*dx+dz*dz);
            if(mag<0.0001) return;

            double friction=kohi("friction",2.0);
            double horizontal=kohi("horizontal",0.35);
            double vertical=kohi("vertical",0.35);
            double verticalLimit=kohi("vertical-limit",0.4);
            double extraHorizontal=kohi("extra-horizontal",0.425);
            double extraVertical=kohi("extra-vertical",0.085);

            Vector out=new Vector(
                calculationPre.getX()/friction-(dx/mag)*horizontal,
                Math.min(calculationPre.getY()/friction+vertical,verticalLimit),
                calculationPre.getZ()/friction-(dz/mag)*horizontal);

            int extra=attacker.isSprinting()?1:0;
            ItemStack held=attacker.getItemInHand();
            if(held!=null) extra+=held.getEnchantmentLevel(Enchantment.KNOCKBACK);
            if(extra>0) {
                double yaw=Math.toRadians(attacker.getLocation().getYaw());
                out.setX(out.getX()-Math.sin(yaw)*extra*extraHorizontal);
                out.setY(out.getY()+extraVertical);
                out.setZ(out.getZ()+Math.cos(yaw)*extra*extraHorizontal);
            }

            PendingKohiHit pending=new PendingKohiHit();
            pending.victim=victim.getUniqueId();
            pending.preMotion=pre;
            pending.kohiMotion=out;
            pending.createdAt=System.currentTimeMillis();
            pendingKohiHits.put(pending.victim,pending);
        } catch(Throwable t) {
            plugin.getLogger().warning("[Kohi KB] pre-hit capture failed for "+
                victim.getName()+": "+root(t));
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onKohiVelocity(final PlayerVelocityEvent event) {
        if(!kohiEnabled() || event==null) return;
        final Player victim=event.getPlayer();
        final PendingKohiHit pending=pendingKohiHits.remove(victim.getUniqueId());
        if(pending==null || System.currentTimeMillis()-pending.createdAt>500L) return;
        if(event.isCancelled()) return;

        try {
            // Stock initializes this event with the saved pre-hit vector. If a
            // different plugin genuinely changed it, preserve that requested
            // vector; otherwise use the canonical Kohi result.
            Vector outgoing=pending.kohiMotion.clone();
            Vector eventVector=event.getVelocity();
            if(eventVector!=null && eventVector.distanceSquared(pending.preMotion)>1.0E-8)
                outgoing=eventVector.clone();

            final Vector finalOutgoing=outgoing;
            Object handle=invoke(victim,"getHandle");
            setMotion(handle,finalOutgoing);

            // Keep the event equal to stock's saved vector so its broken
            // comparison branch cannot overwrite our direct NMS motion before
            // the immediate PacketPlayOutEntityVelocity is constructed.
            event.setVelocity(pending.preMotion.clone());

            final Body body=bodies.get(key(victim.getName()));
            if(body!=null) {
                body.kohiVelocityApplications++;
                body.lastKohiHorizontal=Math.sqrt(
                    finalOutgoing.getX()*finalOutgoing.getX()+
                    finalOutgoing.getZ()*finalOutgoing.getZ());
                body.lastKohiVertical=finalOutgoing.getY();

                // Stock correctly restores server motion after packet send. That
                // is right for a connected human, but a clientless CombatBody
                // has no inbound movement packets to realize the impulse. Reapply
                // the same vector to its real EntityPlayer on the next tick.
                Bukkit.getScheduler().runTask(plugin,new Runnable() {
                    public void run() {
                        Body live=bodies.get(key(victim.getName()));
                        if(live==null || live.handle==null || live.bukkit==null) return;
                        try {
                            setMotion(live.handle,finalOutgoing);
                            Vector read=motion(live.handle);
                            live.lastKohiMotionApplied=
                                read.distanceSquared(finalOutgoing)<1.0E-8;
                        } catch(Throwable t) {
                            plugin.getLogger().warning("[Kohi KB] CombatBody reapply failed for "+
                                victim.getName()+": "+root(t));
                        }
                    }
                });
            }
        } catch(Throwable t) {
            plugin.getLogger().warning("[Kohi KB] velocity handoff failed for "+
                victim.getName()+": "+root(t));
        }
    }

    @EventHandler
    public void onCombatPearlHit(ProjectileHitEvent event) {
        if(event==null || !(event.getEntity() instanceof EnderPearl)) return;
        final EnderPearl pearl=(EnderPearl)event.getEntity();
        final PendingPearl pending=pendingPearls.remove(pearl.getUniqueId());
        if(pending==null) return;

        final Location impact=pearl.getLocation().clone();
        Bukkit.getScheduler().runTask(plugin,new Runnable() {
            public void run() {
                Body b=bodies.get(key(pending.actor));
                if(b==null || b.bukkit==null || impact.getWorld()==null ||
                   !b.bukkit.getWorld().equals(impact.getWorld())) return;
                try {
                    Location current=b.bukkit.getLocation();
                    boolean nativeMoved=pending.launch!=null &&
                        current.getWorld()!=null &&
                        current.getWorld().equals(pending.launch.getWorld()) &&
                        current.distanceSquared(pending.launch)>4.0;

                    boolean manualTeleport=false;
                    if(!nativeMoved) {
                        Location landing=safePearlLanding(
                            pending.launch,impact,current.getYaw(),current.getPitch());
                        if(landing!=null) {
                            invoke(b.handle,"setPositionRotation",
                                landing.getX(),landing.getY(),landing.getZ(),
                                landing.getYaw(),landing.getPitch());
                            manualTeleport=true;
                        }
                    }

                    // Vanilla ender pearls deal 5.0 damage. If the native
                    // client-oriented path already applied it, do not double-hit.
                    double health=b.bukkit.getHealth();
                    if(health>pending.healthAtLaunch-4.5)
                        b.bukkit.damage(5.0);

                    Location after=b.bukkit.getLocation();
                    plugin.getLogger().info("[CombatBody Raid] pearl impact actor="+b.name+
                        " nativeMoved="+nativeMoved+
                        " manualTeleport="+manualTeleport+
                        " impact="+String.format(Locale.US,"%.2f,%.2f,%.2f",
                            impact.getX(),impact.getY(),impact.getZ())+
                        " body="+String.format(Locale.US,"%.2f,%.2f,%.2f",
                            after.getX(),after.getY(),after.getZ()));
                } catch(Throwable ex) {
                    plugin.getLogger().warning("[CombatBody Raid] impact handoff failed for "+
                        pending.actor+": "+ex.getClass().getSimpleName()+": "+root(ex));
                }
            }
        });
    }

    private Location safePearlLanding(Location launch,Location impact,float yaw,float pitch) {
        if(impact==null || impact.getWorld()==null) return null;
        World world=impact.getWorld();

        double dirX=0.0,dirZ=0.0;
        if(launch!=null && launch.getWorld()!=null && launch.getWorld().equals(world)) {
            dirX=impact.getX()-launch.getX();
            dirZ=impact.getZ()-launch.getZ();
        }
        double mag=Math.sqrt(dirX*dirX+dirZ*dirZ);
        if(mag<0.001) {
            // Fall back to the body's facing direction only when the recorded
            // flight vector is unavailable.
            double rad=Math.toRadians(yaw);
            dirX=-Math.sin(rad);
            dirZ=Math.cos(rad);
        } else {
            dirX/=mag;
            dirZ/=mag;
        }

        // ProjectileHitEvent can fire while the pearl center is still
        // fractionally inside the open gate block. A real connected player is
        // teleported to the valid space on the far side. Search ONLY forward
        // along the pearl's actual travel vector, within two blocks of impact.
        // This permits an open 1.8 fence gate but cannot hop sideways through a
        // closed gate or solid wall.
        double[] forward={0.20,0.55,0.90,1.25,1.60,1.95};
        int baseY=Math.max(1,(int)Math.floor(impact.getY()));
        for(double distance:forward) {
            double px=impact.getX()+dirX*distance;
            double pz=impact.getZ()+dirZ*distance;
            int bx=(int)Math.floor(px);
            int bz=(int)Math.floor(pz);

            if(!pearlPathPassable(world,impact.getX(),impact.getZ(),px,pz,baseY))
                continue;

            for(int dy=-1;dy<=2;dy++) {
                int yy=baseY+dy;
                if(yy<=0 || yy+1>=world.getMaxHeight()) continue;
                if(!pearlBodyColumnClear(world,bx,yy,bz)) continue;
                // An OPEN fence gate is passable along the projectile ray, but
                // it is not a stable player destination. Landing centered in
                // the gate cell leaves the CombatBody straddling the doorway
                // instead of reproducing the far-side teleport a connected
                // 1.8 client gets from an ender pearl.
                if(world.getBlockAt(bx,yy,bz).getType()==Material.FENCE_GATE ||
                   world.getBlockAt(bx,yy+1,bz).getType()==Material.FENCE_GATE)
                    continue;

                Location out=new Location(world,bx+0.5,yy+0.05,bz+0.5,yaw,pitch);
                return out;
            }
        }
        return null;
    }

    private boolean pearlPathPassable(World world,double fromX,double fromZ,
                                      double toX,double toZ,int y) {
        int steps=8;
        for(int i=0;i<=steps;i++) {
            double t=i/(double)steps;
            int x=(int)Math.floor(fromX+(toX-fromX)*t);
            int z=(int)Math.floor(fromZ+(toZ-fromZ)*t);
            if(!pearlCellPassable(world,x,y,z) || !pearlCellPassable(world,x,y+1,z))
                return false;
        }
        return true;
    }

    private boolean pearlBodyColumnClear(World world,int x,int y,int z) {
        return pearlCellPassable(world,x,y,z) &&
            pearlCellPassable(world,x,y+1,z);
    }

    private boolean pearlCellPassable(World world,int x,int y,int z) {
        Block block=world.getBlockAt(x,y,z);
        Material material=block.getType();
        if(material==Material.FENCE_GATE) {
            // 1.8 fence-gate data bit 0x4 is the open flag.
            return (block.getData()&0x4)!=0;
        }
        return !material.isSolid();
    }

    private boolean kohiEnabled() {
        return plugin.getConfig().getBoolean("pvp.kohi-knockback.enabled",true);
    }

    private double kohi(String key,double fallback) {
        return plugin.getConfig().getDouble("pvp.kohi-knockback."+key,fallback);
    }

    private static Vector motion(Object handle) throws Exception {
        return new Vector(
            getDoubleField(handle,"motX"),
            getDoubleField(handle,"motY"),
            getDoubleField(handle,"motZ"));
    }

    private static void setMotion(Object handle,Vector velocity) throws Exception {
        setDoubleField(handle,"motX",velocity.getX());
        setDoubleField(handle,"motY",velocity.getY());
        setDoubleField(handle,"motZ",velocity.getZ());
        if(velocity.getY()>0.0) setFloatField(handle,"fallDistance",0.0f);
    }

    private void face(Body b,Location target) throws Exception {
        Location here=b.bukkit.getLocation();
        double dx=target.getX()-here.getX();
        double dy=target.getY()-(here.getY()+1.62);
        double dz=target.getZ()-here.getZ();
        double horizontal=Math.max(0.001,Math.sqrt(dx*dx+dz*dz));
        float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));
        float pitch=(float)-Math.toDegrees(Math.atan2(dy,horizontal));
        invoke(b.handle,"setPositionRotation",
            here.getX(),here.getY(),here.getZ(),yaw,pitch);
    }

    boolean damage(String name,double amount) {
        Body b=bodies.get(key(name));
        if(b==null || b.bukkit==null) return false;
        try {
            b.bukkit.damage(Math.max(0.1,amount));
            return true;
        } catch(Throwable t) {
            plugin.getLogger().warning("[CombatBody] damage failed for "+b.name+": "+root(t));
            return false;
        }
    }

    boolean kill(String name) {
        Body b=bodies.get(key(name));
        if(b==null || b.bukkit==null) return false;
        try {
            b.bukkit.damage(1000.0);
            return true;
        } catch(Throwable t) {
            plugin.getLogger().warning("[CombatBody] kill failed for "+b.name+": "+root(t));
            return false;
        }
    }

    void noteDeathEvent(String name,List<ItemStack> drops,Location deathLocation) {
        final Body b=bodies.get(key(name));
        if(b==null) return;
        b.deathEventSeen=true;

        final DropProbe probe=dropProbes.get(key(name));
        if(probe!=null) {
            probe.deathEventSeen=true;
            probe.deathLocation=deathLocation==null?null:deathLocation.clone();
            probe.eventStacks=countMarked(drops,probe.marker);
            plugin.getLogger().info("[CombatBody Gate2] death-event actor="+b.name+
                " eventDrops="+probe.eventStacks+"/"+probe.expectedStacks);
            Bukkit.getScheduler().runTaskLater(plugin,new Runnable(){
                public void run(){scanWorldDrops(probe);}
            },2L);
        }

        clearDormantState(b.name);
        Bukkit.getScheduler().runTaskLater(plugin,new Runnable(){
            public void run(){removeBody(b.name,false);}
        },10L);
    }

    void noteDeathEvent(String name) {
        noteDeathEvent(name,null,null);
    }

    boolean deathEventSeen(String name) {
        Body b=bodies.get(key(name));
        return b!=null && b.deathEventSeen;
    }

    boolean despawn(String name) {
        return removeBody(name,true);
    }

    private boolean removeBody(String name,boolean preserveState) {
        Body b=bodies.remove(key(name));
        if(b==null) return false;
        java.util.Iterator<Map.Entry<UUID,PendingPearl>> pearlIt=pendingPearls.entrySet().iterator();
        while(pearlIt.hasNext()) {
            Map.Entry<UUID,PendingPearl> e=pearlIt.next();
            PendingPearl pp=e.getValue();
            if(pp!=null && b.name.equalsIgnoreCase(pp.actor)) pearlIt.remove();
        }
        if(preserveState) captureDormantState(b);
        hideFromOnlinePlayers(b);
        try {invoke(b.worldHandle,"removeEntity",b.handle);}
        catch(Throwable ignored){}
        plugin.getLogger().info("[CombatBody] removed "+b.name+" preserveState="+preserveState);
        return true;
    }

    Location lastKnownLocation(String name) {
        DormantState s=dormantStates.get(key(name));
        Location loc=s==null?null:s.location();
        return loc==null?null:loc.clone();
    }

    boolean hasDormantState(String name) {
        return dormantStates.containsKey(key(name));
    }

    void shutdown() {
        String[] names=new String[bodies.size()];
        int i=0; for(Body b:bodies.values()) names[i++]=b.name;
        for(String name:names) removeBody(name,true);
        dropProbes.clear();
        pendingPearls.clear();
        saveDormantFile();
    }

    private void captureDormantState(Body b) {
        if(b==null || b.bukkit==null) return;
        try {
            if(b.bukkit.isDead() || b.bukkit.getHealth()<=0.0) {
                clearDormantState(b.name);
                return;
            }
            DormantState s=new DormantState();
            s.name=b.name;
            Location loc=b.bukkit.getLocation();
            s.world=loc.getWorld()==null?"":loc.getWorld().getName();
            s.x=loc.getX();s.y=loc.getY();s.z=loc.getZ();
            s.yaw=loc.getYaw();s.pitch=loc.getPitch();
            s.health=b.bukkit.getHealth();
            s.food=b.bukkit.getFoodLevel();
            PlayerInventory inv=b.bukkit.getInventory();
            s.heldSlot=inv.getHeldItemSlot();
            s.contents=cloneItems(inv.getContents(),36);
            s.armor=cloneItems(inv.getArmorContents(),4);
            dormantStates.put(key(b.name),s);
            writeDormantState(s);
            saveDormantFile();
            plugin.getLogger().info("[CombatBody Gate4] captured actor="+b.name+
                " health="+String.format(Locale.US,"%.1f",s.health)+
                " food="+s.food+" at="+s.world+" "+
                String.format(Locale.US,"%.2f,%.2f,%.2f",s.x,s.y,s.z));
        } catch(Throwable t) {
            plugin.getLogger().warning("[CombatBody Gate4] capture failed for "+b.name+": "+root(t));
        }
    }

    private void restoreDormantState(Body b,DormantState s) {
        if(b==null || b.bukkit==null || s==null) return;
        try {
            PlayerInventory inv=b.bukkit.getInventory();
            inv.clear();
            inv.setArmorContents(new ItemStack[4]);
            inv.setContents(cloneItems(s.contents,36));
            inv.setArmorContents(cloneItems(s.armor,4));
            inv.setHeldItemSlot(Math.max(0,Math.min(8,s.heldSlot)));
            b.bukkit.setHealth(Math.max(0.5,Math.min(b.bukkit.getMaxHealth(),s.health)));
            b.bukkit.setFoodLevel(Math.max(0,Math.min(20,s.food)));
            b.bukkit.updateInventory();
        } catch(Throwable t) {
            plugin.getLogger().warning("[CombatBody Gate4] restore failed for "+b.name+": "+root(t));
        }
    }

    private ItemStack[] cloneItems(ItemStack[] src,int size) {
        ItemStack[] out=new ItemStack[size];
        if(src==null) return out;
        for(int i=0;i<Math.min(size,src.length);i++)
            out[i]=src[i]==null?null:src[i].clone();
        return out;
    }

    private void loadDormantStates() {
        ConfigurationSection root=dormantData.getConfigurationSection("actors");
        if(root==null) return;
        for(String k:root.getKeys(false)) {
            ConfigurationSection c=root.getConfigurationSection(k);
            if(c==null) continue;
            DormantState s=new DormantState();
            s.name=c.getString("name",k);
            s.world=c.getString("world","");
            s.x=c.getDouble("x");s.y=c.getDouble("y");s.z=c.getDouble("z");
            s.yaw=(float)c.getDouble("yaw");s.pitch=(float)c.getDouble("pitch");
            s.health=c.getDouble("health",20.0);
            s.food=c.getInt("food",20);
            s.heldSlot=c.getInt("held-slot",0);
            s.contents=readItemList(c.getList("contents"),36);
            s.armor=readItemList(c.getList("armor"),4);
            dormantStates.put(key(s.name),s);
        }
    }

    private ItemStack[] readItemList(List<?> xs,int size) {
        ItemStack[] out=new ItemStack[size];
        if(xs==null) return out;
        for(int i=0;i<Math.min(size,xs.size());i++) {
            Object value=xs.get(i);
            if(value instanceof ItemStack) out[i]=((ItemStack)value).clone();
        }
        return out;
    }

    private void writeDormantState(DormantState s) {
        String base="actors."+key(s.name);
        dormantData.set(base+".name",s.name);
        dormantData.set(base+".world",s.world);
        dormantData.set(base+".x",s.x);dormantData.set(base+".y",s.y);dormantData.set(base+".z",s.z);
        dormantData.set(base+".yaw",(double)s.yaw);dormantData.set(base+".pitch",(double)s.pitch);
        dormantData.set(base+".health",s.health);
        dormantData.set(base+".food",s.food);
        dormantData.set(base+".held-slot",s.heldSlot);
        dormantData.set(base+".contents",new ArrayList<ItemStack>(Arrays.asList(cloneItems(s.contents,36))));
        dormantData.set(base+".armor",new ArrayList<ItemStack>(Arrays.asList(cloneItems(s.armor,4))));
    }

    private void clearDormantState(String name) {
        if(name==null) return;
        dormantStates.remove(key(name));
        dormantData.set("actors."+key(name),null);
        saveDormantFile();
    }

    private void saveDormantFile() {
        try { dormantData.save(dormantFile); }
        catch(IOException e) {
            plugin.getLogger().warning("[CombatBody Gate4] could not save dormant state: "+e.getMessage());
        }
    }

    private void showToOnlinePlayers(Body b) {
        try {
            Object addAction=enumValue("PacketPlayOutPlayerInfo$EnumPlayerInfoAction","ADD_PLAYER");
            Class<?> ep=Class.forName("net.minecraft.server.v1_8_R3.EntityPlayer");
            Object arr=Array.newInstance(ep,1);
            Array.set(arr,0,b.handle);
            Object info=newInstance(
                Class.forName("net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo"),
                addAction,arr);
            Object spawn=newInstance(
                Class.forName("net.minecraft.server.v1_8_R3.PacketPlayOutNamedEntitySpawn"),
                b.handle);
            for(Player viewer:Bukkit.getOnlinePlayers()) {
                sendPacket(viewer,info);
                sendPacket(viewer,spawn);
            }
        } catch(Throwable t) {
            plugin.getLogger().warning("[CombatBody] visibility packets failed for "+b.name+": "+root(t));
        }
    }

    private void hideFromOnlinePlayers(Body b) {
        try {
            int id=((Number)invoke(b.handle,"getId")).intValue();
            Object destroy=newInstance(
                Class.forName("net.minecraft.server.v1_8_R3.PacketPlayOutEntityDestroy"),
                new int[]{id});
            Object removeAction=enumValue("PacketPlayOutPlayerInfo$EnumPlayerInfoAction","REMOVE_PLAYER");
            Class<?> ep=Class.forName("net.minecraft.server.v1_8_R3.EntityPlayer");
            Object arr=Array.newInstance(ep,1);
            Array.set(arr,0,b.handle);
            Object info=newInstance(
                Class.forName("net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo"),
                removeAction,arr);
            for(Player viewer:Bukkit.getOnlinePlayers()) {
                sendPacket(viewer,destroy);
                sendPacket(viewer,info);
            }
        } catch(Throwable ignored){}
    }

    private void sendPacket(Player viewer,Object packet) throws Exception {
        Object handle=invoke(viewer,"getHandle");
        Object connection=getField(handle,"playerConnection");
        invoke(connection,"sendPacket",packet);
    }

    private void applyPrestigeCape(Object profile,String name,UUID uuid) {
        if(profile==null || !plugin.isPrestigeCapeIdentity(name)) return;
        String url=plugin.getConfig().getString("creator-tag.cape-texture-url","");
        if(url==null || !url.startsWith("https://textures.minecraft.net/texture/")) return;
        try {
            String json="{\"timestamp\":"+System.currentTimeMillis()+
                ",\"profileId\":\""+uuid.toString().replace("-","")+
                "\",\"profileName\":\""+name+
                "\",\"textures\":{\"CAPE\":{\"url\":\""+url+"\"}}}";
            String value=java.util.Base64.getEncoder().encodeToString(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Class<?> property=Class.forName("com.mojang.authlib.properties.Property");
            Object capeProperty=newInstance(property,"textures",value);
            Object properties=invoke(profile,"getProperties");
            invoke(properties,"put","textures",capeProperty);
            plugin.getLogger().info("[Creator Cape] profile="+name+" source=mojang-common-cape");
        } catch(Throwable ex) {
            plugin.getLogger().warning("[Creator Cape] could not attach cape to "+name+": "+root(ex));
        }
    }

    private Object enumValue(String simple,String name) throws Exception {
        Class<?> c=Class.forName("net.minecraft.server.v1_8_R3."+simple);
        @SuppressWarnings({"rawtypes","unchecked"})
        Object e=Enum.valueOf((Class)c.asSubclass(Enum.class),name);
        return e;
    }

    private static Object newInstance(Class<?> type,Object... args) throws Exception {
        for(Constructor<?> c:type.getConstructors()) {
            Class<?>[] p=c.getParameterTypes();
            if(matches(p,args)) return c.newInstance(args);
        }
        throw new NoSuchMethodException("No compatible constructor for "+type.getName()+" args="+args.length);
    }

    private static Object invoke(Object target,String name,Object... args) throws Exception {
        if(target==null) throw new NullPointerException("invoke target for "+name);
        for(Method m:target.getClass().getMethods()) {
            if(!m.getName().equals(name)) continue;
            if(matches(m.getParameterTypes(),args)) return m.invoke(target,args);
        }
        Class<?> c=target.getClass();
        while(c!=null) {
            for(Method m:c.getDeclaredMethods()) {
                if(!m.getName().equals(name)) continue;
                if(!matches(m.getParameterTypes(),args)) continue;
                m.setAccessible(true);
                return m.invoke(target,args);
            }
            c=c.getSuperclass();
        }
        throw new NoSuchMethodException(target.getClass().getName()+"."+name+"("+args.length+")");
    }

    private static boolean matches(Class<?>[] params,Object[] args) {
        if(params.length!=args.length) return false;
        for(int i=0;i<params.length;i++) {
            if(args[i]==null) {
                if(params[i].isPrimitive()) return false;
                continue;
            }
            Class<?> want=wrap(params[i]);
            if(!want.isAssignableFrom(args[i].getClass())) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> c) {
        if(!c.isPrimitive()) return c;
        if(c==boolean.class)return Boolean.class;
        if(c==byte.class)return Byte.class;
        if(c==short.class)return Short.class;
        if(c==int.class)return Integer.class;
        if(c==long.class)return Long.class;
        if(c==float.class)return Float.class;
        if(c==double.class)return Double.class;
        if(c==char.class)return Character.class;
        return c;
    }

    private static Object getField(Object target,String name) throws Exception {
        Class<?> c=target.getClass();
        while(c!=null) {
            try {
                Field f=c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch(NoSuchFieldException ignored){c=c.getSuperclass();}
        }
        throw new NoSuchFieldException(name);
    }

    private static void setIntField(Object target,String name,int value) throws Exception {
        Class<?> c=target.getClass();
        while(c!=null) {
            try {
                Field f=c.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(target,value);
                return;
            } catch(NoSuchFieldException ignored){c=c.getSuperclass();}
        }
        throw new NoSuchFieldException(name);
    }

    private static double getDoubleField(Object target,String name) throws Exception {
        Class<?> c=target.getClass();
        while(c!=null) {
            try {
                Field f=c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getDouble(target);
            } catch(NoSuchFieldException ignored){c=c.getSuperclass();}
        }
        throw new NoSuchFieldException(name);
    }

    private static void setDoubleField(Object target,String name,double value) throws Exception {
        Class<?> c=target.getClass();
        while(c!=null) {
            try {
                Field f=c.getDeclaredField(name);
                f.setAccessible(true);
                f.setDouble(target,value);
                return;
            } catch(NoSuchFieldException ignored){c=c.getSuperclass();}
        }
        throw new NoSuchFieldException(name);
    }

    private static void setFloatField(Object target,String name,float value) throws Exception {
        Class<?> c=target.getClass();
        while(c!=null) {
            try {
                Field f=c.getDeclaredField(name);
                f.setAccessible(true);
                f.setFloat(target,value);
                return;
            } catch(NoSuchFieldException ignored){c=c.getSuperclass();}
        }
        throw new NoSuchFieldException(name);
    }

    private static String key(String s){return s==null?"":s.toLowerCase(Locale.ENGLISH);}

    private static String root(Throwable t) {
        Throwable x=t;
        while(x.getCause()!=null && x.getCause()!=x) x=x.getCause();
        String m=x.getMessage();
        return x.getClass().getSimpleName()+(m==null?"":": "+m);
    }
}
