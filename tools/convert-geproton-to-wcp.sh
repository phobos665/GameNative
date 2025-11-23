#!/bin/bash
# Script to convert GE-Proton tar.zst packages to GameNative .wcp format
# Usage: ./convert-geproton-to-wcp.sh <input.tar.zst> [output.wcp]

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Helper functions
error() {
    echo -e "${RED}ERROR: $1${NC}" >&2
    exit 1
}

info() {
    echo -e "${BLUE}INFO: $1${NC}"
}

success() {
    echo -e "${GREEN}SUCCESS: $1${NC}"
}

warning() {
    echo -e "${YELLOW}WARNING: $1${NC}"
}

# Check arguments
if [ $# -lt 1 ]; then
    error "Usage: $0 <input.tar.zst> [output.wcp]"
fi

INPUT_FILE="$1"
if [ ! -f "$INPUT_FILE" ]; then
    error "Input file not found: $INPUT_FILE"
fi

# Determine output filename
if [ $# -ge 2 ]; then
    OUTPUT_FILE="$2"
else
    # Extract base name and create .wcp filename
    BASENAME=$(basename "$INPUT_FILE" .tar.zst)
    OUTPUT_FILE="${BASENAME}.wcp"
fi

info "Converting $INPUT_FILE to $OUTPUT_FILE"

# Create temporary working directory
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

info "Extracting archive to temporary directory..."
tar -xzf "$INPUT_FILE" -C "$TEMP_DIR"

# Find the extracted directory (usually the only subdirectory)
EXTRACTED_DIR=$(find "$TEMP_DIR" -mindepth 1 -maxdepth 1 -type d | head -n 1)
if [ -z "$EXTRACTED_DIR" ]; then
    error "No directory found in archive"
fi

PROTON_NAME=$(basename "$EXTRACTED_DIR")
info "Found Proton directory: $PROTON_NAME"

# Validate required structure
if [ ! -d "$EXTRACTED_DIR/files/bin" ]; then
    error "files/bin directory not found in archive"
fi

if [ ! -d "$EXTRACTED_DIR/files/lib/wine" ]; then
    error "files/lib/wine directory not found in archive"
fi

# Check for wine64 or wine binary
if [ ! -f "$EXTRACTED_DIR/files/bin/wine64" ] && [ ! -f "$EXTRACTED_DIR/files/bin/wine" ]; then
    error "No wine or wine64 binary found in files/bin/"
fi

# Extract version information
VERSION_FILE="$EXTRACTED_DIR/version"
if [ -f "$VERSION_FILE" ]; then
    VERSION_INFO=$(cat "$VERSION_FILE")
    info "Version info: $VERSION_INFO"
else
    warning "No version file found, using directory name"
    VERSION_INFO="$PROTON_NAME"
fi

# Parse version from filename (e.g., GE-Proton10-25 -> 10.25)
if [[ $PROTON_NAME =~ GE-Proton([0-9]+)-([0-9]+) ]]; then
    VERSION_MAJOR="${BASH_REMATCH[1]}"
    VERSION_MINOR="${BASH_REMATCH[2]}"
    VERSION="${VERSION_MAJOR}.${VERSION_MINOR}"
    VERSION_NAME="GE-Proton${VERSION_MAJOR}-${VERSION_MINOR}"
else
    # Fallback to directory name
    VERSION_NAME="$PROTON_NAME"
    VERSION="1.0"
fi

info "Detected version: $VERSION"
info "Version name: $VERSION_NAME"

# Detect binary variant (glibc vs bionic)
WINE_BINARY="$EXTRACTED_DIR/files/bin/wine64"
if [ ! -f "$WINE_BINARY" ]; then
    WINE_BINARY="$EXTRACTED_DIR/files/bin/wine"
fi

info "Detecting binary variant from $WINE_BINARY..."
VARIANT="unknown"
if strings "$WINE_BINARY" | grep -q "/system/bin/linker"; then
    VARIANT="bionic"
    info "Detected variant: bionic (Android)"
elif strings "$WINE_BINARY" | grep -q "/lib64/ld-linux"; then
    VARIANT="glibc"
    info "Detected variant: glibc (Linux)"
elif strings "$WINE_BINARY" | grep -q "/lib/ld-linux"; then
    VARIANT="glibc"
    info "Detected variant: glibc (Linux)"
else
    warning "Could not detect variant, defaulting to glibc"
    VARIANT="glibc"
fi

# Create profile.json
PROFILE_JSON="$EXTRACTED_DIR/profile.json"
info "Creating profile.json..."

cat > "$PROFILE_JSON" << EOF
{
  "type": "Proton",
  "versionName": "$VERSION_NAME",
  "versionCode": 0,
  "description": "$PROTON_NAME ($VARIANT)",
  "variant": "$VARIANT",
  "wine": {
    "binPath": "files/bin",
    "libPath": "files/lib/wine",
    "prefixPack": null
  },
  "files": []
}
EOF

success "Created profile.json"
cat "$PROFILE_JSON"

# Check if protonfixes exist
PROTONFIXES_DIR="$EXTRACTED_DIR/protonfixes"
INCLUDE_PROTONFIXES=false
if [ -d "$PROTONFIXES_DIR" ]; then
    GAMEFIX_COUNT=$(find "$PROTONFIXES_DIR" -name "*.py" | wc -l | tr -d ' ')
    if [ "$GAMEFIX_COUNT" -gt 0 ]; then
        INCLUDE_PROTONFIXES=true
        info "Found $GAMEFIX_COUNT game fixes in protonfixes/"
    fi
fi

# Create the .wcp package (tar.xz format)
info "Creating .wcp package..."
cd "$EXTRACTED_DIR"

# Include Wine binaries, libraries, and protonfixes (game fixes)
if [ "$INCLUDE_PROTONFIXES" = true ]; then
    tar -cJf "$TEMP_DIR/temp.wcp" \
        profile.json \
        files/bin \
        files/lib/wine \
        protonfixes
    success "Included protonfixes with $GAMEFIX_COUNT game-specific fixes"
else
    tar -cJf "$TEMP_DIR/temp.wcp" \
        profile.json \
        files/bin \
        files/lib/wine
    warning "No protonfixes found - compatibility may be limited"
fi

cd - > /dev/null

# Move to final location
mv "$TEMP_DIR/temp.wcp" "$OUTPUT_FILE"

# Get file sizes
INPUT_SIZE=$(du -h "$INPUT_FILE" | cut -f1)
OUTPUT_SIZE=$(du -h "$OUTPUT_FILE" | cut -f1)

success "Conversion complete!"
info "Input:  $INPUT_FILE ($INPUT_SIZE)"
info "Output: $OUTPUT_FILE ($OUTPUT_SIZE)"
info "Variant: $VARIANT"
info "Version: $VERSION_NAME"

echo ""
echo -e "${GREEN}Package ready for import into GameNative!${NC}"
echo -e "${BLUE}Import instructions:${NC}"
echo "  1. Copy $OUTPUT_FILE to your Android device"
echo "  2. Open GameNative → Settings → Wine/Proton Manager"
echo "  3. Select 'Import Wine/Proton Package'"
echo "  4. Choose the .wcp file"
echo ""
echo -e "${YELLOW}Note: This is a $VARIANT package.${NC}"
if [ "$VARIANT" = "glibc" ]; then
    echo "  - Glibc packages run via Box64 (x86_64 translation)"
    echo "  - Performance: ~60-70% of native (translation overhead)"
    echo "  - Compatibility: Excellent for x86_64 Windows apps"
fi
if [ "$INCLUDE_PROTONFIXES" = true ]; then
    echo ""
    echo -e "${GREEN}✓ Game compatibility fixes included ($GAMEFIX_COUNT fixes)${NC}"
    echo "  - Protonfixes provide automatic game-specific tweaks"
    echo "  - Handles missing DLLs, registry settings, and workarounds"
    echo "  - Compatible with 1000+ Steam games"
fi
