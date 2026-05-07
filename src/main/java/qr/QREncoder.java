package qr;

import java.util.ArrayList;
import java.util.List;
import qr.QRVersion.ECLevel;
import qr.QRVersion.BlockInfo;

/**
 * Encodes a string into QR code data codewords (before matrix placement).
 * Supports Byte mode only (UTF-8). For numeric/alphanumeric, byte mode is a
 * valid (slightly less efficient) fallback per the spec.
 */
public class QREncoder {

    public static final class EncodedData {
        public final int version;
        public final ECLevel ecLevel;
        public final int[] codewords; // final interleaved data + EC codewords

        EncodedData(int version, ECLevel ecLevel, int[] codewords) {
            this.version = version;
            this.ecLevel = ecLevel;
            this.codewords = codewords;
        }
    }

    public static EncodedData encode(String text, ECLevel ec) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int version = QRVersion.minVersion(bytes.length + 3, ec); // +3 for mode+length indicator overhead estimate
        // Recalculate precisely
        while (true) {
            int cap = QRVersion.dataCapacity(version, ec);
            int needed = headerBits(version, bytes.length) / 8 + (headerBits(version, bytes.length) % 8 != 0 ? 1 : 0);
            if (cap >= needed) break;
            version++;
            if (version > 40) throw new IllegalArgumentException("Data too large");
        }

        int totalDataBytes = QRVersion.dataCapacity(version, ec);
        BitStream bs = new BitStream();

        // Mode indicator: byte mode = 0100
        bs.append(0b0100, 4);
        // Character count indicator
        int cciBits = version <= 9 ? 8 : 16;
        bs.append(bytes.length, cciBits);
        // Data bytes
        for (byte b : bytes) bs.append(b & 0xFF, 8);
        // Terminator (up to 4 zero bits)
        int termLen = Math.min(4, totalDataBytes * 8 - bs.length());
        if (termLen > 0) bs.append(0, termLen);
        // Pad to byte boundary
        while (bs.length() % 8 != 0) bs.append(0, 1);
        // Pad codewords
        int[] padBytes = {0xEC, 0x11};
        int pi = 0;
        while (bs.length() < totalDataBytes * 8) {
            bs.append(padBytes[pi++ % 2], 8);
        }

        int[] data = bs.toByteArray();
        return new EncodedData(version, ec, interleaveWithEC(data, version, ec));
    }

    private static int headerBits(int version, int dataLen) {
        int cciBits = version <= 9 ? 8 : 16;
        return 4 + cciBits + dataLen * 8;
    }

    private static int[] interleaveWithEC(int[] data, int version, ECLevel ec) {
        BlockInfo bi = QRVersion.blockInfo(version, ec);
        int totalBlocks = bi.blocksInGroup1 + bi.blocksInGroup2;
        int[][] dataBlocks = new int[totalBlocks][];
        int[][] ecBlocks = new int[totalBlocks][];

        int idx = 0;
        for (int b = 0; b < totalBlocks; b++) {
            int size = b < bi.blocksInGroup1 ? bi.dataCodewordsInGroup1 : bi.dataCodewordsInGroup2;
            dataBlocks[b] = new int[size];
            for (int i = 0; i < size; i++) dataBlocks[b][i] = data[idx++];
            ecBlocks[b] = ReedSolomon.computeECBytes(dataBlocks[b], bi.ecCodewordsPerBlock);
        }

        // Interleave data
        List<Integer> result = new ArrayList<>();
        int maxData = bi.blocksInGroup2 > 0 ? bi.dataCodewordsInGroup2 : bi.dataCodewordsInGroup1;
        for (int col = 0; col < maxData; col++)
            for (int b = 0; b < totalBlocks; b++)
                if (col < dataBlocks[b].length) result.add(dataBlocks[b][col]);

        // Interleave EC
        for (int col = 0; col < bi.ecCodewordsPerBlock; col++)
            for (int b = 0; b < totalBlocks; b++)
                result.add(ecBlocks[b][col]);

        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    // Simple bit stream builder
    static class BitStream {
        private final List<Integer> bits = new ArrayList<>();

        void append(int value, int length) {
            for (int i = length - 1; i >= 0; i--)
                bits.add((value >> i) & 1);
        }

        int length() { return bits.size(); }

        int[] toByteArray() {
            int[] bytes = new int[bits.size() / 8];
            for (int i = 0; i < bytes.length; i++) {
                for (int j = 0; j < 8; j++)
                    bytes[i] = (bytes[i] << 1) | bits.get(i * 8 + j);
            }
            return bytes;
        }
    }
}
