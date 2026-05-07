#!/usr/bin/env bash
# Build, generate, and optionally scan/validate QR codes.
# Requires Java 17+ (install via: brew install openjdk@21)
#
# Generate:  ./build.sh generate "<text>" [output.png] [L|M|Q|H]
# Scan:      ./build.sh scan <image.png> [expected text]
# Round-trip:./build.sh roundtrip "<text>" [L|M|Q|H]
set -e

SRC=$(find src -name "*.java")
mkdir -p out
javac -d out $SRC

CMD="${1:-generate}"

case "$CMD" in
  generate)
    TEXT="${2:-https://codingchallenges.substack.com}"
    OUTPUT="${3:-qr.png}"
    EC="${4:-M}"
    java -cp out qr.QRCodeGenerator "$TEXT" "$OUTPUT" "$EC"
    echo "Done. Open $OUTPUT to view the QR code."
    ;;
  scan)
    IMAGE="${2:?Usage: ./build.sh scan <image.png> [expected text]}"
    EXPECTED="${3:-}"
    java -cp out qr.QRReader "$IMAGE" $( [[ -n "$EXPECTED" ]] && echo "\"$EXPECTED\"" )
    ;;
  roundtrip)
    TEXT="${2:-https://codingchallenges.substack.com}"
    EC="${3:-M}"
    TMP=$(mktemp /tmp/qr_roundtrip_XXXXX.png)
    java -cp out qr.QRCodeGenerator "$TEXT" "$TMP" "$EC"
    java -cp out qr.QRReader "$TMP" "$TEXT"
    rm -f "$TMP"
    ;;
  *)
    echo "Unknown command: $CMD"
    echo "Usage: ./build.sh [generate|scan|roundtrip] ..."
    exit 1
    ;;
esac
