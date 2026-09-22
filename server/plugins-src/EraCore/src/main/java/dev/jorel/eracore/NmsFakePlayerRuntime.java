package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
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
final class NmsFakePlayerRuntime {
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
    }

    private final EraCore plugin;
    private final Map<String,Body> bodies=new LinkedHashMap<String,Body>();

    NmsFakePlayerRuntime(EraCore plugin){this.plugin=plugin;}

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

        int maxBodies=probe?1:Math.max(1,Math.min(64,
            plugin.getConfig().getInt("actors.fake-player.max-bodies",8)));
        if(bodies.size()>=maxBodies)
            throw new IllegalStateException("CombatBody cap reached ("+maxBodies+").");

        Player connected=Bukkit.getPlayerExact(name);
        if(connected!=null) throw new IllegalStateException(name+" already has a connected Minecraft client.");

        Class<?> craftServer=Class.forName("org.bukkit.craftbukkit.v1_8_R3.CraftServer");
        Class<?> craftWorld=Class.forName("org.bukkit.craftbukkit.v1_8_R3.CraftWorld");
        Class<?> gameProfile=Class.forName("com.mojang.authlib.GameProfile");
        Class<?> pim=Class.forName("net.minecraft.server.v1_8_R3.PlayerInteractManager");
        Class<?> entityPlayer=Class.forName("net.minecraft.server.v1_8_R3.EntityPlayer");
        Class<?> networkManager=Class.forName("net.minecraft.server.v1_8_R3.NetworkManager");
        Class<?> direction=Class.forName("net.minecraft.server.v1_8_R3.EnumProtocolDirection");
        Class<?> playerConnection=Class.forName("net.minecraft.server.v1_8_R3.PlayerConnection");

        Object mcServer=invoke(craftServer.cast(Bukkit.getServer()),"getServer");
        Object worldServer=invoke(craftWorld.cast(at.getWorld()),"getHandle");
        UUID uuid=ActorDirectory.stableOfflineUuid(name);
        Object profile=newInstance(gameProfile,uuid,name);
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
            at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch());
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

        try {b.bukkit.setHealth(20.0);} catch(Exception ignored){}
        try {b.bukkit.setFoodLevel(20);} catch(Exception ignored){}
        showToOnlinePlayers(b);
        plugin.getLogger().info("[CombatBody] spawned "+name+" uuid="+uuid+
            " at "+at.getWorld().getName()+" "+at.getBlockX()+","+at.getBlockY()+","+at.getBlockZ());
        return b.bukkit;
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

    void noteDeathEvent(String name) {
        final Body b=bodies.get(key(name));
        if(b==null) return;
        b.deathEventSeen=true;
        // Leave the dead body around briefly so vanilla/CraftBukkit can finish
        // death/drop processing before our experimental runtime removes it.
        Bukkit.getScheduler().runTaskLater(plugin,new Runnable(){
            public void run(){despawn(b.name);}
        },10L);
    }

    boolean deathEventSeen(String name) {
        Body b=bodies.get(key(name));
        return b!=null && b.deathEventSeen;
    }

    boolean despawn(String name) {
        Body b=bodies.remove(key(name));
        if(b==null) return false;
        hideFromOnlinePlayers(b);
        try {invoke(b.worldHandle,"removeEntity",b.handle);}
        catch(Throwable ignored){}
        plugin.getLogger().info("[CombatBody] removed "+b.name);
        return true;
    }

    void shutdown() {
        String[] names=new String[bodies.size()];
        int i=0; for(Body b:bodies.values()) names[i++]=b.name;
        for(String name:names) despawn(name);
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

    private static String key(String s){return s==null?"":s.toLowerCase(Locale.ENGLISH);}

    private static String root(Throwable t) {
        Throwable x=t;
        while(x.getCause()!=null && x.getCause()!=x) x=x.getCause();
        String m=x.getMessage();
        return x.getClass().getSimpleName()+(m==null?"":": "+m);
    }
}
