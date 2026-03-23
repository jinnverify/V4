package com.voxlink.audio;

import android.content.Context;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebRTCEngine {

    private static final String TAG = "VoxLink.WebRTC";

    public interface SignalCallback {
        void sendOffer(String targetUserId, String sdp);
        void sendAnswer(String targetUserId, String sdp);
        void sendCandidate(String targetUserId, String candidateJson);
    }

    private PeerConnectionFactory factory;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private final Map<String, PeerConnection> peers = new ConcurrentHashMap<>();
    private SignalCallback signalCallback;
    private boolean muted = false;
    private boolean initialized = false;

    private static final List<PeerConnection.IceServer> ICE_SERVERS;
    static {
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        servers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        ICE_SERVERS = Collections.unmodifiableList(servers);
    }

    public void init(Context context) {
        PeerConnectionFactory.InitializationOptions initOptions =
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .setFieldTrials("")
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory();

        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("echoCancellation", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("noiseSuppression", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("autoGainControl", "true"));

        audioSource = factory.createAudioSource(audioConstraints);
        localAudioTrack = factory.createAudioTrack("VoxLinkAudio", audioSource);
        localAudioTrack.setEnabled(!muted);
        initialized = true;
        Log.d(TAG, "WebRTC initialized");
    }

    public void setSignalCallback(SignalCallback callback) {
        this.signalCallback = callback;
    }

    public void addPeer(String userId, boolean createOffer) {
        if (!initialized || factory == null) return;
        if (peers.containsKey(userId)) {
            Log.d(TAG, "Peer already exists: " + userId);
            return;
        }

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(ICE_SERVERS);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        PeerConnection pc = factory.createPeerConnection(config, new PeerConnectionObserver(userId));
        if (pc == null) {
            Log.e(TAG, "Failed to create PeerConnection for " + userId);
            return;
        }

        pc.addTrack(localAudioTrack, Collections.singletonList("VoxLinkStream"));
        peers.put(userId, pc);
        Log.d(TAG, "Added peer: " + userId + " createOffer=" + createOffer);

        if (createOffer) {
            MediaConstraints constraints = new MediaConstraints();
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
            pc.createOffer(new SdpAdapter("createOffer:" + userId) {
                @Override
                public void onCreateSuccess(SessionDescription sdp) {
                    pc.setLocalDescription(new SdpAdapter("setLocal:" + userId), sdp);
                    if (signalCallback != null) {
                        signalCallback.sendOffer(userId, sdp.description);
                    }
                }
            }, constraints);
        }
    }

    public void handleOffer(String fromUserId, String sdp) {
        PeerConnection pc = peers.get(fromUserId);
        if (pc == null) {
            addPeer(fromUserId, false);
            pc = peers.get(fromUserId);
        }
        if (pc == null) return;

        final PeerConnection fpc = pc;
        SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, sdp);
        fpc.setRemoteDescription(new SdpAdapter("setRemote:" + fromUserId), offer);

        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        fpc.createAnswer(new SdpAdapter("createAnswer:" + fromUserId) {
            @Override
            public void onCreateSuccess(SessionDescription answer) {
                fpc.setLocalDescription(new SdpAdapter("setLocal:" + fromUserId), answer);
                if (signalCallback != null) {
                    signalCallback.sendAnswer(fromUserId, answer.description);
                }
            }
        }, constraints);
    }

    public void handleAnswer(String fromUserId, String sdp) {
        PeerConnection pc = peers.get(fromUserId);
        if (pc == null) return;
        SessionDescription answer = new SessionDescription(SessionDescription.Type.ANSWER, sdp);
        pc.setRemoteDescription(new SdpAdapter("setRemote:" + fromUserId), answer);
    }

    public void handleCandidate(String fromUserId, String sdpMid, int sdpMLineIndex, String candidate) {
        PeerConnection pc = peers.get(fromUserId);
        if (pc == null) return;
        IceCandidate ice = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
        pc.addIceCandidate(ice);
    }

    public void removePeer(String userId) {
        PeerConnection pc = peers.remove(userId);
        if (pc != null) {
            pc.close();
            Log.d(TAG, "Removed peer: " + userId);
        }
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(!muted);
        }
    }

    public boolean isMuted() {
        return muted;
    }

    public void dispose() {
        for (Map.Entry<String, PeerConnection> entry : peers.entrySet()) {
            entry.getValue().close();
        }
        peers.clear();

        if (localAudioTrack != null) {
            localAudioTrack.dispose();
            localAudioTrack = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        initialized = false;
        Log.d(TAG, "WebRTC disposed");
    }

    // ── PeerConnection Observer ──────────────────────────────────────────────

    private class PeerConnectionObserver implements PeerConnection.Observer {
        private final String peerId;

        PeerConnectionObserver(String peerId) {
            this.peerId = peerId;
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            if (signalCallback != null) {
                String json = "{\"sdpMid\":\"" + candidate.sdpMid
                        + "\",\"sdpMLineIndex\":" + candidate.sdpMLineIndex
                        + ",\"candidate\":\"" + candidate.sdp.replace("\"", "\\\"") + "\"}";
                signalCallback.sendCandidate(peerId, json);
            }
        }

        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            Log.d(TAG, "ICE " + peerId + " → " + state);
        }
        @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
        @Override public void onIceConnectionReceivingChange(boolean b) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onAddStream(MediaStream stream) {}
        @Override public void onRemoveStream(MediaStream stream) {}
        @Override public void onDataChannel(DataChannel channel) {}
        @Override public void onRenegotiationNeeded() {}
        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
            Log.d(TAG, "Track received from " + peerId);
        }
    }

    // ── SDP Observer adapter ─────────────────────────────────────────────────

    private static class SdpAdapter implements SdpObserver {
        private final String label;

        SdpAdapter(String label) {
            this.label = label;
        }

        @Override public void onCreateSuccess(SessionDescription sdp) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {
            Log.e(TAG, "SDP " + label + " create failed: " + error);
        }
        @Override public void onSetFailure(String error) {
            Log.e(TAG, "SDP " + label + " set failed: " + error);
        }
    }
}
