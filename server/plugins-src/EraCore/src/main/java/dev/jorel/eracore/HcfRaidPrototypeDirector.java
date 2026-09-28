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
 * Small, measurable HCF raid interaction prototype.
 *
 * This is intentionally NOT the full raid system. It answers the high-value
 * question that must be answered before scaling CombatBodies: can an embodied
 * attacker recognize a real open gate/pearl line while an embodied defender
 * independently chooses to hold, call help, or retreat?
 */
@SuppressWarnings("deprecation")
final class HcfRaidPrototypeDirector {
    enum AttackerDecision { PEARL_ENTRY, HOLD_OUTSIDE, ABORT }
    enum DefenderDecision { HOLD_GATE, CALL_BACKUP, RETREAT }

    static final class AttackerContext {
        final boolean enemyGateOpen;
        final boolean validPearlLine;
        final int ourNearby;
        final int theirVisible;
        final double ourDtr;
        final double theirDtr;
        final int ourGear;
        final int theirGear;
        final String combatClass;
        final int riskTolerance;
        final int gameSense;
        final int aggression;

        AttackerContext(boolean enemyGateOpen,boolean validPearlLine,int ourNearby,int theirVisible,
                        double ourDtr,double theirDtr,int ourGear,int theirGear,String combatClass,
                        int riskTolerance,int gameSense,int aggression) {
            this.enemyGateOpen=enemyGateOpen;this.validPearlLine=validPearlLine;
            this.ourNearby=ourNearby;this.theirVisible=theirVisible;
            this.ourDtr=ourDtr;this.theirDtr=theirDtr;
            this.ourGear=ourGear;this.theirGear=theirGear;
            this.combatClass=combatClass==null?"DIAMOND":combatClass.toUpperCase(Locale.ENGLISH);
            this.riskTolerance=riskTolerance;this.gameSense=gameSense;this.aggression=aggression;
        }
    }

    static final class DefenderContext {
        final int defenderNearby;
        final int attackersVisible;
        final double defenderDtr;
        final int attackerVisibleGear;
        final int defenderGear;
        final boolean escapeRouteKnown;
        final String combatClass;
        final int riskTolerance;
        final int composure;
        final int teamwork;

        DefenderContext(int defenderNearby,int attackersVisible,double defenderDtr,
                        int attackerVisibleGear,int defenderGear,boolean escapeRouteKnown,
                        String combatClass,int riskTolerance,int composure,int teamwork) {
            this.defenderNearby=defenderNearby;this.attackersVisible=attackersVisible;
            this.defenderDtr=defenderDtr;this.attackerVisibleGear=attackerVisibleGear;
            this.defenderGear=defenderGear;this.escapeRouteKnown=escapeRouteKnown;
            this.combatClass=combatClass==null?"DIAMOND":combatClass.toUpperCase(Locale.ENGLISH);
            this.riskTolerance=riskTolerance;this.composure=composure;this.teamwork=teamwork;
        }
    }

    static final class BlockSnapshot {
        final World world;
        final int x,y,z,id;
        final byte data;
        BlockSnapshot(Block b) {
            world=b.getWorld();x=b.getX();y=b.getY();z=b.getZ();
            id=b.getTypeId();data=b.getData();
        }
        void restore() {
            Block b=world.getBlockAt(x,y,z);
            b.setTypeIdAndData(id,data,false);
        }
    }

    static final class Arena {
        final Location gate;
        final Location attackerStart;
        final Location defenderStart;
        final Location backupStart;
        final Location insideTarget;
        final Location holdPoint;
        final Location supportPoint;
        final List<BlockSnapshot> original;

        Arena(Location gate,Location attackerStart,Location defenderStart,Location backupStart,
              Location insideTarget,Location holdPoint,Location supportPoint,
              List<BlockSnapshot> original) {
            this.gate=gate;this.attackerStart=attackerStart;this.defenderStart=defenderStart;
            this.backupStart=backupStart;this.insideTarget=insideTarget;
            this.holdPoint=holdPoint;this.supportPoint=supportPoint;this.original=original;
        }
    }

    static final class ProbeSnapshot {
        final boolean complete,pass;
        final int matrixPassed,matrixTotal;
        final AttackerDecision attackerDecision;
        final DefenderDecision defenderDecision;
        final boolean lineValid,firstPearlLaunched,openGateEntry;
        final boolean defenderHeld,backupResponded,secondPearlLaunched,defenderPressure;
        final String attackerCallout,defenderCallout;

        ProbeSnapshot(boolean complete,boolean pass,int matrixPassed,int matrixTotal,
                      AttackerDecision attackerDecision,DefenderDecision defenderDecision,
                      boolean lineValid,boolean firstPearlLaunched,boolean openGateEntry,
                      boolean defenderHeld,boolean backupResponded,boolean secondPearlLaunched,
                      boolean defenderPressure,String attackerCallout,String defenderCallout) {
            this.complete=complete;this.pass=pass;
            this.matrixPassed=matrixPassed;this.matrixTotal=matrixTotal;
            this.attackerDecision=attackerDecision;this.defenderDecision=defenderDecision;
            this.lineValid=lineValid;this.firstPearlLaunched=firstPearlLaunched;
            this.openGateEntry=openGateEntry;this.defenderHeld=defenderHeld;
            this.backupResponded=backupResponded;this.secondPearlLaunched=secondPearlLaunched;
            this.defenderPressure=defenderPressure;
            this.attackerCallout=attackerCallout==null?"":attackerCallout;
            this.defenderCallout=defenderCallout==null?"":defenderCallout;
        }

        String summary() {
            return "matrix="+matrixPassed+"/"+matrixTotal+
                " attacker="+attackerDecision+
                " defender="+defenderDecision+
                " line="+lineValid+
                " pearl="+firstPearlLaunched+
                " entry="+openGateEntry+
                " hold="+defenderHeld+
                " backup="+backupResponded+
                " secondPearl="+secondPearlLaunched+
                " pressure="+defenderPressure+
                " attackerCallout="+quote(attackerCallout)+
                " defenderCallout="+quote(defenderCallout);
        }

        private static String quote(String s) {
            return "'"+(s==null?"":s.replace("'",""))+"'";
        }
    }

    private static final class Probe {
        String attacker,defender,backup;
        Arena arena;
        long startedAt,phaseStartedAt,secondPearlAt,lastPressureAt;
        int phase;
        int matrixPassed,matrixTotal;
        AttackerDecision attackerDecision;
        DefenderDecision defenderDecision;
        boolean lineValid,firstPearlLaunched,openGateEntry;
        boolean defenderHeld,backupResponded,secondPearlLaunched,defenderPressure;
        String attackerCallout="",defenderCallout="";
        boolean complete,pass;
    }

    private final EraCore plugin;
    private final NmsFakePlayerRuntime bodies;
    private Probe probe;
    private BukkitTask task;
    private Arena activeArena;

    HcfRaidPrototypeDirector(EraCore plugin,NmsFakePlayerRuntime bodies) {
        this.plugin=plugin;this.bodies=bodies;
    }

    static AttackerDecision attackerDecision(AttackerContext c) {
        if(c==null) return AttackerDecision.ABORT;
        if(!c.enemyGateOpen || !c.validPearlLine) return AttackerDecision.HOLD_OUTSIDE;

        // Support classes should normally preserve the outside support lane
        // instead of racing the Diamond through the first visible opening.
        if(("BARD".equals(c.combatClass) || "ARCHER".equals(c.combatClass)) && c.ourNearby>1)
            return AttackerDecision.HOLD_OUTSIDE;

        if(c.ourDtr<=1.01 && c.theirVisible>=c.ourNearby && c.theirDtr>1.01)
            return AttackerDecision.ABORT;

        int score=c.riskTolerance/2+c.gameSense/3+c.aggression/5;
        score+=(c.ourNearby-c.theirVisible)*14;
        score+=(c.ourGear-c.theirGear)*9;
        if(c.theirDtr<=1.01) score+=18;
        if(c.ourDtr<=1.01) score-=22;
        if("DIAMOND".equals(c.combatClass)) score+=10;
        return score>=70?AttackerDecision.PEARL_ENTRY:AttackerDecision.HOLD_OUTSIDE;
    }

    static DefenderDecision defenderDecision(DefenderContext c) {
        if(c==null) return DefenderDecision.RETREAT;

        // Low DTR plus a real gear deficit is the point where preserving the
        // faction matters more than ego-holding the doorway.
        if(c.defenderDtr<=1.01 && c.attackerVisibleGear>c.defenderGear && c.escapeRouteKnown)
            return DefenderDecision.RETREAT;

        // A defender can still physically hold the front while asking another
        // member to collapse. This is deliberately distinct from RETREAT.
        if(c.attackersVisible>c.defenderNearby || c.defenderDtr<=2.01 ||
           (c.teamwork>=72 && c.attackersVisible>=2))
            return DefenderDecision.CALL_BACKUP;

        return DefenderDecision.HOLD_GATE;
    }

    Arena prepareArena(Location near) {
        if(near==null || near.getWorld()==null) return null;
        World w=near.getWorld();
        int cx=near.getBlockX();
        int cz=near.getBlockZ();
        int y=Math.max(4,w.getHighestBlockYAt(cx,cz)+1);

        List<BlockSnapshot> old=new ArrayList<BlockSnapshot>();
        // Temporary 13x15 test pad. Every touched block is snapshotted and
        // restored after the owner-only probe.
        for(int x=cx-6;x<=cx+6;x++) for(int z=cz-7;z<=cz+7;z++) {
            Block floor=w.getBlockAt(x,y-1,z); old.add(new BlockSnapshot(floor));
            floor.setTypeIdAndData(Material.STONE.getId(),(byte)0,false);
            for(int yy=y;yy<=y+4;yy++) {
                Block air=w.getBlockAt(x,yy,z); old.add(new BlockSnapshot(air));
                air.setTypeIdAndData(Material.AIR.getId(),(byte)0,false);
            }
        }

        for(int x=cx-4;x<=cx+4;x++) for(int yy=y;yy<=y+3;yy++) {
            Block wall=w.getBlockAt(x,yy,cz);
            // already snapshotted above; only mutate here.
            boolean gate=Math.abs(x-cx)<=1 && yy<=y+2;
            wall.setTypeIdAndData(gate?Material.FENCE_GATE.getId():Material.SMOOTH_BRICK.getId(),
                gate?(byte)4:(byte)0,false);
        }

        Arena a=new Arena(
            new Location(w,cx+0.5,y,cz+0.5),
            new Location(w,cx+0.5,y,cz-6.0,0f,0f),
            new Location(w,cx+4.5,y,cz+4.0,180f,0f),
            new Location(w,cx-4.5,y,cz+5.0,180f,0f),
            new Location(w,cx+0.5,y+0.2,cz+3.0),
            new Location(w,cx+0.5,y,cz+1.25),
            new Location(w,cx+1.8,y,cz+2.5),
            old
        );
        activeArena=a;
        return a;
    }

    boolean startProbe(String attacker,String defender,String backup,Arena arena) {
        if(attacker==null || defender==null || backup==null || arena==null) return false;
        if(probe!=null && !probe.complete) return false;
        if(bodies.player(attacker)==null || bodies.player(defender)==null || bodies.player(backup)==null)
            return false;

        Probe p=new Probe();
        p.attacker=attacker;p.defender=defender;p.backup=backup;p.arena=arena;
        int[] matrix=decisionMatrix();
        p.matrixPassed=matrix[0];p.matrixTotal=matrix[1];

        setGateOpen(arena,true);
        p.lineValid=validPearlLine(bodies.player(attacker).getEyeLocation(),arena.insideTarget);
        p.attackerDecision=attackerDecision(new AttackerContext(
            true,p.lineValid,2,0,3.0,2.0,3,3,"DIAMOND",82,84,76));
        p.defenderDecision=defenderDecision(new DefenderContext(
            1,2,2.0,3,3,true,"DIAMOND",62,74,84));

        p.startedAt=System.currentTimeMillis();
        p.phaseStartedAt=p.startedAt;
        probe=p;
        if(task!=null) task.cancel();
        task=plugin.getServer().getScheduler().runTaskTimer(plugin,new Runnable() {
            public void run(){tick();}
        },2L,2L);

        plugin.getLogger().info("[Raid Prototype] START attacker="+attacker+
            " defender="+defender+" backup="+backup+
            " matrix="+p.matrixPassed+"/"+p.matrixTotal+
            " attackerDecision="+p.attackerDecision+
            " defenderDecision="+p.defenderDecision+
            " line="+p.lineValid+
            " criteria=12/12+openGatePearl+entry+hold+backup+secondPearl+pressure");
        return true;
    }

    ProbeSnapshot snapshot() {
        Probe p=probe;
        if(p==null) return null;
        return new ProbeSnapshot(p.complete,p.pass,p.matrixPassed,p.matrixTotal,
            p.attackerDecision,p.defenderDecision,p.lineValid,p.firstPearlLaunched,
            p.openGateEntry,p.defenderHeld,p.backupResponded,p.secondPearlLaunched,
            p.defenderPressure,p.attackerCallout,p.defenderCallout);
    }

    void cleanupArena() {
        if(activeArena==null) return;
        // reverse order because the same cells may have been snapshotted more
        // than once by future extensions.
        for(int i=activeArena.original.size()-1;i>=0;i--)
            activeArena.original.get(i).restore();
        activeArena=null;
    }

    void stop() {
        if(task!=null) task.cancel();
        task=null;
        probe=null;
        cleanupArena();
    }

    private void tick() {
        final Probe p=probe;
        if(p==null || p.complete) {
            if(task!=null) task.cancel();
            task=null;
            return;
        }

        Player attacker=bodies.player(p.attacker);
        Player defender=bodies.player(p.defender);
        Player backup=bodies.player(p.backup);
        if(attacker==null || defender==null || backup==null) {
            finish(p,false,"body-missing");
            return;
        }

        long now=System.currentTimeMillis();

        // Phase 0: prove that an actually open 3x3 fence-gate line can be
        // recognized and physically pearled through by a CombatBody.
        if(p.phase==0) {
            if(p.matrixPassed!=p.matrixTotal || p.attackerDecision!=AttackerDecision.PEARL_ENTRY ||
               !p.lineValid) {
                finish(p,false,"decision-or-line");
                return;
            }
            if(!p.firstPearlLaunched && now-p.phaseStartedAt>=450L) {
                int before=bodies.combatPearlCount(p.attacker);
                p.firstPearlLaunched=bodies.combatThrowPearl(p.attacker,p.arena.insideTarget) &&
                    bodies.combatPearlCount(p.attacker)<before;
            }
            if(p.firstPearlLaunched &&
               attacker.getLocation().getZ()>p.arena.gate.getZ()+1.0) {
                p.openGateEntry=true;
                p.attackerCallout="pearled in gate still open";
                // Reset the exact same body for the defended repetition.
                bodies.combatSetPosition(p.attacker,p.arena.attackerStart);
                bodies.combatSetPosition(p.defender,p.arena.defenderStart);
                bodies.combatSetPosition(p.backup,p.arena.backupStart);
                setGateOpen(p.arena,true);
                p.phase=1;
                p.phaseStartedAt=now;
                return;
            }
            if(now-p.phaseStartedAt>4200L) {
                finish(p,false,"open-gate-pearl-timeout");
                return;
            }
            return;
        }

        // Phase 1: the defender independently chooses CALL_BACKUP while still
        // moving to physically body-block the gate. The backup collapses instead
        // of idling elsewhere in the base.
        if(p.phase==1) {
            if(p.defenderDecision==DefenderDecision.RETREAT) {
                finish(p,false,"unexpected-retreat");
                return;
            }

            bodies.combatMoveToward(p.defender,p.arena.holdPoint,0.28,0.0);
            if(p.defenderDecision==DefenderDecision.CALL_BACKUP)
                bodies.combatMoveToward(p.backup,p.arena.supportPoint,0.30,0.03);

            p.defenderHeld=defender.getLocation().distanceSquared(p.arena.holdPoint)<=1.35;
            p.backupResponded=backup.getLocation().distanceSquared(p.arena.supportPoint)<=2.75;

            if(p.defenderHeld && p.defenderCallout.isEmpty())
                p.defenderCallout="one at gate come base hold front";

            if(p.defenderHeld && p.backupResponded && !p.secondPearlLaunched) {
                int before=bodies.combatPearlCount(p.attacker);
                p.secondPearlLaunched=bodies.combatThrowPearl(p.attacker,p.arena.insideTarget) &&
                    bodies.combatPearlCount(p.attacker)<before;
                if(p.secondPearlLaunched) p.secondPearlAt=now;
            }

            if(p.secondPearlLaunched && now-p.secondPearlAt>=650L &&
               now-p.lastPressureAt>=550L &&
               defender.getLocation().distanceSquared(attacker.getLocation())<=10.0) {
                p.lastPressureAt=now;
                p.defenderPressure=bodies.combatAttack(p.defender,p.attacker,true) || p.defenderPressure;
            }

            if(p.secondPearlLaunched && now-p.secondPearlAt>=2800L) {
                boolean pass=p.matrixPassed==p.matrixTotal &&
                    p.firstPearlLaunched && p.openGateEntry &&
                    p.defenderHeld && p.backupResponded &&
                    p.secondPearlLaunched && p.defenderPressure;
                finish(p,pass,"defended-gate");
                return;
            }

            if(now-p.phaseStartedAt>6500L) {
                finish(p,false,"defense-timeout");
            }
        }
    }

    private void finish(Probe p,boolean pass,String reason) {
        p.complete=true;p.pass=pass;
        plugin.getLogger().info("[Raid Prototype] "+(pass?"PASS":"FAIL")+
            " reason="+reason+" "+snapshot().summary());
        if(task!=null) task.cancel();
        task=null;
    }

    private boolean validPearlLine(Location from,Location to) {
        if(from==null || to==null || from.getWorld()==null ||
           !from.getWorld().equals(to.getWorld())) return false;
        World w=from.getWorld();
        double dx=to.getX()-from.getX(),dy=to.getY()-from.getY(),dz=to.getZ()-from.getZ();
        double distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
        int steps=Math.max(1,(int)Math.ceil(distance/0.22));
        for(int i=1;i<steps;i++) {
            double t=(double)i/(double)steps;
            Block b=w.getBlockAt(
                (int)Math.floor(from.getX()+dx*t),
                (int)Math.floor(from.getY()+dy*t),
                (int)Math.floor(from.getZ()+dz*t));
            Material m=b.getType();
            if(m==Material.AIR) continue;
            if(m==Material.FENCE_GATE && (b.getData()&0x4)!=0) continue;
            if(m.isSolid()) return false;
        }
        return true;
    }

    private void setGateOpen(Arena a,boolean open) {
        int cx=a.gate.getBlockX(),y=a.gate.getBlockY(),cz=a.gate.getBlockZ();
        for(int x=cx-1;x<=cx+1;x++) for(int yy=y;yy<=y+2;yy++) {
            Block b=a.gate.getWorld().getBlockAt(x,yy,cz);
            if(b.getType()!=Material.FENCE_GATE) continue;
            byte data=b.getData();
            b.setData(open?(byte)(data|0x4):(byte)(data&~0x4),false);
        }
    }

    private static int[] decisionMatrix() {
        int pass=0,total=0;

        total++; if(attackerDecision(new AttackerContext(
            true,true,2,1,3.0,2.0,3,3,"DIAMOND",80,82,75))==AttackerDecision.PEARL_ENTRY) pass++;
        total++; if(attackerDecision(new AttackerContext(
            false,true,2,1,3.0,2.0,3,3,"DIAMOND",90,90,90))==AttackerDecision.HOLD_OUTSIDE) pass++;
        total++; if(attackerDecision(new AttackerContext(
            true,false,2,1,3.0,2.0,3,3,"DIAMOND",90,90,90))==AttackerDecision.HOLD_OUTSIDE) pass++;
        total++; if(attackerDecision(new AttackerContext(
            true,true,3,1,3.0,1.0,2,3,"BARD",90,90,90))==AttackerDecision.HOLD_OUTSIDE) pass++;
        total++; if(attackerDecision(new AttackerContext(
            true,true,1,2,0.8,3.0,3,3,"DIAMOND",55,70,65))==AttackerDecision.ABORT) pass++;
        total++; if(attackerDecision(new AttackerContext(
            true,true,3,1,3.0,0.8,3,2,"DIAMOND",50,68,60))==AttackerDecision.PEARL_ENTRY) pass++;

        total++; if(defenderDecision(new DefenderContext(
            2,1,3.0,3,3,true,"DIAMOND",50,70,60))==DefenderDecision.HOLD_GATE) pass++;
        total++; if(defenderDecision(new DefenderContext(
            1,2,3.0,3,3,true,"DIAMOND",60,70,80))==DefenderDecision.CALL_BACKUP) pass++;
        total++; if(defenderDecision(new DefenderContext(
            1,1,0.8,4,3,true,"DIAMOND",50,75,70))==DefenderDecision.RETREAT) pass++;
        total++; if(defenderDecision(new DefenderContext(
            1,1,0.8,3,3,false,"DIAMOND",50,75,70))==DefenderDecision.CALL_BACKUP) pass++;
        total++; if(defenderDecision(new DefenderContext(
            3,1,2.7,3,3,true,"DIAMOND",55,80,75))==DefenderDecision.HOLD_GATE) pass++;
        total++; if(defenderDecision(new DefenderContext(
            2,2,3.0,3,3,true,"BARD",50,80,85))==DefenderDecision.CALL_BACKUP) pass++;

        return new int[]{pass,total};
    }
}
