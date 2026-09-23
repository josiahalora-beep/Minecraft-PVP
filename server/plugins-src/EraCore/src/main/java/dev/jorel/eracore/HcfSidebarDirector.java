package dev.jorel.eracore;

import org.bukkit.Bukkit;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Lightweight shared 1.8-style HCF sidebar.
 *
 * Uses the main scoreboard so logical TAB teams and name colors keep working.
 * The content is intentionally server-wide: SOTW/event clocks, population and
 * border size. Per-player faction data is left to /f show and chat suffixes.
 */
final class HcfSidebarDirector {
    private final EraCore plugin;
    private final SimWorldDirector world;
    private final HcfEventDirector events;
    private final Set<String> lastLines=new LinkedHashSet<String>();
    private BukkitTask task;
    private Objective objective;

    HcfSidebarDirector(EraCore plugin,SimWorldDirector world,HcfEventDirector events) {
        this.plugin=plugin;
        this.world=world;
        this.events=events;
    }

    void start() {
        stop();
        if(!plugin.getConfig().getBoolean("presentation.sidebar",true)) return;

        Scoreboard board=Bukkit.getScoreboardManager().getMainScoreboard();
        Objective old=board.getObjective("daegonhcf");
        if(old!=null) old.unregister();

        objective=board.registerNewObjective("daegonhcf","dummy");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.setDisplayName(EraCore.colorText("&6&lDAEGON HCF"));

        refresh();
        task=Bukkit.getScheduler().runTaskTimer(plugin,new Runnable() {
            public void run(){refresh();}
        },20L,20L);
    }

    void stop() {
        if(task!=null) task.cancel();
        task=null;
        if(objective!=null) {
            try { objective.unregister(); } catch(Throwable ignored){}
        }
        objective=null;
        lastLines.clear();
    }

    private void refresh() {
        if(objective==null) return;
        Scoreboard board=Bukkit.getScoreboardManager().getMainScoreboard();
        for(String line:lastLines) board.resetScores(line);
        lastLines.clear();

        List<String> lines=new ArrayList<String>();
        lines.add(EraCore.colorText("&8-------------"));

        if(world!=null && world.sotwProtectionActive()) {
            lines.add(EraCore.colorText("&fMap: &aSOTW"));
            lines.add(EraCore.colorText("&fSOTW: &a"+clock(world.sotwMillisLeft())));
        } else {
            lines.add(EraCore.colorText("&fMode: &aHCF"));
        }

        if(events!=null) {
            String event=shortName(events.sidebarEventName());
            if(events.sidebarEventActive()) {
                lines.add(EraCore.colorText(events.sidebarConquestActive()?"&cConquest":"&6KOTH"));
                if(!events.sidebarConquestActive()) {
                    lines.add(EraCore.colorText("&e"+event));
                    lines.add(EraCore.colorText("&fCap: &e"+clock(events.sidebarEventMillis())));
                }
            } else if(events.sidebarEventMillis()>0L) {
                lines.add(EraCore.colorText("&fNext Event"));
                lines.add(EraCore.colorText("&e"+event));
                lines.add(EraCore.colorText("&fIn: &e"+clock(events.sidebarEventMillis())));
            }
        }

        int online=Math.max(Bukkit.getOnlinePlayers().size(),plugin.simulatedLogicalOnlineCount());
        lines.add(EraCore.colorText("&fOnline: &a"+Math.min(999,online)));

        int border=Math.max(1,plugin.getConfig().getInt("map.world-border",3000)/2);
        lines.add(EraCore.colorText("&fBorder: &b"+Math.min(9999,border)));
        lines.add(EraCore.colorText("&8-------------"));

        // 1.8 score entry strings are limited to 16 characters including color
        // codes. Every constructed line above is kept under that limit.
        int score=lines.size();
        Set<String> used=new HashSet<String>();
        for(String raw:lines) {
            String line=raw;
            if(line.length()>16) line=line.substring(0,16);
            while(used.contains(line) && line.length()<16) line=line+" ";
            used.add(line);
            lastLines.add(line);
            objective.getScore(line).setScore(score--);
        }
    }

    private String shortName(String name) {
        String s=name==null?"Event":name;
        if(s.length()>14) s=s.substring(0,14);
        return s;
    }

    private String clock(long millis) {
        long total=Math.max(0L,millis/1000L);
        long minutes=Math.min(99L,total/60L);
        long seconds=total%60L;
        return String.format(Locale.US,"%02d:%02d",minutes,seconds);
    }
}
