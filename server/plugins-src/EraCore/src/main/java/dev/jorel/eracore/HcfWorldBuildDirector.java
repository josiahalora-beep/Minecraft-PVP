package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * One staged, resumable production-map pipeline.
 *
 * Normal restarts do not rebuild completed stages. A fresh SOTW/reset leaves
 * map.complete=false and this director resumes only the missing work:
 *
 *   STRUCTURES -> RESOURCES -> READY
 *
 * Every stage is guarded by the shared p95 MSPT probe. Heavy block work lives
 * inside the compositor/resource queues, never in this coordinator tick.
 */
final class HcfWorldBuildDirector {
    private final EraCore plugin;
    private final HcfMapDirector map;
    private final HcfResourceDirector resources;
    private final LegacySchematicComposer composer;
    private BukkitTask task;
    private String lastStage="";
    private long lastLog;

    HcfWorldBuildDirector(EraCore plugin,HcfMapDirector map,
                          HcfResourceDirector resources,LegacySchematicComposer composer) {
        this.plugin=plugin;
        this.map=map;
        this.resources=resources;
        this.composer=composer;
    }

    void start() {
        stop();
        if(!plugin.getConfig().getBoolean("world-build.auto-resume",true)) return;
        task=Bukkit.getScheduler().runTaskTimer(plugin,new Runnable() {
            public void run(){tick();}
        },60L,20L);
    }

    void stop() {
        if(task!=null) task.cancel();
        task=null;
    }

    private void tick() {
        if(!plugin.getConfig().getBoolean("world-build.auto-resume",true)) return;

        boolean requested=plugin.getConfig().getBoolean("map.auto-bootstrap",false) ||
            plugin.getConfig().getBoolean("world-build.active",false);
        if(!requested) {
            stage(plugin.getConfig().getBoolean("world-build.complete",false)?"READY":"IDLE");
            return;
        }
        if(!plugin.getConfig().getBoolean("world-build.active",false)) {
            plugin.getConfig().set("world-build.active",true);
            plugin.saveConfig();
        }

        double p95=plugin.currentP95Mspt();
        double ceiling=Math.max(15.0,plugin.getConfig().getDouble("world-build.max-p95-mspt",20.0));
        if(p95>=0.0 && p95>ceiling) {
            stage("PAUSED_MSPT");
            return;
        }

        boolean structures=plugin.getConfig().getBoolean("map.structures-complete",
            plugin.getConfig().getBoolean("map.complete",false));
        boolean resourceDone=plugin.getConfig().getBoolean("world-build.resources-complete",false);

        if(!structures) {
            stage("STRUCTURES");
            if(composer==null || composer.busy()) return;
            List<String> missing=composer.missingProductionAssets();
            if(!missing.isEmpty()) {
                stage("WAITING_ASSETS");
                log("Production map is waiting for assets: "+join(missing,", "));
                return;
            }
            if(!composer.queueProductionMap(map))
                log("Production structure queue could not start; it will retry automatically.");
            return;
        }

        if(!resourceDone) {
            stage("RESOURCES");
            if(resources==null || resources.busy()) return;
            map.ensureOreMountainWorldNow();
            if(!resources.queueBootstrapPublicSites()) {
                log("Resource-site queue could not start; it will retry automatically.");
            }
            return;
        }

        if(!plugin.getConfig().getBoolean("map.complete",false) ||
           !plugin.getConfig().getBoolean("world-build.complete",false)) {
            stage("FINALIZE");
            plugin.finalizeProductionSpawn();
            map.bootstrapWarps();
            plugin.getConfig().set("map.complete",true);
            plugin.getConfig().set("map.auto-bootstrap",false);
            plugin.getConfig().set("world-build.complete",true);
            plugin.getConfig().set("world-build.active",false);
            plugin.saveConfig();
            plugin.getLogger().info("[world-build] READY: production structures/resources are materialized.");
            if(plugin.hasHumanOnline())
                plugin.broadcastCommunityEvent("&a[Map] &fDaegon HCF production map is ready.");
        }
        stage("READY");
    }

    String status() {
        double p95=plugin.currentP95Mspt();
        return "stage="+currentStage()+
            " structures="+plugin.getConfig().getBoolean("map.structures-complete",false)+
            " resources="+plugin.getConfig().getBoolean("world-build.resources-complete",false)+
            " complete="+plugin.getConfig().getBoolean("world-build.complete",false)+
            " composerJobs="+(composer==null?0:composer.queuedJobs())+
            " resourceOps="+(resources==null?0:resources.queuedOperations())+
            " p95="+String.format(Locale.US,"%.2f",Math.max(0.0,p95));
    }

    private String currentStage() {
        if(!plugin.getConfig().getBoolean("map.structures-complete",
            plugin.getConfig().getBoolean("map.complete",false))) return composer!=null&&composer.busy()?"STRUCTURES":"STRUCTURES_WAIT";
        if(!plugin.getConfig().getBoolean("world-build.resources-complete",false)) return resources!=null&&resources.busy()?"RESOURCES":"RESOURCES_WAIT";
        return plugin.getConfig().getBoolean("world-build.complete",false)?"READY":"FINALIZE";
    }

    private void stage(String s) {
        if(s==null) s="";
        if(!s.equals(lastStage)) {
            lastStage=s;
            log("stage="+s);
        }
    }

    private void log(String s) {
        long now=System.currentTimeMillis();
        if(now-lastLog<1000L && s.equals("stage="+lastStage)) return;
        lastLog=now;
        plugin.getLogger().info("[world-build] "+s);
    }

    private static String join(Collection<String> xs,String sep) {
        StringBuilder b=new StringBuilder();
        for(String x:xs){if(b.length()>0)b.append(sep);b.append(x);}
        return b.toString();
    }
}
