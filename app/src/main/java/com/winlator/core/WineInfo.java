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
    private static final Pattern pattern = Pattern
            .compile("^(wine|proton|Proton)\\-([0-9\\.]+)(?:\\-([0-9\\.]+))?\\-(x86|x86_64|arm64ec)(?:\\-([0-9]+))?$");
    public final String version;
    public final String type;
    public String subversion;
    public final String path;
    private String arch;
    private final String binPath;
    private final String libPath;
    private final String wineDllPath;

    public WineInfo(String type, String version, String arch) {
        this.type = type;
        this.version = version;
        this.subversion = null;
        this.arch = arch;
        this.path = null;
        this.binPath = "bin";
        this.libPath = "lib";
        this.wineDllPath = "lib/wine";
    }

    public WineInfo(String type, String version, String subversion, String arch, String path) {
        this.type = type;
        this.version = version;
        this.subversion = subversion != null && !subversion.isEmpty() ? subversion : null;
        this.arch = arch;
        this.path = path;
        this.binPath = "bin";
        this.libPath = "lib";
        this.wineDllPath = "lib/wine";
    }

    public WineInfo(String type, String version, String arch, String path) {
        this.type = type;
        this.version = version;
        this.arch = arch;
        this.path = path;
        this.binPath = "bin";
        this.libPath = "lib";
        this.wineDllPath = "lib/wine";
    }

    public WineInfo(String type, String version, String arch, String path,
            String binPath, String libPath, String wineDllPath) {
        this.type = type;
        this.version = version;
        this.subversion = null;
        this.arch = arch;
        this.path = path;
        this.binPath = binPath != null ? binPath : "bin";
        this.libPath = libPath != null ? libPath : "lib";
        this.wineDllPath = wineDllPath != null ? wineDllPath : "lib/wine";
    }

    public WineInfo(String type, String version, String subversion, String arch, String path,
            String binPath, String libPath, String wineDllPath) {
        this.type = type;
        this.version = version;
        this.subversion = subversion != null && !subversion.isEmpty() ? subversion : null;
        this.arch = arch;
        this.path = path;
        this.binPath = binPath != null ? binPath : "bin";
        this.libPath = libPath != null ? libPath : "lib";
        this.wineDllPath = wineDllPath != null ? wineDllPath : "lib/wine";
    }

    private WineInfo(Parcel in) {
        type = in.readString();
        version = in.readString();
        subversion = in.readString();
        arch = in.readString();
        path = in.readString();
        binPath = in.readString();
        libPath = in.readString();
        wineDllPath = in.readString();
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

    public String getFullBinPath() {
        return path != null ? path + "/" + binPath : null;
    }

    public String getFullLibPath() {
        return path != null ? path + "/" + libPath : null;
    }

    public String getFullWineDllPath() {
        return path != null ? path + "/" + wineDllPath : null;
    }

    public boolean isMainWineVersion() {
        WineInfo other = WineInfo.MAIN_WINE_VERSION;

        boolean pathMatches = (path == null && other.path == null) ||
                (path != null && path.equals(other.path));

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
            FileUtils.copy(new File(wineBinDir, wow64Mode ? "wine-preloader-wow64" : "wine32-preloader"),
                    winePreloaderBinFile);
            FileUtils.chmod(wineBinFile, 0771);
            FileUtils.chmod(winePreloaderBinFile, 0771);
            return wow64Mode ? "wine" : "wine64";
        } else {
            // Use flexible bin path
            String fullBinPath = getFullBinPath();
            if (fullBinPath != null) {
                File wine64 = new File(fullBinPath, "wine64");
                if (wine64.isFile())
                    return "wine64";

                File wine = new File(fullBinPath, "wine");
                if (wine.isFile())
                    return "wine";
            }

            // Fallback to old behavior for backward compatibility
            return (new File(path, "/bin/wine64")).isFile() ? "wine64" : "wine";
        }
    }

    public String identifier() {
        if (type.equals("proton"))
            return "proton-" + fullVersion() + "-" + arch;
        else
            return "wine-" + fullVersion() + "-" + arch;
    }

    public String fullVersion() {
        return version + (subversion != null ? "-" + subversion : "");
    }

    @NonNull
    @Override
    public String toString() {
        if (type.equals("proton"))
            return "Proton " + fullVersion() + (this == MAIN_WINE_VERSION ? " (Custom)" : "");
        else
            return "Wine " + fullVersion() + (this == MAIN_WINE_VERSION ? " (Custom)" : "");
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
        dest.writeString(binPath);
        dest.writeString(libPath);
        dest.writeString(wineDllPath);
    }

    @NonNull
    public static WineInfo fromIdentifier(Context context, ContentsManager contentsManager, String identifier) {
        ImageFs imageFs = ImageFs.find(context);
        String path = "";

        Log.d("WineInfo", "Creating WineInfo from identifier " + identifier);

        if (identifier.equals(MAIN_WINE_VERSION.identifier()))
            return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, null);

        ContentProfile wineProfile = contentsManager.getProfileByEntryName(identifier);

        if (wineProfile != null && (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            identifier = identifier.substring(0, identifier.length() - 2).toLowerCase();
        }

        Matcher matcher = pattern.matcher(identifier);

        if (matcher.find()) {
            String[] wineVersions = context.getResources().getStringArray(R.array.bionic_wine_entries);
            for (String wineVersion : wineVersions) {
                if (wineVersion.contains(identifier)) {
                    path = imageFs.getRootDir().getPath() + "/opt/" + identifier;
                    break;
                }
            }

            if (wineProfile != null && (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                    || wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
                path = contentsManager.getInstallDir(context, wineProfile).getPath();
                
                // Use flexible paths from profile, with defaults for backward compatibility
                String binPath = (wineProfile.wineBinPath != null && !wineProfile.wineBinPath.isEmpty()) 
                    ? wineProfile.wineBinPath : "bin";
                String libPath = (wineProfile.wineLibPath != null && !wineProfile.wineLibPath.isEmpty()) 
                    ? wineProfile.wineLibPath : "lib";
                String wineDllPath = (wineProfile.wineDllPath != null && !wineProfile.wineDllPath.isEmpty()) 
                    ? wineProfile.wineDllPath : libPath + "/wine";
                
                Log.d("WineInfo", "Creating WineInfo with flexible paths: bin=" + binPath + ", lib=" + libPath + ", dll=" + wineDllPath);
                return new WineInfo(matcher.group(1), matcher.group(2), matcher.group(4), path, binPath, libPath, wineDllPath);
            }

            return new WineInfo(matcher.group(1), matcher.group(2), matcher.group(4), path);
        } else
            return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, null);
    }

    public static boolean isMainWineVersion(String wineVersion) {
        return wineVersion == null || wineVersion.equals(MAIN_WINE_VERSION.identifier());
    }
}
