package com.voxlink.audio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import androidx.core.app.NotificationCompat;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.voxlink.ui.RoomActivity;

public class VoiceService extends Service {

    private static final String TAG              = "VoxLink.Service";
    private static final String PREFS_NAME       = "voxlink_service";
    public  static final String ACTION_START     = "com.voxlink.START";
    public  static final String ACTION_STOP      = "com.voxlink.STOP";
    public  static final String ACTION_MUTE      = "com.voxlink.MUTE";
    public  static final String CHANNEL_ID       = "voxlink_voice";
    public  static final int    NOTIFICATION_ID  = 101;

    public static final String EXTRA_ROOM_ID     = "room_id";
    public static final String EXTRA_USER_ID     = "user_id";
    public static final String EXTRA_USER_NAME   = "user_name";
    public static final String EXTRA_BASE_URL    = "base_url";
    public static final String EXTRA_TOKEN       = "token";

    private WebRTCEngine               webrtcEngine;
    private PowerManager.WakeLock      wakeLock;
    private WifiManager.WifiLock       wifiLock;
    private ScheduledExecutorService   heartbeatScheduler;
    private volatile boolean           isMuted     = false;
    private String                     currentRoom = "";

    private final AtomicInteger heartbeatFailCount = new AtomicInteger(0);

    // Floating overlay dot
    private WindowManager              windowManager;
    private View                       overlayDot;
    private GradientDrawable           dotDrawable;
    private Handler                    overlayHandler;
    private boolean                    overlayAdded = false;

    private final IBinder binder = new VoiceBinder();

    public class VoiceBinder extends Binder {
        public VoiceService getService() { return VoiceService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String roomId  = prefs.getString("room_id", null);
            String baseUrl = prefs.getString("base_url", null);
            if (roomId != null && baseUrl != null) {
                Log.d(TAG, "System restart — resuming voice for " + roomId);
                startVoice(
                    roomId,
                    prefs.getString("user_id", null),
                    prefs.getString("user_name", null),
                    prefs.getString("base_url", null),
                    prefs.getString("token", null)
                );
            } else {
                stopSelf();
            }
            return START_STICKY;
        }

        String action = intent.getAction();
        if (action == null) action = ACTION_START;

        switch (action) {
            case ACTION_START:
                startVoice(
                    intent.getStringExtra(EXTRA_ROOM_ID),
                    intent.getStringExtra(EXTRA_USER_ID),
                    intent.getStringExtra(EXTRA_USER_NAME),
                    intent.getStringExtra(EXTRA_BASE_URL),
                    intent.getStringExtra(EXTRA_TOKEN)
                );
                break;
            case ACTION_STOP:
                stopVoice();
                break;
            case ACTION_MUTE:
                toggleMute();
                break;
        }
        return START_STICKY;
    }

    private void startVoice(String roomId, String userId, String userName,
                            String baseUrl, String token) {
        if (roomId == null) { stopSelf(); return; }

        // Persist for START_STICKY restart
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString("room_id",   roomId)
            .putString("user_id",   userId)
            .putString("user_name", userName)
            .putString("base_url",  baseUrl)
            .putString("token",     token)
            .apply();

        currentRoom = roomId;

        // Foreground notification FIRST
        startForeground(NOTIFICATION_ID, buildNotification(roomId, false));

        // CPU wake lock
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoxLink::Voice");
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L);

        // WiFi lock
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiLock == null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VoxLink::WiFi");
        }
        if (!wifiLock.isHeld()) wifiLock.acquire();

        // Init WebRTC engine
        if (webrtcEngine != null) {
            webrtcEngine.dispose();
            webrtcEngine = null;
        }
        webrtcEngine = new WebRTCEngine();
        webrtcEngine.init(getApplicationContext());
        webrtcEngine.setMuted(isMuted);

        startServiceHeartbeat(baseUrl, roomId, userId, token);
        showOverlayDot();

        Log.d(TAG, "Voice started for room " + roomId);
    }

    private void startServiceHeartbeat(String baseUrl, String roomId, String userId, String token) {
        if (heartbeatScheduler != null) heartbeatScheduler.shutdownNow();
        if (baseUrl == null || token == null) return;

        heartbeatFailCount.set(0);
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler.scheduleWithFixedDelay(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("room_id", roomId);
                body.put("user_id", userId);
                body.put("token",   token);
                body.put("muted",   isMuted);

                HttpURLConnection c = (HttpURLConnection)
                        new URL(baseUrl + "/ping").openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setDoOutput(true);
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = c.getOutputStream()) { os.write(data); }
                int code = c.getResponseCode();
                c.disconnect();

                if (code == 200) {
                    heartbeatFailCount.set(0);
                } else {
                    Log.w(TAG, "Heartbeat server returned " + code);
                    heartbeatFailCount.incrementAndGet();
                }
            } catch (Exception e) {
                int fails = heartbeatFailCount.incrementAndGet();
                Log.w(TAG, "Heartbeat fail #" + fails + ": " + e.getMessage());
            }
        }, 3, 8, TimeUnit.SECONDS);
    }

    private void stopVoice() {
        releaseAll();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply();
        stopForeground(true);
        stopSelf();
    }

    private void releaseAll() {
        removeOverlayDot();
        if (webrtcEngine != null) { webrtcEngine.dispose(); webrtcEngine = null; }
        if (heartbeatScheduler != null) { heartbeatScheduler.shutdownNow(); heartbeatScheduler = null; }
        if (wakeLock != null && wakeLock.isHeld()) { try { wakeLock.release(); } catch (Exception ignored) {} }
        if (wifiLock != null && wifiLock.isHeld()) { try { wifiLock.release(); } catch (Exception ignored) {} }
        wakeLock = null;
        wifiLock = null;
    }

    private void toggleMute() {
        isMuted = !isMuted;
        if (webrtcEngine != null) webrtcEngine.setMuted(isMuted);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(currentRoom, isMuted));
        updateOverlayColor();
    }

    public void setMuted(boolean muted) {
        isMuted = muted;
        if (webrtcEngine != null) webrtcEngine.setMuted(muted);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(currentRoom, isMuted));
        updateOverlayColor();
    }

    public boolean isMuted() { return isMuted; }
    public WebRTCEngine getWebRTCEngine() { return webrtcEngine; }
    public boolean isConnected() { return heartbeatFailCount.get() < 5; }

    private Notification buildNotification(String roomId, boolean muted) {
        Intent ri = new Intent(this, RoomActivity.class);
        ri.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, ri, piFlags);

        Intent muteI = new Intent(this, VoiceService.class).setAction(ACTION_MUTE);
        PendingIntent mutePi = PendingIntent.getService(this, 1, muteI, piFlags);

        Intent stopI = new Intent(this, VoiceService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stopI, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle("VoxLink · " + roomId)
            .setContentText(muted ? "Muted" : "Voice active")
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(new NotificationCompat.Action.Builder(
                android.R.drawable.presence_audio_busy,
                muted ? "Unmute" : "Mute",
                mutePi).build())
            .addAction(new NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Leave",
                stopPi).build())
            .build();
    }

    // ── Floating overlay dot ──────────────────────────────────────────────

    private void showOverlayDot() {
        if (overlayAdded) return;
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission — skipping dot");
            return;
        }

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        float density = getResources().getDisplayMetrics().density;
        int sizePx = (int) (12 * density);
        int xPx    = (int) (8 * density);
        int yPx    = (int) (80 * density);

        dotDrawable = new GradientDrawable();
        dotDrawable.setShape(GradientDrawable.OVAL);
        dotDrawable.setColor(isMuted ? 0xFFFFFFFF : 0xFF4ADE80);
        dotDrawable.setSize(sizePx, sizePx);

        overlayDot = new View(this);
        overlayDot.setBackground(dotDrawable);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = xPx;
        params.y = yPx;

        windowManager.addView(overlayDot, params);
        overlayAdded = true;
    }

    private void updateOverlayColor() {
        if (overlayAdded && dotDrawable != null) {
            dotDrawable.setColor(isMuted ? 0xFFFFFFFF : 0xFF4ADE80);
        }
    }

    private void removeOverlayDot() {
        if (overlayAdded && overlayDot != null && windowManager != null) {
            try { windowManager.removeView(overlayDot); } catch (Exception ignored) {}
            overlayAdded = false;
            overlayDot = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Voice Call", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Active voice room");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "Task removed — voice continues in background");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseAll();
    }
}
