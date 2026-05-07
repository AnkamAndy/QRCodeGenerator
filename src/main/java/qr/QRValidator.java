package qr;

/**
 * Validation utilities for decoded QR data.
 *
 * Provides:
 *   - Reed-Solomon syndrome check (error detection on a full block)
 *   - Format info BCH integrity check
 *   - Version info BCH integrity check
 *   - Round-trip validation (encode → decode → compare)
 */
public class QRValidator {

    public static final class ValidationReport {
        public final boolean formatInfoValid;
        public final boolean versionInfoValid; // always true for v < 7
        public final boolean ecSyndromesZero;
        public final boolean roundTripMatch;
        public final String decodedText;
        public final String details;

        ValidationReport(boolean fmt, boolean ver, boolean ec, boolean rt, String text, String details) {
            this.formatInfoValid = fmt;
            this.versionInfoValid = ver;
            this.ecSyndromesZero = ec;
            this.roundTripMatch = rt;
            this.decodedText = text;
            this.details = details;
        }

        public boolean isFullyValid() {
            return formatInfoValid && versionInfoValid && ecSyndromesZero;
        }

        @Override
        public String toString() { return details; }
    }

    /**
     * Full validation pipeline: scan → decode → report.
     *
     * @param imagePath path to the QR code PNG
     * @param expectedText if non-null, also checks round-trip equality
     */
    public static ValidationReport validate(String imagePath, String expectedText) throws Exception {
        QRScanner.ScanResult scan = QRScanner.scan(imagePath);
        return validateMatrix(scan.modules, scan.version, expectedText);
    }

    /** Validate from a pre-scanned module matrix. */
    public static ValidationReport validateMatrix(int[][] modules, int version, String expectedText) {
        StringBuilder details = new StringBuilder();
        details.append(String.format("=== QR Validation Report ===%n"));
        details.append(String.format("Version : %d  (%dx%d modules)%n", version, modules.length, modules.length));

        // 1. Format info check
        boolean fmtValid = validateFormatInfo(modules, details);

        // 2. Version info check
        boolean verValid = version < 7 || validateVersionInfo(modules, version, details);

        // 3. Decode
        QRDecoder.DecodeResult decoded;
        try {
            decoded = QRDecoder.decode(modules, version);
        } catch (Exception e) {
            details.append("DECODE ERROR: ").append(e.getMessage()).append(System.lineSeparator());
            return new ValidationReport(fmtValid, verValid, false, false, null, details.toString());
        }

        details.append(String.format("EC Level: %s%n", decoded.ecLevel));
        details.append(String.format("Mask    : %d%n", decoded.maskPattern));
        details.append(String.format("Decoded : %s%n", decoded.text));
        details.append(String.format("EC Check: %s%n", decoded.ecPassed ? "PASS" : "FAIL"));

        // 4. Round-trip check
        boolean rtMatch = expectedText == null || expectedText.equals(decoded.text);
        if (expectedText != null) {
            details.append(String.format("Expected: %s%n", expectedText));
            details.append(String.format("Match   : %s%n", rtMatch ? "YES" : "NO"));
        }

        String overall = (fmtValid && verValid && decoded.ecPassed && rtMatch) ? "VALID" : "INVALID";
        details.append(String.format("Overall : %s%n", overall));

        return new ValidationReport(fmtValid, verValid, decoded.ecPassed, rtMatch, decoded.text, details.toString());
    }

    // -------------------------------------------------------------------------

    /**
     * Reed-Solomon syndrome check: computes syndromes S_i = block(α^i) for i=1..ecCount.
     * If all syndromes are zero the block contains no detected errors.
     *
     * @param block  data bytes followed by EC bytes (total length = data + ecCount)
     * @param ecCount number of EC codewords
     * @return true if all syndromes are zero (no errors detected)
     */
    public static boolean syndromeCheck(int[] block, int ecCount) {
        for (int i = 0; i < ecCount; i++) {
            int s = 0;
            for (int b : block) s = gfMul(s, gfExp(1, i + 1)) ^ b; // Horner via shift
            // Simpler: evaluate polynomial at α^i
            s = 0;
            int alpha = gfExp(0, i + 1); // α^(i+1)
            for (int b : block) s = gfMul(s, alpha) ^ b; // nope, we need α^i not shifting
        }
        // Correct approach: syndrome S_i = sum(block[j] * α^(i*j))
        for (int i = 0; i < ecCount; i++) {
            int s = 0;
            for (int j = 0; j < block.length; j++)
                s ^= gfMul(block[j], gfPow(i + 1, j));
            if (s != 0) return false;
        }
        return true;
    }

    // GF(256) helpers (same field as ReedSolomon.java)
    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[(LOG[a] + LOG[b]) % 255];
    }

    private static int gfPow(int base, int exp) {
        // α^(base*exp) — here base is the primitive root index
        if (exp == 0) return 1;
        return EXP[(base * exp) % 255];
    }

    private static int gfExp(int unused, int n) { return EXP[n % 255]; }

    // -------------------------------------------------------------------------

    private static boolean validateFormatInfo(int[][] modules, StringBuilder sb) {
        int size = modules.length;
        int raw1 = readFormatBitsTopLeft(modules, size);
        int raw2 = readFormatBitsAlt(modules, size);

        boolean ok1 = bchFormatOk(raw1);
        boolean ok2 = bchFormatOk(raw2);
        boolean ok = ok1 || ok2;

        sb.append(String.format("Format  : copy1=%s copy2=%s → %s%n",
                ok1 ? "OK" : "ERR", ok2 ? "OK" : "ERR", ok ? "VALID" : "INVALID"));
        return ok;
    }

    private static int readFormatBitsTopLeft(int[][] m, int size) {
        int[] rows = {8,8,8,8,8,8,8,8,7,5,4,3,2,1,0};
        int[] cols = {0,1,2,3,4,5,7,8,8,8,8,8,8,8,8};
        int val = 0;
        for (int i = 0; i < 15; i++) val = (val << 1) | m[rows[i]][cols[i]];
        return val;
    }

    private static int readFormatBitsAlt(int[][] m, int size) {
        int val = 0;
        for (int i = 0; i < 7; i++) val = (val << 1) | m[size - 1 - i][8];
        val = (val << 1) | m[8][size - 8];
        for (int i = 0; i < 7; i++) val = (val << 1) | m[8][size - 7 + i];
        return val;
    }

    private static boolean bchFormatOk(int raw) {
        int unmasked = raw ^ 0b101010000010010;
        int rem = unmasked;
        for (int i = 14; i >= 10; i--)
            if ((rem >> i & 1) != 0) rem ^= 0b10100110111 << (i - 10);
        return (rem & 0b1111111111) == 0;
    }

    private static boolean validateVersionInfo(int[][] modules, int version, StringBuilder sb) {
        int size = modules.length;
        // Read top-right version block
        int info = 0;
        for (int i = 5; i >= 0; i--)
            for (int j = size - 11; j < size - 8; j++)
                info = (info << 1) | modules[i][j];

        boolean ok = bchVersionOk(info, version);
        sb.append(String.format("Version : BCH check %s%n", ok ? "PASS" : "FAIL"));
        return ok;
    }

    private static boolean bchVersionOk(int raw, int expectedVersion) {
        int g = 0b1111100100101;
        int rem = raw;
        for (int i = 17; i >= 12; i--)
            if ((rem >> i & 1) != 0) rem ^= g << (i - 12);
        if ((rem & 0xFFF) != 0) return false;
        return (raw >> 12) == expectedVersion;
    }
}
