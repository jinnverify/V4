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
import java.util.concurrent.atomic.AtomicLong;

import com.voxlink.model.Room;

public class SignalingClient {

    private static final String TAG = "VoxLink.Signal";
    private static final int POLL_INTERVAL_MS       = 3000;
    private static final int SIGNAL_POLL_FAST_MS    = 1000;
    private static final int SIGNAL_POLL_SLOW_MS    = 3000;
    private static final long FAST_POLL_DURATION_MS = 15000;
    private static final int TIMEOUT_MS             = 8000;

    private final String baseUrl;
    private String userId;
    private String roomId;
    private String token;

    private volatile ScheduledExecutorService scheduler;
    private volatile java.util.concurrent.ExecutorService ioExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong joinedAt = new AtomicLong(0);

    private SignalingListener listener;

    public interface SignalingListener {
        void onJoined(Room room, String resolvedUserId);
        void onMembersUpdated(List<Room.Member> members);
        void onMemberLeft(String userId);
        void onSignalReceived(String fromUserId, String type, String payload);
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

    public String getToken()   { return token; }
    public String getBaseUrl() { return baseUrl; }
    public String getUserId()  { return userId; }
    public String getRoomId()  { return roomId; }

    public void stopPolling() {
        connected.set(false);
        ScheduledExecutorService s = scheduler;
        if (s != null) { s.shutdownNow(); scheduler = null; }
    }

    public void join(String roomId, String password, String userName) {
        stopPolling();

        this.roomId = roomId;
        this.userId = userName + "_" + (System.currentTimeMillis() % 9999);

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
                    token = resp.optString("token");
                    Room room = parseRoom(resp);
                    connected.set(true);
                    joinedAt.set(System.currentTimeMillis());

                    Log.d(TAG, "Joined: room=" + roomId + " user=" + userId);

                    mainHandler.post(() -> {
                        if (listener != null) listener.onJoined(room, userId);
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

    public void postSignal(String targetUserId, String type, String payload) {
        final java.util.concurrent.ExecutorService exec = ioExecutor;
        if (exec == null || exec.isShutdown()) return;
        exec.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("room_id",   roomId);
                body.put("user_id",   userId);
                body.put("token",     token);
                body.put("target_id", targetUserId);
                body.put("type",      type);
                body.put("payload",   payload);
                postJson("/signal", body);
            } catch (Exception e) {
                Log.w(TAG, "postSignal failed: " + e.getMessage());
            }
        });
    }

    private void startPolling() {
        scheduler = Executors.newSingleThreadScheduledExecutor();

        // Member polling at fixed 3s
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

        // Signal polling — self-scheduling for adaptive rate
        scheduleNextSignalPoll(500);
    }

    private void scheduleNextSignalPoll(long delayMs) {
        ScheduledExecutorService s = scheduler;
        if (s == null || s.isShutdown()) return;
        s.schedule(() -> {
            if (!connected.get()) return;
            pollSignals();
            long elapsed = System.currentTimeMillis() - joinedAt.get();
            long next = elapsed < FAST_POLL_DURATION_MS ? SIGNAL_POLL_FAST_MS : SIGNAL_POLL_SLOW_MS;
            scheduleNextSignalPoll(next);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void pollSignals() {
        try {
            JSONObject resp = getJson("/signal?room_id=" + java.net.URLEncoder.encode(roomId, "UTF-8")
                    + "&user_id=" + java.net.URLEncoder.encode(userId, "UTF-8")
                    + "&token=" + token);
            if (resp == null || !resp.has("signals")) return;
            JSONArray signals = resp.getJSONArray("signals");
            for (int i = 0; i < signals.length(); i++) {
                JSONObject sig = signals.getJSONObject(i);
                String from    = sig.getString("from");
                String type    = sig.getString("type");
                String payload = sig.getString("payload");
                mainHandler.post(() -> {
                    if (listener != null) listener.onSignalReceived(from, type, payload);
                });
            }
        } catch (Exception e) {
            Log.w(TAG, "Signal poll: " + e.getMessage());
        }
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
