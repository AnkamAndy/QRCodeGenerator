package qr;

import qr.QRVersion.ECLevel;
import qr.QRVersion.BlockInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes a sampled QR module matrix back to the original text.
 *
 * Steps:
 *   1. Read format information (both copies, pick the valid one).
 *   2. Read version information for v7+.
 *   3. Extract data bits via two-column zigzag (skipping fixed modules).
 *   4. Un-apply mask.
 *   5. De-interleave data + EC codewords into blocks.
 *   6. Validate each block with Reed-Solomon (see QRValidator).
 *   7. Decode the data bit stream (byte mode, numeric, alphanumeric).
 */
public class QRDecoder {

    public static final class DecodeResult {
        public final String text;
        public final ECLevel ecLevel;
        public final int maskPattern;
        public final int version;
        public final boolean ecPassed; // all blocks had zero syndrome

        DecodeResult(String text, ECLevel ecLevel, int maskPattern, int version, boolean ecPassed) {
            this.text = text;
            this.ecLevel = ecLevel;
            this.maskPattern = maskPattern;
            this.version = version;
            this.ecPassed = ecPassed;
        }

        @Override
        public String toString() {
            return String.format("Text    : %s%nVersion : %d%nEC Level: %s%nMask    : %d%nEC OK   : %s",
                    text, version, ecLevel, maskPattern, ecPassed ? "PASS" : "FAIL (data may be corrupt)");
        }
    }

    public static DecodeResult decode(int[][] modules, int version) {
        int size = modules.length;

        // 1. Read format information
        int fmt = readFormatInfo(modules, size);
        if (fmt < 0) throw new IllegalStateException("Could not read valid format information");

        ECLevel ec = ecFromBits((fmt >> 13) & 0b11);
        int maskPattern = (fmt >> 10) & 0b111;

        // 2. Build fixed-module map (same logic as QRMatrix)
        boolean[][] fixed = buildFixedMap(version, size);

        // 3. Extract raw data bits (un-masked)
        int[] codewords = extractCodewords(modules, fixed, maskPattern, size);

        // 4. De-interleave and validate EC
        BlockInfo bi = QRVersion.blockInfo(version, ec);
        int totalData = QRVersion.dataCapacity(version, ec);
        boolean[] ecResults = new boolean[bi.blocksInGroup1 + bi.blocksInGroup2];
        int[] dataBytes = deinterleaveAndCheck(codewords, bi, totalData, ecResults);
        boolean allEcPassed = allTrue(ecResults);

        // 5. Decode bit stream
        String text = decodeBitStream(dataBytes, version);

        return new DecodeResult(text, ec, maskPattern, version, allEcPassed);
    }

    // -------------------------------------------------------------------------
    // Format information

    private static int readFormatInfo(int[][] modules, int size) {
        // Try top-left copy first, then top-right/bottom-left
        int raw1 = 0, raw2 = 0;
        // Top-left: row 8, cols 0-5,7,8 and col 8, rows 0-5,7,8
        int[] hCols = {0,1,2,3,4,5,7,8};
        int[] vRows = {0,1,2,3,4,5,7,8};
        for (int i = 0; i < 8; i++) raw1 = (raw1 << 1) | modules[8][hCols[i]];
        for (int i = 7; i >= 0; i--) raw1 = (raw1 << 1) | modules[vRows[i]][8];
        // Actually format bits are 15-bit; re-read properly
        raw1 = readFormatBitsTopLeft(modules, size);
        raw2 = readFormatBitsAlt(modules, size);

        int decoded1 = decodeFormatBCH(raw1);
        int decoded2 = decodeFormatBCH(raw2);
        if (decoded1 >= 0) return decoded1;
        if (decoded2 >= 0) return decoded2;
        return -1;
    }

    private static int readFormatBitsTopLeft(int[][] m, int size) {
        // 15 bits: positions per spec
        int[] rows = {8,8,8,8,8,8,8,8,7,5,4,3,2,1,0};
        int[] cols = {0,1,2,3,4,5,7,8,8,8,8,8,8,8,8};
        int val = 0;
        for (int i = 0; i < 15; i++) val = (val << 1) | m[rows[i]][cols[i]];
        return val;
    }

    private static int readFormatBitsAlt(int[][] m, int size) {
        int val = 0;
        for (int i = 0; i < 7; i++) val = (val << 1) | m[size - 1 - i][8];
        for (int i = 7; i < 8; i++) val = (val << 1) | m[8][size - 8];
        for (int i = 0; i < 7; i++) val = (val << 1) | m[8][size - 7 + i];
        return val;
    }

    /** Verify and decode a 15-bit raw format word. Returns decoded 5-bit data or -1. */
    private static int decodeFormatBCH(int raw) {
        int unmasked = raw ^ 0b101010000010010;
        // BCH(15,5): check remainder of unmasked / generator 0b10100110111
        int rem = unmasked;
        int g = 0b10100110111;
        for (int i = 14; i >= 10; i--)
            if ((rem >> i & 1) != 0) rem ^= g << (i - 10);
        if ((rem & 0b1111111111) != 0) return -1; // error
        return (unmasked >> 10) & 0b11111;
    }

    private static ECLevel ecFromBits(int bits) {
        return switch (bits) {
            case 0b01 -> ECLevel.L;
            case 0b00 -> ECLevel.M;
            case 0b11 -> ECLevel.Q;
            case 0b10 -> ECLevel.H;
            default   -> ECLevel.M;
        };
    }

    // -------------------------------------------------------------------------
    // Fixed module map

    private static boolean[][] buildFixedMap(int version, int size) {
        boolean[][] fixed = new boolean[size][size];

        // Finder patterns + separators
        markRect(fixed, 0, 0, 8, 8);
        markRect(fixed, 0, size - 8, 8, 8);
        markRect(fixed, size - 8, 0, 8, 8);

        // Timing patterns
        for (int i = 6; i < size - 6; i++) { fixed[6][i] = true; fixed[i][6] = true; }

        // Alignment patterns
        int[] centers = QRVersion.alignmentPatternCenters(version);
        for (int r : centers)
            for (int c : centers)
                if (!fixed[r][c]) markRect(fixed, r - 2, c - 2, 5, 5);

        // Dark module
        fixed[4 * version + 9][8] = true;

        // Format info
        for (int c = 0; c < 9; c++) fixed[8][c] = true;
        for (int r = 0; r < 9; r++) fixed[r][8] = true;
        for (int c = size - 8; c < size; c++) fixed[8][c] = true;
        for (int r = size - 7; r < size; r++) fixed[r][8] = true;

        // Version info (v7+)
        if (version >= 7) {
            for (int i = 0; i < 6; i++)
                for (int j = size - 11; j < size - 8; j++) {
                    fixed[i][j] = true;
                    fixed[j][i] = true;
                }
        }
        return fixed;
    }

    private static void markRect(boolean[][] f, int r, int c, int h, int w) {
        for (int dr = 0; dr < h; dr++)
            for (int dc = 0; dc < w; dc++)
                if (r+dr < f.length && c+dc < f[0].length) f[r+dr][c+dc] = true;
    }

    // -------------------------------------------------------------------------
    // Data extraction (reverse zigzag + unmask)

    private static int[] extractCodewords(int[][] modules, boolean[][] fixed, int mask, int size) {
        List<Integer> bits = new ArrayList<>();
        boolean upward = true;
        int col = size - 1;
        while (col > 0) {
            if (col == 6) col--;
            for (int rowOff = 0; rowOff < size; rowOff++) {
                int row = upward ? size - 1 - rowOff : rowOff;
                for (int dc = 0; dc < 2; dc++) {
                    int c = col - dc;
                    if (!fixed[row][c]) {
                        int bit = modules[row][c];
                        if (shouldUnmask(mask, row, c)) bit ^= 1;
                        bits.add(bit);
                    }
                }
            }
            upward = !upward;
            col -= 2;
        }
        int[] cw = new int[bits.size() / 8];
        for (int i = 0; i < cw.length; i++)
            for (int b = 0; b < 8; b++)
                cw[i] = (cw[i] << 1) | bits.get(i * 8 + b);
        return cw;
    }

    private static boolean shouldUnmask(int mask, int r, int c) {
        return switch (mask) {
            case 0 -> (r + c) % 2 == 0;
            case 1 -> r % 2 == 0;
            case 2 -> c % 3 == 0;
            case 3 -> (r + c) % 3 == 0;
            case 4 -> (r / 2 + c / 3) % 2 == 0;
            case 5 -> (r * c) % 2 + (r * c) % 3 == 0;
            case 6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0;
            case 7 -> ((r + c) % 2 + (r * c) % 3) % 2 == 0;
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // De-interleave and EC check

    private static int[] deinterleaveAndCheck(int[] codewords, BlockInfo bi, int totalData, boolean[] ecResults) {
        int totalBlocks = bi.blocksInGroup1 + bi.blocksInGroup2;
        int ecPer = bi.ecCodewordsPerBlock;

        // Split interleaved codewords back into blocks
        int maxData = bi.blocksInGroup2 > 0 ? bi.dataCodewordsInGroup2 : bi.dataCodewordsInGroup1;
        int[][] dataBlocks = new int[totalBlocks][];
        for (int b = 0; b < totalBlocks; b++) {
            int sz = b < bi.blocksInGroup1 ? bi.dataCodewordsInGroup1 : bi.dataCodewordsInGroup2;
            dataBlocks[b] = new int[sz];
        }
        int[][] ecBlocks = new int[totalBlocks][ecPer];

        // De-interleave data columns
        int cwIdx = 0;
        for (int col = 0; col < maxData && cwIdx < codewords.length; col++)
            for (int b = 0; b < totalBlocks; b++)
                if (col < dataBlocks[b].length) dataBlocks[b][col] = codewords[cwIdx++];

        // De-interleave EC columns
        for (int col = 0; col < ecPer && cwIdx < codewords.length; col++)
            for (int b = 0; b < totalBlocks; b++)
                ecBlocks[b][col] = codewords[cwIdx++];

        // Validate each block
        for (int b = 0; b < totalBlocks; b++) {
            int[] block = new int[dataBlocks[b].length + ecPer];
            System.arraycopy(dataBlocks[b], 0, block, 0, dataBlocks[b].length);
            System.arraycopy(ecBlocks[b], 0, block, dataBlocks[b].length, ecPer);
            ecResults[b] = QRValidator.syndromeCheck(block, ecPer);
        }

        // Concatenate data bytes
        int[] data = new int[totalData];
        int idx = 0;
        for (int[] db : dataBlocks)
            for (int v : db) data[idx++] = v;
        return data;
    }

    // -------------------------------------------------------------------------
    // Bit-stream decoder (byte / numeric / alphanumeric modes)

    private static String decodeBitStream(int[] dataBytes, int version) {
        // Convert to bit array
        int[] bits = new int[dataBytes.length * 8];
        for (int i = 0; i < dataBytes.length; i++)
            for (int b = 7; b >= 0; b--)
                bits[i * 8 + (7 - b)] = (dataBytes[i] >> b) & 1;

        int pos = 0;
        StringBuilder sb = new StringBuilder();

        while (pos + 4 <= bits.length) {
            int mode = readBits(bits, pos, 4); pos += 4;
            if (mode == 0b0000) break; // terminator

            switch (mode) {
                case 0b0100 -> { // byte mode
                    int cciBits = version <= 9 ? 8 : 16;
                    if (pos + cciBits > bits.length) break;
                    int count = readBits(bits, pos, cciBits); pos += cciBits;
                    byte[] buf = new byte[count];
                    for (int i = 0; i < count && pos + 8 <= bits.length; i++) {
                        buf[i] = (byte) readBits(bits, pos, 8); pos += 8;
                    }
                    sb.append(new String(buf, java.nio.charset.StandardCharsets.ISO_8859_1));
                }
                case 0b0001 -> { // numeric mode
                    int cciBits = version <= 9 ? 10 : version <= 26 ? 12 : 14;
                    if (pos + cciBits > bits.length) break;
                    int count = readBits(bits, pos, cciBits); pos += cciBits;
                    sb.append(decodeNumeric(bits, pos, count));
                    pos += (count / 3) * 10 + (count % 3 == 2 ? 7 : count % 3 == 1 ? 4 : 0);
                }
                case 0b0010 -> { // alphanumeric mode
                    int cciBits = version <= 9 ? 9 : version <= 26 ? 11 : 13;
                    if (pos + cciBits > bits.length) break;
                    int count = readBits(bits, pos, cciBits); pos += cciBits;
                    sb.append(decodeAlphanumeric(bits, pos, count));
                    pos += (count / 2) * 11 + (count % 2) * 6;
                }
                default -> { break; } // unknown mode — stop
            }
        }
        return sb.toString();
    }

    private static int readBits(int[] bits, int pos, int len) {
        int val = 0;
        for (int i = 0; i < len && pos + i < bits.length; i++)
            val = (val << 1) | bits[pos + i];
        return val;
    }

    private static String decodeNumeric(int[] bits, int pos, int count) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < count) {
            int rem = count - i;
            if (rem >= 3) {
                int v = readBits(bits, pos, 10); pos += 10;
                sb.append(String.format("%03d", v)); i += 3;
            } else if (rem == 2) {
                int v = readBits(bits, pos, 7); pos += 7;
                sb.append(String.format("%02d", v)); i += 2;
            } else {
                int v = readBits(bits, pos, 4); pos += 4;
                sb.append(v); i++;
            }
        }
        return sb.toString();
    }

    private static final String ALPHANUM_TABLE = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

    private static String decodeAlphanumeric(int[] bits, int pos, int count) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < count) {
            if (count - i >= 2) {
                int v = readBits(bits, pos, 11); pos += 11;
                sb.append(ALPHANUM_TABLE.charAt(v / 45));
                sb.append(ALPHANUM_TABLE.charAt(v % 45));
                i += 2;
            } else {
                int v = readBits(bits, pos, 6); pos += 6;
                sb.append(ALPHANUM_TABLE.charAt(v)); i++;
            }
        }
        return sb.toString();
    }

    private static boolean allTrue(boolean[] arr) {
        for (boolean b : arr) if (!b) return false;
        return true;
    }
}
