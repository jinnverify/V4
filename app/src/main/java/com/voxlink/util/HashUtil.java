package com.voxlink.util;

/**
 * VoxLink Custom Cipher — Android side
 * ──────────────────────────────────────
 * MUST stay in sync with server/VoxCipher.js
 *
 * Algorithm (identical to JS):
 *  1. payload  = "server|roomId|password"
 *  2. XOR each byte with position-derived key (SECRET_SEED)
 *  3. Prepend payload-length byte
 *  4. Pack 8-bit bytes into 5-bit groups → map to ALPHA (32 chars)
 *  5. Append 5-char checksum
 *
 * No external libraries — pure Java.
 */
public class HashUtil {

    // ── Must match VoxCipher.js ──────────────────────────────────────────────
    private static final long   SECRET_SEED = 0x56584C4BL;
    private static final String ALPHA       = "VXLK9B2P7NM4GRJQ8CZYFW3H5T6ASD1E"; // 32 chars

    public static class RoomInfo {
        public final String server;
        public final String roomId;
        public final String password;
        public RoomInfo(String server, String roomId, String password) {
            this.server   = server;
            this.roomId   = roomId;
            this.password = password != null ? password : "";
        }
    }

    // ── Key derivation (mirrors JS xorKey) ───────────────────────────────────
    private static int xorKey(int i) {
        long k = (SECRET_SEED ^ imul(i, 0x9E3779B9L)) & 0xFFFFFFFFL;
        k = (k ^ (k >>> 16)) & 0xFFFFFFFFL;
        k = imul(k, 0x45D9F3BL) & 0xFFFFFFFFL;
        k = (k ^ (k >>> 16)) & 0xFFFFFFFFL;
        return (int)(k & 0xFF);
    }

    /** Unsigned 32-bit multiply — mirrors JS Math.imul */
    private static long imul(long a, long b) {
        return (a * b) & 0xFFFFFFFFL;
    }

    // ── 5-char checksum ──────────────────────────────────────────────────────
    private static String checksum(int[] raw) {
        long cs = SECRET_SEED;
        for (int b : raw)
            cs = (((cs << 5) | (cs >>> 27)) ^ b) & 0xFFFFFFFFL;
        return "" + ALPHA.charAt((int)((cs >>> 27) & 31))
                  + ALPHA.charAt((int)((cs >>> 22) & 31))
                  + ALPHA.charAt((int)((cs >>> 17) & 31))
                  + ALPHA.charAt((int)((cs >>> 12) & 31))
                  + ALPHA.charAt((int)((cs >>>  7) & 31));
    }

    // ── Encode ───────────────────────────────────────────────────────────────
    public static String encode(String server, String roomId, String password) {
        String payload  = server + "|" + roomId + "|" + (password != null ? password : "");
        byte[] payBytes = payload.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (payBytes.length > 255) return null;

        int[] raw = new int[payBytes.length];
        for (int i = 0; i < payBytes.length; i++) raw[i] = payBytes[i] & 0xFF;

        int[] xored = new int[raw.length];
        for (int i = 0; i < raw.length; i++) xored[i] = (raw[i] ^ xorKey(i)) & 0xFF;

        // data = [length, xored...]
        int[] data = new int[1 + xored.length];
        data[0] = raw.length;
        System.arraycopy(xored, 0, data, 1, xored.length);

        // Pack to 5-bit groups
        StringBuilder bits = new StringBuilder();
        for (int b : data)
            for (int bit = 7; bit >= 0; bit--)
                bits.append((b >> bit) & 1);
        while (bits.length() % 5 != 0) bits.append('0');

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < bits.length(); i += 5)
            result.append(ALPHA.charAt(Integer.parseInt(bits.substring(i, i + 5), 2)));

        result.append(checksum(raw));
        return result.toString();
    }

    // ── Decode ───────────────────────────────────────────────────────────────
    public static RoomInfo decode(String hash) {
        if (hash == null || hash.trim().length() < 8) return null;
        try {
            String h     = hash.trim().toUpperCase();
            String check = h.substring(h.length() - 5);
            String body  = h.substring(0, h.length() - 5);

            StringBuilder bits = new StringBuilder();
            for (char c : body.toCharArray()) {
                int idx = ALPHA.indexOf(c);
                if (idx < 0) return null;
                String b5 = Integer.toBinaryString(idx);
                while (b5.length() < 5) b5 = "0" + b5;
                bits.append(b5);
            }

            int numBytes = bits.length() / 8;
            int[] allBytes = new int[numBytes];
            for (int i = 0; i < numBytes; i++)
                allBytes[i] = Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);

            if (allBytes.length < 2) return null;
            int payloadLen = allBytes[0];
            if (allBytes.length < 1 + payloadLen) return null;

            int[] xored = new int[payloadLen];
            System.arraycopy(allBytes, 1, xored, 0, payloadLen);

            int[] raw = new int[payloadLen];
            for (int i = 0; i < payloadLen; i++)
                raw[i] = (xored[i] ^ xorKey(i)) & 0xFF;

            if (!checksum(raw).equals(check)) return null;

            byte[] payBytes = new byte[raw.length];
            for (int i = 0; i < raw.length; i++) payBytes[i] = (byte) raw[i];
            String payload = new String(payBytes, java.nio.charset.StandardCharsets.US_ASCII);

            String[] parts = payload.split("\\|", 3);
            if (parts.length < 2) return null;

            String server   = parts[0].trim();
            String roomId   = parts[1].trim();
            String password = parts.length > 2 ? parts[2].trim() : "";
            if (server.isEmpty() || roomId.isEmpty()) return null;

            return new RoomInfo(server, roomId, password);

        } catch (Exception e) {
            return null;
        }
    }
}
