# GE-Proton 10-25 .wcp Conversion Summary

## Conversion Details

**Date:** November 23, 2025
**Source:** `GE-Proton10-25.tar.zst`
**Output:** `ge-proton-10-25-x86_64.wcp`
**Tool:** `tools/convert-geproton-to-wcp.sh`

## Package Information

- **Type:** Proton (glibc build)
- **Version Name:** GE-Proton-10.25-x86_64
- **Version Code:** 0
- **Architecture:** x86_64 (glibc)
- **Variant:** glibc
- **Size:** 417 MB
- **File Count:** 8,658 files

## Structure

```
ge-proton-10-25-x86_64.wcp
├── profile.json              # Metadata
├── bin/                      # Wine executables (wine, wineserver, etc.)
├── lib/                      # Wine libraries
│   └── wine/
│       ├── x86_64-unix/      # Native x86_64 Unix libraries
│       ├── x86_64-windows/   # 64-bit Windows PE DLLs
│       └── i386-windows/     # 32-bit Windows PE DLLs (WoW64)
├── share/                    # Resources, fonts, configs
│   ├── wine/                 # Wine resources
│   ├── fonts/                # TrueType fonts
│   ├── espeak-ng-data/       # Text-to-speech data
│   └── ...
└── prefixPack.tzst           # Wine prefix template
```

## profile.json

```json
{
  "type": "Proton",
  "versionName": "GE-Proton-10.25-x86_64",
  "versionCode": 0,
  "description": "GE-Proton 10-25 - Community-enhanced Proton with additional game fixes and performance improvements (glibc x86_64)",
  "files": [],
  "variant": "glibc",
  "proton": {
    "binPath": "bin",
    "libPath": "lib",
    "prefixPack": "prefixPack.tzst"
  }
}
```

## Key Features

1. **Glibc Build**: Full x86_64 glibc build for Linux compatibility
2. **WoW64 Support**: Includes i386-windows libraries for 32-bit game support
3. **Complete Wine Stack**: All Wine executables, libraries, and resources
4. **Minimal Prefix**: Pre-configured Wine prefix template with essential registry settings
5. **Game-Ready**: Community fixes and enhancements from GE-Proton

## Testing Instructions

1. **Import the .wcp file:**
   - Open GameNative
   - Navigate to Wine/Proton Manager
   - Import `ge-proton-10-25-x86_64.wcp`

2. **Create a glibc container:**
   - Create new container
   - Set variant to "glibc"
   - Select "GE-Proton-10.25-x86_64" as Wine version

3. **Test with a game:**
   - Configure game in container
   - Launch and verify Wine functionality
   - Check for graphics, audio, input support

## Comparison with Bionic Build

| Feature | Bionic (proton-10.0-arm64ec) | Glibc (ge-proton-10-25-x86_64) |
|---------|------------------------------|----------------------------------|
| Architecture | ARM64EC | x86_64 |
| C Library | Android Bionic | GNU C Library (glibc) |
| Execution | PRoot + Box64 | Native (Box64 for emulation) |
| Size | 251 MB | 417 MB |
| Files | 1,761 | 8,658 |
| WoW64 | Yes | Yes |
| Launcher | BionicProgramLauncherComponent | GlibcProgramLauncherComponent |

## Known Limitations

1. **Requires Box64**: Glibc x86_64 builds need Box64 for ARM64 devices
2. **Larger Size**: More files and dependencies than bionic builds
3. **First Import**: May take time due to file count and compression

## Future Enhancements

1. **GE-Proton Fixes Database**: Extract protonfixes to JSON (see GE_PROTON_FIXES_PORTING_DESIGN.md)
2. **Automatic Updates**: Check for new GE-Proton releases
3. **Multiple Variants**: Support different GE-Proton versions (stable, experimental)
4. **Size Optimization**: Remove unnecessary files for mobile use

## References

- [GE-Proton GitHub](https://github.com/GloriousEggroll/proton-ge-custom)
- [Proton File Structure Reference](docs/PROTON_FILE_STRUCTURE_REFERENCE.md)
- [GE-Proton Fixes Design](GE_PROTON_FIXES_PORTING_DESIGN.md)
