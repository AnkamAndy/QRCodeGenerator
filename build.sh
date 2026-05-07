#!/usr/bin/env bash
# Build and run the QR code generator.
# Requires Java 17+ (install via: brew install openjdk@21)
set -e

SRC=$(find src -name "*.java")
mkdir -p out
javac -d out $SRC

TEXT="${1:-https://codingchallenges.substack.com}"
OUTPUT="${2:-qr.png}"
EC="${3:-M}"

java -cp out qr.QRCodeGenerator "$TEXT" "$OUTPUT" "$EC"
echo "Done. Open $OUTPUT to view the QR code."
