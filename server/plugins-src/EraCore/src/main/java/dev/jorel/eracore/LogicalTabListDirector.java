package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Keeps the full simulated population visible in the 1.8 tab list without
 * opening one Minecraft connection per identity.
 *
 * Uses reflection so EraCore still compiles against the ordinary Spigot jar.
 */
final class LogicalTabListDirector {
    private final EraCore plugin;
    private final SimWorldDirector world;
    private final Map<String,Object> fakeEntityByName = new HashMap<String,Object>();
    private final Map<UUID,Set<String>> sentByViewer = new HashMap<UUID,Set<String>>();
    private BukkitTask task;

    LogicalTabListDirector(EraCore plugin, SimWorldDirector world) {
        this.plugin=plugin;
        this.world=world;
    }

    void start() {
        stop();
        ensureTeams();
        task=Bukkit.getScheduler().runTaskTimer(plugin,new Runnable() {
            public void run(){syncAll();}
        },30L,40L);
    }

    void stop() {
        if(task!=null) task.cancel();
        task=null;
        for(Player p:Bukkit.getOnlinePlayers()) {
            if(!plugin.isBotIdentity(p.getName())) clearViewer(p);
        }
        sentByViewer.clear();
        fakeEntityByName.clear();
    }

    void showTo(Player viewer) {
        if(viewer==null || plugin.isBotIdentity(viewer.getName())) return;
        Bukkit.getScheduler().runTaskLater(plugin,new Runnable() {
            public void run(){syncViewer(viewer);}
        },10L);
    }

    void onPhysicalJoin(Player p) {
        if(p==null || !plugin.isBotIdentity(p.getName())) return;
        String k=key(p.getName());
        for(Player viewer:Bukkit.getOnlinePlayers()) {
            if(plugin.isBotIdentity(viewer.getName())) continue;
            Set<String> sent=sentByViewer.get(viewer.getUniqueId());
            if(sent!=null && sent.remove(k)) send(viewer,k,false);
        }
        applyPhysicalRankTeam(p);
    }

    void onPhysicalQuit(String name) {
        // Reappears as a logical tab identity on the next reconciliation.
    }

    int logicalTabCount() {
        return world==null?0:world.allIdentityNames().size();
    }

    private void syncAll() {
        for(Player p:Bukkit.getOnlinePlayers()) {
            if(!plugin.isBotIdentity(p.getName())) syncViewer(p);
        }
    }

    private void syncViewer(Player viewer) {
        if(viewer==null || !viewer.isOnline()) return;

        Set<String> desired=new LinkedHashSet<String>();
        for(String name:world.allIdentityNames()) {
            if(name.equalsIgnoreCase(viewer.getName())) continue;
            if(Bukkit.getPlayerExact(name)!=null) continue;
            desired.add(key(name));
            applyFakeRankTeam(name);
        }

        Set<String> sent=sentByViewer.get(viewer.getUniqueId());
        if(sent==null) {
            sent=new LinkedHashSet<String>();
            sentByViewer.put(viewer.getUniqueId(),sent);
        }

        for(String existing:new ArrayList<String>(sent)) {
            if(!desired.contains(existing)) {
                send(viewer,existing,false);
                sent.remove(existing);
            }
        }

        for(String name:desired) {
            if(sent.add(name)) send(viewer,name,true);
        }
    }

    private void clearViewer(Player viewer) {
        Set<String> sent=sentByViewer.remove(viewer.getUniqueId());
        if(sent==null) return;
        for(String name:sent) send(viewer,name,false);
    }

    private void ensureTeams() {
        Scoreboard b=Bukkit.getScoreboardManager().getMainScoreboard();
        makeTeam(b,"simtab0","&7[Member] ");
        makeTeam(b,"simtab1","&a[Vip] ");
        makeTeam(b,"simtab2","&b[Elite] ");
        makeTeam(b,"simtab3","&d[Legend] ");
        makeTeam(b,"simtab4","&6[Titan] ");
        makeTeam(b,"simtabyt","&c[YT] ");
    }

    private Team makeTeam(Scoreboard b,String name,String prefix) {
        Team t=b.getTeam(name);
        if(t==null) t=b.registerNewTeam(name);
        t.setPrefix(EraCore.colorText(prefix));
        return t;
    }

    private void applyFakeRankTeam(String name) {
        if(Bukkit.getPlayerExact(name)!=null) return;
        Scoreboard b=Bukkit.getScoreboardManager().getMainScoreboard();
        String team=plugin.isCreatorIdentity(name)?"simtabyt":"simtab"+plugin.simulatedDonorLevel(name);
        Team t=b.getTeam(team);
        if(t==null) return;
        OfflinePlayer op=Bukkit.getOfflinePlayer(name);
        if(!t.hasPlayer(op)) t.addPlayer(op);
    }

    private void applyPhysicalRankTeam(Player p) {
        if(plugin.isCreatorIdentity(p.getName())) return; // existing [YT] team owns creators
        Scoreboard b=Bukkit.getScoreboardManager().getMainScoreboard();
        Team t=b.getTeam("simtab"+plugin.simulatedDonorLevel(p.getName()));
        if(t!=null && !t.hasPlayer(p)) t.addPlayer(p);
    }

    private Object fakeEntity(String lowerName) throws Exception {
        Object cached=fakeEntityByName.get(lowerName);
        if(cached!=null) return cached;

        String realName=world.identityDisplayName(lowerName);
        if(realName==null || realName.isEmpty()) realName=lowerName;

        Object craftServer=Bukkit.getServer();
        Object mcServer=craftServer.getClass().getMethod("getServer").invoke(craftServer);

        Object craftWorld=Bukkit.getWorlds().get(0);
        Object worldServer=craftWorld.getClass().getMethod("getHandle").invoke(craftWorld);

        Class<?> gpClass=Class.forName("com.mojang.authlib.GameProfile");
        UUID uuid=UUID.nameUUIDFromBytes(("EraSim:"+lowerName).getBytes(StandardCharsets.UTF_8));
        Object profile=gpClass.getConstructor(UUID.class,String.class).newInstance(uuid,realName);

        Class<?> wsClass=Class.forName("net.minecraft.server.v1_8_R3.WorldServer");
        Class<?> pimClass=Class.forName("net.minecraft.server.v1_8_R3.PlayerInteractManager");
        Object pim=pimClass.getConstructor(wsClass).newInstance(worldServer);

        Class<?> msClass=Class.forName("net.minecraft.server.v1_8_R3.MinecraftServer");
        Class<?> epClass=Class.forName("net.minecraft.server.v1_8_R3.EntityPlayer");
        Object ep=epClass.getConstructor(msClass,wsClass,gpClass,pimClass)
            .newInstance(mcServer,worldServer,profile,pim);

        try {
            Field ping=epClass.getField("ping");
            ping.setInt(ep,35+Math.abs(lowerName.hashCode()%75));
        } catch(Throwable ignored){}

        fakeEntityByName.put(lowerName,ep);
        return ep;
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private void send(Player viewer,String lowerName,boolean add) {
        try {
            Object ep=fakeEntity(lowerName);
            Class<?> epClass=Class.forName("net.minecraft.server.v1_8_R3.EntityPlayer");
            Class<?> actionClass=Class.forName("net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo$EnumPlayerInfoAction");
            Object action=Enum.valueOf((Class<Enum>)actionClass.asSubclass(Enum.class),add?"ADD_PLAYER":"REMOVE_PLAYER");

            Object arr=Array.newInstance(epClass,1);
            Array.set(arr,0,ep);

            Class<?> packetClass=Class.forName("net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo");
            Constructor<?> ctor=packetClass.getConstructor(actionClass,arr.getClass());
            Object packet=ctor.newInstance(action,arr);

            Object handle=viewer.getClass().getMethod("getHandle").invoke(viewer);
            Field connection=handle.getClass().getField("playerConnection");
            Object pc=connection.get(handle);

            Class<?> packetBase=Class.forName("net.minecraft.server.v1_8_R3.Packet");
            pc.getClass().getMethod("sendPacket",packetBase).invoke(pc,packet);
        } catch(Throwable t) {
            if(add) plugin.getLogger().warning("Logical tab entry failed for "+lowerName+": "+t.getClass().getSimpleName()+": "+t.getMessage());
        }
    }

    private String key(String s){return s==null?"":s.toLowerCase(Locale.ENGLISH);}
}
