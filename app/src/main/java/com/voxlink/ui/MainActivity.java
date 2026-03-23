package com.voxlink.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.voxlink.R;
import com.voxlink.util.HashUtil;

public class MainActivity extends AppCompatActivity {

    private EditText etHash;
    private EditText etUsername;
    private Button btnJoin;
    private TextView tvHashStatus;

    private ActivityResultLauncher<String> permLauncher;
    private Runnable pendingAction;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getPreferences(MODE_PRIVATE);
        setupPermLauncher();   // must be before initViews/handleDeepLink
        initViews();
        handleDeepLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleDeepLink(intent);
    }

    private void initViews() {
        etHash       = findViewById(R.id.et_hash);
        etUsername   = findViewById(R.id.et_username);
        btnJoin      = findViewById(R.id.btn_join);
        tvHashStatus = findViewById(R.id.tv_hash_status);

        etUsername.setText(prefs.getString("username", ""));

        etHash.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                updateHashStatus(s.toString());
            }
        });

        btnJoin.setOnClickListener(v -> onJoinClicked());
    }

    private void updateHashStatus(String raw) {
        String hash = raw.trim();
        if (hash.isEmpty()) {
            tvHashStatus.setVisibility(View.GONE);
            etHash.setTransformationMethod(null);
            return;
        }
        HashUtil.RoomInfo info = HashUtil.decode(hash);
        tvHashStatus.setVisibility(View.VISIBLE);
        if (info != null) {
            tvHashStatus.setText("✓  Valid hash — ready to join");
            tvHashStatus.setTextColor(0xFF4ADE80);
            // Mask the raw hash string — don't use setInputType (triggers TextWatcher loop)
            etHash.setTransformationMethod(PasswordTransformationMethod.getInstance());
        } else {
            tvHashStatus.setText("✗  Invalid hash");
            tvHashStatus.setTextColor(0xFFEF4444);
            etHash.setTransformationMethod(null);
        }
    }

    private void onJoinClicked() {
        String hash     = etHash.getText().toString().trim();
        String username = etUsername.getText().toString().trim();

        if (TextUtils.isEmpty(hash)) {
            etHash.setError("Paste the room hash from dashboard");
            etHash.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(username)) {
            etUsername.setError("Enter your name");
            etUsername.requestFocus();
            return;
        }

        HashUtil.RoomInfo info = HashUtil.decode(hash);
        if (info == null) {
            etHash.setError("Invalid hash — copy again from dashboard");
            etHash.requestFocus();
            return;
        }

        prefs.edit().putString("username", username).apply();

        final String server   = info.server;
        final String roomId   = info.roomId;
        final String password = info.password;

        pendingAction = () -> openRoom(server, roomId, password, username);
        requestMicPermission();
    }

    private void openRoom(String server, String roomId, String password, String username) {
        Intent i = new Intent(this, RoomActivity.class);
        i.putExtra(RoomActivity.EXTRA_SERVER,   server);
        i.putExtra(RoomActivity.EXTRA_ROOM_ID,  roomId);
        i.putExtra(RoomActivity.EXTRA_PASSWORD, password);
        i.putExtra(RoomActivity.EXTRA_USERNAME, username);
        startActivity(i);
    }

    // Deep link: voxlink://join?s=HOST&r=ROOM&p=PASS
    private void handleDeepLink(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;

        String server   = data.getQueryParameter("s");
        String roomId   = data.getQueryParameter("r");
        String password = data.getQueryParameter("p");

        if (!TextUtils.isEmpty(server) && !TextUtils.isEmpty(roomId)) {
            String hash = HashUtil.encode(server, roomId, password != null ? password : "");
            if (hash != null) etHash.setText(hash);

            String savedName = prefs.getString("username", "");
            if (!TextUtils.isEmpty(savedName)) {
                final String p = password != null ? password : "";
                pendingAction = () -> openRoom(server, roomId, p, savedName);
                requestMicPermission();
            } else {
                Toast.makeText(this, "Room detected! Enter your name to join.", Toast.LENGTH_SHORT).show();
                etUsername.requestFocus();
            }
        }
    }

    private void requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            if (pendingAction != null) { pendingAction.run(); pendingAction = null; }
        } else {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void setupPermLauncher() {
        permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                if (granted && pendingAction != null) pendingAction.run();
                else if (!granted)
                    Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show();
                pendingAction = null;
            });
    }
}
