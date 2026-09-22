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
    final int finishTier;

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

        int members=Math.max(1,Math.min(8,this.profile.members));
        this.surfaceHalfX=8+members+((seed/7)%2);       // 19..35 wide for 1..8
        this.surfaceHalfZ=7+members+((seed/13)%3);      // slightly less regular
        this.surfaceHeight=5+Math.min(2,Math.max(0,this.profile.builderQuality-45)/22);

        int access=2;
        if(this.profile.gameSense>=58 || this.profile.pvpIq>=68) access++;
        if((this.profile.gameSense>=78 && this.profile.decisiveness>=62) ||
           (this.profile.riskTolerance>=72 && this.profile.pvpIq>=72)) access++;
        this.entrances=Math.max(2,Math.min(4,access));

        int depth=18+(seed%6);
        this.undergroundY=Math.max(10,y-depth);

        // Underground space is intentionally generous without becoming a giant
        // empty hall. Farms are separate wings and therefore do not force the
        // central room to scale linearly with every system.
        this.coreHalfX=12+members+((seed/19)%3);
        this.coreHalfZ=10+members+((seed/23)%3);
        this.storageVariant=(seed/31)%3;

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
        if("gate".equals(k)) return new int[]{cx,surfaceY+1,cz-surfaceHalfZ};
        if("core".equals(k) || "home".equals(k)) return new int[]{cx,undergroundY+1,cz};
        if("drop".equals(k)) return new int[]{cx-3,surfaceY+1,cz-1};
        if("drop-bottom".equals(k)) return new int[]{cx-3,undergroundY+1,cz-1};
        if("elevator".equals(k)) return new int[]{cx+3,undergroundY+1,cz+1};
        if("storage".equals(k)) return new int[]{cx-coreHalfX+5,undergroundY+1,cz+1};
        if("refill".equals(k)) return new int[]{cx-1,undergroundY+1,cz-coreHalfZ+4};
        if("brewer".equals(k)) return new int[]{cx+coreHalfX-6,undergroundY+1,cz+1};
        if("farm".equals(k) || "money-farm".equals(k))
            return new int[]{cx,undergroundY-5,cz+coreHalfZ+9};
        if("wart-farm".equals(k))
            return new int[]{cx,undergroundY-5,cz+coreHalfZ+20};
        if("portal-nether".equals(k))
            return new int[]{cx+coreHalfX-3,undergroundY+1,cz-coreHalfZ+5};
        if("portal-end".equals(k))
            return new int[]{cx+coreHalfX-3,undergroundY+1,cz-coreHalfZ+12};
        return new int[]{cx,undergroundY+1,cz};
    }


    int[] storageSlot(int index) {
        int i=Math.max(0,Math.min(13,index));
        if(storageVariant==0) {
            // Two long central banks: visually dense and easy to scan.
            int row=i<7?0:1;
            int col=i%7;
            return new int[]{cx-10+col*3,undergroundY+1,cz-3+row*6};
        }
        if(storageVariant==1) {
            // Two perimeter banks with a broad center aisle.
            int row=i<7?0:1;
            int col=i%7;
            return new int[]{cx-coreHalfX+3+row*(coreHalfX*2-8),
                undergroundY+1,cz-9+col*3};
        }
        // Split aisles: slightly less perfect, common for a base expanded in
        // stages instead of planned as one showroom.
        int row=i<7?0:1;
        int col=i%7;
        return new int[]{cx-9+row*12,undergroundY+1,cz-9+col*3};
    }

    int surfacePadRadius() {
        return Math.max(surfaceHalfX,surfaceHalfZ)+8;
    }

    private static int positiveHash(String text) {
        int h=text==null?0:text.toLowerCase(Locale.ENGLISH).hashCode();
        return h==Integer.MIN_VALUE?0:Math.abs(h);
    }
}
