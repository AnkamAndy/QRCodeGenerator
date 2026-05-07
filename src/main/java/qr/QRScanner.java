package qr;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Reads a QR code PNG and samples the module grid into a boolean matrix.
 * Assumes an axis-aligned, unrotated QR code (as produced by QRCodeGenerator).
 *
 * Strategy:
 *   1. Binarise the image (Otsu threshold).
 *   2. Find the top-left finder pattern by scanning for the first dark run
 *      on the leading rows, then measure the 1:1:3:1:1 module-width ratio.
 *   3. Determine module size and quiet-zone offset from the finder.
 *   4. Derive QR size from timing pattern length.
 *   5. Sample each module by taking the majority vote of a centre sub-region.
 */
public class QRScanner {

    public static final class ScanResult {
        public final int[][] modules; // [row][col], 1=dark 0=light
        public final int version;
        public final int moduleSize; // pixels per module

        ScanResult(int[][] modules, int version, int moduleSize) {
            this.modules = modules;
            this.version = version;
            this.moduleSize = moduleSize;
        }
    }

    public static ScanResult scan(String imagePath) throws Exception {
        BufferedImage img = ImageIO.read(new File(imagePath));
        if (img == null) throw new IllegalArgumentException("Cannot read image: " + imagePath);

        int w = img.getWidth(), h = img.getHeight();
        boolean[][] dark = binarise(img, w, h);

        // --- Find finder pattern top-left corner ---
        // Scan rows until we hit a dark run that looks like the start of a finder
        int finderRow = -1, finderCol = -1, modulePixels = -1;
        outer:
        for (int r = 0; r < h / 2; r++) {
            for (int c = 0; c < w / 2; c++) {
                if (!dark[r][c]) continue;
                // Measure the 1:1:3:1:1 pattern horizontally
                int[] runs = horizontalRuns(dark, r, c, w);
                if (runs == null) continue;
                int unit = runs[0]; // estimated module width
                if (unit < 1) continue;
                finderRow = r;
                finderCol = c;
                modulePixels = unit;
                break outer;
            }
        }
        if (modulePixels < 1) throw new IllegalStateException("Could not locate finder pattern");

        // Quiet zone is 4 modules; finder starts at module (4,4) → pixel (4*mp, 4*mp)
        int quietPixels = 4 * modulePixels;
        int originX = finderCol - quietPixels; // pixel x of module (0,0)
        int originY = finderRow - quietPixels; // pixel y of module (0,0)

        // Refine module size using horizontal timing pattern (row 6 from origin)
        // Count transitions between col 8 and end of timing strip
        int timingRow = originY + 6 * modulePixels + modulePixels / 2;
        if (timingRow >= 0 && timingRow < h) {
            int refined = refineModuleSize(dark, timingRow, originX + 8 * modulePixels, w);
            if (refined > 0) modulePixels = refined;
        }

        // Determine QR size by counting modules along timing row
        int qrSize = deriveQRSize(dark, originX, originY, modulePixels, w, h);
        if (qrSize < 21 || (qrSize - 17) % 4 != 0)
            throw new IllegalStateException("Invalid QR size derived: " + qrSize);
        int version = (qrSize - 17) / 4;

        // Sample modules
        int[][] modules = new int[qrSize][qrSize];
        for (int r = 0; r < qrSize; r++) {
            for (int c = 0; c < qrSize; c++) {
                int px = originX + c * modulePixels + modulePixels / 2;
                int py = originY + r * modulePixels + modulePixels / 2;
                modules[r][c] = sampleModule(dark, px, py, modulePixels, w, h);
            }
        }

        return new ScanResult(modules, version, modulePixels);
    }

    // -------------------------------------------------------------------------

    private static boolean[][] binarise(BufferedImage img, int w, int h) {
        // Compute Otsu threshold on grayscale
        int[] hist = new int[256];
        int[][] gray = new int[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int g = (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
                gray[y][x] = g;
                hist[g]++;
            }
        }
        int total = w * h;
        int threshold = otsu(hist, total);

        boolean[][] dark = new boolean[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                dark[y][x] = gray[y][x] <= threshold;
        return dark;
    }

    private static int otsu(int[] hist, int total) {
        double sum = 0;
        for (int i = 0; i < 256; i++) sum += i * hist[i];
        double sumB = 0, wB = 0, max = 0;
        int threshold = 128;
        for (int i = 0; i < 256; i++) {
            wB += hist[i];
            if (wB == 0) continue;
            double wF = total - wB;
            if (wF == 0) break;
            sumB += i * hist[i];
            double mB = sumB / wB;
            double mF = (sum - sumB) / wF;
            double between = wB * wF * (mB - mF) * (mB - mF);
            if (between > max) { max = between; threshold = i; }
        }
        return threshold;
    }

    /**
     * Scan a row from startCol and return run-lengths if they match the 1:1:3:1:1 finder ratio.
     * Returns null if no match. Index 0 is the estimated unit module width.
     */
    private static int[] horizontalRuns(boolean[][] dark, int row, int startCol, int w) {
        int[] runs = new int[5];
        int idx = 0, col = startCol;
        boolean cur = dark[row][col];
        while (col < w && idx < 5) {
            int start = col;
            while (col < w && dark[row][col] == cur) col++;
            runs[idx++] = col - start;
            cur = !cur;
        }
        if (idx < 5) return null;
        // Check 1:1:3:1:1 ratio within 50% tolerance
        int unit = (runs[0] + runs[1] + runs[2] + runs[3] + runs[4]) / 7;
        if (unit < 1) return null;
        if (!approx(runs[0], unit) || !approx(runs[1], unit) ||
            !approx(runs[2], unit * 3) || !approx(runs[3], unit) || !approx(runs[4], unit))
            return null;
        runs[0] = unit;
        return runs;
    }

    private static boolean approx(int val, int target) {
        return Math.abs(val - target) <= Math.max(1, target / 2);
    }

    private static int refineModuleSize(boolean[][] dark, int row, int startPx, int imgW) {
        if (row < 0 || row >= dark.length || startPx < 0 || startPx >= imgW) return -1;
        int transitions = 0, col = startPx;
        boolean prev = dark[row][col];
        int end = Math.min(imgW - 1, startPx + 200);
        while (col < end) {
            col++;
            if (dark[row][col] != prev) { transitions++; prev = dark[row][col]; }
        }
        if (transitions < 2) return -1;
        return (col - startPx) / (transitions + 1);
    }

    private static int deriveQRSize(boolean[][] dark, int ox, int oy, int mp, int w, int h) {
        // Walk along the top edge (row 0) from the quiet zone to find QR width
        int count = 0;
        for (int c = 0; c < 200; c++) {
            int px = ox + c * mp + mp / 2;
            int py = oy + mp / 2;
            if (px < 0 || px >= w || py < 0 || py >= h) break;
            count = c + 1;
            // Check if next module is outside image or white beyond expected symbol
            int nextPx = ox + (c + 1) * mp + mp / 2;
            if (nextPx >= w) break;
            // Check for quiet zone on the right: 4 consecutive light modules after last dark
            if (c > 20) {
                boolean allLight = true;
                for (int k = 1; k <= 3; k++) {
                    int kpx = ox + (c + k) * mp + mp / 2;
                    if (kpx < w && dark[py][kpx]) { allLight = false; break; }
                }
                if (allLight) break;
            }
        }
        // Snap to nearest valid QR size: 4v+17
        for (int v = 40; v >= 1; v--) {
            int s = 4 * v + 17;
            if (Math.abs(s - count) <= 2) return s;
        }
        return count;
    }

    private static int sampleModule(boolean[][] dark, int cx, int cy, int mp, int w, int h) {
        int radius = Math.max(1, mp / 4);
        int darkCount = 0, total = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < w && y >= 0 && y < dark.length) {
                    if (dark[y][x]) darkCount++;
                    total++;
                }
            }
        }
        return darkCount * 2 >= total ? 1 : 0;
    }
}
