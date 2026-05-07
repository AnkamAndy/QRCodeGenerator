package qr;

import qr.QRVersion.ECLevel;

/**
 * Builds the QR code boolean matrix from encoded codewords.
 * Handles: finder patterns, separators, timing, alignment patterns,
 * dark module, format info, version info (v7+), data placement, and masking.
 */
public class QRMatrix {

    private static final int DARK = 1;
    private static final int LIGHT = 0;
    private static final int UNSET = -1;

    private final int version;
    private final ECLevel ec;
    private final int size;
    private final int[][] modules;   // 1=dark, 0=light
    private final boolean[][] fixed; // true = reserved (not data)

    public QRMatrix(int version, ECLevel ec) {
        this.version = version;
        this.ec = ec;
        this.size = version * 4 + 17;
        this.modules = new int[size][size];
        this.fixed = new boolean[size][size];
        for (int[] row : modules) java.util.Arrays.fill(row, UNSET);
    }

    /** Place all function patterns and return. */
    public void placeFunctionPatterns() {
        placeFinderPattern(0, 0);
        placeFinderPattern(size - 7, 0);
        placeFinderPattern(0, size - 7);
        placeSeparators();
        placeTimingPatterns();
        placeAlignmentPatterns();
        placeDarkModule();
        reserveFormatArea();
        if (version >= 7) reserveVersionArea();
    }

    private void set(int row, int col, int value, boolean isFixed) {
        modules[row][col] = value;
        if (isFixed) fixed[row][col] = true;
    }

    private void placeFinderPattern(int topRow, int leftCol) {
        for (int r = 0; r < 7; r++) {
            for (int c = 0; c < 7; c++) {
                boolean border = r == 0 || r == 6 || c == 0 || c == 6;
                boolean inner = r >= 2 && r <= 4 && c >= 2 && c <= 4;
                set(topRow + r, leftCol + c, (border || inner) ? DARK : LIGHT, true);
            }
        }
    }

    private void placeSeparators() {
        // Horizontal separators
        for (int c = 0; c < 8; c++) {
            set(7, c, LIGHT, true);
            set(7, size - 1 - c, LIGHT, true);
            set(size - 8, c, LIGHT, true);
        }
        // Vertical separators
        for (int r = 0; r < 8; r++) {
            set(r, 7, LIGHT, true);
            set(r, size - 8, LIGHT, true);
            set(size - 1 - r, 7, LIGHT, true);
        }
    }

    private void placeTimingPatterns() {
        for (int i = 8; i < size - 8; i++) {
            set(6, i, i % 2 == 0 ? DARK : LIGHT, true);
            set(i, 6, i % 2 == 0 ? DARK : LIGHT, true);
        }
    }

    private void placeAlignmentPatterns() {
        int[] centers = QRVersion.alignmentPatternCenters(version);
        for (int r : centers) {
            for (int c : centers) {
                if (fixed[r][c]) continue; // overlap with finder/timing
                for (int dr = -2; dr <= 2; dr++) {
                    for (int dc = -2; dc <= 2; dc++) {
                        boolean border = Math.abs(dr) == 2 || Math.abs(dc) == 2;
                        boolean center = dr == 0 && dc == 0;
                        set(r + dr, c + dc, (border || center) ? DARK : LIGHT, true);
                    }
                }
            }
        }
    }

    private void placeDarkModule() {
        set(4 * version + 9, 8, DARK, true);
    }

    private void reserveFormatArea() {
        // Top-left horizontal
        for (int c = 0; c < 9; c++) if (!fixed[8][c]) { modules[8][c] = LIGHT; fixed[8][c] = true; }
        // Top-left vertical
        for (int r = 0; r < 9; r++) if (!fixed[r][8]) { modules[r][8] = LIGHT; fixed[r][8] = true; }
        // Top-right horizontal
        for (int c = size - 8; c < size; c++) { modules[8][c] = LIGHT; fixed[8][c] = true; }
        // Bottom-left vertical
        for (int r = size - 7; r < size; r++) { modules[r][8] = LIGHT; fixed[r][8] = true; }
    }

    private void reserveVersionArea() {
        for (int i = 0; i < 6; i++) {
            for (int j = size - 11; j < size - 8; j++) {
                modules[i][j] = LIGHT; fixed[i][j] = true;
                modules[j][i] = LIGHT; fixed[j][i] = true;
            }
        }
    }

    /** Place data codewords using the two-column upward/downward zigzag. */
    public void placeData(int[] codewords) {
        int bitIdx = 0;
        int totalBits = codewords.length * 8;
        boolean upward = true;
        int col = size - 1;
        while (col > 0) {
            if (col == 6) col--; // skip timing column
            for (int rowOff = 0; rowOff < size; rowOff++) {
                int row = upward ? size - 1 - rowOff : rowOff;
                for (int dc = 0; dc < 2; dc++) {
                    int c = col - dc;
                    if (!fixed[row][c]) {
                        int bit = 0;
                        if (bitIdx < totalBits) {
                            int byteVal = codewords[bitIdx / 8];
                            bit = (byteVal >> (7 - bitIdx % 8)) & 1;
                            bitIdx++;
                        }
                        modules[row][c] = bit;
                    }
                }
            }
            upward = !upward;
            col -= 2;
        }
    }

    /** Apply mask pattern m (0-7) to non-fixed modules, return masked copy. */
    public int[][] applyMask(int m) {
        int[][] masked = new int[size][size];
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                masked[r][c] = modules[r][c];
                if (!fixed[r][c] && shouldFlip(m, r, c))
                    masked[r][c] ^= 1;
            }
        }
        return masked;
    }

    private boolean shouldFlip(int mask, int r, int c) {
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

    /** Evaluate penalty score for a masked matrix. */
    public static int penalty(int[][] mat) {
        int size = mat.length;
        int score = 0;

        // Rule 1: 5+ in a row/col
        for (int r = 0; r < size; r++) {
            score += runPenalty(mat[r]);
            int[] col = new int[size];
            for (int c = 0; c < size; c++) col[c] = mat[r][c];
            score += runPenalty(col);
        }

        // Rule 2: 2x2 blocks
        for (int r = 0; r < size - 1; r++)
            for (int c = 0; c < size - 1; c++)
                if (mat[r][c] == mat[r+1][c] && mat[r][c] == mat[r][c+1] && mat[r][c] == mat[r+1][c+1])
                    score += 3;

        // Rule 3: finder-like patterns
        int[] pat1 = {1,0,1,1,1,0,1,0,0,0,0};
        int[] pat2 = {0,0,0,0,1,0,1,1,1,0,1};
        for (int r = 0; r < size; r++) {
            for (int c = 0; c <= size - 11; c++) {
                if (matchRow(mat, r, c, pat1) || matchRow(mat, r, c, pat2)) score += 40;
                if (matchCol(mat, c, r, pat1) || matchCol(mat, c, r, pat2)) score += 40;
            }
        }

        // Rule 4: dark module ratio
        int dark = 0;
        for (int[] row : mat) for (int v : row) if (v == 1) dark++;
        int total = size * size;
        int pct = dark * 100 / total;
        int prev5 = Math.abs((pct / 5) * 5 - 50) / 5;
        int next5 = Math.abs(((pct / 5) + 1) * 5 - 50) / 5;
        score += Math.min(prev5, next5) * 10;

        return score;
    }

    private static int runPenalty(int[] line) {
        int score = 0, run = 1;
        for (int i = 1; i < line.length; i++) {
            if (line[i] == line[i-1]) { run++; if (run == 5) score += 3; else if (run > 5) score++; }
            else run = 1;
        }
        return score;
    }

    private static boolean matchRow(int[][] m, int r, int c, int[] pat) {
        for (int i = 0; i < pat.length; i++) if (m[r][c+i] != pat[i]) return false;
        return true;
    }

    private static boolean matchCol(int[][] m, int c, int r, int[] pat) {
        for (int i = 0; i < pat.length; i++) if (m[r+i][c] != pat[i]) return false;
        return true;
    }

    /** Write format info bits into a masked matrix. */
    public static void writeFormatInfo(int[][] mat, ECLevel ec, int maskPattern) {
        int ecBits = switch (ec) {
            case L -> 0b01;
            case M -> 0b00;
            case Q -> 0b11;
            case H -> 0b10;
        };
        int data = (ecBits << 3) | maskPattern;
        int format = bch15_5(data) ^ 0b101010000010010; // XOR mask per spec

        int size = mat.length;
        // Top-left horizontal
        int[] hPos = {8,8,8,8,8,8,8,8,7,5,4,3,2,1,0};
        int[] hFixed = {0,1,2,3,4,5,7,8,8,8,8,8,8,8,8};
        for (int i = 0; i < 15; i++) {
            int bit = (format >> (14 - i)) & 1;
            mat[hPos[i]][hFixed[i]] = bit;   // top-left region
        }
        // Top-right / bottom-left copies
        for (int i = 0; i < 7; i++) mat[8][size - 1 - i] = (format >> i) & 1;
        for (int i = 0; i < 8; i++) mat[size - 1 - i][8] = (format >> i) & 1;
        // Dark module stays dark
        mat[size - 8][8] = 1;
    }

    private static int bch15_5(int data) {
        int g = 0b10100110111; // Generator for BCH(15,5)
        int d = data << 10;
        for (int i = 14; i >= 10; i--)
            if ((d >> i & 1) != 0) d ^= g << (i - 10);
        return (data << 10) | d;
    }

    /** Write version info for version >= 7. */
    public static void writeVersionInfo(int[][] mat, int version) {
        if (version < 7) return;
        int info = bch18_6(version);
        int size = mat.length;
        for (int i = 0; i < 18; i++) {
            int bit = (info >> i) & 1;
            int r = i / 3, c = i % 3;
            mat[r][size - 11 + c] = bit;
            mat[size - 11 + c][r] = bit;
        }
    }

    private static int bch18_6(int data) {
        int g = 0b1111100100101;
        int d = data << 12;
        for (int i = 17; i >= 12; i--)
            if ((d >> i & 1) != 0) d ^= g << (i - 12);
        return (data << 12) | d;
    }

    public int getSize() { return size; }
    public int[][] getModules() { return modules; }
}
