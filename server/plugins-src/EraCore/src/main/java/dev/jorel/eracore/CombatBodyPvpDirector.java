package dev.jorel.eracore;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Gate-3 CombatBody mechanics controller.
 *
 * This deliberately starts small: it proves that server-side EntityPlayers can
 * perform the same core HCF mechanics the Mineflayer R24 path already models:
 * movement/strafe, real NMS melee hits, sprint-reset W-taps, and straight-down
 * splash Healing II from physical inventory.
 */
final class CombatBodyPvpDirector {
    static final class ProbeSnapshot {
        final boolean complete;
        final boolean pass;
        final boolean movement;
        final int landedHits;
        final int wTaps;
        final int pots;
        final boolean healObserved;
        final double maxPostPotHealth;

        ProbeSnapshot(boolean complete,boolean pass,boolean movement,int landedHits,
                      int wTaps,int pots,boolean healObserved,double maxPostPotHealth) {
            this.complete=complete;this.pass=pass;this.movement=movement;
            this.landedHits=landedHits;this.wTaps=wTaps;this.pots=pots;
            this.healObserved=healObserved;this.maxPostPotHealth=maxPostPotHealth;
        }

        String summary() {
            return "movement="+movement+
                " landedHits="+landedHits+
                " wTaps="+wTaps+
                " pots="+pots+
                " healObserved="+healObserved+
                " maxPostPotHealth="+String.format(java.util.Locale.US,"%.1f",maxPostPotHealth);
        }
    }

    private static final class Probe {
        String a,b;
        Location startA,startB;
        long startedAt;
        long combatStartsAt;
        long endsAt;
        long lastAttackA,lastAttackB;
        long lastPotA,lastPotB;
        int attacksA,attacksB;
        int landedHits;
        int wTaps;
        int pots;
        boolean movement;
        boolean healObserved;
        double maxPostPotHealth=8.0;
        boolean complete;
        boolean pass;
    }

    private final EraCore plugin;
    private final NmsFakePlayerRuntime bodies;
    private Probe probe;
    private BukkitTask task;

    CombatBodyPvpDirector(EraCore plugin,NmsFakePlayerRuntime bodies) {
        this.plugin=plugin;this.bodies=bodies;
    }

    boolean startProbe(String a,String b) {
        if(a==null || b==null || a.equalsIgnoreCase(b)) return false;
        if(probe!=null && !probe.complete) return false;
        Player pa=bodies.player(a),pb=bodies.player(b);
        if(pa==null || pb==null || pa.getWorld()!=pb.getWorld()) return false;

        Probe p=new Probe();
        p.a=a;p.b=b;
        p.startA=pa.getLocation().clone();
        p.startB=pb.getLocation().clone();
        p.startedAt=System.currentTimeMillis();
        p.combatStartsAt=p.startedAt+1400L;
        p.endsAt=p.startedAt+9000L;
        try {pa.setHealth(8.0);} catch(Throwable ignored){}
        p.maxPostPotHealth=8.0;
        probe=p;

        if(task!=null) task.cancel();
        task=plugin.getServer().getScheduler().runTaskTimer(plugin,new Runnable(){
            public void run(){tick();}
        },1L,2L);
        plugin.getLogger().info("[CombatBody Gate3] START "+a+" vs "+b+
            " criteria=movement+2hits+1wtap+1physicalHealII+healthGain");
        return true;
    }

    ProbeSnapshot snapshot() {
        Probe p=probe;
        if(p==null) return null;
        return new ProbeSnapshot(p.complete,p.pass,p.movement,p.landedHits,p.wTaps,
            p.pots,p.healObserved,p.maxPostPotHealth);
    }

    void stop() {
        if(task!=null) task.cancel();
        task=null;
        probe=null;
    }

    private void tick() {
        Probe p=probe;
        if(p==null || p.complete) {
            if(task!=null) task.cancel();
            task=null;
            return;
        }

        Player a=bodies.player(p.a),b=bodies.player(p.b);
        if(a==null || b==null || a.isDead() || b.isDead()) {
            finish(p,false,"body-missing-or-dead");
            return;
        }

        long now=System.currentTimeMillis();
        double da=p.startA.distanceSquared(a.getLocation());
        double db=p.startB.distanceSquared(b.getLocation());
        if(da>=0.64 || db>=0.64) p.movement=true;

        if(p.pots>0) {
            double hp=a.getHealth();
            if(hp>p.maxPostPotHealth) p.maxPostPotHealth=hp;
            if(hp>8.5) p.healObserved=true;
        }

        // Prove the straight-down pot before allowing the opponent to pressure.
        if(now<p.combatStartsAt) {
            bodies.combatMoveToward(p.b,a.getLocation(),0.18,0.08);
            if(p.pots==0 && now-p.lastPotA>=600L && a.getHealth()<=12.0) {
                int before=bodies.combatHealPotionCount(p.a);
                if(bodies.combatSplashHealAtFeet(p.a) &&
                   bodies.combatHealPotionCount(p.a)<before) {
                    p.pots++;
                    p.lastPotA=now;
                }
            }
            return;
        }

        drive(p,p.a,p.b,true,now);
        drive(p,p.b,p.a,false,now);

        if(now>=p.endsAt) {
            boolean pass=p.movement && p.landedHits>=2 && p.wTaps>=1 &&
                p.pots>=1 && p.healObserved;
            finish(p,pass,"deadline");
        }
    }

    private void drive(Probe p,String self,String target,boolean left,long now) {
        Player me=bodies.player(self),enemy=bodies.player(target);
        if(me==null || enemy==null) return;

        // R24-style emergency priority: pot before greedily taking another hit.
        if(me.getHealth()<=10.0) {
            long last=left?p.lastPotA:p.lastPotB;
            if(now-last>=1400L && bodies.combatHealPotionCount(self)>0) {
                int before=bodies.combatHealPotionCount(self);
                if(bodies.combatSplashHealAtFeet(self) &&
                   bodies.combatHealPotionCount(self)<before) {
                    p.pots++;
                    if(left)p.lastPotA=now; else p.lastPotB=now;
                    return;
                }
            }
        }

        double d2=me.getLocation().distanceSquared(enemy.getLocation());
        int seq=left?p.attacksA:p.attacksB;
        double strafe=((seq/2)&1)==0?(left?0.11:-0.11):(left?-0.11:0.11);
        if(d2>8.4) {
            bodies.combatMoveToward(self,enemy.getLocation(),0.31,strafe);
            return;
        }

        long last=left?p.lastAttackA:p.lastAttackB;
        if(now-last<240L) {
            bodies.combatMoveToward(self,enemy.getLocation(),0.12,strafe);
            return;
        }

        boolean wTap=(seq%2)==1;
        boolean landed=bodies.combatAttack(self,target,wTap);
        if(left){p.lastAttackA=now;p.attacksA++;}
        else {p.lastAttackB=now;p.attacksB++;}
        if(landed) {
            p.landedHits++;
            if(wTap) p.wTaps++;
        }
    }

    private void finish(Probe p,boolean pass,String reason) {
        p.complete=true;
        p.pass=pass;
        plugin.getLogger().info("[CombatBody Gate3] "+(pass?"PASS":"FAIL")+
            " reason="+reason+" movement="+p.movement+
            " landedHits="+p.landedHits+
            " wTaps="+p.wTaps+
            " pots="+p.pots+
            " healObserved="+p.healObserved+
            " maxPostPotHealth="+String.format(java.util.Locale.US,"%.1f",p.maxPostPotHealth));
        if(task!=null) task.cancel();
        task=null;
    }
}
