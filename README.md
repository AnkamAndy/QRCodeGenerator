# QR Code Generator

A QR code generator **and scanner/validator** written in Java from scratch — no third-party libraries. Implements the full ISO/IEC 18004 specification including Reed-Solomon error correction, all 8 mask patterns, PNG rendering, image scanning, and round-trip validation.

Built as part of the [Coding Challenges QR Code Generator](https://codingchallenges.substack.com/p/from-the-challenges-qr-code-generator) challenge.

## Features

**Generation**
- Versions 1–40 (up to ~2,900 characters)
- All four error correction levels: **L, M, Q, H**
- Byte mode encoding (UTF-8)
- Reed-Solomon error correction over GF(256)
- All 8 mask patterns with ISO penalty scoring (rules 1–4)
- Automatic best-mask selection
- Format info (BCH 15,5) and version info (BCH 18,6) with XOR masking
- Finder, separator, timing, alignment patterns, and dark module
- PNG output with configurable module size and quiet zone

**Scanning & Validation**
- Image binarisation (Otsu threshold)
- Finder pattern detection via 1:1:3:1:1 run-length ratio
- Module grid sampling with majority-vote sub-pixel averaging
- Format info integrity check (both BCH copies)
- Version info BCH check (v7+)
- Reed-Solomon syndrome check (zero-syndrome = no errors)
- Numeric, alphanumeric, and byte-mode decoding
- Round-trip validation: encode → render → scan → decode → compare

## Requirements

- Java 17 or later

```bash
brew install openjdk@21
```

## Usage

### Build

```bash
chmod +x build.sh
# All three modes compile the same source tree first
```

### Generate a QR code

```bash
./build.sh generate "<text>" [output.png] [L|M|Q|H]
```

```bash
./build.sh generate "https://github.com/AnkamAndy/QRCodeGenerator" qr.png M
./build.sh generate "Hello, World!" hello.png H
./build.sh generate "WIFI:S:MyNetwork;T:WPA;P:mypassword;;" wifi.png Q
```

### Scan and validate a QR code image

```bash
./build.sh scan <image.png> [expected text]
```

```bash
# Decode and print validation report
./build.sh scan qr.png

# Also verify the decoded text matches an expected value
./build.sh scan qr.png "https://github.com/AnkamAndy/QRCodeGenerator"
```

Example output:
```
Scanning: qr.png
=== QR Validation Report ===
Version : 3  (29x29 modules)
Format  : copy1=OK copy2=OK → VALID
EC Level: M
Mask    : 5
Decoded : https://github.com/AnkamAndy/QRCodeGenerator
EC Check: PASS
Match   : YES
Overall : VALID
```

### Round-trip test (generate → scan → validate)

```bash
./build.sh roundtrip "<text>" [L|M|Q|H]
```

```bash
./build.sh roundtrip "Hello, World!" H
```

### Compile and run manually

```bash
mkdir -p out
javac -d out $(find src -name "*.java")

# Generate
java -cp out qr.QRCodeGenerator "your text here" output.png M

# Scan
java -cp out qr.QRReader output.png "your text here"
```

## Project Structure

```
src/main/java/qr/
├── ReedSolomon.java       GF(256) arithmetic and EC codeword generation
├── QRVersion.java         Capacity tables, block structures, alignment centers (v1–40)
├── QREncoder.java         Byte-mode bit stream, padding, data/EC interleaving
├── QRMatrix.java          Matrix construction, masking, penalty scoring, format/version info
├── QRCodeGenerator.java   Generator entry point — encode → place → mask → render PNG
├── QRScanner.java         Image → module matrix (Otsu binarise, finder detection, sampling)
├── QRDecoder.java         Module matrix → text (format info, zigzag extract, unmask, decode)
├── QRValidator.java       Syndrome check, BCH format/version validation, round-trip report
└── QRReader.java          Scanner CLI entry point
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

### 7. Scanning

`QRScanner` reads a PNG and locates the QR code:
1. **Binarise** — convert to grayscale, compute Otsu threshold, produce a dark/light boolean grid.
2. **Find finder pattern** — scan rows for a run sequence matching the 1:1:3:1:1 dark/light ratio.
3. **Derive module size** — measure run lengths; refine via the horizontal timing pattern.
4. **Determine QR size** — walk the top edge counting modules until the quiet zone resumes; snap to `4v+17`.
5. **Sample modules** — for each grid position, take majority vote over a centre sub-region to handle minor image noise.

### 8. Decoding

`QRDecoder` converts the module matrix to text:
1. Read format info from both copies; verify BCH(15,5) checksum; extract EC level and mask pattern.
2. Reconstruct the fixed-module map (same regions as encoding).
3. Walk the two-column zigzag, skip fixed modules, XOR each data module with its mask condition.
4. Pack bits into codewords; de-interleave data and EC blocks.
5. Decode the bit stream — supports byte, numeric, and alphanumeric segment modes.

### 9. Validation

`QRValidator` runs three independent checks and combines them into a `ValidationReport`:
- **Format BCH** — verifies both format info copies.
- **Version BCH** — verifies the version info block (v7+).
- **RS syndrome** — evaluates S_i = Σ block[j]·α^(i·j) for i=1..ecCount. All-zero syndromes confirm no bit errors.

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
