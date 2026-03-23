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
import java.nio.charset.StandardCharsets;
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
    private static final int    FRAME_BYTES = 640; // 20ms @ 16kHz PCM16 mono
    private static final int    HEADER_SIZE = 24;  // 8 room + 8 user + 4 udpKey + 4 seq
    private static final int    FRAME_MS    = 20;

    // Jitter buffer: larger = more latency but smoother audio
    private static final int    JITTER_FRAMES     = 6;   // 120ms buffer
    private static final int    STALE_SENDER_MS   = 5000; // clean sender after 5s silence

    private AudioRecord    audioRecord;
    private AudioTrack     audioTrack;
    private DatagramSocket udpSocket;

    private final AtomicBoolean running  = new AtomicBoolean(false);
    private final AtomicBoolean muted    = new AtomicBoolean(false);
    private final AtomicBoolean speaking = new AtomicBoolean(false);

    private final String serverHost;
    private final int    serverPort;
    private final String userId;
    private final String roomId;
    private final byte[] udpKeyBytes;

    private ExecutorService executor;
    private InetAddress serverAddr;

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
            // Resolve server address once
            serverAddr = InetAddress.getByName(serverHost);

            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING);
            if (minBuf <= 0) { Log.e(TAG, "Bad mic buffer size"); return false; }

            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_IN, ENCODING,
                Math.max(minBuf, FRAME_BYTES * 4));

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
                .setBufferSizeInBytes(Math.max(outBuf, FRAME_BYTES * 6))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

            udpSocket = new DatagramSocket();
            udpSocket.setSoTimeout(FRAME_MS); // short timeout = tight recv loop
            udpSocket.setReceiveBufferSize(128 * 1024);
            udpSocket.setSendBufferSize(64 * 1024);

            boolean ok = audioRecord.getState() == AudioRecord.STATE_INITIALIZED
                      && audioTrack.getState()  == AudioTrack.STATE_INITIALIZED;

            Log.d(TAG, "init: mic=" + audioRecord.getState()
                    + " spk=" + audioTrack.getState()
                    + " udp=" + udpSocket.getLocalPort()
                    + " → " + serverHost + ":" + serverPort);
            return ok;

        } catch (Exception e) {
            Log.e(TAG, "init failed: " + e.getMessage(), e);
            return false;
        }
    }

    public void start() {
        if (running.get()) return;
        running.set(true);
        audioRecord.startRecording();
        audioTrack.play();
        executor = Executors.newFixedThreadPool(3);
        executor.execute(this::sendLoop);
        executor.execute(this::recvLoop);
        executor.execute(this::mixLoop);
        Log.d(TAG, "Started: room=" + roomId + " user=" + userId
                + " key=" + bytesToHex(udpKeyBytes));
    }

    // ── SEND: mic → UDP ─────────────────────────────────────────────────────

    private void sendLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] pcm = new byte[FRAME_BYTES];
        byte[] pkt = new byte[HEADER_SIZE + FRAME_BYTES];
        long seq = 0;

        // Write static header parts once
        writeHeader(pkt);

        try {
            while (running.get()) {
                int read = audioRecord.read(pcm, 0, FRAME_BYTES);
                if (read < 0) {
                    Log.w(TAG, "mic read error: " + read);
                    Thread.sleep(FRAME_MS);
                    continue;
                }
                if (read == 0) continue;

                boolean voice = isVoiceActive(pcm, read);
                speaking.set(voice);

                if (muted.get() || !voice) continue;

                // Write sequence number
                int s = (int)(seq & 0xFFFFFFFFL);
                pkt[20] = (byte)(s >> 24);
                pkt[21] = (byte)(s >> 16);
                pkt[22] = (byte)(s >> 8);
                pkt[23] = (byte) s;
                seq++;

                System.arraycopy(pcm, 0, pkt, HEADER_SIZE, read);

                if (udpSocket != null && !udpSocket.isClosed()) {
                    udpSocket.send(new DatagramPacket(pkt, HEADER_SIZE + read, serverAddr, serverPort));
                }
            }
        } catch (Exception e) {
            if (running.get()) Log.e(TAG, "sendLoop crash: " + e.getMessage(), e);
        }
        Log.d(TAG, "sendLoop ended");
    }

    // ── RECV: UDP → jitter buffers ──────────────────────────────────────────

    private void recvLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buf = new byte[2048];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        long recvCount = 0;

        while (running.get()) {
            try {
                if (udpSocket == null || udpSocket.isClosed()) break;
                pkt.setLength(buf.length);
                udpSocket.receive(pkt);

                int len = pkt.getLength();
                if (len <= HEADER_SIZE) continue;

                // Extract sender short ID from header bytes 8-15
                String senderId = new String(buf, 8, 8, StandardCharsets.UTF_8)
                        .replace("\0", "").trim();
                if (senderId.isEmpty() || senderId.equals(userId)) continue;

                int dataLen = len - HEADER_SIZE;
                byte[] frame = new byte[dataLen];
                System.arraycopy(buf, HEADER_SIZE, frame, 0, dataLen);

                SenderBuffer sb = senderBuffers.computeIfAbsent(senderId, k -> new SenderBuffer());
                sb.addFrame(frame);
                recvCount++;

                if (recvCount % 100 == 1) {
                    Log.d(TAG, "recv #" + recvCount + " from=" + senderId + " len=" + dataLen
                            + " senders=" + senderBuffers.size());
                }

            } catch (java.net.SocketTimeoutException ignored) {
                // Expected — no data in this FRAME_MS window
            } catch (Exception e) {
                if (running.get()) Log.w(TAG, "recvLoop: " + e.getMessage());
            }
        }
        Log.d(TAG, "recvLoop ended, total=" + recvCount);
    }

    // ── MIXER: jitter buffers → speaker ─────────────────────────────────────

    private void mixLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] mixBuf = new byte[FRAME_BYTES];
        byte[] silence = new byte[FRAME_BYTES];
        int samplesPerFrame = FRAME_BYTES / 2;
        long lastCleanup = System.currentTimeMillis();

        while (running.get()) {
            long frameStart = System.nanoTime();
            try {
                boolean hasAudio = false;
                int[] mixed = new int[samplesPerFrame];

                for (Map.Entry<String, SenderBuffer> entry : senderBuffers.entrySet()) {
                    byte[] frame = entry.getValue().pollFrame();
                    if (frame == null) continue;
                    hasAudio = true;

                    int samples = Math.min(frame.length / 2, samplesPerFrame);
                    for (int i = 0; i < samples; i++) {
                        short sv = (short)((frame[i * 2 + 1] << 8) | (frame[i * 2] & 0xFF));
                        mixed[i] += sv;
                    }
                }

                if (hasAudio) {
                    for (int i = 0; i < samplesPerFrame; i++) {
                        int v = Math.max(-32768, Math.min(32767, mixed[i]));
                        mixBuf[i * 2]     = (byte)(v & 0xFF);
                        mixBuf[i * 2 + 1] = (byte)((v >> 8) & 0xFF);
                    }
                    audioTrack.write(mixBuf, 0, FRAME_BYTES);
                } else {
                    // Write silence to keep AudioTrack alive and prevent underrun
                    audioTrack.write(silence, 0, FRAME_BYTES);
                }

                // Clean stale senders periodically (every 1s)
                long now = System.currentTimeMillis();
                if (now - lastCleanup > 1000) {
                    lastCleanup = now;
                    senderBuffers.entrySet().removeIf(
                            e -> now - e.getValue().lastFrameTime > STALE_SENDER_MS);
                }

                // Pace to ~20ms per frame
                long elapsed = (System.nanoTime() - frameStart) / 1_000_000;
                long sleepMs = FRAME_MS - elapsed;
                if (sleepMs > 1) Thread.sleep(sleepMs);

            } catch (Exception e) {
                if (running.get()) Log.w(TAG, "mixLoop: " + e.getMessage());
            }
        }
        Log.d(TAG, "mixLoop ended");
    }

    // ── VAD ─────────────────────────────────────────────────────────────────

    private boolean isVoiceActive(byte[] buf, int len) {
        long sum = 0;
        int samples = len / 2;
        for (int i = 0; i + 1 < len; i += 2) {
            short s = (short)((buf[i + 1] << 8) | (buf[i] & 0xFF));
            sum += Math.abs(s);
        }
        return samples > 0 && (sum / samples) > 300;
    }

    // ── Header ──────────────────────────────────────────────────────────────

    private void writeHeader(byte[] pkt) {
        byte[] rb = roomId.getBytes(StandardCharsets.UTF_8);
        byte[] ub = userId.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 8; i++) pkt[i]     = (i < rb.length) ? rb[i] : 0;
        for (int i = 0; i < 8; i++) pkt[8 + i] = (i < ub.length) ? ub[i] : 0;
        System.arraycopy(udpKeyBytes, 0, pkt, 16, 4);
    }

    // ── Public API ──────────────────────────────────────────────────────────

    public void setMuted(boolean m) { muted.set(m); }
    public boolean isMuted()        { return muted.get(); }
    public boolean isSpeaking()     { return speaking.get(); }

    public void stop() {
        running.set(false);
        senderBuffers.clear();
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); } } catch (Exception ignored) {}
        try { if (audioTrack  != null) { audioTrack.stop();  audioTrack.release();  } } catch (Exception ignored) {}
        try { if (udpSocket   != null && !udpSocket.isClosed()) udpSocket.close(); } catch (Exception ignored) {}
        if (executor != null) executor.shutdownNow();
        audioRecord = null;
        audioTrack  = null;
        udpSocket   = null;
        executor    = null;
        Log.d(TAG, "AudioEngine stopped");
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02x", v & 0xFF));
        return sb.toString();
    }

    // ── Jitter Buffer ───────────────────────────────────────────────────────

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
