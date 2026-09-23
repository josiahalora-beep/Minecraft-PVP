package dev.jorel.eracore;

import java.util.*;

/**
 * Cheap local contextual chat brain.
 *
 * It does not invent authoritative game facts. SimWorldDirector supplies the
 * snapshot; this class only chooses wording/response style from that state.
 */
final class ContextChatBrain {
    static final class Snapshot {
        String speaker;
        String speakerFaction = "";
        String message = "";
        String addressed = "";
        String addressedFaction = "";
        String responder = "";
        String responderFaction = "";
        String responderRole = "member";
        String responderJob = "member";
        String responderClass = "DIAMOND";
        String factionStage = "RECRUITING";
        String factionNeed = "";
        String rivalFaction = "";
        String recentKiller = "";
        String recentVictim = "";
        double dtr = 0.0;
        double maxDtr = 0.0;
        boolean raidable;
        boolean recovery;
        boolean creatorMentioned;
        boolean responderCreator;
        boolean pvpReady;
        int rivalry;
        int responderSkill;
        int responderAggression;
        int responderBargaining;
        int affinity;
        int trust = 50;
        int respect = 50;
        int grudge;
        boolean hasHistory;
        String rememberedFact = "";
    }

    private final Random rng = new Random(640144L);

    String reply(Snapshot s) {
        String m = norm(s.message);
        if (m.isEmpty()) return null;

        if (asksDirectly(m, s.responder)) return directReply(s, m);
        if (isGreeting(m)) return greeting(s);
        if (isRecruiting(m)) return recruitmentReply(s, m);
        if (isFactionQuestion(m)) return factionReply(s, m);
        if (isPvpQuestion(m)) return pvpReply(s, m);
        if (isDtrRaidQuestion(m)) return dtrReply(s, m);
        if (isBaseQuestion(m)) return baseReply(s, m);
        if (isEconomyQuestion(m)) return economyReply(s, m);
        if (isArgument(m)) return argumentReply(s, m);
        if (isPraise(m)) return praiseReply(s);
        if (isCreatorTalk(m) && s.creatorMentioned) return creatorReply(s, m);
        if (isQuestion(m)) return genericQuestionReply(s, m);

        // Only some statements deserve a response. Silence is more realistic.
        if (rng.nextInt(100) < 24) return statementReply(s, m);
        return null;
    }

    String followup(Snapshot s, String priorAiLine) {
        String p = norm(priorAiLine);
        String m = norm(s.message);
        if (p.isEmpty()) return reply(s);

        if (p.contains("msg me") && (m.contains("price") || m.contains("how much"))) {
            return "msg me ill tell you";
        }
        if (p.contains("spawn") && (m.contains("where") || m.contains("coming"))) {
            return s.pvpReady ? "im around spawn rn" : "not there yet";
        }
        if ((p.contains("regening dtr") || p.contains("staying in base")) && isDtrRaidQuestion(m)) {
            return formatDtr(s);
        }
        if (p.contains("recruit") && isRecruiting(m)) {
            return recruitmentReply(s,m);
        }

        return reply(s);
    }

    private String directReply(Snapshot s, String m) {
        if (m.contains("remember") || m.contains("last time") || m.contains("before")) {
            if(s.rememberedFact!=null && !s.rememberedFact.isEmpty())
                return oneOf("yeah "+shortMemory(s.rememberedFact),"i remember "+shortMemory(s.rememberedFact),
                    "yeah i remember that");
            return oneOf("not really","i dont remember much","maybe");
        }
        if (m.contains("where")) return locationState(s);
        if (m.contains("what are you") || m.contains("what you doing") || m.contains("wyd")) return activityState(s);
        if (m.contains("faction")) return factionReply(s,m);
        if (m.contains("dtr")) return formatDtr(s);
        if (m.contains("pvp") || m.contains("fight")) return pvpReply(s,m);
        if (m.contains("base")) return baseReply(s,m);
        if (m.contains("rich") || m.contains("money") || m.contains("farm")) return economyReply(s,m);
        return oneOf("yeah?","what","whats up","yo");
    }

    private String greeting(Snapshot s) {
        if(s.grudge>=55) return oneOf("what","lol what","you again");
        if(s.affinity>=45 || s.trust>=75) return oneOf("yo bro","whats good","sup man","yo");
        if (s.responderCreator && rng.nextBoolean()) return oneOf("yo","sup","whats good");
        return oneOf("yo","sup","hey","whats up","wassup");
    }

    private String recruitmentReply(Snapshot s, String m) {
        if (s.responderFaction.isEmpty()) {
            if(s.responderSkill<45)
                return oneOf("im looking too lol","same idk anyone yet","still trying to find one","if you find one lmk");
            return oneOf("im lff too","still solo rn","need a fac too","same im looking","havent found one yet");
        }
        if (s.factionNeed == null || s.factionNeed.isEmpty()) {
            if(s.grudge>=45) return oneOf("nah ask someone else","we arent taking you rn","dont think so");
            return oneOf("think we are set rn","ask our leader maybe","roster is pretty much full","we might have one spot idk");
        }
        if (m.contains("bard") && s.factionNeed.contains("bard"))
            return oneOf("we actually need bard msg leader","yeah we need a bard","bard? msg our leader");
        if (m.contains("archer") && s.factionNeed.contains("archer"))
            return oneOf("we need archer actually","msg our leader if you archer","archer spot might be open");
        if (m.contains("miner") && s.factionNeed.contains("miner"))
            return oneOf("we need someone mining","miner would help rn","yeah we need a miner");
        if (m.contains("brewer") && s.factionNeed.contains("brewer"))
            return oneOf("we need a brewer bad","if you brew msg leader","brewer spot is open i think");
        if (m.contains("builder") && s.factionNeed.contains("builder"))
            return oneOf("we could use a builder","builder would be nice","msg leader if you can build");
        return oneOf("we need "+s.factionNeed+" rn","msg our leader we need "+s.factionNeed,
            "might take you if you can "+s.factionNeed,"we still need "+s.factionNeed);
    }

    private String factionReply(Snapshot s, String m) {
        if (s.responderFaction.isEmpty()) return "im solo rn";
        if (m.contains("who") || m.contains("what fac") || m.contains("which fac")) {
            return "im in " + s.responderFaction;
        }
        if (m.contains("good") || m.contains("strong")) {
            if (s.pvpReady) return oneOf("we are geared now","we are doing alright","we can fight now");
            return "still getting set up";
        }
        return "im in " + s.responderFaction;
    }

    private String pvpReply(Snapshot s, String m) {
        if (s.recovery || s.raidable) {
            return oneOf("not feeding dtr rn","we are staying in","not roaming till dtr is back",
                "nah we low dtr","later we gotta regen");
        }
        if (!s.pvpReady) {
            if ("GEARING".equals(s.factionStage)) return oneOf("still making sets","not done gearing","need a set first");
            if ("BREWER".equals(s.factionStage)) return oneOf("still making pots","brewer isnt ready yet","need pots first");
            return oneOf("not geared yet","still setting up","we arent ready","later maybe");
        }

        if (m.contains("1v1")) {
            if (s.responderSkill >= 80)
                return oneOf("sure spawn road","yeah run it","im down where","send duel","come north road");
            if (s.responderSkill >= 60)
                return oneOf("after refill maybe","could run one","give me a sec","maybe after this");
            return oneOf("nah im good","not 1v1ing rn","im not that good lol","rather roam with fac");
        }

        if (s.grudge>=55 && s.responderAggression>=55)
            return oneOf("where you at","come out then","we'll see you","say where");
        if (s.responderAggression >= 78)
            return oneOf("we're outside","come road","where are you","we're looking rn");
        if (s.responderAggression <= 38)
            return oneOf("not really looking for fights","we're just chilling rn","only if someone hits us","probably staying around base");
        return oneOf("might roam later","we're around","depends who is on","maybe if the fac goes out","we might go road");
    }

    private String dtrReply(Snapshot s, String m) {
        if (s.raidable) return oneOf("we are raidable rn dont come lol","yeah we went raidable","we are holding base rn");
        if (s.recovery) return "we are regening " + formatDtr(s);
        if (m.contains("what") || m.contains("how much") || m.contains("dtr")) return formatDtr(s);
        return s.dtr <= 1.5 ? "our dtr is low rn" : "dtr is fine";
    }

    private String baseReply(Snapshot s, String m) {
        if (s.responderFaction.isEmpty()) return "dont have one yet";
        if ("SCOUT_CLAIM".equals(s.factionStage)) return "still finding a claim";
        if ("GATHER_STARTER".equals(s.factionStage)) return "we claimed just gathering mats";
        if ("BUILD_STARTER".equals(s.factionStage)) return "building it rn";
        if (s.recovery) return "we are staying in base till dtr is back";
        if (m.contains("where")) return oneOf("not leaking coords lol","find it","not posting coords");
        return oneOf("base is up","yeah we got one","we finished it");
    }

    private String economyReply(Snapshot s, String m) {
        if ("farmer".equalsIgnoreCase(s.responderJob)) {
            if (m.contains("farm") || m.contains("money")) return oneOf("cane is carrying rn","just expanding the farm","been farming most of sotw");
        }
        if ("miner".equalsIgnoreCase(s.responderJob)) {
            return oneOf("been mining all map","trying to get sets done","mining for the fac");
        }
        if ("brewer".equalsIgnoreCase(s.responderJob)) {
            return oneOf("making pots rn","trying to finish the brewer","pots cost too much to buy");
        }
        return activityState(s);
    }

    private String argumentReply(Snapshot s, String m) {
        if(s.grudge>=55) {
            if(s.rememberedFact!=null && !s.rememberedFact.isEmpty() && rng.nextBoolean())
                return oneOf("you were saying that last time too","i remember what happened last time","come prove it again");
            return oneOf("come fight then","same talk every time","we can run it again","you know where we are");
        }
        if (s.rivalFaction != null && !s.rivalFaction.isEmpty() && s.rivalry >= 20) {
            if (s.responderAggression >= 65) return oneOf("tell " + s.rivalFaction + " to come out","they started it lol","we will see them again");
            return oneOf("im not arguing in chat","ggs either way","they keep coming to our base");
        }
        return s.responderAggression >= 70 ? oneOf("come prove it","lol okay","then come fight") : oneOf("alright lol","gg","not that serious");
    }

    private String praiseReply(Snapshot s) {
        return oneOf("ty","thanks","appreciate it","gg");
    }

    private String creatorReply(Snapshot s, String m) {
        if (m.contains("died") || m.contains("death")) return oneOf("who killed him","no way lol","gg");
        if (m.contains("on") || m.contains("online") || m.contains("joined")) return oneOf("yeah hes on","saw him join","hes around");
        if (m.contains("base")) return oneOf("everyone is building near them lol","their area is gonna be active");
        return oneOf("lol","yeah","watch chat");
    }

    private String genericQuestionReply(Snapshot s, String m) {
        if (m.contains("where")) return locationState(s);
        if (m.contains("who")) {
            if (s.recentKiller != null && !s.recentKiller.isEmpty()) return s.recentKiller;
            return "idk";
        }
        if (m.contains("what")) return activityState(s);
        if (m.contains("when")) return oneOf("soon","idk yet","later probably","after we finish this","not sure");
        return oneOf("idk","probably","maybe","could be","no clue","depends");
    }

    private String statementReply(Snapshot s, String m) {
        if (m.contains("gg")) return oneOf("gg","ggs","gf");
        if (m.contains("bruh") || m.contains("lol")) return oneOf("lol","lmao","bro lol","fr");
        if (m.contains("rip")) return oneOf("rip","damn","unlucky");
        int roll=rng.nextInt(100);
        if(roll<10) return oneOf("yeah","true","fr","maybe","idk");
        return null;
    }

    private String locationState(Snapshot s) {
        if (s.recovery) return "at our base";
        if ("BUILD_STARTER".equals(s.factionStage)) return "at the base";
        if ("GATHER_STARTER".equals(s.factionStage)) return "mining rn";
        if ("ECONOMY".equals(s.factionStage) && "farmer".equalsIgnoreCase(s.responderJob)) return "at the farm";
        if ("BREWER".equals(s.factionStage) && "brewer".equalsIgnoreCase(s.responderJob)) return "at the brewer";
        if (s.pvpReady) return oneOf("around our base","around spawn","roaming");
        return "around our claim";
    }

    private String activityState(Snapshot s) {
        if (s.recovery) return "regening dtr";
        if ("SCOUT_CLAIM".equals(s.factionStage)) return "finding a claim";
        if ("GATHER_STARTER".equals(s.factionStage)) return "getting mats";
        if ("BUILD_STARTER".equals(s.factionStage)) return "building the base";
        if ("ECONOMY".equals(s.factionStage)) return "getting money up";
        if ("BREWER".equals(s.factionStage)) return "working on pots";
        if ("GEARING".equals(s.factionStage)) return "finishing sets";
        if ("PVP_READY".equals(s.factionStage)) return "probably roaming soon";
        return "recruiting";
    }

    private String formatDtr(Snapshot s) {
        if (s.maxDtr <= 0) return "idk our dtr";
        return String.format(Locale.US, "%.1f/%.1f dtr", s.dtr, s.maxDtr);
    }

    private boolean asksDirectly(String m, String name) {
        if (name == null || name.isEmpty()) return false;
        return m.contains(name.toLowerCase(Locale.ENGLISH));
    }

    private boolean isGreeting(String m) {
        return m.equals("yo") || m.equals("hey") || m.equals("sup") || m.startsWith("yo ") || m.startsWith("hey ");
    }

    private boolean isRecruiting(String m) {
        return m.contains("lff") || m.contains("recruit") || m.contains("need a fac") ||
            m.contains("need faction") || m.contains("join a faction") || m.contains("inv me");
    }

    private boolean isFactionQuestion(String m) {
        return m.contains("what faction") || m.contains("what fac") || m.contains("which faction") ||
            m.contains("your faction") || m.contains("your fac");
    }

    private boolean isPvpQuestion(String m) {
        return m.contains("pvp") || m.contains("1v1") || m.contains("fight") || m.contains("spawn pvp") ||
            m.contains("who wants to fight") || m.contains("roam");
    }

    private boolean isDtrRaidQuestion(String m) {
        return m.contains("dtr") || m.contains("raidable") || m.contains("raid");
    }

    private boolean isBaseQuestion(String m) {
        return m.contains("base") || m.contains("claim");
    }

    private boolean isEconomyQuestion(String m) {
        return m.contains("farm") || m.contains("money") || m.contains("rich") || m.contains("mine") ||
            m.contains("brewer") || m.contains("pots");
    }

    private boolean isArgument(String m) {
        return m.contains("trash") || m.contains("bad") || m.contains("running") || m.contains("numbers") ||
            m.contains("jumped") || m.contains("scared") || m.contains("cry");
    }

    private boolean isPraise(String m) {
        return m.contains("nice") || m.contains("good fight") || m.contains("gf") || m.contains("good base");
    }

    private boolean isCreatorTalk(String m) {
        return m.contains("stimpy") || m.contains("stimp") || m.contains("marcel") ||
            m.contains("painful") || m.contains("alex") || m.contains("skimpy");
    }

    private boolean isQuestion(String m) {
        return m.endsWith("?") || m.startsWith("who ") || m.startsWith("what ") || m.startsWith("where ") ||
            m.startsWith("when ") || m.startsWith("how ") || m.contains(" anyone ");
    }

    private String shortMemory(String memory) {
        if(memory==null) return "";
        String m=memory.trim();
        if(m.length()>72) m=m.substring(0,72).trim();
        if(m.endsWith(".")) m=m.substring(0,m.length()-1);
        return m;
    }

    private String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ENGLISH).trim().replaceAll("\\s+"," ");
    }

    private String oneOf(String... xs) {
        return xs[rng.nextInt(xs.length)];
    }
}
