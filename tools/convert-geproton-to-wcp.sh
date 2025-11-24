#!/bin/bash
set -e

# Script to convert GE-Proton tar.zst to GameNative .wcp format
# Usage: ./convert-geproton-to-wcp.sh <input.tar.zst> <output.wcp>

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <input-geproton.tar.zst> <output.wcp>"
    echo "Example: $0 GE-Proton10-25.tar.zst ge-proton-10-25-x86_64.wcp"
    exit 1
fi

INPUT_ARCHIVE="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
OUTPUT_WCP="$(cd "$(dirname "$2")" 2>/dev/null && pwd)/$(basename "$2")" || OUTPUT_WCP="$2"

if [ ! -f "$INPUT_ARCHIVE" ]; then
    echo "Error: Input file '$INPUT_ARCHIVE' not found"
    exit 1
fi

echo "=========================================="
echo "GE-Proton to .wcp Converter"
echo "=========================================="
echo "Input:  $INPUT_ARCHIVE"
echo "Output: $OUTPUT_WCP"
echo ""

# Create temporary working directory
WORK_DIR=$(mktemp -d -t geproton-wcp-XXXXXX)
echo "Working directory: $WORK_DIR"

cleanup() {
    echo "Cleaning up temporary files..."
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

# Extract GE-Proton archive
echo ""
echo "[1/6] Extracting GE-Proton archive..."
tar -xzf "$INPUT_ARCHIVE" -C "$WORK_DIR"

# Find the GE-Proton directory (handles GE-Proton10-25, etc.)
GEPROTON_DIR=$(find "$WORK_DIR" -maxdepth 1 -type d -name "GE-Proton*" | head -1)
if [ -z "$GEPROTON_DIR" ]; then
    echo "Error: Could not find GE-Proton directory in archive"
    exit 1
fi

GEPROTON_NAME=$(basename "$GEPROTON_DIR")
echo "Found: $GEPROTON_NAME"

# Extract version info from directory name
# GE-Proton10-25 -> 10-25
PROTON_VERSION=$(echo "$GEPROTON_NAME" | sed 's/GE-Proton//')
echo "Version: $PROTON_VERSION"

# Create WCP structure
echo ""
echo "[2/6] Creating .wcp structure..."
WCP_BUILD_DIR="$WORK_DIR/wcp_build"
mkdir -p "$WCP_BUILD_DIR"

# Copy Wine files from GE-Proton/files/
GEPROTON_FILES="$GEPROTON_DIR/files"

# Copy bin directory
echo "  - Copying bin/..."
if [ -d "$GEPROTON_FILES/bin" ]; then
    cp -r "$GEPROTON_FILES/bin" "$WCP_BUILD_DIR/"
else
    echo "Error: bin/ directory not found in GE-Proton"
    exit 1
fi

# Copy lib directory
echo "  - Copying lib/..."
if [ -d "$GEPROTON_FILES/lib" ]; then
    cp -r "$GEPROTON_FILES/lib" "$WCP_BUILD_DIR/"
else
    echo "Error: lib/ directory not found in GE-Proton"
    exit 1
fi

# Copy share directory
echo "  - Copying share/..."
if [ -d "$GEPROTON_FILES/share" ]; then
    cp -r "$GEPROTON_FILES/share" "$WCP_BUILD_DIR/"
else
    echo "Warning: share/ directory not found, creating minimal structure..."
    mkdir -p "$WCP_BUILD_DIR/share/wine"
fi

# Copy protonfixes directory (Python game compatibility fixes)
echo "  - Copying protonfixes/..."
if [ -d "$GEPROTON_DIR/protonfixes" ]; then
    cp -r "$GEPROTON_DIR/protonfixes" "$WCP_BUILD_DIR/"
    FIXES_COUNT=$(find "$GEPROTON_DIR/protonfixes/gamefixes-steam" -name "*.py" -type f | wc -l)
    echo "    Found $FIXES_COUNT game fixes"
else
    echo "Warning: protonfixes/ directory not found"
fi

# Copy proton launcher script (for reference, not directly executable on Android)
if [ -f "$GEPROTON_DIR/proton" ]; then
    cp "$GEPROTON_DIR/proton" "$WCP_BUILD_DIR/"
    echo "  - Copied proton launcher script"
fi

echo ""
echo "[3/6] Creating Wine prefix template..."

# Create a minimal Wine prefix structure
PREFIX_DIR="$WORK_DIR/prefix_build/.wine"
mkdir -p "$PREFIX_DIR"

# Create essential prefix structure
mkdir -p "$PREFIX_DIR/dosdevices"
mkdir -p "$PREFIX_DIR/drive_c/windows/system32"
mkdir -p "$PREFIX_DIR/drive_c/windows/syswow64"
mkdir -p "$PREFIX_DIR/drive_c/users"
mkdir -p "$PREFIX_DIR/drive_c/ProgramData"
mkdir -p "$PREFIX_DIR/drive_c/Program Files"
mkdir -p "$PREFIX_DIR/drive_c/Program Files (x86)"

# Create symbolic links for dosdevices
cd "$PREFIX_DIR/dosdevices"
ln -s ../drive_c c:
ln -s / z:
cd - > /dev/null

# Create minimal registry files
cat > "$PREFIX_DIR/system.reg" << 'EOF'
WINE REGISTRY Version 2
;; All keys relative to \\Machine

#arch=win64

[Software\\Wine] 1700000000
"Version"="wine-10.0"

[Software\\Wine\\Direct3D] 1700000000
"csmt"=dword:00000001
"renderer"="vulkan"

[System\\CurrentControlSet\\Control\\Session Manager\\Environment] 1700000000
"TEMP"="C:\\windows\\temp"
"TMP"="C:\\windows\\temp"
EOF

cat > "$PREFIX_DIR/user.reg" << 'EOF'
WINE REGISTRY Version 2
;; All keys relative to \\User

#arch=win64

[Software\\Wine\\DllOverrides] 1700000000
"*d3d12"="native"
"*dxgi"="native"
"*d3d11"="native"
EOF

cat > "$PREFIX_DIR/userdef.reg" << 'EOF'
WINE REGISTRY Version 2

#arch=win64
EOF

# Create .update-timestamp
date +%s > "$PREFIX_DIR/.update-timestamp"

# Compress prefix
echo "  - Compressing prefix template..."
cd "$WORK_DIR/prefix_build"
tar --zstd -cf "$WCP_BUILD_DIR/prefixPack.tzst" .wine
cd - > /dev/null

echo ""
echo "[4/6] Creating profile.json..."

# Determine version name and code
# For GE-Proton10-25: versionName="GE-Proton-10.25-x86_64", versionCode=0
VERSION_NAME=$(echo "$PROTON_VERSION" | sed 's/-/./')
VERSION_NAME="GE-Proton-${VERSION_NAME}-x86_64"

cat > "$WCP_BUILD_DIR/profile.json" << EOF
{
  "type": "Proton",
  "versionName": "${VERSION_NAME}",
  "versionCode": 0,
  "description": "GE-Proton ${PROTON_VERSION} - Community-enhanced Proton with additional game fixes and performance improvements (glibc x86_64)",
  "files": [],
  "variant": "glibc",
  "proton": {
    "binPath": "bin",
    "libPath": "lib",
    "prefixPack": "prefixPack.tzst"
  }
}
EOF

echo "Profile metadata:"
cat "$WCP_BUILD_DIR/profile.json"

echo ""
echo "[5/6] Creating .wcp archive..."
cd "$WCP_BUILD_DIR"
tar -cJf "$OUTPUT_WCP" .
cd - > /dev/null

# Get final file size
WCP_SIZE=$(du -h "$OUTPUT_WCP" | cut -f1)

echo ""
echo "[6/6] Verification..."
echo "  - Listing .wcp contents..."
tar -tJf "$OUTPUT_WCP" | head -20
echo "  ..."
echo "  Total files: $(tar -tJf "$OUTPUT_WCP" | wc -l)"

echo ""
echo "=========================================="
echo "✅ Conversion Complete!"
echo "=========================================="
echo "Output file: $OUTPUT_WCP"
echo "Size: $WCP_SIZE"
echo ""
echo "Next steps:"
echo "1. Test the .wcp file by importing it in GameNative"
echo "2. Create a glibc container and select this Proton version"
echo "3. Test with a game to verify Wine functionality"
echo ""
