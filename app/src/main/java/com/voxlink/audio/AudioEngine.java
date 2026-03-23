package com.voxlink.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioEngine {

    private static final String TAG         = "VoxLink.Audio";
    private static final int    SAMPLE_RATE = 16000;
    private static final int    CHANNEL_IN  = AudioFormat.CHANNEL_IN_MONO;
    private static final int    CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int    ENCODING    = AudioFormat.ENCODING_PCM_16BIT;
    private static final int    FRAME_BYTES = 640; // 20ms @ 16kHz PCM16
    private static final int    HEADER_SIZE = 24;  // 8 room + 8 user + 4 udpKey + 4 seq

    // Jitter buffer: hold up to 3 frames per sender before mixing
    private static final int    JITTER_FRAMES = 3;
    private static final int    JITTER_TIMEOUT_MS = 60; // max wait before playing what we have

    private AudioRecord    audioRecord;
    private AudioTrack     audioTrack;

    private DatagramSocket sendSocket;

    private final AtomicBoolean running  = new AtomicBoolean(false);
    private final AtomicBoolean muted    = new AtomicBoolean(false);
    private final AtomicBoolean speaking = new AtomicBoolean(false);

    private final String serverHost;
    private final int    serverPort;
    private final String userId;
    private final String roomId;
    private final byte[] udpKeyBytes; // 4-byte auth key

    private ExecutorService executor;

    // Per-sender jitter buffers: senderId → ring buffer of PCM frames
    private final ConcurrentHashMap<String, SenderBuffer> senderBuffers = new ConcurrentHashMap<>();

    public AudioEngine(String serverHost, int serverPort, String userId, String roomId, byte[] udpKey) {
        this.serverHost  = serverHost;
        this.serverPort  = serverPort;
        this.userId      = userId;
        this.roomId      = roomId;
        this.udpKeyBytes = (udpKey != null && udpKey.length == 4) ? udpKey : new byte[4];
    }

    public boolean init() {
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING);
            if (minBuf <= 0) { Log.e(TAG, "Bad buffer size"); return false; }

            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_IN, ENCODING,
                Math.max(minBuf, FRAME_BYTES * 2));

            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

            AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(ENCODING)
                .setChannelMask(CHANNEL_OUT)
                .build();

            int outBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING);
            audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(Math.max(outBuf, FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

            sendSocket = new DatagramSocket();
            sendSocket.setSoTimeout(500);
            sendSocket.setReceiveBufferSize(64 * 1024);
            sendSocket.setSendBufferSize(64 * 1024);

            return audioRecord.getState() == AudioRecord.STATE_INITIALIZED
                && audioTrack.getState()  == AudioTrack.STATE_INITIALIZED;

        } catch (Exception e) {
            Log.e(TAG, "init: " + e.getMessage());
            return false;
        }
    }

    public void start() {
        if (running.get()) return;
        running.set(true);
        audioRecord.startRecording();
        audioTrack.play();
        executor = Executors.newFixedThreadPool(3); // send + receive + mixer
        executor.execute(this::sendLoop);
        executor.execute(this::recvLoop);
        executor.execute(this::mixLoop);
        Log.d(TAG, "AudioEngine started → " + serverHost + ":" + serverPort
                + " room=" + roomId + " user=" + userId
                + " udpKey=" + bytesToHex(udpKeyBytes)
                + " localPort=" + sendSocket.getLocalPort());
    }

    private void sendLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] pcm = new byte[FRAME_BYTES];
        byte[] pkt = new byte[FRAME_BYTES + HEADER_SIZE];
        long   seq = 0;
        writeHeader(pkt);

        try {
            InetAddress addr = InetAddress.getByName(serverHost);
            while (running.get()) {
                int read = audioRecord.read(pcm, 0, pcm.length);

                if (read < 0) {
                    Log.w(TAG, "AudioRecord.read error: " + read);
                    Thread.sleep(20);
                    continue;
                }
                if (read == 0) continue;

                boolean voice = isVoiceActive(pcm, read);
                speaking.set(voice);

                if (muted.get() || !voice) continue;

                // Sequence number at bytes 20-23 (wraps at 32-bit boundary)
                int seqInt = (int)(seq & 0xFFFFFFFFL);
                pkt[20] = (byte)(seqInt >> 24);
                pkt[21] = (byte)(seqInt >> 16);
                pkt[22] = (byte)(seqInt >> 8);
                pkt[23] = (byte) seqInt;
                seq++;

                System.arraycopy(pcm, 0, pkt, HEADER_SIZE, read);

                if (sendSocket != null && !sendSocket.isClosed()) {
                    sendSocket.send(new DatagramPacket(pkt, HEADER_SIZE + read, addr, serverPort));
                    if (seq % 50 == 1) Log.v(TAG, "sent pkt seq=" + (seq-1) + " to " + serverHost + ":" + serverPort);
                }
            }
        } catch (Exception e) {
            if (running.get()) Log.e(TAG, "sendLoop: " + e.getMessage(), e);
        }
    }

    private void recvLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[]         buf = new byte[2048]; // larger buffer to avoid truncation
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);

        while (running.get()) {
            try {
                if (sendSocket == null || sendSocket.isClosed()) break;
                pkt.setLength(buf.length); // reset length before each receive
                sendSocket.receive(pkt);
                int len = pkt.getLength();
                if (len <= HEADER_SIZE) continue;

                // Extract sender ID from header bytes 8-15
                String senderId = new String(buf, 8, 8, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("\0", "").trim();
                if (senderId.isEmpty() || senderId.equals(userId)) continue;

                int dataLen = len - HEADER_SIZE;
                byte[] frame = new byte[dataLen];
                System.arraycopy(buf, HEADER_SIZE, frame, 0, dataLen);

                // Put into sender's jitter buffer
                SenderBuffer sb = senderBuffers.computeIfAbsent(senderId, k -> new SenderBuffer());
                sb.addFrame(frame);
                Log.v(TAG, "recv frame from " + senderId + " len=" + dataLen);

            } catch (java.net.SocketTimeoutException ignored) {
                // normal silence window — no data from server in timeout period
            } catch (Exception e) {
                if (running.get()) Log.w(TAG, "recvLoop: " + e.getMessage());
            }
        }
    }

    /**
     * Mixer thread: every 20ms, collect one frame from each sender buffer,
     * mix them (sum + clip to int16 range), write to AudioTrack.
     */
    private void mixLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] mixBuf = new byte[FRAME_BYTES];
        int samplesPerFrame = FRAME_BYTES / 2; // 16-bit samples

        while (running.get()) {
            try {
                boolean hasAudio = false;
                int[] mixed = new int[samplesPerFrame];

                // Collect one frame from each sender and mix
                for (Map.Entry<String, SenderBuffer> entry : senderBuffers.entrySet()) {
                    byte[] frame = entry.getValue().pollFrame();
                    if (frame == null) continue;
                    hasAudio = true;

                    int samples = Math.min(frame.length / 2, samplesPerFrame);
                    for (int i = 0; i < samples; i++) {
                        short s = (short)((frame[i * 2 + 1] << 8) | (frame[i * 2] & 0xFF));
                        mixed[i] += s;
                    }
                }

                // Clean up stale senders (no data for >2s)
                long now = System.currentTimeMillis();
                senderBuffers.entrySet().removeIf(e -> now - e.getValue().lastFrameTime > 2000);

                if (hasAudio) {
                    // Clip to int16 range and write
                    for (int i = 0; i < samplesPerFrame; i++) {
                        int v = Math.max(-32768, Math.min(32767, mixed[i]));
                        mixBuf[i * 2]     = (byte)(v & 0xFF);
                        mixBuf[i * 2 + 1] = (byte)((v >> 8) & 0xFF);
                    }
                    audioTrack.write(mixBuf, 0, FRAME_BYTES);
                } else {
                    // No audio from anyone — sleep for one frame period
                    Thread.sleep(20);
                }
            } catch (Exception e) {
                if (running.get()) Log.w(TAG, "mixLoop: " + e.getMessage());
            }
        }
    }

    /** Energy-based Voice Activity Detection — skip sending silence */
    private boolean isVoiceActive(byte[] buf, int len) {
        long sum = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            short s = (short)((buf[i + 1] << 8) | (buf[i] & 0xFF));
            sum += Math.abs(s);
        }
        return (sum / (len / 2)) > 300;
    }

    /**
     * Header: 8B roomId + 8B userId + 4B udpKey + 4B seq = 24 bytes
     */
    private void writeHeader(byte[] pkt) {
        byte[] rb = roomId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] ub = userId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < 8; i++) pkt[i]     = (i < rb.length) ? rb[i] : 0;
        for (int i = 0; i < 8; i++) pkt[8 + i] = (i < ub.length) ? ub[i] : 0;
        // UDP auth key at bytes 16-19
        System.arraycopy(udpKeyBytes, 0, pkt, 16, 4);
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02x", v & 0xFF));
        return sb.toString();
    }

    public void setMuted(boolean m) { muted.set(m); }
    public boolean isMuted()        { return muted.get(); }
    public boolean isSpeaking()     { return speaking.get(); }

    public void stop() {
        running.set(false);
        senderBuffers.clear();
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); audioRecord = null; } } catch (Exception ignored) {}
        try { if (audioTrack  != null) { audioTrack.stop();  audioTrack.release();  audioTrack  = null; } } catch (Exception ignored) {}
        try { if (sendSocket  != null && !sendSocket.isClosed()) { sendSocket.close(); } sendSocket = null; } catch (Exception ignored) {}
        if (executor != null) executor.shutdownNow();
        Log.d(TAG, "AudioEngine stopped");
    }

    /**
     * Per-sender ring buffer that holds up to JITTER_FRAMES PCM frames.
     * The mixer thread polls one frame at a time.
     */
    static class SenderBuffer {
        private final byte[][] frames = new byte[JITTER_FRAMES][];
        private int writePos = 0;
        private int readPos  = 0;
        private int count    = 0;
        volatile long lastFrameTime = System.currentTimeMillis();

        synchronized void addFrame(byte[] frame) {
            frames[writePos] = frame;
            writePos = (writePos + 1) % JITTER_FRAMES;
            if (count < JITTER_FRAMES) {
                count++;
            } else {
                // Buffer full — drop oldest
                readPos = (readPos + 1) % JITTER_FRAMES;
            }
            lastFrameTime = System.currentTimeMillis();
        }

        synchronized byte[] pollFrame() {
            if (count == 0) return null;
            byte[] f = frames[readPos];
            frames[readPos] = null;
            readPos = (readPos + 1) % JITTER_FRAMES;
            count--;
            return f;
        }
    }
}
