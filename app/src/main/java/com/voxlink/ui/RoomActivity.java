package com.voxlink.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.voxlink.R;
import com.voxlink.audio.VoiceService;
import com.voxlink.model.Room;
import com.voxlink.network.SignalingClient;
import com.voxlink.util.HashUtil;

import java.util.ArrayList;
import java.util.List;

public class RoomActivity extends AppCompatActivity {

    public static final String EXTRA_SERVER   = "server";
    public static final String EXTRA_ROOM_ID  = "room_id";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_IS_HOST  = "is_host";

    private String server;
    private String roomId;
    private String password;
    private String username;
    private String resolvedUserId;

    private TextView tvRoomId;
    private TextView tvStatus;
    private TextView tvMemberCount;
    private Button btnMute;
    private Button btnLeave;
    private Button btnShare;
    private ListView lvMembers;

    private MemberAdapter memberAdapter;
    private final List<Room.Member> members = new ArrayList<>();

    private VoiceService voiceService;
    private boolean serviceBound = false;
    private boolean isMuted = false;

    private SignalingClient signalingClient;
    private String udpHost;
    private String udpKey;
    private String udpRoomId;
    private String udpUserId;
    private int udpPort = 45000;
    private String signalingToken;
    private String signalingBaseUrl;

    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            voiceService = ((VoiceService.VoiceBinder) b).getService();
            serviceBound = true;
            // Sync mute state from service in case activity was recreated
            isMuted = voiceService.isMuted();
            btnMute.setText(isMuted ? "Muted" : "Mute");
            btnMute.setAlpha(isMuted ? 0.55f : 1.0f);
        }
        @Override
        public void onServiceDisconnected(ComponentName n) {
            // Only called on crash/unexpected disconnect, NOT on unbind
            serviceBound = false;
            voiceService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room);

        server   = getIntent().getStringExtra(EXTRA_SERVER);
        roomId   = getIntent().getStringExtra(EXTRA_ROOM_ID);
        password = getIntent().getStringExtra(EXTRA_PASSWORD);
        username = getIntent().getStringExtra(EXTRA_USERNAME);

        initViews();
        registerBackHandler();
        connectSignaling();
    }

    private void initViews() {
        tvRoomId      = findViewById(R.id.tv_room_id);
        tvStatus      = findViewById(R.id.tv_status);
        tvMemberCount = findViewById(R.id.tv_member_count);
        btnMute       = findViewById(R.id.btn_mute);
        btnLeave      = findViewById(R.id.btn_leave);
        btnShare      = findViewById(R.id.btn_share);
        lvMembers     = findViewById(R.id.lv_members);

        tvRoomId.setText("Room: " + roomId);
        tvStatus.setText("Connecting...");
        tvMemberCount.setText("0 online");

        memberAdapter = new MemberAdapter(this, members);
        lvMembers.setAdapter(memberAdapter);

        btnMute.setOnClickListener(v -> toggleMute());
        btnLeave.setOnClickListener(v -> confirmLeave());
        btnShare.setOnClickListener(v -> shareRoom());
    }

    private void connectSignaling() {
        signalingClient = new SignalingClient(server);
        signalingClient.setListener(new SignalingClient.SignalingListener() {
            @Override
            public void onJoined(Room room, String uHost, int uPort, String uKey,
                                 String uRoomId, String uUserId, String userId) {
                udpHost   = uHost;
                udpPort   = uPort;
                udpKey    = uKey;
                udpRoomId = uRoomId;
                udpUserId = uUserId;
                resolvedUserId = userId;
                signalingToken   = signalingClient.getToken();
                signalingBaseUrl = signalingClient.getBaseUrl();
                tvStatus.setText("Connected");
                updateMembers(room.members);
                startVoiceService();
            }
            @Override
            public void onMembersUpdated(List<Room.Member> updated) {
                updateMembers(updated);
            }
            @Override
            public void onMemberLeft(String uid) {
                members.removeIf(m -> m.userId.equals(uid));
                memberAdapter.notifyDataSetChanged();
                tvMemberCount.setText(members.size() + " online");
            }
            @Override
            public void onError(String msg) {
                tvStatus.setText("Error: " + msg);
                Toast.makeText(RoomActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onDisconnected() {
                tvStatus.setText("Disconnected");
            }
        });
        signalingClient.join(roomId, password, username);
    }

    private void startVoiceService() {
        Intent intent = new Intent(this, VoiceService.class);
        intent.setAction(VoiceService.ACTION_START);
        intent.putExtra(VoiceService.EXTRA_ROOM_ID,      roomId);
        intent.putExtra(VoiceService.EXTRA_USER_ID,      resolvedUserId);
        intent.putExtra(VoiceService.EXTRA_USER_NAME,    username);
        intent.putExtra(VoiceService.EXTRA_SERVER_HOST,  udpHost);
        intent.putExtra(VoiceService.EXTRA_SERVER_PORT,  udpPort);
        intent.putExtra(VoiceService.EXTRA_UDP_KEY,      udpKey);
        intent.putExtra(VoiceService.EXTRA_UDP_ROOM_ID,  udpRoomId);
        intent.putExtra(VoiceService.EXTRA_UDP_USER_ID,  udpUserId);
        intent.putExtra(VoiceService.EXTRA_BASE_URL,     signalingBaseUrl);
        intent.putExtra(VoiceService.EXTRA_TOKEN,        signalingToken);
        startForegroundService(intent);
        bindService(new Intent(this, VoiceService.class), serviceConn, Context.BIND_AUTO_CREATE);
    }

    private void updateMembers(List<Room.Member> list) {
        members.clear();
        members.addAll(list);
        memberAdapter.notifyDataSetChanged();
        tvMemberCount.setText(members.size() + " online");
    }

    private void toggleMute() {
        isMuted = !isMuted;
        if (serviceBound && voiceService != null) voiceService.setMuted(isMuted);
        btnMute.setText(isMuted ? "Muted" : "Mute");
        btnMute.setAlpha(isMuted ? 0.55f : 1.0f);
    }

    private void shareRoom() {
        String hash = HashUtil.encode(server, roomId, password != null ? password : "");
        if (hash == null) {
            Toast.makeText(this, "Failed to generate room hash", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("VoxLink", hash));

        String shareText = "Join me on VoxLink!\n\nRoom Hash:\n" + hash
                + "\n\nPaste this hash in VoxLink app to join!";

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(share, "Share Room"));
    }

    private void confirmLeave() {
        new AlertDialog.Builder(this)
            .setTitle("Leave Room?")
            .setMessage("You will be disconnected from the voice call.")
            .setPositiveButton("Leave",  (d, w) -> leaveRoom())
            .setNegativeButton("Stay",   null)
            .show();
    }

    private void leaveRoom() {
        if (signalingClient != null) signalingClient.leave();
        Intent stop = new Intent(this, VoiceService.class);
        stop.setAction(VoiceService.ACTION_STOP);
        startService(stop);
        if (serviceBound) {
            unbindService(serviceConn);
            serviceBound = false;
        }
        // Go back to MainActivity so user can join another room
        Intent main = new Intent(this, MainActivity.class);
        main.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(main);
        finish();
    }

    private void registerBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Minimize instead of leaving — user switching to game
                moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Only unbind — do NOT stop service. Voice must keep running.
        if (serviceBound) {
            unbindService(serviceConn);
            serviceBound = false;
        }
        // Stop signaling polling/heartbeat — service has its own heartbeat
        // But do NOT call leave() — user is still in the room via VoiceService
        if (signalingClient != null) {
            signalingClient.stopPolling();
        }
    }
}
