# GE-Proton to WCP Converter

This tool converts GE-Proton tar.zst packages to GameNative's .wcp (Wine Container Package) format.

## Requirements

- `bash` shell
- `tar` with zstd and xz support
- `strings` utility (part of binutils)

## Usage

### Basic Conversion

```bash
./convert-geproton-to-wcp.sh <input.tar.zst> [output.wcp]
```

**Examples:**

```bash
# Convert with auto-generated output filename
./convert-geproton-to-wcp.sh GE-Proton10-25.tar.zst
# Creates: GE-Proton10-25.wcp

# Convert with custom output filename
./convert-geproton-to-wcp.sh GE-Proton10-25.tar.zst custom-proton.wcp
```

### Batch Conversion

To convert multiple GE-Proton packages:

```bash
for file in GE-Proton*.tar.zst; do
    ./convert-geproton-to-wcp.sh "$file"
done
```

## What the Script Does

1. **Extracts** the tar.zst archive to a temporary directory
2. **Validates** required structure (files/bin, files/lib/wine)
3. **Detects** binary variant (glibc vs bionic) by analyzing ELF headers
4. **Parses** version information from filename and version file
5. **Creates** profile.json with metadata
6. **Packages** essential files into tar.xz (.wcp) format

## Package Structure

The resulting .wcp package contains:

```
profile.json                # Metadata for GameNative
files/
  bin/                      # Wine executables (wine, wine64, wineserver, etc.)
  lib/
    wine/                   # Wine libraries
      x86_64-windows/       # Windows DLLs (64-bit)
      i386-windows/         # Windows DLLs (32-bit)
      x86_64-unix/          # Unix libraries (64-bit)
      i386-unix/            # Unix libraries (32-bit)
      dxvk/                 # DXVK graphics layer (if present)
      vkd3d-proton/         # VKD3D DirectX 12 (if present)
      nvapi/                # NVIDIA API wrappers (if present)
      icu/                  # ICU libraries (if present)
```

## profile.json Format

```json
{
  "type": "Proton",
  "versionName": "GE-Proton10-25",
  "versionCode": 0,
  "description": "GE-Proton10-25 (glibc)",
  "variant": "glibc",
  "wine": {
    "binPath": "files/bin",
    "libPath": "files/lib/wine",
    "prefixPack": null
  },
  "files": []
}
```

### Fields Explanation

- **type**: "Proton" or "Wine"
- **versionName**: Identifier used internally (e.g., "GE-Proton10-25")
- **versionCode**: Incremental version number (0 for first import)
- **description**: Human-readable description
- **variant**: "glibc" (x86_64 via Box64) or "bionic" (ARM64 native)
- **wine.binPath**: Path to Wine binaries within package
- **wine.libPath**: Path to Wine libraries within package
- **wine.prefixPack**: Path to prefix tarball (null for glibc packages)
- **files**: Array of file mappings (empty for Wine/Proton)

## Binary Variant Detection

The script automatically detects the binary variant:

- **glibc**: Linux x86_64 binaries (requires Box64 translation on Android)
  - Detected by `/lib64/ld-linux` or `/lib/ld-linux` in ELF interpreter
  - Performance: ~60-70% of native (translation overhead)
  - Best for: Maximum x86_64 compatibility

- **bionic**: Android ARM64 native binaries
  - Detected by `/system/bin/linker` in ELF interpreter
  - Performance: 100% native
  - Best for: ARM64 Windows apps and games

## Size Comparison

GE-Proton packages are significantly compressed when converted to .wcp:

- **Original tar.zst**: ~460 MB (includes protonfixes, bundled libraries)
- **Converted .wcp**: ~113 MB (Wine binaries + libraries only)
- **Compression ratio**: ~75% reduction

The script excludes:
- protonfixes/ directory (to be ported separately)
- files/lib/x86_64-linux-gnu/ (bundled system libraries)
- Launcher scripts and metadata

## Installation on GameNative

1. Copy the .wcp file to your Android device
2. Open GameNative → Settings → Wine/Proton Manager
3. Select "Import Wine/Proton Package"
4. Choose the .wcp file
5. Wait for installation to complete

## Troubleshooting

### Error: "files/bin directory not found"
- Ensure the input file is a valid GE-Proton package
- Check that the archive extracted correctly

### Error: "No wine or wine64 binary found"
- The package may be corrupted
- Try re-downloading the GE-Proton release

### Warning: "Could not detect variant"
- The script will default to "glibc"
- You can manually edit profile.json before installation if needed

## Advanced Usage

### Custom Version Code

To create multiple versions of the same Wine/Proton:

1. Convert the package
2. Extract the .wcp: `tar -xJf package.wcp`
3. Edit `profile.json` and increment `versionCode`
4. Repackage: `tar -cJf package-v2.wcp profile.json files/`

### Manual Profile Editing

Extract, edit, and repackage:

```bash
# Extract
tar -xJf GE-Proton10-25.wcp

# Edit profile.json
nano profile.json

# Repackage
tar -cJf GE-Proton10-25-custom.wcp profile.json files/
```

## Supported GE-Proton Versions

This script is tested with:
- GE-Proton 8.x
- GE-Proton 9.x
- GE-Proton 10.x

It should work with any GE-Proton release that follows the standard structure.

## Notes

- **prefixPack is null**: Glibc packages don't need a Wine prefix pack since they run in a full Linux environment via PRoot
- **DXVK/VKD3D included**: If present in the source package, graphics layers are preserved
- **No file validation**: The script doesn't validate individual DLLs (GameNative does this during installation)

## See Also

- [GE-Proton Releases](https://github.com/GloriousEggroll/proton-ge-custom/releases)
- [GameNative Documentation](../docs/)
- [Wine/Proton Import Guide](../docs/PREFIXED_WINE_PROTON_SUPPORT.md)
