package qr;

/**
 * GF(256) arithmetic and Reed-Solomon error correction codeword generation.
 * Uses the QR code primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11D).
 */
public class ReedSolomon {

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

    private static int mul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    /** Generate generator polynomial of degree ecCount. */
    private static int[] generatorPoly(int ecCount) {
        int[] g = {1};
        for (int i = 0; i < ecCount; i++) {
            int[] factor = {1, EXP[i]};
            int[] result = new int[g.length + 1];
            for (int j = 0; j < g.length; j++)
                for (int k = 0; k < factor.length; k++)
                    result[j + k] ^= mul(g[j], factor[k]);
            g = result;
        }
        return g;
    }

    /**
     * Compute EC codewords for a block of data bytes.
     *
     * @param data    data codeword bytes
     * @param ecCount number of EC codewords to produce
     * @return EC codeword array of length ecCount
     */
    public static int[] computeECBytes(int[] data, int ecCount) {
        int[] gen = generatorPoly(ecCount);
        int[] remainder = new int[ecCount];
        for (int dataByte : data) {
            int factor = dataByte ^ remainder[0];
            System.arraycopy(remainder, 1, remainder, 0, ecCount - 1);
            remainder[ecCount - 1] = 0;
            for (int j = 0; j < ecCount; j++)
                remainder[j] ^= mul(gen[j + 1], factor);
        }
        return remainder;
    }
}
