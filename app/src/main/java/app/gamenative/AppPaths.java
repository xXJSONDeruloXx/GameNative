package app.gamenative;

import android.content.ComponentName;
import android.content.Context;
import android.os.Process;

import com.winlator.xenvironment.ImageFs;

import java.io.File;

/**
 * Centralizes package/app-ID-derived paths and intent/component names.
 */
public final class AppPaths {
    public static final String LEGACY_APPLICATION_ID = "app.gamenative";

    private AppPaths() {}

    public static String getLaunchGameAction() {
        return BuildConfig.APPLICATION_ID + ".LAUNCH_GAME";
    }

    public static String getScopedAction(String suffix) {
        return BuildConfig.APPLICATION_ID + "." + suffix;
    }

    public static String getAppDataDir() {
        try {
            int userId = Math.max(0, Process.myUid() / 100000);
            return "/data/user/" + userId + "/" + BuildConfig.APPLICATION_ID;
        } catch (Throwable ignored) {
            return "/data/data/" + BuildConfig.APPLICATION_ID;
        }
    }

    public static String getAppDataDir(Context context) {
        File parent = context.getFilesDir().getParentFile();
        return parent != null ? parent.getPath() : getAppDataDir();
    }

    public static String getLegacyAppDataDir() {
        return "/data/data/" + LEGACY_APPLICATION_ID;
    }

    public static String getStorageDir() {
        return getAppDataDir() + "/storage";
    }

    public static String getStorageDir(Context context) {
        return getAppDataDir(context) + "/storage";
    }

    public static String getImageFsRootPath(Context context) {
        return ImageFs.find(context).getRootDir().getPath();
    }

    public static String getImageFsTmpPath(Context context) {
        return new File(ImageFs.find(context).getRootDir(), "tmp").getPath();
    }

    public static String getImageFsDevInputDirPath(Context context) {
        return new File(ImageFs.find(context).getRootDir(), "dev/input").getPath();
    }

    public static String getImageFsLdLinuxAarch64Path(Context context) {
        return getAppDataDir(context) + "/files/imagefs/usr/lib/ld-linux-aarch64.so.1";
    }

    public static String getImageFsGamepadMemPath(Context context, int playerIndex) {
        String suffix = playerIndex <= 1 ? "" : String.valueOf(playerIndex - 1);
        return getImageFsTmpPath(context) + "/gamepad" + suffix + ".mem";
    }

    public static String getDefaultDrives() {
        return "D:" + android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS) +
                "E:" + getStorageDir();
    }

    public static String[] getMediaconvEnvVars() {
        String imageFsHome = getAppDataDir() + "/files/imagefs/home/xuser";
        return new String[] {
                "MEDIACONV_AUDIO_DUMP_FILE=" + imageFsHome + "/audio.dmp",
                "MEDIACONV_VIDEO_DUMP_FILE=" + imageFsHome + "/video.dmp",
                "MEDIACONV_VIDEO_TRANSCODED_FILE=" + imageFsHome + "/transcoded.mkv",
                "MEDIACONV_AUDIO_TRANSCODED_FILE=" + imageFsHome + "/transcoded.wav",
                "MEDIACONV_BLANK_AUDIO_FILE=" + imageFsHome + "/blank.wav",
                "MEDIACONV_BLANK_VIDEO_FILE=" + imageFsHome + "/blank.mkv",
        };
    }

    public static ComponentName getDefaultLauncherAlias(Context context) {
        return new ComponentName(context.getPackageName(), context.getPackageName() + ".MainActivityAliasDefault");
    }

    public static ComponentName getAltLauncherAlias(Context context) {
        return new ComponentName(context.getPackageName(), context.getPackageName() + ".MainActivityAliasAlt");
    }
}
