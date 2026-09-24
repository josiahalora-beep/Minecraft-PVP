package dev.jorel.eracore;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Deterministic HCF base design contract.
 *
 * This is deliberately not a schematic preset.  A faction profile is converted
 * into stable architectural decisions and semantic anchors.  The physical
 * builder, worker AI and storage/brewer directors all consume the same plan so
 * visual variation never breaks navigation.
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

        // Family selection happens BEFORE dimensions. In the original viewer
        // POC the five references were different architectural grammars, not
        // merely trim palettes; footprint and room topology therefore depend on
        // the selected family while remaining deterministic for the faction.
        int family=seed%5;
        String arch=this.profile.archetype==null?"BALANCED":this.profile.archetype.toUpperCase(Locale.ENGLISH);
        if("TRAPPER".equals(arch)) family=((seed/5)&1)==0?4:3;
        else if("ECONOMY".equals(arch)) family=((seed/7)&1)==0?1:2;
        else if("PVP".equals(arch)) family=((seed/11)&1)==0?0:2;
        else if("UNDERDOG".equals(arch)) family=((seed/13)&1)==0?3:4;
        this.primaryFamily=family;
        int secondary=(family+1+((seed/59)%4))%5;
        if(secondary==family) secondary=(secondary+1)%5;
        this.secondaryFamily=secondary;
        this.utilitySide=((seed/47)&1)==0?-1:1;

        int members=Math.max(1,Math.min(8,this.profile.members));

        // Concealment-first surface planning: visible scale grows much slower
        // than underground capacity. A large faction needs more storage/farms,
        // not a 40-block glass advertisement on the surface.
        int sx=5+(members/2)+((seed/7)&1);
        int sz=5+((members+1)/3)+((seed/13)&1);
        int sh=3+Math.min(2,Math.max(0,this.profile.builderQuality-50)/25);

        // v9 silhouette proportions: families differ before any material is
        // placed. Tunnel is a long buried spine, Cave is broad/low, Modern is a
        // stepped clean plan, Redemption stays compact, Base-HCF stays broad.
        if(family==0) { sx=Math.max(5,sx-1); sz=Math.max(5,sz-1); }           // compact/vertical
        else if(family==1) { sx+=1; sz+=1; }                                 // classic balanced
        else if(family==2) { sx+=2; sz+=1; sh=Math.min(5,sh+1); }            // polished/stepped
        else if(family==3) { sx=Math.max(4,sx-2); sz+=4; sh=Math.min(3,sh); } // narrow long tunnel
        else if(family==4) { sx+=2; sz+=1; sh=Math.max(3,sh-1); }             // broad low cave

        this.surfaceHalfX=sx;
        this.surfaceHalfZ=sz;
        this.surfaceHeight=Math.min(5,sh);

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
        this.frontGateOffset=((seed/41)%5)-2;

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
        if("gate".equals(k)) return new int[]{cx+frontGateOffset,surfaceY+1,cz-surfaceHalfZ};
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
        int extra=primaryFamily==3?9:(primaryFamily==4?13:11);
        return Math.max(surfaceHalfX,surfaceHalfZ)+extra;
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
