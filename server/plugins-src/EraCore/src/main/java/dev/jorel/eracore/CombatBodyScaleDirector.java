package dev.jorel.eracore;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Gate-5 CombatBody scale benchmark.
 *
 * This is intentionally an ACTIVE workload: staged CombatBodies move, close
 * distance and trade real NMS melee/W-tap hits. The metric is scheduler cadence,
 * not idle entity count. A 12-body pass covers a full 5v5 around one human with
 * two-body headroom, which is the minimum useful target for the single-player
 * HCF experience.
 */
final class CombatBodyScaleDirector {
    static final class StageResult {
        final int bodies;
        final int samples;
        final double averageMs,p50Ms,p95Ms,maxMs;
        final int liveBodies;
        final boolean pass;

        StageResult(int bodies,int samples,double averageMs,double p50Ms,double p95Ms,
                    double maxMs,int liveBodies,boolean pass) {
            this.bodies=bodies;this.samples=samples;this.averageMs=averageMs;
            this.p50Ms=p50Ms;this.p95Ms=p95Ms;this.maxMs=maxMs;
            this.liveBodies=liveBodies;this.pass=pass;
        }

        String summary() {
            return "bodies="+bodies+
                " samples="+samples+
                " avgMs="+fmt(averageMs)+
                " p50Ms="+fmt(p50Ms)+
                " p95Ms="+fmt(p95Ms)+
                " maxMs="+fmt(maxMs)+
                " live="+liveBodies+
                " pass="+pass;
        }
    }

    static final class ProbeSnapshot {
        final boolean complete;
        final boolean pass;
        final int highestPassing;
        final int requestedMax;
        final List<StageResult> stages;

        ProbeSnapshot(boolean complete,boolean pass,int highestPassing,int requestedMax,
                      List<StageResult> stages) {
            this.complete=complete;this.pass=pass;this.highestPassing=highestPassing;
            this.requestedMax=requestedMax;this.stages=stages;
        }

        String summary() {
            return "required12="+(highestPassing>=12)+
                " highestPassing="+highestPassing+
                " requestedMax="+requestedMax+
                " stages="+stages.size();
        }
    }

    private static final int[] COUNTS={2,4,8,12,16};
    private static final int WARMUP_TICKS=40;
    private static final int SAMPLE_TICKS=100;
    private static final double MAX_AVG_MS=55.0;
    private static final double MAX_P95_MS=70.0;

    private final EraCore plugin;
    private final NmsFakePlayerRuntime bodies;
    private final SimWorldDirector simWorld;

    private final List<String> names=new ArrayList<String>();
    private final List<String> active=new ArrayList<String>();
    private final List<Double> samples=new ArrayList<Double>();
    private final List<StageResult> results=new ArrayList<StageResult>();

    private BukkitTask task;
    private Location center;
    private int requestedMax;
    private int stageCursor;
    private int stageTick;
    private long lastTickNanos;
    private long globalTick;
    private boolean complete;
    private boolean pass;
    private int highestPassing;

    CombatBodyScaleDirector(EraCore plugin,NmsFakePlayerRuntime bodies,SimWorldDirector simWorld) {
        this.plugin=plugin;this.bodies=bodies;this.simWorld=simWorld;
    }

    boolean startProbe(Location at,int maxBodies) {
        if(at==null || at.getWorld()==null || task!=null) return false;
        int max=Math.max(2,Math.min(16,maxBodies));
        if((max&1)==1) max--;
        List<String> candidates=simWorld.logicalOnlineIdentityNames(max);
        if(candidates.size()<max) return false;

        stopBodiesOnly();
        names.clear();names.addAll(candidates);
        active.clear();samples.clear();results.clear();
        center=at.clone();requestedMax=max;stageCursor=0;stageTick=0;
        lastTickNanos=0L;globalTick=0L;complete=false;pass=false;highestPassing=0;

        if(!materializeStage(stageCount())) {
            cleanup(false,"initial-materialization-failed");
            return false;
        }

        task=plugin.getServer().getScheduler().runTaskTimer(plugin,new Runnable() {
            public void run(){tick();}
        },1L,1L);
        plugin.getLogger().info("[CombatBody Gate5] START requestedMax="+requestedMax+
            " required12=true thresholds=avg<="+fmt(MAX_AVG_MS)+"ms,p95<="+fmt(MAX_P95_MS)+"ms");
        return true;
    }

    ProbeSnapshot snapshot() {
        return new ProbeSnapshot(complete,pass,highestPassing,requestedMax,
            new ArrayList<StageResult>(results));
    }

    void stop() {
        if(task!=null) task.cancel();
        task=null;
        stopBodiesOnly();
    }

    private void tick() {
        if(complete) return;
        globalTick++;

        long now=System.nanoTime();
        if(lastTickNanos!=0L && stageTick>=WARMUP_TICKS)
            samples.add((now-lastTickNanos)/1000000.0);
        lastTickNanos=now;

        driveActive();
        stageTick++;

        if(stageTick < WARMUP_TICKS+SAMPLE_TICKS) return;

        StageResult result=finishStage(stageCount());
        results.add(result);
        if(result.pass) highestPassing=Math.max(highestPassing,result.bodies);
        plugin.getLogger().info("[CombatBody Gate5 stage] "+result.summary());

        if(stageCount()>=requestedMax || stageCursor>=COUNTS.length-1) {
            pass=highestPassing>=12;
            complete=true;
            plugin.getLogger().info("[CombatBody Gate5] "+(pass?"PASS":"FAIL")+
                " required12="+(highestPassing>=12)+
                " highestPassing="+highestPassing+
                " requestedMax="+requestedMax);
            if(task!=null) task.cancel();
            task=null;
            stopBodiesOnly();
            return;
        }

        stageCursor++;
        while(stageCursor<COUNTS.length-1 && COUNTS[stageCursor]>requestedMax) stageCursor++;
        if(!materializeStage(stageCount())) {
            cleanup(false,"materialization-failed-"+stageCount());
            return;
        }
        stageTick=0;
        samples.clear();
        lastTickNanos=0L;
    }

    private int stageCount() {
        int c=COUNTS[Math.max(0,Math.min(stageCursor,COUNTS.length-1))];
        return Math.min(c,requestedMax);
    }

    private boolean materializeStage(int count) {
        World w=center.getWorld();
        if(w==null) return false;

        for(int i=active.size();i<count;i++) {
            String name=names.get(i);
            int lane=i/2;
            int pairCount=Math.max(1,count/2);
            double zOffset=(lane-(pairCount-1)/2.0)*5.0;
            double xOffset=(i%2==0)?-2.8:2.8;
            int bx=(int)Math.floor(center.getX()+xOffset);
            int bz=(int)Math.floor(center.getZ()+zOffset);
            int y=Math.max(4,w.getHighestBlockYAt(bx,bz)+1);
            Location spawn=new Location(w,bx+0.5,y,bz+0.5,i%2==0?-90f:90f,0f);
            try {
                Player p=bodies.spawn(name,spawn,true);
                equip(p);
                active.add(name);
            } catch(Exception ex) {
                plugin.getLogger().warning("[CombatBody Gate5] materialize failed actor="+name+
                    " reason="+ex.getClass().getSimpleName()+": "+ex.getMessage());
                return false;
            }
        }
        return active.size()==count;
    }

    private void driveActive() {
        for(int i=0;i+1<active.size();i+=2) {
            String aName=active.get(i),bName=active.get(i+1);
            Player a=bodies.player(aName),b=bodies.player(bName);
            if(a==null || b==null || a.isDead() || b.isDead()) continue;

            if(a.getHealth()<8.0) a.setHealth(20.0);
            if(b.getHealth()<8.0) b.setHealth(20.0);

            double d2=a.getLocation().distanceSquared(b.getLocation());
            double strafe=((globalTick/12+i)&1)==0?0.09:-0.09;
            if(d2>8.0) {
                bodies.combatMoveToward(aName,b.getLocation(),0.26,strafe);
                bodies.combatMoveToward(bName,a.getLocation(),0.26,-strafe);
                continue;
            }

            bodies.combatMoveToward(aName,b.getLocation(),0.10,strafe);
            bodies.combatMoveToward(bName,a.getLocation(),0.10,-strafe);
            if(globalTick%11L==0L) {
                boolean wTap=((globalTick/11L+i)&1L)==1L;
                bodies.combatAttack(aName,bName,wTap);
            } else if(globalTick%11L==5L) {
                boolean wTap=((globalTick/11L+i)&1L)==0L;
                bodies.combatAttack(bName,aName,wTap);
            }
        }
    }

    private StageResult finishStage(int count) {
        List<Double> sorted=new ArrayList<Double>(samples);
        Collections.sort(sorted);
        double sum=0.0,max=0.0;
        for(double v:sorted){sum+=v;if(v>max)max=v;}
        double avg=sorted.isEmpty()?999.0:sum/sorted.size();
        double p50=percentile(sorted,0.50);
        double p95=percentile(sorted,0.95);
        int live=0;
        for(int i=0;i<count && i<active.size();i++) {
            Player p=bodies.player(active.get(i));
            if(p!=null && !p.isDead()) live++;
        }
        boolean ok=live==count && avg<=MAX_AVG_MS && p95<=MAX_P95_MS;
        return new StageResult(count,sorted.size(),avg,p50,p95,max,live,ok);
    }

    private double percentile(List<Double> sorted,double q) {
        if(sorted.isEmpty()) return 999.0;
        int index=(int)Math.ceil(q*sorted.size())-1;
        index=Math.max(0,Math.min(sorted.size()-1,index));
        return sorted.get(index);
    }

    private void equip(Player p) {
        PlayerInventory inv=p.getInventory();
        inv.clear();inv.setArmorContents(new ItemStack[4]);
        ItemStack sword=new ItemStack(Material.DIAMOND_SWORD,1);
        sword.addUnsafeEnchantment(Enchantment.DAMAGE_ALL,1);
        inv.setItem(0,sword);
        inv.setHelmet(new ItemStack(Material.DIAMOND_HELMET,1));
        inv.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE,1));
        inv.setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS,1));
        inv.setBoots(new ItemStack(Material.DIAMOND_BOOTS,1));
        inv.setHeldItemSlot(0);
        p.setHealth(20.0);p.setFoodLevel(20);
        p.updateInventory();
    }

    private void cleanup(boolean success,String reason) {
        complete=true;pass=success;
        plugin.getLogger().info("[CombatBody Gate5] "+(success?"PASS":"FAIL")+
            " reason="+reason+" required12="+(highestPassing>=12)+
            " highestPassing="+highestPassing+" requestedMax="+requestedMax);
        if(task!=null) task.cancel();
        task=null;
        stopBodiesOnly();
    }

    private void stopBodiesOnly() {
        for(String name:new ArrayList<String>(active)) {
            try {bodies.despawn(name);} catch(Throwable ignored) {}
        }
        active.clear();
    }

    private static String fmt(double v) {
        return String.format(Locale.US,"%.2f",v);
    }
}
