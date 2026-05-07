# QR Code Generator

A QR code generator written in Java from scratch — no third-party libraries. Implements the full ISO/IEC 18004 specification including Reed-Solomon error correction, all 8 mask patterns, and PNG rendering via Java's built-in `ImageIO`.

Built as part of the [Coding Challenges QR Code Generator](https://codingchallenges.substack.com/p/from-the-challenges-qr-code-generator) challenge.

## Features

- Versions 1–40 (up to ~2,900 characters)
- All four error correction levels: **L, M, Q, H**
- Byte mode encoding (UTF-8)
- Reed-Solomon error correction over GF(256)
- All 8 mask patterns with ISO penalty scoring (rules 1–4)
- Automatic best-mask selection
- Format info (BCH 15,5) and version info (BCH 18,6) with XOR masking
- Finder, separator, timing, alignment patterns, and dark module
- PNG output with configurable module size and quiet zone

## Requirements

- Java 17 or later

```bash
brew install openjdk@21
```

## Usage

### Build and run

```bash
chmod +x build.sh
./build.sh "<text>" <output.png> <EC level>
```

| Argument | Default | Description |
|---|---|---|
| `text` | `https://codingchallenges.substack.com` | Content to encode |
| `output.png` | `qr.png` | Output file path |
| EC level | `M` | `L`, `M`, `Q`, or `H` |

### Examples

```bash
# Encode a URL with medium error correction
./build.sh "https://github.com/AnkamAndy/QRCodeGenerator" qr.png M

# Encode plain text with high error correction
./build.sh "Hello, World!" hello.png H

# Encode a WiFi credential
./build.sh "WIFI:S:MyNetwork;T:WPA;P:mypassword;;" wifi.png Q
```

### Compile and run manually

```bash
mkdir -p out
javac -d out $(find src -name "*.java")
java -cp out qr.QRCodeGenerator "your text here" output.png M
```

## Project Structure

```
src/main/java/qr/
├── ReedSolomon.java       GF(256) arithmetic and EC codeword generation
├── QRVersion.java         Capacity tables, block structures, alignment centers (v1–40)
├── QREncoder.java         Byte-mode bit stream, padding, data/EC interleaving
├── QRMatrix.java          Matrix construction, masking, penalty scoring, format/version info
└── QRCodeGenerator.java   Entry point — orchestrates encode → place → mask → render
```

## How It Works

### 1. Data Encoding

The input string is encoded in **byte mode** (UTF-8). A bit stream is built with:
- 4-bit mode indicator (`0100`)
- Character count indicator (8 bits for v1–9, 16 bits for v10–40)
- Raw UTF-8 data bytes
- Terminator and padding to fill the data capacity for the chosen version

### 2. Error Correction

Each block of data bytes is passed through **Reed-Solomon encoding** over GF(256) using the QR primitive polynomial `x⁸ + x⁴ + x³ + x² + 1`. The number of EC codewords per block depends on the version and EC level. Data and EC codewords are then interleaved across blocks.

### 3. Matrix Construction

A square matrix of size `4v + 17` modules is populated in order:
1. Finder patterns (3 corners) + separators
2. Timing patterns
3. Alignment patterns (version ≥ 2)
4. Dark module
5. Reserved format and version info areas
6. Data codewords via two-column zigzag scan

### 4. Masking

All 8 mask patterns are applied to the data modules. Each candidate is scored using the four ISO penalty rules:
- **Rule 1** — runs of 5+ same-color modules in a row/column
- **Rule 2** — 2×2 blocks of same-color modules
- **Rule 3** — patterns resembling finder patterns
- **Rule 4** — deviation from 50% dark module ratio

The mask with the lowest penalty score is chosen.

### 5. Format & Version Info

**Format information** (EC level + mask pattern) is encoded as a BCH(15,5) codeword, XOR-masked with `101010000010010`, and written to two reserved areas. **Version information** (versions 7+) is encoded as a BCH(18,6) codeword written in two 6×3 blocks.

### 6. Rendering

The final matrix is rendered to a PNG at 10 pixels per module with a 4-module quiet zone on all sides.

## Error Correction Levels

| Level | EC Capacity | Use Case |
|---|---|---|
| L | ~7% | Clean environments, maximize data density |
| M | ~15% | General purpose (default) |
| Q | ~25% | Industrial environments |
| H | ~30% | Maximum damage tolerance |

## References

- [ISO/IEC 18004:2015](https://www.iso.org/standard/62021.html) — QR Code specification
- [Thonky QR Code Tutorial](https://www.thonky.com/qr-code-tutorial/) — Excellent step-by-step walkthrough
- [Coding Challenges: QR Code Generator](https://codingchallenges.substack.com/p/from-the-challenges-qr-code-generator)
