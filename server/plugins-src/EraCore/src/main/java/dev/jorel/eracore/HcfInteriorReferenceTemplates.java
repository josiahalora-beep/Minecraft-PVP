package dev.jorel.eracore;

import org.bukkit.Material;
import org.bukkit.World;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Exact Phase-3 interior geometry extracted from the user-supplied HCF schematics. */
@SuppressWarnings("deprecation")
final class HcfInteriorReferenceTemplates {
    private static final int STANDARD=0, MODE_MODERN_DEEP=1, MODE_MODERN_SHAFT=2;
    private static final int[][] SURFACE={
        {0,0,0,19,21,19},
        {0,0,0,29,20,26},
        {30,116,30,17,9,17},
        {17,45,16,11,12,11},
        {16,28,15,13,12,13}
    };
    private static final int[][] EXPECTED={
        {40,0,0},{144,0,0},{391,202,24},{220,107,16},{192,94,24}
    };

    private static final class Piece {
        final int sx,sy,sz,w,h,l,mode;
        final byte[] rle;
        Piece(int sx,int sy,int sz,int w,int h,int l,int mode,String gz64) {
            this.sx=sx;this.sy=sy;this.sz=sz;this.w=w;this.h=h;this.l=l;this.mode=mode;
            this.rle=inflate(gz64);
        }
    }

    private static final String MODERN_MAIN_DATA=
        "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";
    private static final String MODERN_DEEP_DATA=
        "H4sIAG7ouGoC/61TMW7DMAw8klJdIx07eM7cZgzQsYU+kbmjv5EXdPQX8smSFJ3EBmwYSAbjdGfpjqQt/AIgAvgEZFQ0bvre" +
        "dMUcaPxH8U1RFOVbHFl5O0P+UJ/3us/QuOu6gfqm6orGze9TeRs5hrQxRyLDkOP8zV/AZ3af/Z2v9ztI7VfRztFfBh2za3RM" +
        "ytOq3m6tR/Mnc1D+NfdVT/PmFZ0Hjnr54fzOcrRvGRLSkH3NC5rPR7U846J7prNL/p+M6yX9EDVluqvlssPrJfpYQOrtSWj6" +
        "l9X+r3OI7+zngl/1nkDnisa7DX78tGwBFZ5kUxE09q6kp9Ygs+yRd+M9KPUOGFIbcy55sZYT4R92gAo7MAQAAA==";
    private static final String MODERN_SHAFT_DATA=
        "H4sIAG7ouGoC/2PgYWRg4GRgYGDCQQPlAYx+XwskAAAA";
    private static final String TUNNEL_DATA=
        "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" +
        "lvIGuINdbNRXfbbrror40pQywWcbYYn5lbzn3ch/9jj5Pwf6XNn8rR9CA4nvL/Rt3gdo4JGcNRL3F3J3INZz4xzRz9n1Vbb3" +
        "kh5YrqQJvtNt3LgGHcZHP8joi7Ud3zVeCL3VQhe10AnpgudoL4RnODf6F/CcZiftMU6azefejn7fHrYD+kWNuH8itjPaXBvh" +
        "8/wfi1b0p4sCv4/y/yQkPJ7Lf6xk/7eN6lMJW73wFENfeYTc9xC3tdjp6G+yKuzlK+ENzI9YelVlPC/lv1MmuP6RzWW6wq7M" +
        "/8iJZV6Ntny3Gc/pmgK/bM+Kj5yh/5WMyYLvNWIX9XJ+aCWdZ+b+Hrx/VOB3Lb6BC9ERtMwKvNupD7lgfF3oPCXGe/UZFYw/" +
        "Be39NnjBbeGjdyVnyA3O80Z/4uRXwd/cK18Cz3lfjPOAZBcb/fXITzbQo6jTkDduJvanaV4v+2aena4F4/yPnlrOOK6wTuWZ" +
        "VfbxJd7FR7qXcxHm1wVGaZ9WPURx7MWOrnxVccxzSdKI6ihGfC95nrKbaat1VX6b/rzQU5j/VHw7LfkAfYb60SfxHDyq1MVv" +
        "MuaT3Ye6x4tcH/TTV+N/E2LubhH+Bdr6GTn/yueo4gfD/zoi/pL9xU1sRUwjXjjfbf4fR8pYh32rx7qNtqFW76jxrJRrKu/8" +
        "049yA368lrjBGVrN//XAvS2ep9h+P43zbfK5GOzB/Wh/oi/tCb55q3NapFs5R14X5e2M3rA8gKVJuu4VCwzy3HCKw4Qv7oW3" +
        "e3tEn32O831O4b2PgMdnwN//yH+V1MBMvosjmGkkPeMGdGWEBls5060L/JyIbxHvtDlgaC1+RnquxDI11tSjfcZrrGEt9o9d" +
        "ga8zkXPZ5zPzP5/9bcq0dA9jTFvJfykYjNuLH47vK5Hb5G3JH6vPvHCU8U7SN3p+L3yun/wf4JhP2XpDXhbi2G85/mfLjAym" +
        "PrIp+NchGUzd9RFk7wvI298tePy/FTga5sr+0E1p4rmbYvnGTfF/U+Q/dxNs16duOje5Pc6Fm87FTZE/ttcU7bl72jNor7qn" +
        "vRg374dlf2mhM8gbUyH+xOI+fpAhp/BBuUP621Smfuvy79BvzjudzhPj/2/5DmPVfg+NNX+by9/2cUo3v2Cr/wWbNCWraHoA" +
        "AA==";
    private static final String CAVE_DATA=
        "H4sIAG7ouGoC/+0cTYtcx7E/582bmd3Z75XWH7LRKZHAWAcphkAOUmJEcl3IBuzsLYQ9JiE+6CLIKRAfBIZFF4F+abq6q17X" +
        "q9f95s1KspVYh6XndVXXV1dVV39Iyr9SavHKhb/Uzt7ytw6th1Yp1TLYjOG86beF70BfvwW6OrQ3sYkDHXXS9V3ZsmbbxTuy" +
        "67uynwttg7aawxicOwlbbNkHNGxoU3/m6d5xnyH/e5JkeNv8NPr4Te3yJn0ffLTso3d0olP7Tb6YvtO8bvv7JPx2ga+/IQ3y" +
        "m02yjv3+4A9lf4A5aX5EXzDw26i4xkB79sEnfjKfgDrGsfk/Btv8sFT65U74C/r8oNVMZ1vHOft1o5oW5xBaq5QJfdDGteOR" +
        "UfoTr/Q15prPA51HwS4qjSc6cxhToBHxHq0T7rc73W85fkwOwAW5ZzgG5sVgXVPj2wDuH2z0z6bj5SbxMi9ssNdKtWLcmI7m" +
        "YXnMGK8koyvKOKYX4M+35mOrfIyZrk/7x+Br0UamCl+N6EVwroPGWqU/Xsqr+vwH8qkN8qkN8qmqfDzP/D/nlLQX6ssOcR/3" +
        "SddpDg32zUSuievAM6wJWDvDWmGQd/7TpLn5Sxj71ya2YG/L4rzLLQFX/2aV8g/Dpxyif++jLHJciUcc823AeZRkLuaUDfwM" +
        "tN+Yzke7frQT6F3l/dtlovsPlXJy8NFBTFb4NxvG1Wyqv8n+7iWv7+u8jLBRT7/va7yyXRztPSbyG9Ot/ZtNcVvA6eRCvJ0N" +
        "OhMtM/ABloc6OsN5tgxnVZV7O5y6zGpUZoi9NcbanMWcZvHr0D6O4g/jl+zW/Mzq5TsiF93GfO+EzWv9p2weXCF/3BJ+Y4X/" +
        "nQp/8FvCbwlfKdGX8tn3WDYed3X51Ab+0+B1+epz+3NZ/7eNm4/wDKBbb8N38yCdqeoHJvU90L3fq9IYbGtjFjgnXsRZ4mdH" +
        "xy6ZP9EYw9fMCTIvhE+ZEo0JOmykMUmX5Ls1XQzq4iboRDbdRo9p9rRqf4O+tzfAzzboervnEzl/nFXlUxv4ToPX5VIDuT7k" +
        "jZQ3PmP2+ayr2crfkFPcI/RBnWP9Y5rXeJ4whH/E543jjuFU+ahRPmPy2fdGLrXBbmqCfGqCfGqDfOV5BX/4XNwnfIiLelx8" +
        "Jub7E/h+adNeVPhJOhtxRR9K5yJDWKJnBvR+er6qwleN8FUjfFWFb9/ONf/UN/DZmo/6V7m+fJ99VX6TbWRLtdknOs0Jrdcf" +
        "0ZnJExvPJuN84VxnmEmwFxmW6JiOzufYEn3N9hbb8TCjPKie6Ouhhjw6WdWQx0PTwfo8kq2m5LmG7ldEP8yhLfR77DcF33N4" +
        "XmeEL0C/Rblkv/mRffMmvhjvfRSeUer8lgP64TzKiX7KtzSG7ra9uBMD+Nqqzg7mwKE9+jCNe0hzsOp8sQTXYbyu4MQ5Rrjk" +
        "4XHeuvt7lc7q268CTMinu/1q0nlWwM3yup4sN8Gj/CVtI3GcuOMb8nPCxn0cZ9k51kCuMr2peCW+ye4O5U4+d2RZXNpk84VN" +
        "9y1dXFrc08L9S6vj7wUb5xFuCN6aHryTu83nIi2ey/NYJh92bT574jFFcKJjKnDT5rXux479be62NcYx7z/TiSbo9nHhN/nb" +
        "x6i/wRbo0B21R59dwfcx2vJ1q1po931nW8KDc1azVhFuMG9qRpPmEM5h4Wy8Zbx5bM5pn/xV3j927W6m6yv47QS41LmEM2Mx" +
        "WeaRY8AVcByeNxMO7XlrtDi8rfCgeZW/Y37+FZ51Pzbpvgff74H8Uec/L5POT3EuHemZfA18yQWcBmEa7e0YDpydW8J5rKOs" +
        "5E/de4fgIwZpGaS1g/b0qCfQ2Gd9UcfXOso4Q12WDJ7o+vi2Ro45jPdsqe/Wht+lOIvvEL/sw6bUrDE3fOfSPCmc132d78Au" +
        "VFyzGsL/rlH2n9B6ZS3OlcrnfHH9u1BFfI25VF/qtF7eT3WXwd8z+o1tpmE7GnCPvaCxEE+hdR0+ru9Rp1n3XgVw1+TLSL9h" +
        "c7KP9CzqzmHxbPm+72LX4G/o84ruRzM+nCcvdII7pmODvhV53LcdDYoji7wM7j00Ozf3OusbZXHZ9+eUU0K/xd8N1ugO5SEb" +
        "+6/yWTjgG+RLdvNUxwAtlIvG8HniPEgfy+wLYwyTi+ZJC16kjxynWa4mP9F4D22plkXZDRtnOT+d/Yf0cjrbK87NY9XR7Wos" +
        "ZmO6p+b31b27cDaPM53tFL+F3N16i/7RMBntK7Y2oC2Jpn6KfsJ1wnxI+OSjyc4+5l2LuB7xDgt9MJb7fsNyIHzvIYz7uMNx" +
        "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";

    private static final Piece MODERN_MAIN=new Piece(18,71,8,31,45,43,STANDARD,MODERN_MAIN_DATA);
    private static final Piece MODERN_DEEP=new Piece(35,25,39,11,11,13,MODE_MODERN_DEEP,MODERN_DEEP_DATA);
    private static final Piece MODERN_SHAFT=new Piece(35,36,39,11,1,6,MODE_MODERN_SHAFT,MODERN_SHAFT_DATA);
    private static final Piece TUNNEL=new Piece(2,3,1,36,42,33,STANDARD,TUNNEL_DATA);
    private static final Piece CAVE=new Piece(7,2,5,36,26,35,STANDARD,CAVE_DATA);

    private HcfInteriorReferenceTemplates() {}

    static boolean hasExactInterior(int family) { return family>=0 && family<=4; }

    static void queue(ArrayDeque<HcfBaseBuilder.Op> q,World w,HcfBasePlan p) {
        if(p.primaryFamily==2) {
            queuePiece(q,w,p,MODERN_MAIN);
            queuePiece(q,w,p,MODERN_DEEP);
            queuePiece(q,w,p,MODERN_SHAFT);
        } else if(p.primaryFamily==3) queuePiece(q,w,p,TUNNEL);
        else if(p.primaryFamily==4) queuePiece(q,w,p,CAVE);
        // Redemption and Base-HCF have no separate below-surface component:
        // their complete schematic interiors are already inside the exact Phase-2B volume.
    }

    private static void queuePiece(ArrayDeque<HcfBaseBuilder.Op> q,World world,HcfBasePlan p,Piece t) {
        if(t.mode==MODE_MODERN_DEEP) {
            int mainBottom=p.surfaceY-45;
            if(mainBottom<14) return;
        }
        if(t.mode==MODE_MODERN_SHAFT) {
            int mainBottom=p.surfaceY-45;
            if(mainBottom<14) return;
            int deepBase=Math.max(2,mainBottom-15);
            for(int wy=deepBase+11;wy<mainBottom;wy++) queueLayer(q,world,p,t,wy);
            return;
        }
        int cursor=0,total=t.w*t.h*t.l;
        for(int i=0;i+3<t.rle.length;i+=4) {
            int count=((t.rle[i]&255)<<8)|(t.rle[i+1]&255);
            int id=t.rle[i+2]&255; byte data=t.rle[i+3];
            Material m=Material.getMaterial(id); if(m==null) m=Material.AIR;
            for(int n=0;n<count && cursor<total;n++,cursor++) {
                int x=cursor%t.w,qv=cursor/t.w,z=qv%t.l,y=qv/t.l;
                int sy=t.sy+y,wy=worldY(p,sy);
                if(wy<=0 || wy>=world.getMaxHeight()) continue;
                q.add(new HcfBaseBuilder.Op(world,worldX(p,t.sx+x),wy,worldZ(p,t.sz+z),m,data));
            }
        }
        if(cursor!=total) throw new IllegalStateException("Interior template truncated family="+p.primaryFamily);
    }

    private static void queueLayer(ArrayDeque<HcfBaseBuilder.Op> q,World world,HcfBasePlan p,Piece t,int wy) {
        int cursor=0,total=t.w*t.l;
        for(int i=0;i+3<t.rle.length;i+=4) {
            int count=((t.rle[i]&255)<<8)|(t.rle[i+1]&255);
            int id=t.rle[i+2]&255; byte data=t.rle[i+3];
            Material m=Material.getMaterial(id); if(m==null) m=Material.AIR;
            for(int n=0;n<count && cursor<total;n++,cursor++) {
                int x=cursor%t.w,z=cursor/t.w;
                q.add(new HcfBaseBuilder.Op(world,worldX(p,t.sx+x),wy,worldZ(p,t.sz+z),m,data));
            }
        }
    }

    private static int worldX(HcfBasePlan p,int sx) {
        int[] s=SURFACE[p.primaryFamily];
        return p.cx-((s[3]-1)/2)+(sx-s[0]);
    }
    private static int worldZ(HcfBasePlan p,int sz) {
        int[] s=SURFACE[p.primaryFamily];
        return p.cz-((s[5]-1)/2)+(sz-s[2]);
    }
    private static int worldY(HcfBasePlan p,int sy) {
        int[] s=SURFACE[p.primaryFamily];
        if(p.primaryFamily==2 && sy<=35) {
            int mainBottom=p.surfaceY-45;
            if(mainBottom<14) return Integer.MIN_VALUE;
            return Math.max(2,mainBottom-15)+(sy-25);
        }
        return p.surfaceY+(sy-s[1]);
    }
    static int[] sourceToWorld(HcfBasePlan p,int sx,int sy,int sz) {
        int y=worldY(p,sy); if(y==Integer.MIN_VALUE) return null;
        return new int[]{worldX(p,sx),y,worldZ(p,sz)};
    }

    static int[] anchor(HcfBasePlan p,String kind) {
        if(!hasExactInterior(p.primaryFamily)) return null;
        String k=kind==null?"":kind.toLowerCase(Locale.ENGLISH);
        int[] s=null;
        switch(p.primaryFamily) {
            case 0:
                if("storage".equals(k)||"refill".equals(k)) s=new int[]{7,12,10};
                else if("elevator".equals(k)||"core".equals(k)||"home".equals(k)) s=new int[]{16,8,8};
                else s=new int[]{12,2,12};
                break;
            case 1:
                if("storage".equals(k)||"refill".equals(k)) s=new int[]{12,13,8};
                else s=new int[]{9,1,8};
                break;
            case 2:
                if("brewer".equals(k)) s=new int[]{36,90,17};
                else if("elevator".equals(k)||"drop-bottom".equals(k)) s=new int[]{28,96,27};
                else if("farm".equals(k)||"money-farm".equals(k)||"wart-farm".equals(k)) s=new int[]{37,33,40};
                else if("utility".equals(k)) s=new int[]{31,89,23};
                else if("enchant".equals(k)) s=new int[]{38,99,35};
                else s=new int[]{35,99,24};
                break;
            case 3:
                if("brewer".equals(k)) s=new int[]{6,12,10};
                else if("portal-end".equals(k)) s=new int[]{15,11,25};
                else if("enchant".equals(k)) s=new int[]{33,12,25};
                else s=new int[]{16,11,11};
                break;
            case 4:
                if("brewer".equals(k)) s=new int[]{34,9,12};
                else if("portal-end".equals(k)) s=new int[]{15,15,10};
                else if("elevator".equals(k)||"drop-bottom".equals(k)) s=new int[]{34,17,16};
                else if("farm".equals(k)||"money-farm".equals(k)||"wart-farm".equals(k)) s=new int[]{12,7,12};
                else if("enchant".equals(k)) s=new int[]{34,17,36};
                else s=new int[]{21,17,36};
                break;
        }
        return s==null?null:sourceToWorld(p,s[0],s[1],s[2]);
    }

    static String verify(World world,HcfBasePlan p) {
        int[] got=countSpecials(world,p),exp=EXPECTED[p.primaryFamily];
        int mismatch=Math.abs(got[0]-exp[0])+Math.abs(got[1]-exp[1])+Math.abs(got[2]-exp[2]);
        return "family="+p.primaryFamilyName()+" chest="+got[0]+"/"+exp[0]+
            " hopper="+got[1]+"/"+exp[1]+" brewer="+got[2]+"/"+exp[2]+" mismatches="+mismatch;
    }

    private static int[] countSpecials(World w,HcfBasePlan p) {
        int chest=0,hopper=0,brewer=0;
        if(p.primaryFamily==0 || p.primaryFamily==1) {
            int[] s=SURFACE[p.primaryFamily];
            int ox=p.cx-((s[3]-1)/2),oz=p.cz-((s[5]-1)/2);
            int[] c=countBox(w,ox,p.surfaceY,oz,s[3],s[4],s[5]); chest+=c[0];hopper+=c[1];brewer+=c[2];
        } else if(p.primaryFamily==2) {
            int[] c=countPiece(w,p,MODERN_MAIN);chest+=c[0];hopper+=c[1];brewer+=c[2];
            if(p.surfaceY-45>=14) { c=countPiece(w,p,MODERN_DEEP);chest+=c[0];hopper+=c[1];brewer+=c[2]; }
            int[] s=SURFACE[2]; int ox=p.cx-8,oz=p.cz-8;
            c=countBox(w,ox,p.surfaceY,oz,17,9,17);chest+=c[0];hopper+=c[1];brewer+=c[2];
        } else {
            Piece t=p.primaryFamily==3?TUNNEL:CAVE; int[] c=countPiece(w,p,t);chest+=c[0];hopper+=c[1];brewer+=c[2];
        }
        return new int[]{chest,hopper,brewer};
    }
    private static int[] countPiece(World w,HcfBasePlan p,Piece t) {
        int y=worldY(p,t.sy); if(y==Integer.MIN_VALUE) return new int[]{0,0,0};
        return countBox(w,worldX(p,t.sx),y,worldZ(p,t.sz),t.w,t.h,t.l);
    }
    private static int[] countBox(World w,int ox,int oy,int oz,int width,int height,int length) {
        int c=0,h=0,b=0;
        for(int y=oy;y<oy+height;y++) if(y>0&&y<w.getMaxHeight())
            for(int z=oz;z<oz+length;z++) for(int x=ox;x<ox+width;x++) {
                int id=w.getBlockAt(x,y,z).getTypeId();
                if(id==54||id==146)c++; else if(id==154)h++; else if(id==117)b++;
            }
        return new int[]{c,h,b};
    }

    private static byte[] inflate(String s) {
        try {
            byte[] packed=Base64.getDecoder().decode(s);
            GZIPInputStream in=new GZIPInputStream(new ByteArrayInputStream(packed));
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buf=new byte[4096]; int n;
            while((n=in.read(buf))>0) out.write(buf,0,n);
            in.close(); return out.toByteArray();
        } catch(IOException e) { throw new IllegalStateException("Bad embedded HCF interior template",e); }
    }
}