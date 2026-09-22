package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * One lookup surface for every persistent HCF identity.
 *
 * Session state (logical online/offline) is deliberately separate from body
 * state (Mineflayer / NMS combat body / abstract). Runtime transitions must not
 * change the actor's UUID or make the simulation treat it as a different person.
 */
final class ActorDirectory {
    enum Runtime {
        HUMAN,
        MINEFLAYER,
        COMBAT_BODY,
        ABSTRACT,
        OFFLINE
    }

    static final class Snapshot {
        final String name;
        final UUID uuid;
        final Runtime runtime;
        final boolean logicalOnline;
        final String faction;
        final Location location;

        Snapshot(String name, UUID uuid, Runtime runtime, boolean logicalOnline,
                 String faction, Location location) {
            this.name=name;
            this.uuid=uuid;
            this.runtime=runtime;
            this.logicalOnline=logicalOnline;
            this.faction=faction==null?"":faction;
            this.location=location==null?null:location.clone();
        }

        String summary() {
            String where=location==null || location.getWorld()==null
                ? "unknown"
                : location.getWorld().getName()+" "+
                  location.getBlockX()+","+location.getBlockY()+","+location.getBlockZ();
            return name+" runtime="+runtime+
                " online="+logicalOnline+
                " faction="+(faction.isEmpty()?"none":faction)+
                " uuid="+uuid+
                " location="+where;
        }
    }

    static final class TeleportResult {
        final boolean ok;
        final String message;
        TeleportResult(boolean ok,String message){this.ok=ok;this.message=message;}
    }

    private final EraCore plugin;
    private final SimWorldDirector simWorld;
    private final NmsFakePlayerRuntime fakePlayers;

    ActorDirectory(EraCore plugin, SimWorldDirector simWorld, NmsFakePlayerRuntime fakePlayers) {
        this.plugin=plugin;
        this.simWorld=simWorld;
        this.fakePlayers=fakePlayers;
    }

    static UUID stableOfflineUuid(String name) {
        String canonical=name==null?"":name;
        return UUID.nameUUIDFromBytes(("OfflinePlayer:"+canonical).getBytes(StandardCharsets.UTF_8));
    }

    Snapshot resolve(String requested) {
        if(requested==null || requested.trim().isEmpty()) return null;

        Player physical=onlinePlayerIgnoreCase(requested);
        if(physical!=null) {
            boolean simulated=simWorld!=null && simWorld.hasIdentity(physical.getName());
            Runtime rt=simulated?Runtime.MINEFLAYER:Runtime.HUMAN;
            String faction=simulated?simWorld.factionOfIdentity(physical.getName()):"";
            return new Snapshot(
                physical.getName(),
                physical.getUniqueId(),
                rt,
                true,
                faction,
                physical.getLocation()
            );
        }

        if(fakePlayers!=null) {
            NmsFakePlayerRuntime.BodySnapshot body=fakePlayers.snapshot(requested);
            if(body!=null) {
                String faction=simWorld==null?"":simWorld.factionOfIdentity(body.name);
                return new Snapshot(body.name,body.uuid,Runtime.COMBAT_BODY,true,faction,body.location);
            }
        }

        if(simWorld==null || !simWorld.hasIdentity(requested)) return null;
        String canonical=simWorld.canonicalIdentity(requested);
        boolean online=simWorld.isLogicalOnlineIdentity(canonical);
        return new Snapshot(
            canonical,
            stableOfflineUuid(canonical),
            online?Runtime.ABSTRACT:Runtime.OFFLINE,
            online,
            simWorld.factionOfIdentity(canonical),
            online?simWorld.logicalLocationFor(canonical):null
        );
    }

    TeleportResult teleport(Player viewer,String requested,boolean materializeAbstract) {
        Snapshot s=resolve(requested);
        if(s==null) return new TeleportResult(false,"Unknown actor: "+requested);
        if(!s.logicalOnline || s.runtime==Runtime.OFFLINE)
            return new TeleportResult(false,s.name+" is currently offline.");

        if(s.runtime==Runtime.ABSTRACT && materializeAbstract) {
            if(fakePlayers==null || !fakePlayers.enabled())
                return new TeleportResult(false,
                    s.name+" is logical-online but abstract; combat-body materialization is still gated.");
            try {
                Location at=s.location;
                if(at==null) return new TeleportResult(false,"No simulated location is available for "+s.name+".");
                fakePlayers.spawn(s.name,at,false);
                s=resolve(s.name);
            } catch(Exception ex) {
                return new TeleportResult(false,"Could not materialize "+s.name+": "+ex.getMessage());
            }
        }

        if(s.runtime==Runtime.ABSTRACT)
            return new TeleportResult(false,
                s.name+" is logical-online but not physically materialized. "+
                "Use /simactor spawn "+s.name+" after the fake-player gate passes.");

        if(s.location==null || s.location.getWorld()==null)
            return new TeleportResult(false,"No live location is available for "+s.name+".");

        viewer.teleport(s.location);
        return new TeleportResult(true,"Teleported to "+s.name+" ["+s.runtime+"].");
    }

    private Player onlinePlayerIgnoreCase(String name) {
        Player exact=Bukkit.getPlayerExact(name);
        if(exact!=null) return exact;
        for(Player p:Bukkit.getOnlinePlayers())
            if(p.getName().equalsIgnoreCase(name)) return p;
        return null;
    }
}
