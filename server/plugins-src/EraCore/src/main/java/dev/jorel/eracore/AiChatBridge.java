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
 */
final class AiChatBridge {
    interface Handler {
        void complete(AiReply reply);
    }

    static final class AiReply {
        final String text;
        final int affinityDelta;
        AiReply(String text,int affinityDelta) {
            this.text=text==null?"":text.trim();
            this.affinityDelta=Math.max(-2,Math.min(2,affinityDelta));
        }
    }

    private final EraCore plugin;

    AiChatBridge(EraCore plugin) {
        this.plugin=plugin;
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("ai-chat.enabled",true);
    }

    boolean request(final String channel,final String speaker,final String responder,
                    final String context,final String message,final Handler handler) {
        if(!enabled() || message==null || message.trim().isEmpty()) return false;

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
                            int tab=line.indexOf('\t');
                            int delta=0;
                            String text=line;
                            if(tab>=0) {
                                try { delta=Integer.parseInt(line.substring(0,tab).trim()); }
                                catch(Exception ignored) {}
                                text=line.substring(tab+1);
                            }
                            text=sanitize(text);
                            if(!text.isEmpty()) result=new AiReply(text,delta);
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

    private static String sanitize(String s) {
        if(s==null) return "";
        String x=s.replace('\n',' ').replace('\r',' ').replace('\t',' ').trim();
        while(x.contains("  ")) x=x.replace("  "," ");
        if(x.length()>180) x=x.substring(0,180).trim();
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
