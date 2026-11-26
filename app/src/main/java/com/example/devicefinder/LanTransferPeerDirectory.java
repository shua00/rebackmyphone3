package com.example.devicefinder;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** File-transfer-owned candidates. Chat discovery is a fallback hint, never an authority. */
public final class LanTransferPeerDirectory {
    public static final long ENDPOINT_TTL_MS = 120_000L;
    public static final int MAX_ENTRIES = 128;
    private static final String PREFS = "lan_transfer_peer_directory_v1";
    private static final String KEY_ENTRIES = "entries_v1";
    private static final String KEY_CURRENT_NETWORK = "current_network";
    private static final Object LOCK = new Object();
    public static final String SOURCE_FILE_DISCOVERY = "file_discovery";
    public static final String SOURCE_CHAT_HINT = "chat_hint";

    public static final class Candidate {
        public final String peerId, host, networkFingerprint, source;
        public final int port, pairingPort;
        public final long lastSeenAtMs;
        private Candidate(String id, String address, int tcpPort, int controlPort,
                String network, String via, long seen) {
            peerId=id; host=address; port=tcpPort; pairingPort=controlPort;
            networkFingerprint=network; source=via; lastSeenAtMs=seen;
        }
        public boolean isConnectable() { return port >= 1 && port <= 65535; }
        public boolean isPairingConnectable() { return pairingPort >= 1 && pairingPort <= 65535; }
        public boolean isAuthenticated() { return false; }
    }
    private LanTransferPeerDirectory() {}

    public static void activateNetwork(Context context, String fingerprint) {
        if (context == null || !validFingerprint(fingerprint))
            throw new IllegalArgumentException("Invalid current LAN fingerprint");
        synchronized (LOCK) {
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_CURRENT_NETWORK, fingerprint).commit();
        }
    }
    public static String getCurrentNetwork(Context context) {
        if (context == null) return null;
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CURRENT_NETWORK, null);
    }
    public static void recordFileDiscoveryCandidate(Context c, String id, String host, int port,
            String network, long now) {
        record(c, id, host, port, LanTransferPairingControlProtocol.DEFAULT_PORT,
                network, SOURCE_FILE_DISCOVERY, now);
    }
    public static void recordFileDiscoveryCandidate(Context c, String id, String host,
            int filePort, int pairingPort, String network, long now) {
        record(c, id, host, filePort, pairingPort, network, SOURCE_FILE_DISCOVERY, now);
    }
    /** Optional chat hint. It is usable only when no fresh file-discovery lease exists. */
    public static void recordChatDiscoveryHint(Context c, String id, String host, int port,
            String network, long now) {
        record(c, id, host, port, LanTransferPairingControlProtocol.DEFAULT_PORT,
                network, SOURCE_CHAT_HINT, now);
    }

    private static void record(Context context, String id, String host, int port,
            int pairingPort, String network, String source, long now) {
        if (context == null || !validPeerId(id) || !validIpv4(host)
                || port < 0 || port > 65535 || pairingPort < 1 || pairingPort > 65535
                || !validFingerprint(network) || now <= 0L
                || !(SOURCE_FILE_DISCOVERY.equals(source) || SOURCE_CHAT_HINT.equals(source)))
            throw new IllegalArgumentException("Invalid LAN transfer discovery candidate");
        synchronized (LOCK) {
            List<Candidate> next = read(context);
            // Maintain a separate lease per source and peer. Chat cannot evict file discovery.
            for (int i=next.size()-1;i>=0;i--) {
                Candidate old=next.get(i);
                if (old.peerId.equals(id) && old.networkFingerprint.equals(network)
                        && old.source.equals(source)) next.remove(i);
            }
            next.add(new Candidate(id,host,port,pairingPort,network,source,now));
            prune(next,now);
            Collections.sort(next,(a,b)->Long.compare(b.lastSeenAtMs,a.lastSeenAtMs));
            while(next.size()>MAX_ENTRIES*2) next.remove(next.size()-1);
            persist(context,next);
        }
    }
    public static Candidate findCurrent(Context c, String id, long now) {
        String network=getCurrentNetwork(c);
        if(c==null || !validPeerId(id) || !validFingerprint(network) || now<=0L) return null;
        synchronized(LOCK) {
            Candidate chat=null;
            for(Candidate item:read(c)) {
                if(!item.peerId.equals(id) || !item.networkFingerprint.equals(network) || !fresh(item,now))
                    continue;
                if(SOURCE_FILE_DISCOVERY.equals(item.source)) return item;
                if(SOURCE_CHAT_HINT.equals(item.source) && (chat==null || item.lastSeenAtMs>chat.lastSeenAtMs))
                    chat=item;
            }
            return chat;
        }
    }
    public static List<Candidate> loadCurrent(Context c, long now) {
        String network=getCurrentNetwork(c);
        if(c==null || !validFingerprint(network) || now<=0L) return Collections.emptyList();
        synchronized(LOCK) {
            Map<String,Candidate> selected=new LinkedHashMap<>();
            for(Candidate item:read(c)) {
                if(!item.networkFingerprint.equals(network) || !fresh(item,now)) continue;
                Candidate old=selected.get(item.peerId);
                if(old==null || (SOURCE_CHAT_HINT.equals(old.source)
                        && SOURCE_FILE_DISCOVERY.equals(item.source))
                        || (old.source.equals(item.source) && item.lastSeenAtMs>old.lastSeenAtMs))
                    selected.put(item.peerId,item);
            }
            List<Candidate> result=new ArrayList<>(selected.values());
            Collections.sort(result,(a,b)->Long.compare(b.lastSeenAtMs,a.lastSeenAtMs));
            return Collections.unmodifiableList(result);
        }
    }
    public static void pruneExpired(Context c,long now) {
        if(c==null || now<=0L) return;
        synchronized(LOCK) {
            List<Candidate> entries=read(c); int before=entries.size(); prune(entries,now);
            if(entries.size()!=before) persist(c,entries);
        }
    }
    public static void clear(Context c) {
        if(c==null)return;
        synchronized(LOCK) {
            c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                    .remove(KEY_ENTRIES).commit();
        }
    }
    private static List<Candidate> read(Context c) {
        List<Candidate> out=new ArrayList<>();
        String raw=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getString(KEY_ENTRIES,null);
        if(raw==null || raw.isEmpty())return out;
        try {
            JSONArray rows=new JSONArray(raw);
            for(int i=0;i<rows.length() && i<MAX_ENTRIES*4;i++) {
                JSONObject row=rows.optJSONObject(i); if(row==null)continue;
                String id=row.optString("peer_id",""), host=row.optString("host","");
                int port=row.optInt("port",-1);
                int pairingPort=row.optInt("pairing_port",LanTransferPairingControlProtocol.DEFAULT_PORT);
                String network=row.optString("network","");
                String source=row.optString("source",""); long seen=row.optLong("seen_at_ms",0L);
                if(validPeerId(id)&&validIpv4(host)&&port>=0&&port<=65535
                        &&pairingPort>=1&&pairingPort<=65535&&validFingerprint(network)
                        &&(SOURCE_FILE_DISCOVERY.equals(source)||SOURCE_CHAT_HINT.equals(source))&&seen>0L)
                    out.add(new Candidate(id,host,port,pairingPort,network,source,seen));
            }
        } catch(Exception corrupt) { return new ArrayList<>(); }
        return out;
    }
    private static void persist(Context c,List<Candidate> entries) {
        JSONArray rows=new JSONArray();
        try {
            for(Candidate item:entries) {
                JSONObject row=new JSONObject();
                row.put("peer_id",item.peerId); row.put("host",item.host); row.put("port",item.port);
                row.put("pairing_port",item.pairingPort);
                row.put("network",item.networkFingerprint); row.put("source",item.source);
                row.put("seen_at_ms",item.lastSeenAtMs); rows.put(row);
            }
        } catch(Exception impossible) { throw new IllegalStateException(impossible); }
        if(!c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString(KEY_ENTRIES,rows.toString()).commit())
            throw new IllegalStateException("Unable to persist transfer endpoint cache");
    }
    private static void prune(List<Candidate> entries,long now) {
        for(int i=entries.size()-1;i>=0;i--) if(!fresh(entries.get(i),now))entries.remove(i);
    }
    private static boolean fresh(Candidate item,long now) {
        return item.lastSeenAtMs<=now && now-item.lastSeenAtMs<=ENDPOINT_TTL_MS;
    }
    private static boolean validPeerId(String s) { return s!=null&&s.matches("[A-Za-z0-9._:-]{1,128}"); }
    private static boolean validFingerprint(String s) {
        return s!=null&&!s.isEmpty()&&s.length()<=256&&!s.contains("\n")&&!s.contains("\r");
    }
    private static boolean validIpv4(String s) {
        if(s==null)return false; String[] p=s.split("\\.",-1); if(p.length!=4)return false;
        for(String part:p) {
            if(part.isEmpty()||part.length()>3)return false; int n=0;
            for(int i=0;i<part.length();i++){char ch=part.charAt(i);if(ch<'0'||ch>'9')return false;n=n*10+ch-'0';}
            if(n>255)return false;
        }
        return true;
    }
}
