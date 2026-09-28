package dev.jorel.eracore;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 6 real-base raid integration.
 *
 * Unlike HcfRaidPrototypeDirector, this class never builds a test arena. It
 * locates the defender faction's actual schematic-derived gate, reads the live
 * SimWorld policy, and drives CombatBodies against that geometry. The existing
 * raid prototype remains the deterministic regression test for pearl physics.
 */
final class HcfLiveRaidDirector {
    static final class Arena {
        final String defenderFaction;
        final Location baseCenter,gate,attackerStart,defenderStart,backupStart;
        final Location supportStart,insideTarget,holdPoint,backupPoint,retreatPoint;
        final double outwardX,outwardZ,lateralX,lateralZ;
        final List<Block> gateGroup;
        boolean originallyOpen;

        Arena(String defenderFaction,Location baseCenter,Location gate,
              Location attackerStart,Location defenderStart,Location backupStart,
              Location supportStart,Location insideTarget,Location holdPoint,
              Location backupPoint,Location retreatPoint,
              double outwardX,double outwardZ,double lateralX,double lateralZ,
              List<Block> gateGroup) {
            this.defenderFaction=defenderFaction;
            this.baseCenter=baseCenter;this.gate=gate;
            this.attackerStart=attackerStart;this.defenderStart=defenderStart;
            this.backupStart=backupStart;this.supportStart=supportStart;
            this.insideTarget=insideTarget;this.holdPoint=holdPoint;
            this.backupPoint=backupPoint;this.retreatPoint=retreatPoint;
            this.outwardX=outwardX;this.outwardZ=outwardZ;
            this.lateralX=lateralX;this.lateralZ=lateralZ;
            this.gateGroup=gateGroup;
        }
    }

    static final class Snapshot {
        final boolean complete,pass,realGate,gateOpen,lineValid;
        final HcfRaidPolicy.AttackerDecision attackerDecision,supportDecision;
        final HcfRaidPolicy.DefenderDecision defenderDecision;
        final boolean firstPearl,attackerEntry,defenderHeld,backupResponded;
        final boolean defenderRetreated,attackerCallout,supportResolved,supportFollowed;
        final boolean pressure;
        final int gateBlocks;
        final String outcome;

        Snapshot(Probe p) {
            complete=p.complete;pass=p.pass;realGate=p.arena!=null && p.arena.gateGroup!=null && !p.arena.gateGroup.isEmpty();
            gateOpen=p.gateOpen;lineValid=p.lineValid;
            attackerDecision=p.attackerDecision;supportDecision=p.supportDecision;
            defenderDecision=p.defenderDecision;
            firstPearl=p.firstPearl;attackerEntry=p.attackerEntry;
            defenderHeld=p.defenderHeld;backupResponded=p.backupResponded;
            defenderRetreated=p.defenderRetreated;attackerCallout=p.attackerCallout;
            supportResolved=p.supportResolved;supportFollowed=p.supportFollowed;
            pressure=p.pressure;
            gateBlocks=p.arena==null || p.arena.gateGroup==null?0:p.arena.gateGroup.size();
            outcome=p.outcome==null?"":p.outcome;
        }

        String summary() {
            return "realGate="+realGate+
                " gateBlocks="+gateBlocks+
                " gateOpen="+gateOpen+
                " line="+lineValid+
                " attacker="+attackerDecision+
                " defender="+defenderDecision+
                " support="+(supportDecision==null?"NONE":supportDecision.name())+
                " pearl="+firstPearl+
                " entry="+attackerEntry+
                " hold="+defenderHeld+
                " backup="+backupResponded+
                " retreat="+defenderRetreated+
                " attackerChat="+attackerCallout+
                " supportResolved="+supportResolved+
                " supportFollowed="+supportFollowed+
                " pressure="+pressure+
                " outcome="+outcome;
        }
    }

    private static final class Probe {
        String attacker,defender,backup,support;
        String attackerFaction,defenderFaction;
        Arena arena;
        HcfRaidPolicy.AttackerDecision attackerDecision,supportDecision;
        HcfRaidPolicy.DefenderDecision defenderDecision;
        long startedAt,lastAttackAt;
        boolean gateOpen,lineValid,firstPearl,attackerEntry;
        boolean defenderHeld,backupResponded,defenderRetreated;
        boolean defenderCallout,attackerCallout;
        boolean supportResolved,supportPearl,supportFollowed;
        boolean pressure,complete,pass;
        String outcome="";
    }

    private final EraCore plugin;
    private final NmsFakePlayerRuntime bodies;
    private final SimWorldDirector simWorld;
    private final HcfGateDirector gates;
    private Probe probe;
    private Arena activeArena;
    private BukkitTask task;

    HcfLiveRaidDirector(EraCore plugin,NmsFakePlayerRuntime bodies,
                        SimWorldDirector simWorld,HcfGateDirector gates) {
        this.plugin=plugin;this.bodies=bodies;this.simWorld=simWorld;this.gates=gates;
    }

    Arena prepareArena(String defenderFaction) {
        Location center=simWorld.raidBaseCenter(defenderFaction);
        Location expected=simWorld.raidBaseGateLocation(defenderFaction);
        if(center==null || expected==null || center.getWorld()==null ||
           !center.getWorld().equals(expected.getWorld())) return null;

        List<Block> group=gates.connectedGateGroupNear(expected,5);
        if(group==null || group.isEmpty()) return null;

        Block primary=group.get(0);
        double best=Double.MAX_VALUE;
        for(Block b:group) {
            double dx=(b.getX()+0.5)-expected.getX();
            double dy=b.getY()-expected.getY();
            double dz=(b.getZ()+0.5)-expected.getZ();
            double d=dx*dx+dy*dy+dz*dz;
            if(d<best) {best=d;primary=b;}
        }

        Location gate=new Location(primary.getWorld(),primary.getX()+0.5,primary.getY(),primary.getZ()+0.5);
        double dx=gate.getX()-center.getX(),dz=gate.getZ()-center.getZ();
        double ox=0.0,oz=0.0;
        if(Math.abs(dx)>=Math.abs(dz)) ox=dx>=0?1.0:-1.0;
        else oz=dz>=0?1.0:-1.0;
        if(ox==0.0 && oz==0.0) oz=-1.0;
        double lx=-oz,lz=ox;

        Location attackerStart=standingPoint(gate.clone().add(ox*5.5,0,oz*5.5));
        Location supportStart=standingPoint(gate.clone().add(ox*5.0,0,oz*5.0).add(lx*2.2,0,lz*2.2));
        Location defenderStart=standingPoint(gate.clone().add(-ox*3.8,0,-oz*3.8).add(lx*2.0,0,lz*2.0));
        Location backupStart=standingPoint(gate.clone().add(-ox*5.5,0,-oz*5.5).add(-lx*2.0,0,-lz*2.0));
        Location holdPoint=standingPoint(gate.clone().add(-ox*1.25,0,-oz*1.25));
        Location backupPoint=standingPoint(gate.clone().add(-ox*2.8,0,-oz*2.8).add(-lx*1.6,0,-lz*1.6));
        Location retreatPoint=standingPoint(gate.clone().add(-ox*6.0,0,-oz*6.0));
        Location insideTarget=gate.clone().add(-ox*2.8,0.20,-oz*2.8);

        Arena a=new Arena(defenderFaction,center,gate,attackerStart,defenderStart,backupStart,
            supportStart,insideTarget,holdPoint,backupPoint,retreatPoint,
            ox,oz,lx,lz,new ArrayList<Block>(group));
        activeArena=a;
        return a;
    }

    boolean start(String attacker,String defender,String backup,String support,Arena arena) {
        if(attacker==null || defender==null || backup==null || arena==null) return false;
        if(probe!=null && !probe.complete) return false;
        if(bodies.player(attacker)==null || bodies.player(defender)==null || bodies.player(backup)==null)
            return false;
        if(support!=null && !support.isEmpty() && bodies.player(support)==null) return false;

        Probe p=new Probe();
        p.attacker=attacker;p.defender=defender;p.backup=backup;
        p.support=support==null?"":support;p.arena=arena;
        p.attackerFaction=simWorld.factionOfIdentity(attacker);
        p.defenderFaction=simWorld.factionOfIdentity(defender);
        if(p.attackerFaction.isEmpty() || p.defenderFaction.isEmpty() ||
           p.attackerFaction.equalsIgnoreCase(p.defenderFaction)) return false;

        arena.originallyOpen=gates.isGroupOpen(arena.gateGroup);
        gates.setGroupOpen(arena.gateGroup,true);
        p.gateOpen=gates.isGroupOpen(arena.gateGroup);

        Player attackBody=bodies.player(attacker);
        p.lineValid=attackBody!=null &&
            HcfRaidPrototypeDirector.validPearlLine(attackBody.getEyeLocation(),arena.insideTarget);
        p.attackerDecision=simWorld.raidAttackerDecision(
            attacker,p.defenderFaction,p.gateOpen,p.lineValid);
        p.defenderDecision=simWorld.raidDefenderDecision(defender,p.attackerFaction);

        if(!p.support.isEmpty()) {
            Player supportBody=bodies.player(p.support);
            boolean supportLine=supportBody!=null &&
                HcfRaidPrototypeDirector.validPearlLine(supportBody.getEyeLocation(),arena.insideTarget);
            p.supportDecision=simWorld.raidAttackerDecision(
                p.support,p.defenderFaction,p.gateOpen,supportLine);
        }

        p.startedAt=System.currentTimeMillis();
        probe=p;
        if(task!=null) task.cancel();
        task=plugin.getServer().getScheduler().runTaskTimer(plugin,new Runnable() {
            public void run(){tick();}
        },2L,2L);

        plugin.getLogger().info("[Phase 6 Live Raid] START base="+p.defenderFaction+
            " gateBlocks="+arena.gateGroup.size()+
            " attacker="+attacker+" attackerDecision="+p.attackerDecision+
            " defender="+defender+" defenderDecision="+p.defenderDecision+
            " support="+(p.support.isEmpty()?"none":p.support)+
            " supportDecision="+(p.supportDecision==null?"NONE":p.supportDecision)+
            " gateOpen="+p.gateOpen+" line="+p.lineValid);
        return true;
    }

    Snapshot snapshot() {
        Probe p=probe;
        return p==null?null:new Snapshot(p);
    }

    void cleanupArena() {
        Arena a=activeArena;
        if(a==null) return;
        gates.setGroupOpen(a.gateGroup,a.originallyOpen);
        activeArena=null;
    }

    void stop() {
        if(task!=null) task.cancel();
        task=null;
        cleanupArena();
        probe=null;
    }

    private void tick() {
        Probe p=probe;
        if(p==null || p.complete) {
            if(task!=null) task.cancel();
            task=null;
            return;
        }

        Player attacker=bodies.player(p.attacker);
        Player defender=bodies.player(p.defender);
        Player backup=bodies.player(p.backup);
        Player support=p.support.isEmpty()?null:bodies.player(p.support);
        if(attacker==null || defender==null || backup==null ||
           (!p.support.isEmpty() && support==null)) {
            finish(p,false,"body-missing");
            return;
        }

        long now=System.currentTimeMillis();
        driveDefense(p,defender,backup);

        if(p.attackerDecision==HcfRaidPolicy.AttackerDecision.ABORT) {
            bodies.combatMoveToward(p.attacker,
                p.arena.attackerStart.clone().add(p.arena.outwardX*3.0,0,p.arena.outwardZ*3.0),
                0.28,0.0);
            if(now-p.startedAt>=1500L) maybeFinish(p,"attacker-abort");
            return;
        }

        if(p.attackerDecision==HcfRaidPolicy.AttackerDecision.HOLD_OUTSIDE) {
            bodies.combatMoveToward(p.attacker,p.arena.attackerStart,0.20,0.05);
            if(now-p.startedAt>=1800L) maybeFinish(p,"attacker-hold");
            return;
        }

        if(!p.gateOpen || !p.lineValid) {
            finish(p,false,"entry-policy-without-open-line");
            return;
        }

        if(!p.firstPearl && now-p.startedAt>=500L) {
            int before=bodies.combatPearlCount(p.attacker);
            p.firstPearl=bodies.combatThrowPearl(p.attacker,p.arena.insideTarget) &&
                bodies.combatPearlCount(p.attacker)<before;
        }

        if(p.firstPearl && isInside(attacker.getLocation(),p.arena)) {
            p.attackerEntry=true;
            if(!p.attackerCallout) {
                boolean stillOpen=gates.isGroupOpen(p.arena.gateGroup);
                plugin.broadcastSimulatedFactionChat(p.attackerFaction,p.attacker,
                    stillOpen?"im in gate still open":"im in gate closed");
                p.attackerCallout=true;
            }
        }

        driveSupport(p,support,now);

        if(p.attackerEntry) {
            if(p.defenderDecision!=HcfRaidPolicy.DefenderDecision.RETREAT) {
                bodies.combatMoveToward(p.attacker,defender.getLocation(),0.24,0.08);
                if(now-p.lastAttackAt>=560L &&
                   attacker.getLocation().distanceSquared(defender.getLocation())<=10.0) {
                    boolean aHit=bodies.combatAttack(p.attacker,p.defender,true);
                    boolean dHit=bodies.combatAttack(p.defender,p.attacker,true);
                    p.pressure=p.pressure || aHit || dHit;
                    p.lastAttackAt=now;
                }
                emergencyPot(p.attacker,attacker);
                emergencyPot(p.defender,defender);
            }
            if(now-p.startedAt>=5200L) maybeFinish(p,"entry-window");
        } else if(now-p.startedAt>4800L) {
            finish(p,false,"pearl-entry-timeout");
        }
    }

    private void driveDefense(Probe p,Player defender,Player backup) {
        if(p.defenderDecision==HcfRaidPolicy.DefenderDecision.RETREAT) {
            bodies.combatMoveToward(p.defender,p.arena.retreatPoint,0.30,0.02);
            p.defenderRetreated=defender.getLocation().distanceSquared(p.arena.retreatPoint)<=3.25;
            if(!p.defenderCallout) {
                plugin.broadcastSimulatedFactionChat(p.defenderFaction,p.defender,
                    "backing up dont feed dtr");
                p.defenderCallout=true;
            }
            return;
        }

        bodies.combatMoveToward(p.defender,p.arena.holdPoint,0.28,0.0);
        p.defenderHeld=defender.getLocation().distanceSquared(p.arena.holdPoint)<=2.25;

        if(p.defenderDecision==HcfRaidPolicy.DefenderDecision.CALL_BACKUP) {
            bodies.combatMoveToward(p.backup,p.arena.backupPoint,0.30,0.04);
            p.backupResponded=backup.getLocation().distanceSquared(p.arena.backupPoint)<=3.25;
            if(!p.defenderCallout) {
                plugin.broadcastSimulatedFactionChat(p.defenderFaction,p.defender,
                    "one at gate come base hold front");
                p.defenderCallout=true;
            }
        }
    }

    private void driveSupport(Probe p,Player support,long now) {
        if(p.support.isEmpty()) { p.supportResolved=true;return; }
        if(support==null || p.supportDecision==null) return;

        if(p.supportDecision==HcfRaidPolicy.AttackerDecision.PEARL_ENTRY) {
            if(!p.attackerEntry) return;
            if(!gates.isGroupOpen(p.arena.gateGroup)) {
                p.supportResolved=true;
                return;
            }
            if(!p.supportPearl && now-p.startedAt>=1200L) {
                boolean line=HcfRaidPrototypeDirector.validPearlLine(
                    support.getEyeLocation(),p.arena.insideTarget);
                if(!line) {p.supportResolved=true;return;}
                int before=bodies.combatPearlCount(p.support);
                p.supportPearl=bodies.combatThrowPearl(p.support,p.arena.insideTarget) &&
                    bodies.combatPearlCount(p.support)<before;
            }
            if(p.supportPearl && isInside(support.getLocation(),p.arena)) {
                p.supportFollowed=true;p.supportResolved=true;
            }
            return;
        }

        Location outside=p.arena.supportStart.clone();
        if(p.supportDecision==HcfRaidPolicy.AttackerDecision.ABORT)
            outside.add(p.arena.outwardX*2.5,0,p.arena.outwardZ*2.5);
        bodies.combatMoveToward(p.support,outside,0.24,0.06);
        if(isOutside(support.getLocation(),p.arena)) p.supportResolved=true;
    }

    private void emergencyPot(String name,Player body) {
        if(body==null || body.isDead() || body.getHealth()>9.0) return;
        if(bodies.combatHealPotionCount(name)>0) bodies.combatSplashHealAtFeet(name);
    }

    private void maybeFinish(Probe p,String outcome) {
        boolean attackerOk;
        if(p.attackerDecision==HcfRaidPolicy.AttackerDecision.PEARL_ENTRY)
            attackerOk=p.firstPearl && p.attackerEntry && p.attackerCallout;
        else attackerOk=isOutside(bodies.player(p.attacker).getLocation(),p.arena);

        boolean defenderOk;
        if(p.defenderDecision==HcfRaidPolicy.DefenderDecision.RETREAT)
            defenderOk=p.defenderRetreated;
        else if(p.defenderDecision==HcfRaidPolicy.DefenderDecision.CALL_BACKUP)
            defenderOk=p.defenderHeld && p.backupResponded && p.defenderCallout;
        else defenderOk=p.defenderHeld;

        boolean supportOk=p.support.isEmpty() || p.supportResolved;
        if(p.supportDecision==HcfRaidPolicy.AttackerDecision.PEARL_ENTRY)
            supportOk=p.supportResolved && p.supportFollowed;

        boolean pressureOk=p.attackerDecision!=HcfRaidPolicy.AttackerDecision.PEARL_ENTRY ||
            p.defenderDecision==HcfRaidPolicy.DefenderDecision.RETREAT || p.pressure;

        boolean pass=p.gateOpen && p.lineValid && attackerOk && defenderOk && supportOk && pressureOk;
        finish(p,pass,outcome);
    }

    private void finish(Probe p,boolean pass,String outcome) {
        p.complete=true;p.pass=pass;p.outcome=outcome;
        Snapshot s=new Snapshot(p);
        plugin.getLogger().info("[Phase 6 Live Raid] "+(pass?"PASS":"FAIL")+" "+s.summary());
        if(task!=null) task.cancel();
        task=null;
        cleanupArena();
    }

    private boolean isInside(Location at,Arena a) {
        if(at==null || a==null || at.getWorld()==null || !at.getWorld().equals(a.gate.getWorld()))
            return false;
        double side=(at.getX()-a.gate.getX())*a.outwardX+
            (at.getZ()-a.gate.getZ())*a.outwardZ;
        return side<-0.35;
    }

    private boolean isOutside(Location at,Arena a) {
        if(at==null || a==null || at.getWorld()==null || !at.getWorld().equals(a.gate.getWorld()))
            return false;
        double side=(at.getX()-a.gate.getX())*a.outwardX+
            (at.getZ()-a.gate.getZ())*a.outwardZ;
        return side>0.75;
    }

    private Location standingPoint(Location desired) {
        if(desired==null || desired.getWorld()==null) return desired;
        World w=desired.getWorld();
        int bx=desired.getBlockX(),bz=desired.getBlockZ(),base=desired.getBlockY();
        int[] yOffsets={0,1,-1,2,-2,3,-3};
        for(int radius=0;radius<=2;radius++) {
            for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++) {
                if(radius>0 && Math.abs(dx)!=radius && Math.abs(dz)!=radius) continue;
                for(int dy:yOffsets) {
                    int y=base+dy;
                    if(y<=1 || y+1>=w.getMaxHeight()) continue;
                    if(bodyColumnClear(w,bx+dx,y,bz+dz) &&
                       w.getBlockAt(bx+dx,y-1,bz+dz).getType().isSolid())
                        return new Location(w,bx+dx+0.5,y,bz+dz+0.5,desired.getYaw(),desired.getPitch());
                }
            }
        }
        return desired.clone();
    }

    private boolean bodyColumnClear(World w,int x,int y,int z) {
        return passable(w.getBlockAt(x,y,z)) && passable(w.getBlockAt(x,y+1,z));
    }

    private boolean passable(Block b) {
        Material m=b.getType();
        return m==Material.AIR || (m==Material.FENCE_GATE && gates.isOpen(b));
    }
}
