package qr;

/**
 * CLI entry point for scanning and validating QR code images.
 *
 * Usage:
 *   java -cp out qr.QRReader <image.png> [expected text]
 *
 * Exits with code 0 on success, 1 on validation failure.
 */
public class QRReader {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: QRReader <image.png> [expected text]");
            System.exit(1);
        }

        String imagePath = args[0];
        String expected  = args.length >= 2 ? args[1] : null;

        System.out.println("Scanning: " + imagePath);

        QRValidator.ValidationReport report = QRValidator.validate(imagePath, expected);
        System.out.println(report);

        System.exit(report.isFullyValid() ? 0 : 1);
    }
}
