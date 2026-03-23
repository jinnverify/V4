package com.voxlink.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.voxlink.model.Room;

public class SignalingClient {

    private static final String TAG = "VoxLink.Signal";
    private static final int POLL_INTERVAL_MS  = 3000;
    private static final int TIMEOUT_MS        = 8000;

    private final String baseUrl;
    private String userId;
    private String roomId;
    private String token;
    private String udpHost;
    private String udpKey;
    private String udpRoomId;
    private String udpUserId;
    private int    udpPort = 45000;
    private String resolvedUserId;

    private volatile ScheduledExecutorService scheduler;
    private volatile java.util.concurrent.ExecutorService ioExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private SignalingListener listener;

    public interface SignalingListener {
        void onJoined(Room room, String udpHost, int udpPort, String udpKey,
                      String udpRoomId, String udpUserId, String resolvedUserId);
        void onMembersUpdated(List<Room.Member> members);
        void onMemberLeft(String userId);
        void onError(String message);
        void onDisconnected();
    }

    public SignalingClient(String serverHost) {
        if (serverHost.startsWith("http://") || serverHost.startsWith("https://")) {
            this.baseUrl = serverHost.replaceAll("/$", "");
        } else {
            boolean isLocal = serverHost.startsWith("192.168.")
                           || serverHost.startsWith("10.")
                           || serverHost.matches("^172\\.(1[6-9]|2[0-9]|3[01])\\..+")
                           || serverHost.equals("localhost")
                           || serverHost.startsWith("localhost:");
            this.baseUrl = (isLocal ? "http://" : "https://") + serverHost;
        }
        Log.d(TAG, "Server base URL: " + this.baseUrl);
    }

    public void setListener(SignalingListener listener) {
        this.listener = listener;
    }

    public String getUdpHost()        { return udpHost; }
    public String getUdpKey()         { return udpKey; }
    public String getUdpRoomId()      { return udpRoomId; }
    public String getUdpUserId()      { return udpUserId; }
    public int    getUdpPort()        { return udpPort; }
    public String getResolvedUserId() { return resolvedUserId; }
    public String getToken()          { return token; }
    public String getBaseUrl()        { return baseUrl; }

    /** Stop polling without sending leave */
    public void stopPolling() {
        connected.set(false);
        ScheduledExecutorService s = scheduler;
        if (s != null) { s.shutdownNow(); scheduler = null; }
    }

    /** Join a room */
    public void join(String roomId, String password, String userName) {
        // Clean up any previous session
        stopPolling();

        this.roomId = roomId;
        this.userId = userName + "_" + (System.currentTimeMillis() % 9999);
        this.resolvedUserId = this.userId;

        if (ioExecutor == null || ioExecutor.isShutdown()) {
            ioExecutor = Executors.newSingleThreadExecutor();
        }

        ioExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("room_id",    roomId);
                body.put("password",   password != null ? password : "");
                body.put("user_id",    userId);
                body.put("user_name",  userName);

                JSONObject resp = postJson("/join", body);

                if (resp != null && resp.optBoolean("success", false)) {
                    token     = resp.optString("token");
                    udpKey    = resp.optString("udp_key", "");
                    udpRoomId = resp.optString("udp_room_id", "");
                    udpUserId = resp.optString("udp_user_id", "");
                    udpHost   = resp.optString("udp_host", baseUrl.replaceAll("https?://", "").split(":")[0]);
                    udpPort   = resp.optInt("udp_port", 45000);

                    Room room = parseRoom(resp);
                    connected.set(true);

                    Log.d(TAG, "Joined: room=" + roomId + " udpHost=" + udpHost
                            + ":" + udpPort + " udpRoom=" + udpRoomId
                            + " udpUser=" + udpUserId);

                    final String uid = resolvedUserId;
                    final String key = udpKey;
                    final String uRid = udpRoomId;
                    final String uUid = udpUserId;
                    mainHandler.post(() -> {
                        if (listener != null) listener.onJoined(room, udpHost, udpPort, key, uRid, uUid, uid);
                    });

                    startPolling();
                } else {
                    String err = resp != null ? resp.optString("error", "Join failed") : "Server unreachable";
                    mainHandler.post(() -> {
                        if (listener != null) listener.onError(err);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Join error: " + e.getMessage());
                mainHandler.post(() -> {
                    if (listener != null) listener.onError("Connection failed: " + e.getMessage());
                });
            }
        });
    }

    public void leave() {
        connected.set(false);
        ScheduledExecutorService s = scheduler;
        if (s != null) { s.shutdownNow(); scheduler = null; }

        final java.util.concurrent.ExecutorService exec = ioExecutor;
        if (exec == null || exec.isShutdown()) return;
        exec.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("room_id", roomId);
                body.put("user_id", userId);
                body.put("token",   token);
                postJson("/leave", body);
            } catch (Exception ignored) {}
            exec.shutdown();
            ioExecutor = null;
        });
    }

    // Polling only — no separate heartbeat (VoiceService handles heartbeat)
    private void startPolling() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            if (!connected.get()) return;
            try {
                JSONObject resp = getJson("/poll?room_id=" + java.net.URLEncoder.encode(roomId, "UTF-8")
                        + "&user_id=" + java.net.URLEncoder.encode(userId, "UTF-8")
                        + "&token=" + token);
                if (resp == null) return;
                if (resp.optBoolean("disbanded", false)) {
                    connected.set(false);
                    mainHandler.post(() -> { if (listener != null) listener.onDisconnected(); });
                    return;
                }
                if (resp.has("members")) {
                    List<Room.Member> members = parseMemberList(resp.getJSONArray("members"));
                    mainHandler.post(() -> { if (listener != null) listener.onMembersUpdated(members); });
                }
            } catch (Exception e) {
                Log.w(TAG, "Poll: " + e.getMessage());
            }
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────────

    private JSONObject postJson(String path, JSONObject body) throws Exception {
        HttpURLConnection c = open(baseUrl + path, "POST");
        try {
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = c.getOutputStream()) { os.write(data); }
            if (c.getResponseCode() == 200) return new JSONObject(read(c));
            Log.w(TAG, path + " returned " + c.getResponseCode());
            return null;
        } finally {
            c.disconnect();
        }
    }

    private JSONObject getJson(String path) throws Exception {
        HttpURLConnection c = open(baseUrl + path, "GET");
        try {
            if (c.getResponseCode() == 200) return new JSONObject(read(c));
            return null;
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept",       "application/json");
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        return c;
    }

    private String read(HttpURLConnection c) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String l; while ((l = br.readLine()) != null) sb.append(l);
        }
        return sb.toString();
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    private Room parseRoom(JSONObject j) throws Exception {
        Room room = new Room(j.optString("room_id", roomId), "");
        room.memberCount = j.optInt("member_count", 1);
        if (j.has("members")) room.members = parseMemberList(j.getJSONArray("members"));
        return room;
    }

    private List<Room.Member> parseMemberList(JSONArray arr) throws Exception {
        List<Room.Member> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.getJSONObject(i);
            Room.Member member = new Room.Member(m.optString("user_id"), m.optString("user_name", "Player"));
            member.isMuted    = m.optBoolean("muted",   false);
            member.isSpeaking = m.optBoolean("speaking", false);
            list.add(member);
        }
        return list;
    }

    public static String buildShareUrl(String serverHost, String roomId, String password) {
        String base = serverHost.startsWith("http") ? serverHost : "https://" + serverHost;
        try {
            return base + "/join?r=" + java.net.URLEncoder.encode(roomId, "UTF-8")
                    + "&p=" + java.net.URLEncoder.encode(password != null ? password : "", "UTF-8");
        } catch (Exception e) {
            return base + "/join?r=" + roomId + "&p=" + (password != null ? password : "");
        }
    }
}
