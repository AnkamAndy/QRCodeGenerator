package qr;

import qr.QRVersion.ECLevel;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Top-level QR code generator. Encodes text, selects best mask, renders PNG.
 */
public class QRCodeGenerator {

    private static final int MODULE_PIXELS = 10; // pixels per module
    private static final int QUIET_MODULES = 4;  // quiet zone width in modules

    /**
     * Generate a QR code PNG for the given text.
     *
     * @param text      content to encode
     * @param ec        error correction level
     * @param outputPath file path for the output PNG
     */
    public static void generate(String text, ECLevel ec, String outputPath) throws Exception {
        // 1. Encode
        QREncoder.EncodedData encoded = QREncoder.encode(text, ec);
        int version = encoded.version;

        // 2. Build matrix and place function patterns
        QRMatrix matrix = new QRMatrix(version, ec);
        matrix.placeFunctionPatterns();

        // 3. Place data
        matrix.placeData(encoded.codewords);

        // 4. Pick best mask (lowest penalty)
        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        int[][] bestMatrix = null;
        for (int m = 0; m < 8; m++) {
            int[][] candidate = matrix.applyMask(m);
            QRMatrix.writeFormatInfo(candidate, ec, m);
            QRMatrix.writeVersionInfo(candidate, version);
            int p = QRMatrix.penalty(candidate);
            if (p < bestPenalty) {
                bestPenalty = p;
                bestMask = m;
                bestMatrix = candidate;
            }
        }

        // 5. Render to PNG
        int qrSize = matrix.getSize();
        int imgSize = (qrSize + QUIET_MODULES * 2) * MODULE_PIXELS;
        BufferedImage img = new BufferedImage(imgSize, imgSize, BufferedImage.TYPE_INT_RGB);
        // Fill white
        for (int y = 0; y < imgSize; y++)
            for (int x = 0; x < imgSize; x++)
                img.setRGB(x, y, 0xFFFFFF);
        // Draw modules
        for (int r = 0; r < qrSize; r++) {
            for (int c = 0; c < qrSize; c++) {
                int color = bestMatrix[r][c] == 1 ? 0x000000 : 0xFFFFFF;
                int px = (c + QUIET_MODULES) * MODULE_PIXELS;
                int py = (r + QUIET_MODULES) * MODULE_PIXELS;
                for (int dy = 0; dy < MODULE_PIXELS; dy++)
                    for (int dx = 0; dx < MODULE_PIXELS; dx++)
                        img.setRGB(px + dx, py + dy, color);
            }
        }

        ImageIO.write(img, "PNG", new File(outputPath));
        System.out.printf("QR code written to %s (version %d, mask %d, penalty %d)%n",
                outputPath, version, bestMask, bestPenalty);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: QRCodeGenerator <text> [output.png] [L|M|Q|H]");
            System.out.println("Defaults: output=qr.png, EC=M");
            // Demo
            generate("https://codingchallenges.substack.com", ECLevel.M, "qr.png");
            return;
        }
        String text = args[0];
        String output = args.length >= 2 ? args[1] : "qr.png";
        ECLevel ec = args.length >= 3 ? ECLevel.valueOf(args[2].toUpperCase()) : ECLevel.M;
        generate(text, ec, output);
    }
}
