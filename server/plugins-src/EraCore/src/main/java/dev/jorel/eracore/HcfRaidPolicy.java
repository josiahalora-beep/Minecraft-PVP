package dev.jorel.eracore;

import java.util.Locale;

/**
 * Shared deterministic HCF raid policy.
 *
 * The policy does not know whether its observations came from the COLD
 * simulation, Mineflayer, or a CombatBody. That keeps raid decisions identical
 * across runtimes while physical movement remains runtime-specific.
 */
final class HcfRaidPolicy {
    enum AttackerDecision { PEARL_ENTRY, HOLD_OUTSIDE, ABORT }
    enum DefenderDecision { HOLD_GATE, CALL_BACKUP, RETREAT }

    static final class AttackerContext {
        final boolean enemyGateOpen,validPearlLine;
        final int ourNearby,theirVisible;
        final double ourDtr,theirDtr;
        final int ourGear,theirGear;
        final String combatClass;
        final int riskTolerance,gameSense,aggression;

        AttackerContext(boolean enemyGateOpen,boolean validPearlLine,int ourNearby,int theirVisible,
                        double ourDtr,double theirDtr,int ourGear,int theirGear,String combatClass,
                        int riskTolerance,int gameSense,int aggression) {
            this.enemyGateOpen=enemyGateOpen;this.validPearlLine=validPearlLine;
            this.ourNearby=Math.max(0,ourNearby);this.theirVisible=Math.max(0,theirVisible);
            this.ourDtr=ourDtr;this.theirDtr=theirDtr;
            this.ourGear=Math.max(0,ourGear);this.theirGear=Math.max(0,theirGear);
            this.combatClass=combatClass==null?"DIAMOND":combatClass.toUpperCase(Locale.ENGLISH);
            this.riskTolerance=clamp(riskTolerance);this.gameSense=clamp(gameSense);
            this.aggression=clamp(aggression);
        }
    }

    static final class DefenderContext {
        final int defenderNearby,attackersVisible;
        final double defenderDtr;
        final int attackerVisibleGear,defenderGear;
        final boolean escapeRouteKnown;
        final String combatClass;
        final int riskTolerance,composure,teamwork;

        DefenderContext(int defenderNearby,int attackersVisible,double defenderDtr,
                        int attackerVisibleGear,int defenderGear,boolean escapeRouteKnown,
                        String combatClass,int riskTolerance,int composure,int teamwork) {
            this.defenderNearby=Math.max(0,defenderNearby);
            this.attackersVisible=Math.max(0,attackersVisible);
            this.defenderDtr=defenderDtr;
            this.attackerVisibleGear=Math.max(0,attackerVisibleGear);
            this.defenderGear=Math.max(0,defenderGear);
            this.escapeRouteKnown=escapeRouteKnown;
            this.combatClass=combatClass==null?"DIAMOND":combatClass.toUpperCase(Locale.ENGLISH);
            this.riskTolerance=clamp(riskTolerance);this.composure=clamp(composure);
            this.teamwork=clamp(teamwork);
        }
    }

    private HcfRaidPolicy() {}

    static AttackerDecision attackerDecision(AttackerContext c) {
        if(c==null) return AttackerDecision.ABORT;
        if(!c.enemyGateOpen || !c.validPearlLine) return AttackerDecision.HOLD_OUTSIDE;

        // Bard/Archer preserve the outside support lane whenever a teammate can
        // be the first body through the opening.
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

        if(c.defenderDtr<=1.01 && c.attackerVisibleGear>c.defenderGear && c.escapeRouteKnown)
            return DefenderDecision.RETREAT;

        if(c.attackersVisible>c.defenderNearby || c.defenderDtr<=2.01 ||
           (c.teamwork>=72 && c.attackersVisible>=2))
            return DefenderDecision.CALL_BACKUP;

        return DefenderDecision.HOLD_GATE;
    }

    private static int clamp(int n) { return Math.max(0,Math.min(100,n)); }
}
