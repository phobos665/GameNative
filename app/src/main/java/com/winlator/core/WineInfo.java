package com.winlator.core;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.xenvironment.ImageFs;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.gamenative.R;

public class WineInfo implements Parcelable {

    public static final WineInfo MAIN_WINE_VERSION = new WineInfo("wine", "9.2", "x86_64");
    private static final Pattern pattern = Pattern.compile("^(wine|proton|Proton)\\-([0-9]+(?:\\.[0-9]+)*)\\-?([0-9\\.]+)?\\-(x86|x86_64|arm64ec)$");
    public final String version;
    public final String type;
    public String subversion;
    public final String path;
    public String libPath; // Path to lib directory from profile.json (e.g., "lib" or "lib/wine")
    private String arch;

    public WineInfo(String type, String version, String arch) {
        this.type = type;
        this.version = version;
        this.subversion = null;
        this.arch = arch;
        this.path = null;
        this.libPath = null;
    }

    public WineInfo(String type, String version, String subversion, String arch, String path) {
        this.type = type;
        this.version = version;
        this.subversion = subversion != null && !subversion.isEmpty() ? subversion : null;
        this.arch = arch;
        this.path = path;
        this.libPath = null;
    }

    public WineInfo(String type, String version, String arch, String path) {
        this.type = type;
        this.version = version;
        this.arch = arch;
        this.path = path;
        this.libPath = null;
    }

    private WineInfo(Parcel in) {
        type = in.readString();
        version = in.readString();
        subversion = in.readString();
        arch = in.readString();
        path = in.readString();
        libPath = in.readString();
    }

    public String getArch() {
        return arch;
    }

    public void setArch(String arch) {
        this.arch = arch;
    }

    public boolean isWin64() {
        return arch.equals("x86_64") || arch.equals("arm64ec");
    }

    public boolean isArm64EC() {
        return arch.equals("arm64ec");
    }

    public boolean isMainWineVersion() {
        WineInfo other = WineInfo.MAIN_WINE_VERSION;

        boolean pathMatches
                = (path == null && other.path == null)
                || (path != null && path.equals(other.path));

        return type.equals(other.type)
                && version.equals(other.version)
                && arch.equals(other.arch)
                && pathMatches;
    }

    public String getExecutable(Context context, boolean wow64Mode) {
        if (this == MAIN_WINE_VERSION) {
            File wineBinDir = new File(ImageFs.find(context).getRootDir(), "/opt/wine/bin");
            File wineBinFile = new File(wineBinDir, "wine");
            File winePreloaderBinFile = new File(wineBinDir, "wine-preloader");
            FileUtils.copy(new File(wineBinDir, wow64Mode ? "wine-wow64" : "wine32"), wineBinFile);
            FileUtils.copy(new File(wineBinDir, wow64Mode ? "wine-preloader-wow64" : "wine32-preloader"), winePreloaderBinFile);
            FileUtils.chmod(wineBinFile, 0771);
            FileUtils.chmod(winePreloaderBinFile, 0771);
            return wow64Mode ? "wine" : "wine64";
        } else {
            return (new File(path, "/bin/wine64")).isFile() ? "wine64" : "wine";
        }
    }

    public String identifier() {
        if (type.equals("proton")) {
            return "proton-" + fullVersion() + "-" + arch;
        } else {
            return "wine-" + fullVersion() + "-" + arch;
        }
    }

    public String fullVersion() {
        return version + (subversion != null ? "-" + subversion : "");
    }

    @NonNull
    @Override
    public String toString() {
        if (type.equals("proton")) {
            return "Proton " + fullVersion() + (this == MAIN_WINE_VERSION ? " (Custom)" : "");
        } else {
            return "Wine " + fullVersion() + (this == MAIN_WINE_VERSION ? " (Custom)" : "");
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<WineInfo> CREATOR = new Parcelable.Creator<WineInfo>() {
        public WineInfo createFromParcel(Parcel in) {
            return new WineInfo(in);
        }

        public WineInfo[] newArray(int size) {
            return new WineInfo[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(type);
        dest.writeString(version);
        dest.writeString(subversion);
        dest.writeString(arch);
        dest.writeString(path);
        dest.writeString(libPath);
    }

    @NonNull
    public static WineInfo fromIdentifier(Context context, ContentsManager contentsManager, String identifier) {
        // Handle main Wine version
        if (identifier.equals(MAIN_WINE_VERSION.identifier())) {
            return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, null);
        }

        // Try to find profile using multiple lookup strategies
        ContentProfile wineProfile = findWineProfile(contentsManager, identifier);
        if (wineProfile == null) {
            Log.w("WineInfo", "   ⚠️ No profile found for identifier: '" + identifier + "'");
        }

        // Parse identifier components using regex
        Matcher matcher = pattern.matcher(identifier);
        if (!matcher.find()) {
            Log.w("WineInfo", "   ❌ Identifier doesn't match pattern, falling back to main Wine");
            // Identifier doesn't match expected pattern, fall back to main Wine
            return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, null);
        }

        String type = matcher.group(1);
        String version = matcher.group(2);
        String arch = matcher.group(4);
        String path = null;

        // Check if it's a built-in Wine version (bionic or glibc)
        if (wineProfile == null) {
            Log.d("WineInfo", "   No profile found, checking built-in versions");
            ImageFs imageFs = ImageFs.find(context);

            // Check bionic Wine versions
            String[] bionicWineVersions = context.getResources().getStringArray(R.array.bionic_wine_entries);
            for (String wineVersion : bionicWineVersions) {
                if (wineVersion.contains(identifier)) {
                    path = imageFs.getRootDir().getPath() + "/opt/" + identifier;
                    break;
                }
            }

            // Check glibc Wine versions (also in /opt, not /opt/glibc)
            if (path == null) {
                String[] glibcWineVersions = context.getResources().getStringArray(R.array.glibc_wine_entries);
                for (String wineVersion : glibcWineVersions) {
                    if (wineVersion.contains(identifier)) {
                        path = imageFs.getRootDir().getPath() + "/opt/" + identifier;
                        break;
                    }
                }
            }

            Log.d("WineInfo", "   Returning WineInfo: type=" + type + ", version=" + version + ", arch=" + arch + ", path=" + path);
            return new WineInfo(type, version, arch, path);
        }

        // Use imported Wine/Proton profile
        if (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            path = ContentsManager.getInstallDir(context, wineProfile).getPath();
            WineInfo wineInfo = new WineInfo(type, version, arch, path);
            wineInfo.libPath = wineProfile.wineLibPath;
            return wineInfo;
        }

        Log.d("WineInfo", "   Returning default WineInfo: type=" + type + ", version=" + version + ", arch=" + arch + ", path=" + path);
        return new WineInfo(type, version, arch, path);
    }

    /**
     * Attempts to find a Wine/Proton profile using multiple lookup strategies.
     * Tries various identifier formats to handle different naming conventions.
     */
    private static ContentProfile findWineProfile(ContentsManager contentsManager, String identifier) {
        ContentProfile profile;

        // Try: identifier-0 (with version code 0)
        profile = contentsManager.getProfileByEntryName(identifier + "-0");
        if (profile != null) {
            return profile;
        }

        // Try: identifier as-is
        profile = contentsManager.getProfileByEntryName(identifier);
        if (profile != null) {
            return profile;
        }

        // Try: capitalized identifier with version codes 0-10 (supports prefixed builds like "GE-Proton")
        String lowerIdentifier = identifier.toLowerCase();
        if (lowerIdentifier.contains("proton") || lowerIdentifier.contains("wine")) {
            String capitalizedIdentifier = Character.toUpperCase(identifier.charAt(0)) + identifier.substring(1);
            for (int verCode = 0; verCode <= 10; verCode++) {
                profile = contentsManager.getProfileByEntryName(capitalizedIdentifier + "-" + verCode);
                if (profile != null) {
                    return profile;
                }
            }
        }

        // Try: dots replaced with dashes
        if (identifier.contains(".")) {
            String identifierWithDashes = identifier.replace('.', '-');
            profile = contentsManager.getProfileByEntryName(identifierWithDashes + "-0");
            if (profile != null) {
                return profile;
            }

            profile = contentsManager.getProfileByEntryName(identifierWithDashes);
            if (profile != null) {
                return profile;
            }
        }

        Log.d("WineInfo", "Could not find appropriate wine profile for: " + identifier);
        return null;
    }

    public static boolean isMainWineVersion(String wineVersion) {
        return wineVersion == null || wineVersion.equals(MAIN_WINE_VERSION.identifier());
    }
}
