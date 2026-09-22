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
    private final Map<UUID,Long> lastRefreshByViewer = new HashMap<UUID,Long>();
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
        lastRefreshByViewer.clear();
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

        int visible=Math.max(20,Math.min(150,
            plugin.getConfig().getInt("logical-tab.visible-identities",100)));

        // The tab roster is a logical population view, independent from the small
        // number of HOT Mineflayer bodies. Keep currently embodied identities in
        // the roster first, then deterministically fill to the configured count.
        Set<String> roster=new LinkedHashSet<String>();
        for(Player online:Bukkit.getOnlinePlayers()) {
            if(plugin.isBotIdentity(online.getName()) && world.identityDisplayName(online.getName())!=null)
                roster.add(key(online.getName()));
        }
        for(String name:world.allIdentityNames()) {
            if(roster.size()>=visible) break;
            roster.add(key(name));
        }

        Set<String> desired=new LinkedHashSet<String>();
        for(String lower:roster) {
            String display=world.identityDisplayName(lower);
            if(display==null || display.isEmpty()) display=lower;
            if(display.equalsIgnoreCase(viewer.getName())) continue;
            if(Bukkit.getPlayerExact(display)!=null) continue;
            desired.add(lower);
            applyFakeRankTeam(display);
        }

        Set<String> sent=sentByViewer.get(viewer.getUniqueId());
        if(sent==null) {
            sent=new LinkedHashSet<String>();
            sentByViewer.put(viewer.getUniqueId(),sent);
        }

        List<String> remove=new ArrayList<String>();
        for(String existing:new ArrayList<String>(sent)) {
            if(!desired.contains(existing)) {
                remove.add(existing);
                sent.remove(existing);
            }
        }
        if(!remove.isEmpty()) sendBatch(viewer,remove,false);

        List<String> add=new ArrayList<String>();
        for(String name:desired) {
            if(sent.add(name)) add.add(name);
        }
        if(!add.isEmpty()) sendBatch(viewer,add,true);

        // Reassert the logical roster periodically. One batched packet is cheap
        // and repairs clients/modded tab overlays that discard synthetic entries
        // after their initial join burst.
        long now=System.currentTimeMillis();
        long refreshMs=Math.max(5,plugin.getConfig().getInt("logical-tab.refresh-seconds",10))*1000L;
        Long last=lastRefreshByViewer.get(viewer.getUniqueId());
        if(last==null || now-last>=refreshMs) {
            if(!desired.isEmpty()) sendBatch(viewer,desired,true);
            lastRefreshByViewer.put(viewer.getUniqueId(),now);
        }
    }

    private void clearViewer(Player viewer) {
        Set<String> sent=sentByViewer.remove(viewer.getUniqueId());
        lastRefreshByViewer.remove(viewer.getUniqueId());
        if(sent==null) return;
        if(!sent.isEmpty()) sendBatch(viewer,sent,false);
    }

    private void ensureTeams() {
        Scoreboard b=Bukkit.getScoreboardManager().getMainScoreboard();
        makeTeam(b,"simtab0","&7[Member] ");
        makeTeam(b,"simtab1","&a[Basic] ");
        makeTeam(b,"simtab2","&f[Silver] ");
        makeTeam(b,"simtab3","&6[Gold] ");
        makeTeam(b,"simtab4","&b[Platinum] ");
        makeTeam(b,"simtabyt","&c[Yt] ");
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
        UUID uuid=ActorDirectory.stableOfflineUuid(realName);
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

    private void send(Player viewer,String lowerName,boolean add) {
        sendBatch(viewer,Collections.singletonList(lowerName),add);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private void sendBatch(Player viewer,Collection<String> lowerNames,boolean add) {
        if(viewer==null || lowerNames==null || lowerNames.isEmpty()) return;
        try {
            Class<?> epClass=Class.forName("net.minecraft.server.v1_8_R3.EntityPlayer");
            Class<?> actionClass=Class.forName("net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo$EnumPlayerInfoAction");
            Object action=Enum.valueOf((Class<Enum>)actionClass.asSubclass(Enum.class),add?"ADD_PLAYER":"REMOVE_PLAYER");

            List<Object> entities=new ArrayList<Object>();
            for(String lowerName:lowerNames) entities.add(fakeEntity(lowerName));

            Object arr=Array.newInstance(epClass,entities.size());
            for(int i=0;i<entities.size();i++) Array.set(arr,i,entities.get(i));

            Class<?> packetClass=Class.forName("net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo");
            Constructor<?> ctor=packetClass.getConstructor(actionClass,arr.getClass());
            Object packet=ctor.newInstance(action,arr);

            Object handle=viewer.getClass().getMethod("getHandle").invoke(viewer);
            Field connection=handle.getClass().getField("playerConnection");
            Object pc=connection.get(handle);

            Class<?> packetBase=Class.forName("net.minecraft.server.v1_8_R3.Packet");
            pc.getClass().getMethod("sendPacket",packetBase).invoke(pc,packet);
        } catch(Throwable t) {
            if(add) plugin.getLogger().warning("Logical tab batch failed for "+lowerNames.size()+
                " identities: "+t.getClass().getSimpleName()+": "+t.getMessage());
        }
    }

    private String key(String s){return s==null?"":s.toLowerCase(Locale.ENGLISH);}
}
