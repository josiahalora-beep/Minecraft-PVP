package dev.jorel.eracore;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Deterministic HCF base design contract.
 *
 * Phase 2B now uses an exact schematic-derived visible surface component for
 * each canonical HCF family. The faction profile still controls semantic anchors,
 * underground topology and later variation, so worker AI and storage/brewer
 * directors consume one stable plan without rewriting the reference facade.
 */
final class HcfBasePlan {
    static final class Profile {
        int members = 3;
        int builderQuality = 55;
        int organization = 55;
        int pvpIq = 55;
        int economicIq = 55;
        int riskTolerance = 50;
        int gameSense = 55;
        int decisiveness = 55;
        int wealthTier = 0;
        String archetype = "BALANCED";
    }

    final String faction;
    final int cx;
    final int surfaceY;
    final int cz;
    final Profile profile;
    final int seed;

    final int surfaceHalfX;
    final int surfaceHalfZ;
    final int surfaceHeight;
    final int entrances;
    final int undergroundY;
    final int coreHalfX;
    final int coreHalfZ;
    final int storageVariant;
    final int surfaceShape; // 0 compact, 1 chamfered/organic, 2 asymmetric low-profile
    final int frontGateOffset;
    final int utilitySide;
    final int finishTier;
    // Design vocabulary distilled from the five supplied HCF base families.
    // 0 Redemption, 1 Base-HCF, 2 ModernHCF, 3 Tunnel, 4 Cave.
    final int primaryFamily;
    final int secondaryFamily;

    final Material surfaceFrame;
    final Material surfaceFloor;
    final Material undergroundFloor;
    final Material undergroundTrim;

    private HcfBasePlan(String faction,int cx,int y,int cz,Profile profile) {
        this.faction=faction==null?"":faction;
        this.cx=cx;
        this.surfaceY=y;
        this.cz=cz;
        this.profile=profile==null?new Profile():profile;
        this.seed=positiveHash(this.faction+"|"+cx+"|"+cz);

        // A faction chooses its architectural family independently of WHERE
        // it eventually claims. The previous coordinate-dependent family seed
        // meant moving four blocks while scouting could turn a Base-HCF plan
        // into Cave/Modern, which is unlike a human faction choosing a design
        // and then searching for land that fits it.
        int familySeed=positiveHash(this.faction+"|family");
        int family=familySeed%5;
        String arch=this.profile.archetype==null?"BALANCED":this.profile.archetype.toUpperCase(Locale.ENGLISH);

        // Deterministic QA identities explicitly cover the five canonical
        // reference families and the Modern palette proof surface.
        if(this.faction.startsWith("QARedemption2")) family=0;
        else if(this.faction.startsWith("QABase0")) family=1;
        else if(this.faction.startsWith("QAModern14") || this.faction.startsWith("QAPalette")) family=2;
        else if(this.faction.startsWith("QATunnel21")) family=3;
        else if(this.faction.startsWith("QACave55")) family=4;
        else if("TRAPPER".equals(arch)) family=((familySeed/5)&1)==0?4:3;
        else if("ECONOMY".equals(arch)) family=((familySeed/7)&1)==0?1:2;
        else if("PVP".equals(arch)) family=((familySeed/11)&1)==0?0:2;
        else if("UNDERDOG".equals(arch)) family=((familySeed/13)&1)==0?3:4;
        this.primaryFamily=family;
        int secondary=(family+1+((familySeed/59)%4))%5;
        if(secondary==family) secondary=(secondary+1)%5;
        this.secondaryFamily=secondary;
        this.utilitySide=((seed/47)&1)==0?-1:1;

        int members=Math.max(1,Math.min(8,this.profile.members));

        // Phase 2B corrective reset: surface dimensions come from the exact
        // selected visible component of the supplied reference schematic.
        // Do not resize these during the fidelity phase; member count continues
        // to scale underground capacity instead. Controlled exterior variation
        // belongs in the later variation phase, after this canonical baseline.
        this.surfaceHalfX=HcfSurfaceReferenceTemplates.width(family)/2;
        this.surfaceHalfZ=HcfSurfaceReferenceTemplates.length(family)/2;
        this.surfaceHeight=HcfSurfaceReferenceTemplates.height(family)-1;

        int access=1;
        if(this.profile.gameSense>=62 || this.profile.pvpIq>=72) access++;
        if(this.profile.gameSense>=82 && this.profile.decisiveness>=72) access++;
        this.entrances=Math.max(1,Math.min(3,access));

        int depth=(family==0?17:(family==3?20:18))+(seed%5);
        this.undergroundY=Math.max(10,y-depth);

        int hx=12+members+((seed/19)%3);
        int hz=10+members+((seed/23)%3);
        if(family==0) { hx=Math.max(13,hx-1); hz=Math.max(12,hz-1); }
        else if(family==1) { hx+=1; hz+=2; }
        else if(family==2) { hx+=3; hz+=2; }
        else if(family==3) { hx+=4; hz=Math.max(11,hz-1); }
        else if(family==4) { hx+=2; hz+=2; }
        this.coreHalfX=hx;
        this.coreHalfZ=hz;

        // Storage topology follows the reference grammar instead of randomizing
        // independently from the rest of the base.
        if(family==0) this.storageVariant=0;          // compact central/double sided
        else if(family==1) this.storageVariant=1;     // perimeter/ring
        else if(family==2) this.storageVariant=2;     // organized split aisles
        else if(family==3) this.storageVariant=2;     // corridor-friendly banks
        else this.storageVariant=((seed/31)&1)==0?1:2;// irregular cave annex

        if(family==1 || family==4) this.surfaceShape=1;
        else if(family==2) this.surfaceShape=2;
        else this.surfaceShape=(seed/37)%2;
        // Reference-facing primary gate center measured directly from the
        // selected canonical surface component.
        this.frontGateOffset=HcfSurfaceReferenceTemplates.primaryGateOffsetX(family);

        int finish=this.profile.builderQuality>=76?2:(this.profile.builderQuality>=48?1:0);
        if(this.profile.wealthTier>=2 && finish<2) finish++;
        this.finishTier=Math.min(2,finish);

        if(finishTier==0) {
            this.surfaceFrame=Material.COBBLESTONE;
            this.surfaceFloor=Material.COBBLESTONE;
            this.undergroundFloor=Material.STONE;
            this.undergroundTrim=Material.COBBLESTONE;
        } else if(finishTier==1) {
            this.surfaceFrame=Material.SMOOTH_BRICK;
            this.surfaceFloor=Material.SMOOTH_BRICK;
            this.undergroundFloor=Material.SMOOTH_BRICK;
            this.undergroundTrim=Material.WOOD;
        } else {
            this.surfaceFrame=Material.SMOOTH_BRICK;
            this.surfaceFloor=Material.SMOOTH_BRICK;
            this.undergroundFloor=Material.SMOOTH_BRICK;
            this.undergroundTrim=(seed&1)==0?Material.WOOD:Material.BRICK;
        }
    }

    static HcfBasePlan of(String faction,int cx,int y,int cz,Profile profile) {
        return new HcfBasePlan(faction,cx,y,cz,profile);
    }

    int[] anchor(String kind) {
        String k=kind==null?"":kind.toLowerCase(Locale.ENGLISH);
        if("gate".equals(k)) return new int[]{
            cx+HcfSurfaceReferenceTemplates.primaryGateOffsetX(primaryFamily),
            surfaceY+HcfSurfaceReferenceTemplates.primaryGateOffsetY(primaryFamily),
            cz+HcfSurfaceReferenceTemplates.primaryGateOffsetZ(primaryFamily)
        };
        if("core".equals(k) || "home".equals(k)) return new int[]{cx,undergroundY+1,cz};
        if("drop".equals(k)) return new int[]{cx-3,surfaceY+1,cz-1};
        if("drop-bottom".equals(k)) return new int[]{cx-3,undergroundY+1,cz-1};
        if("elevator".equals(k)) return new int[]{cx+3,undergroundY+1,cz+1};
        if("storage".equals(k)) return new int[]{cx-coreHalfX+5,undergroundY+1,cz+1};
        if("refill".equals(k)) return new int[]{cx-1,undergroundY+1,cz-coreHalfZ+4};
        if("brewer".equals(k)) return new int[]{cx+utilitySide*(coreHalfX-6),undergroundY,cz+1};
        if("farm".equals(k) || "money-farm".equals(k))
            return new int[]{cx,undergroundY-6,cz+5};
        if("wart-farm".equals(k))
            return new int[]{cx,undergroundY-6,cz-8};
        if("portal-nether".equals(k))
            return new int[]{cx+utilitySide*(coreHalfX-3),undergroundY+1,cz-coreHalfZ+5};
        if("portal-end".equals(k))
            return new int[]{cx+utilitySide*(coreHalfX-3),undergroundY+1,cz-coreHalfZ+12};
        if("enchant".equals(k))
            return new int[]{cx+utilitySide*(coreHalfX-5),undergroundY+1,cz+coreHalfZ-5};
        if("war-room".equals(k) || "warroom".equals(k))
            return new int[]{cx,undergroundY+1,cz+coreHalfZ-5};
        if("utility".equals(k))
            return new int[]{cx-utilitySide*(coreHalfX-5),undergroundY+1,cz+coreHalfZ-5};
        return new int[]{cx,undergroundY+1,cz};
    }


    int[] storageSlot(int index) {
        int i=Math.max(0,Math.min(13,index));
        int row=i<7?0:1;
        int col=i%7;
        int storageSide=-utilitySide; // brewer/portals own utilitySide permanently

        int outer;
        int inner;
        if(storageVariant==0) {
            // Central double-sided island shifted away from utility machinery.
            outer=6;
            inner=2;
        } else if(storageVariant==1) {
            // Perimeter-style banks, still leaving a dedicated utility strip.
            outer=Math.max(7,coreHalfX-4);
            inner=Math.max(3,outer-4);
        } else {
            // Split aisles: looks like a room expanded in stages.
            outer=Math.min(9,coreHalfX-5);
            inner=4;
        }

        int x=cx+storageSide*(row==0?outer:inner);
        // Double chests extend +X; nudge negative-side banks one block inward
        // so the pair remains fully inside the reserved storage half.
        if(storageSide<0) x-=1;
        int[] zOffsets={-10,-7,-4,2,5,8,10};
        int z=cz+zOffsets[col]; // permanent center gap for dropdown/elevator traffic
        return new int[]{x,undergroundY+1,z};
    }

    int concealmentTier() {
        int score=profile.builderQuality + profile.gameSense/2 + profile.organization/3 + profile.wealthTier*18;
        return score>=150?2:(score>=105?1:0);
    }

    int terrainCradleRadius() {
        // Authored FreeMap terrain is authoritative. This radius is only the
        // immediate structural support / hand-terraforming envelope, not a
        // concealment landform. Never let base generation reshape a claim-sized
        // ring around the structure.
        return Math.max(surfaceHalfX,surfaceHalfZ)+3;
    }

    int surfacePadRadius() {
        // Include EVERY architectural module in the terraform/claim envelope.
        // Tunnel grammar reaches farther than the central box; Cave and Modern
        // families also reserve more breathing room for annexes. The claim
        // director adds its outside buffer on top of this value.
        int surfaceReach=Math.max(surfaceHalfX,surfaceHalfZ)+9;
        int undergroundReach=Math.max(coreHalfX,coreHalfZ)+14;
        if(primaryFamily==3) undergroundReach=Math.max(undergroundReach,coreHalfX+20);
        else if(primaryFamily==4) undergroundReach=Math.max(undergroundReach,coreHalfX+15);
        else if(primaryFamily==2) undergroundReach=Math.max(undergroundReach,Math.max(coreHalfX,coreHalfZ)+16);
        return Math.max(surfaceReach,undergroundReach);
    }

    String primaryFamilyName() { return familyName(primaryFamily); }
    String secondaryFamilyName() { return familyName(secondaryFamily); }

    private String familyName(int id) {
        switch(id) {
            case 0: return "REDEMPTION";
            case 1: return "BASE_HCF";
            case 2: return "MODERN_HCF";
            case 3: return "TUNNEL";
            case 4: return "CAVE";
            default: return "BALANCED";
        }
    }

    private static int positiveHash(String text) {
        int h=text==null?0:text.toLowerCase(Locale.ENGLISH).hashCode();
        return h==Integer.MIN_VALUE?0:Math.abs(h);
    }
}
