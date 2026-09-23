package dev.jorel.eracore;

import org.bukkit.Bukkit;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Optional semantic-chat bridge.
 *
 * The Minecraft thread never waits on network I/O. A local Node sidecar owns
 * the external model call; if it is unavailable, callers receive null and use
 * the deterministic ContextChatBrain fallback.
 *
 * The model may PROPOSE a social action, but authoritative Java code validates
 * every proposal before changing factions, economy, moderation or other state.
 */
final class AiChatBridge {
    private long lastRequestAt;
    private long minuteWindowStart;
    private int minuteWindowCount;
    private long acceptedRequests;
    private long rejectedByBudget;
    interface Handler {
        void complete(AiReply reply);
    }

    static final class AiReply {
        final String text;
        final int affinityDelta;
        final int trustDelta;
        final int respectDelta;
        final String action;
        final String memory;

        AiReply(String text,int affinityDelta,int trustDelta,int respectDelta,
                String action,String memory) {
            this.text=text==null?"":text.trim();
            this.affinityDelta=clampDelta(affinityDelta);
            this.trustDelta=clampDelta(trustDelta);
            this.respectDelta=clampDelta(respectDelta);
            this.action=cleanToken(action);
            this.memory=sanitize(memory,140);
        }

        private static int clampDelta(int n) {
            return Math.max(-2,Math.min(2,n));
        }

        private static String cleanToken(String s) {
            if(s==null) return "NONE";
            String x=s.trim().toUpperCase(java.util.Locale.ENGLISH).replaceAll("[^A-Z0-9_]", "");
            return x.isEmpty()?"NONE":x;
        }
    }

    private final EraCore plugin;

    AiChatBridge(EraCore plugin) {
        this.plugin=plugin;
    }

    synchronized boolean budgetAvailable() {
        long now=System.currentTimeMillis();
        long minGap=Math.max(1000L,plugin.getConfig().getLong("sim-chat.ai-min-seconds-between-requests",8L)*1000L);
        int maxPerMinute=Math.max(1,plugin.getConfig().getInt("sim-chat.ai-max-requests-per-minute",4));
        if(minuteWindowStart==0L || now-minuteWindowStart>=60000L) {
            minuteWindowStart=now;
            minuteWindowCount=0;
        }
        if(now-lastRequestAt<minGap || minuteWindowCount>=maxPerMinute) {
            rejectedByBudget++;
            return false;
        }
        lastRequestAt=now;
        minuteWindowCount++;
        acceptedRequests++;
        return true;
    }

    synchronized String budgetStatus() {
        return "accepted="+acceptedRequests+" budgetFallbacks="+rejectedByBudget+
            " minute="+minuteWindowCount+"/"+Math.max(1,plugin.getConfig().getInt("sim-chat.ai-max-requests-per-minute",4));
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("ai-chat.enabled",true);
    }

    boolean request(final String channel,final String speaker,final String responder,
                    final String context,final String message,final Handler handler) {
        if(!enabled() || message==null || message.trim().isEmpty()) return false;
        if(!budgetAvailable()) return false;

        final String endpoint=plugin.getConfig().getString("ai-chat.endpoint","http://127.0.0.1:8765/reply");
        final int timeout=Math.max(750,plugin.getConfig().getInt("ai-chat.timeout-ms",6500));

        Bukkit.getScheduler().runTaskAsynchronously(plugin,new Runnable() {
            public void run() {
                AiReply result=null;
                HttpURLConnection conn=null;
                try {
                    URL url=new URL(endpoint);
                    conn=(HttpURLConnection)url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(Math.min(timeout,1200));
                    conn.setReadTimeout(timeout);
                    conn.setRequestProperty("Content-Type","application/json; charset=utf-8");

                    String json="{"+
                        "\"channel\":\""+escape(channel)+"\","+
                        "\"speaker\":\""+escape(speaker)+"\","+
                        "\"responder\":\""+escape(responder)+"\","+
                        "\"context\":\""+escape(context)+"\","+
                        "\"message\":\""+escape(message)+"\""+
                        "}";

                    byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(bytes.length);
                    OutputStream out=conn.getOutputStream();
                    out.write(bytes);
                    out.close();

                    if(conn.getResponseCode()>=200 && conn.getResponseCode()<300) {
                        BufferedReader reader=new BufferedReader(new InputStreamReader(conn.getInputStream(),StandardCharsets.UTF_8));
                        String line=reader.readLine();
                        reader.close();
                        if(line!=null && !line.trim().isEmpty()) {
                            String[] parts=line.split("\\t",-1);
                            if(parts.length>=6) {
                                int affinity=parseInt(parts[0]);
                                int trust=parseInt(parts[1]);
                                int respect=parseInt(parts[2]);
                                String action=parts[3];
                                String memory=parts[4];
                                StringBuilder reply=new StringBuilder();
                                for(int i=5;i<parts.length;i++) {
                                    if(reply.length()>0) reply.append(' ');
                                    reply.append(parts[i]);
                                }
                                String text=sanitize(reply.toString(),180);
                                if(!text.isEmpty()) result=new AiReply(text,affinity,trust,respect,action,memory);
                            } else if(parts.length>=2) {
                                // Backward compatible with the original
                                // affinity<TAB>reply sidecar response.
                                String text=sanitize(parts[1],180);
                                if(!text.isEmpty()) result=new AiReply(text,parseInt(parts[0]),0,0,"NONE","");
                            }
                        }
                    }
                } catch(Throwable ignored) {
                    result=null;
                } finally {
                    if(conn!=null) conn.disconnect();
                }

                final AiReply done=result;
                Bukkit.getScheduler().runTask(plugin,new Runnable() {
                    public void run() {
                        if(handler!=null) handler.complete(done);
                    }
                });
            }
        });
        return true;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s==null?"0":s.trim()); }
        catch(Exception ignored) { return 0; }
    }

    private static String sanitize(String s,int max) {
        if(s==null) return "";
        String x=s.replace('\n',' ').replace('\r',' ').replace('\t',' ').trim();
        while(x.contains("  ")) x=x.replace("  "," ");
        if(x.length()>max) x=x.substring(0,max).trim();
        return x;
    }

    private static String escape(String s) {
        if(s==null) return "";
        StringBuilder b=new StringBuilder();
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if(c=='\\') b.append("\\\\");
            else if(c=='\"') b.append("\\\"");
            else if(c=='\n') b.append("\\n");
            else if(c=='\r') b.append("\\r");
            else if(c=='\t') b.append("\\t");
            else if(c<32) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }
}
