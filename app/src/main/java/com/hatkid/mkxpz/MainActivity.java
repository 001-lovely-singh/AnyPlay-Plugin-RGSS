package com.hatkid.mkxpz;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.os.storage.StorageManager;
import android.os.storage.OnObbStateChangeListener;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import java.util.Locale;
import java.util.Arrays;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.libsdl.app.SDL;
import org.libsdl.app.SDLActivity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import com.anyplay.overlay.AnyPlayUi;

public class MainActivity extends SDLActivity
{
    // This activity inherits from SDLActivity activity.
    // Put your Java-side stuff here.

    private static final String TAG = "mkxp-z[Activity]";
    private static final String GAME_PATH_DEFAULT = Environment.getExternalStorageDirectory() + "/mkxp-z";
    private static String GAME_PATH = GAME_PATH_DEFAULT;
    private static String OBB_MAIN_FILENAME;
    private static boolean DEBUG = false;

    // ---- AnyPlay integration -------------------------------------------
    // AnyPlay starts this activity with action "com.anyplay.rgss.LAUNCH" and the
    // full game folder in the string extra "game_dir". When it is present we use
    // that folder INSTEAD of the fixed /storage/emulated/0/mkxp-z one. Opening
    // the app normally (launcher icon) still uses the default folder.
    private static final String EXTRA_GAME_DIR = "game_dir";
    private static final String EXTRA_RUNTIME_MODE = "runtime_mode";
    private boolean mCustomGameDir = false;

    // ---- AnyPlay: play straight from a .zip (nothing is extracted) -----------------
    // AnyPlay starts this activity with the SAME action and these extras instead of
    // "game_dir":
    //   game_zip       absolute path of the .zip
    //   game_zip_root  folder INSIDE the zip that holds Game.ini / Data ("" = top level;
    //                  when the extra is missing the plugin looks for it itself)
    //   save_dir       writable folder for saves (AnyPlay/Saves/<zipName>/)
    // The game's files are mounted from the zip by the engine (PhysFS). The save folder
    // becomes the working folder of the process, so save_data / File.open("Save1...")
    // / AnyPlayLog.txt all land there and the zip itself is never touched.
    // The native side learns about all this from environment variables (see
    // applyGameZipFromIntent), the same way it already learns the runtime reason.
    private static final String EXTRA_GAME_ZIP = "game_zip";
    private static final String EXTRA_GAME_ZIP_ROOT = "game_zip_root";
    private static final String EXTRA_SAVE_DIR = "save_dir";
    private static String sGameZip = null;           // zip being played, null = folder game
    private static ZipGameInfo sZipInfo = null;      // what was read from that zip
    private String mLaunchError = null;              // set when the launch cannot continue
    private boolean mNeedAllFiles = false;           // launch stopped: "All files access" not granted yet

    // Runtime ids. A runtime = one libruby + one engine library, loaded together.
    // One process = one runtime: Ruby cannot be initialised twice in a process,
    // so a different runtime always needs a fresh process (see onNewIntent).
    //   r3   : Ruby 3.1, no compatibility patches      (default)
    //   r18  : Ruby 3.1 + Ruby 1.8 compatibility layer  (game code sees Ruby 1.8.7)
    //   r34  : Ruby 3.4 (+ YJIT on arm64 if the APK was built with it). Never picked
    //          automatically; only by runtime_mode / anyplay_runtime.txt, and only if
    //          the APK really contains it (otherwise r3 is used and the log says so).
    private static final String RT_R3 = "r3";
    private static final String RT_R18 = "r18";
    private static final String RT_R34 = "r34";    // optional: only in APKs built with BUILD_R34=1
    private static String sRuntimeId = RT_R3;
    private static String sNativeLibDir = null;    // set in onCreate / onNewIntent
    private static String sRuntimeReason = "";

    // Which runtime "auto" gives to every game that does NOT need Ruby 1.8 behaviour
    // (RGSS2/RGSS3 games, modern Essentials, unidentified games). Games that need Ruby 1.8
    // behaviour always get RT_R18 (see RuntimeProbe for how that is decided).
    // Default is RT_R3. Set it to RT_R34 to send those games to Ruby 3.4 instead; that only
    // works when the APK was built with r34 (workflow option build_r34). Without r34 in the
    // APK the choice falls back to r3 and AnyPlayLog says so.
    private static final String MODERN_AUTO_RUNTIME = RT_R3;

    protected boolean mStarted = false;

    private StorageManager mStorageManager;

    // AnyPlay overlay (gamepad, keyboard, loading spinner, Close-game dialog)
    private AnyPlayUi mUi;
    private static MainActivity sInstance;

    private void runSDLThread()
    {
        if (!mStarted) {
            Log.i(TAG, "Game path: " + GAME_PATH);
        }

        mStarted = true;

        // Run (resume) native SDL thread
        if (mHasMultiWindow) {
            resumeNativeThread();
        }
    }

    OnObbStateChangeListener obbListener = new OnObbStateChangeListener()
    {
        @Override
        public void onObbStateChange(String path, int state)
        {
            super.onObbStateChange(path, state);

            Log.v(TAG, "OBB state of " + path + " changed to " + state);

            switch (state)
            {
                case OnObbStateChangeListener.MOUNTED:
                    String obbPath = mStorageManager.getMountedObbPath(path);
                    Log.v(TAG, "OBB " + path + " is mounted to " + obbPath);
                    GAME_PATH = obbPath;
                    break;

                case OnObbStateChangeListener.UNMOUNTED:
                    Log.v(TAG, "OBB " + path + " is unmounted");
                    GAME_PATH = GAME_PATH_DEFAULT;
                    break;

                default:
                    Log.e(TAG, "Failed to mount OBB " + path + ": Got state " + state);
                    break;
            }

            runSDLThread();
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (requestCode == 110) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                // Close the App because the User did not allow the all files access permission to be used.
                mSingleton.finish();
            }
        }
    }

    // ---------------------------------------------------------------- zip games

    /**
     * Handles "game_zip" / "game_zip_root" / "save_dir". Returns true when the intent is a
     * zip launch (even a failed one: mLaunchError is then set and the activity closes),
     * false when it is not, so the caller falls back to "game_dir".
     *
     * Effects of a good zip launch:
     *   - GAME_PATH = the save folder. The native code makes GAME_PATH the working folder,
     *     so everything a game writes with plain Ruby file calls lands there.
     *   - Environment variables for the native side:
     *       ANYPLAY_GAME_ZIP        absolute path of the zip
     *       ANYPLAY_GAME_ZIP_ROOT   "" or "Folder/" inside the zip
     *       ANYPLAY_ZIP_INI         copy of the game's Game.ini ("" if none)
     *       ANYPLAY_ZIP_MKXP_JSON   copy of the game's mkxp.json ("" if none)
     *     The two copies live in the plugin's cache folder: the engine reads its settings
     *     before its file system exists, so it cannot read them out of the zip itself.
     */
    private boolean applyGameZipFromIntent(Intent intent)
    {
        sGameZip = null;
        sZipInfo = null;
        setNativeEnv("ANYPLAY_GAME_ZIP", null);
        setNativeEnv("ANYPLAY_GAME_ZIP_ROOT", null);
        setNativeEnv("ANYPLAY_ZIP_INI", null);
        setNativeEnv("ANYPLAY_ZIP_MKXP_JSON", null);

        if (intent == null) return false;
        String zip = intent.getStringExtra(EXTRA_GAME_ZIP);
        if (zip == null || zip.isEmpty()) return false;

        File zipFile = new File(zip);
        if (!zipFile.isFile()) {
            mLaunchError = "Zip file not found: " + zipFile.getName();
            return true;
        }

        File saveDir = pickSaveDir(this, zipFile, intent.getStringExtra(EXTRA_SAVE_DIR));
        if (saveDir == null) {
            mLaunchError = "No writable folder for saves of " + zipFile.getName();
            return true;
        }

        ZipGameInfo info;
        try {
            info = ZipGameInfo.read(zipFile, intent.getStringExtra(EXTRA_GAME_ZIP_ROOT));
        } catch (Exception e) {
            Log.e(TAG, "Could not read zip " + zip, e);
            mLaunchError = "Could not read " + zipFile.getName() + ": " + e.getMessage();
            return true;
        }
        if (info.problem != null) {
            mLaunchError = zipFile.getName() + " cannot be played from the zip: " + info.problem;
            return true;
        }

        // Copies of the two small config files (see the method comment).
        String iniPath = "";
        String jsonPath = "";
        try {
            File cfg = new File(getCacheDir(), "anyplay_zipcfg");
            cfg.mkdirs();
            File ini = new File(cfg, "Game.ini");
            File json = new File(cfg, "mkxp.json");
            if (info.iniBytes != null) {
                writeBytes(ini, info.iniBytes);
                iniPath = ini.getAbsolutePath();
            } else {
                ini.delete();
            }
            if (info.jsonBytes != null) {
                writeBytes(json, info.jsonBytes);
                jsonPath = json.getAbsolutePath();
            } else {
                json.delete();
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not write the config copies", e);
            mLaunchError = "Could not prepare " + zipFile.getName() + ": " + e.getMessage();
            return true;
        }

        setNativeEnv("ANYPLAY_GAME_ZIP", zipFile.getAbsolutePath());
        setNativeEnv("ANYPLAY_GAME_ZIP_ROOT", info.root);
        setNativeEnv("ANYPLAY_ZIP_INI", iniPath);
        setNativeEnv("ANYPLAY_ZIP_MKXP_JSON", jsonPath);

        GAME_PATH = saveDir.getAbsolutePath();
        mCustomGameDir = true;
        sGameZip = zipFile.getAbsolutePath();
        sZipInfo = info;
        Log.i(TAG, "Zip game from AnyPlay: " + sGameZip + " root=\"" + info.root + "\" saves=" + GAME_PATH);
        return true;
    }

    /**
     * Picks the folder for the saves of a zip game: the one AnyPlay asked for, or, when
     * that cannot be written to, a folder in the plugin's own storage. Returns null only
     * when nothing works. A real write test is used because File.canWrite() is not
     * reliable on shared storage.
     */
    private static File pickSaveDir(Context ctx, File zip, String requested)
    {
        File[] candidates = new File[2];
        if (requested != null && !requested.isEmpty()) candidates[0] = new File(requested);

        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        String safe = zip.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        candidates[1] = new File(base, "saves/" + safe);

        for (File dir : candidates) {
            if (dir == null) continue;
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) continue;
            File probe = new File(dir, ".anyplay_write_test");
            try {
                FileOutputStream out = new FileOutputStream(probe);
                out.close();
                probe.delete();
                return dir;
            } catch (IOException e) {
                Log.w(TAG, "Save folder not writable: " + dir + " (" + e.getMessage() + ")");
            }
        }
        return null;
    }

    private static void writeBytes(File f, byte[] data) throws IOException
    {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    /** Sets (value != null) or removes an environment variable of this process. */
    private static void setNativeEnv(String name, String value)
    {
        try {
            if (value == null) android.system.Os.unsetenv(name);
            else android.system.Os.setenv(name, value, true);
        } catch (Exception e) {
            Log.w(TAG, "Could not set " + name + ": " + e);
        }
    }

    /**
     * Reads "game_dir" from the launching intent and, if valid, makes it the
     * game folder. The native side reads the static GAME_PATH field when the SDL
     * thread starts (see main.cpp), so this must run before that (onCreate).
     */
    private void applyGameDirFromIntent(Intent intent)
    {
        if (intent == null) return;

        String dir = intent.getStringExtra(EXTRA_GAME_DIR);
        if (dir == null || dir.isEmpty()) return;

        File f = new File(dir);
        if (!f.isDirectory()) {
            Log.e(TAG, "game_dir does not exist or is not a folder: " + dir);
            return; // fall back to the default folder; native side shows an error if that is missing too
        }

        GAME_PATH = f.getAbsolutePath();
        mCustomGameDir = true;
        Log.i(TAG, "Game folder from AnyPlay: " + GAME_PATH);
    }

    /**
     * Maps the "runtime_mode" extra to a runtime id, or null when the value is
     * missing or unknown.
     *   r3,  ruby3   -> r3
     *   r18, ruby18, r187 -> r18  (Ruby 1.8 compatibility runtime; the real Ruby
     *                          1.8.7 build was removed in Phase B, so the old
     *                          names the launcher may still send map to r18)
     *   r34, ruby34, ruby3_plus, ruby3plus -> r34  (Ruby 3.4)
     */
    private static String runtimeFromMode(String mode)
    {
        if (mode == null) return null;
        switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "r3":
            case "ruby3":
                return RT_R3;
            case "r18":
            case "ruby18":
            case "r187":
                return RT_R18;
            case "r34":
            case "ruby34":
            case "ruby3_plus":
            case "ruby3plus":
                return RT_R34;
            default:
                return null;
        }
    }

    /** The runtime picked for a game and a short ASCII reason for the log. */
    private static final class RuntimeChoice
    {
        final String id;
        final String reason;

        RuntimeChoice(String id, String reason)
        {
            this.id = id;
            this.reason = reason;
        }
    }

    private static boolean isAutoMode(String mode)
    {
        if (mode == null) return true;
        String m = mode.trim().toLowerCase(Locale.ROOT);
        return m.isEmpty() || m.equals("auto");
    }

    /**
     * Decides the runtime for a game folder. Order (first match wins):
     *   1. runtime_mode extra from the launcher, when it names a runtime (not "auto")
     *   2. anyplay_runtime.txt in the game folder (r3 / r18 / auto), per-game override
     *   3. auto-detect. RGSS2/RGSS3 and unknown games -> MODERN_AUTO_RUNTIME. RGSS1 games are
     *      probed by RuntimeProbe: Essentials version in the scripts (<= 18 legacy, >= 19
     *      modern), else the exe ProductVersion (1.x = legacy, Kirin's rule), else modern.
     *      "legacy" = r18, "modern" = MODERN_AUTO_RUNTIME.
     *   4. nothing identifiable -> MODERN_AUTO_RUNTIME (the safe default)
     * Used both at start-up and by onNewIntent, so both always agree.
     */
    private static RuntimeChoice resolveRuntime(String requestedMode, String gameDir, ZipGameInfo zip)
    {
        RuntimeChoice c = resolveRuntimeRaw(requestedMode, gameDir, zip);
        if (!isRuntimeInstalled(c.id)) {
            return new RuntimeChoice(RT_R3, "wanted " + c.id + " (" + c.reason + ") but this APK does not contain it,"
                    + " using r3");
        }
        return c;
    }

    /** r3 and r18 are always in the APK. r34 only when it was built with BUILD_R34=1. */
    private static boolean isRuntimeInstalled(String id)
    {
        if (!RT_R34.equals(id)) return true;
        if (sNativeLibDir == null) return false;
        String[] libs = runtimeLibs(id);
        for (String lib : libs) {
            if (!new File(sNativeLibDir, "lib" + lib + ".so").isFile()) return false;
        }
        return true;
    }

    private static RuntimeChoice resolveRuntimeRaw(String requestedMode, String gameDir, ZipGameInfo zip)
    {
        String warn = "";

        // 1. launcher
        String id = runtimeFromMode(requestedMode);
        if (id != null) {
            return new RuntimeChoice(id, "launcher runtime_mode=" + requestedMode.trim());
        }
        if (!isAutoMode(requestedMode)) {
            warn = " (unknown runtime_mode \"" + requestedMode + "\" ignored)";
        }

        File dir = (gameDir == null || gameDir.isEmpty()) ? null : new File(gameDir);

        // 2. per-game override file
        String override = RuntimeDetector.readOverride(dir);
        if (override != null && !override.equals("auto")) {
            String oid = runtimeFromMode(override);
            if (oid != null) {
                return new RuntimeChoice(oid, "game folder " + RuntimeDetector.OVERRIDE_FILE + "=" + override + warn);
            }
            warn += " (unknown value \"" + override + "\" in " + RuntimeDetector.OVERRIDE_FILE + " ignored)";
        }

        // 3. auto-detect
        // (zip game: from the zip's own file list; the save folder `dir` has no game files)
        RuntimeDetector.Detection d = (zip != null) ? RuntimeDetector.detectZip(zip) : RuntimeDetector.detect(dir);
        if (d.rgss == 1) {
            // RPG Maker XP format. Old (Ruby 1.8) and modern (Ruby 3) games share it, so look closer.
            RuntimeProbe.Verdict v = (zip != null) ? RuntimeProbe.probeRgss1Zip(zip) : RuntimeProbe.probeRgss1(dir);
            return new RuntimeChoice(v.legacy ? RT_R18 : MODERN_AUTO_RUNTIME,
                    "auto: " + d.reason + "; " + v.reason + warn);
        }
        if (d.rgss == 2 || d.rgss == 3) {
            return new RuntimeChoice(MODERN_AUTO_RUNTIME, "auto: " + d.reason + warn);
        }
        return new RuntimeChoice(MODERN_AUTO_RUNTIME, "auto: unknown, safe default (" + d.reason + ")" + warn);
    }

    /**
     * Select a native VM before SDL loads any libraries (see resolveRuntime for the
     * rules). The result is also exported as the environment variable
     * ANYPLAY_RUNTIME_REASON so that AnyPlayLog can print why this runtime was chosen.
     */
    private void selectRubyRuntime(Intent intent)
    {
        String requested = intent == null ? null : intent.getStringExtra(EXTRA_RUNTIME_MODE);
        RuntimeChoice c = resolveRuntime(requested, GAME_PATH, sZipInfo);
        sRuntimeId = c.id;
        sRuntimeReason = c.reason;
        Log.i(TAG, "Runtime " + c.id + " chosen: " + c.reason);
        // Facts about the game folder go into the same log header line (AnyPlayLog prints it).
        String facts = (sZipInfo != null)
                ? RuntimeProbe.describeZip(sZipInfo)
                : RuntimeProbe.describeFolder(GAME_PATH == null ? null : new File(GAME_PATH));
        Log.i(TAG, facts);
        try {
            android.system.Os.setenv("ANYPLAY_RUNTIME_REASON", c.reason + " | " + facts, true);
        } catch (Exception e) {
            Log.w(TAG, "Could not export ANYPLAY_RUNTIME_REASON: " + e);
        }
    }

    /**
     * File names (without "lib" and ".so") of the Ruby library and the engine
     * library of a runtime. The Ruby library is listed BEFORE the engine on purpose:
     * the engine records "NEEDED libruby.so", and Android's linker satisfies that with
     * the library that is already loaded and has that internal SONAME. The APK has no
     * file named libruby.so, so a wrong runtime can never be picked up by accident.
     */
    private static String[] runtimeLibs(String id)
    {
        if (RT_R18.equals(id)) return new String[] { "ruby_r18", "mkxp-z-r18" };
        if (RT_R34.equals(id)) return new String[] { "ruby_r34", "mkxp-z-r34" };
        return new String[] { "ruby_r3", "mkxp-z-r3" };
    }

    @Override
    protected String[] getLibraries()
    {
        String[] rt = runtimeLibs(sRuntimeId);
        return new String[] {
            "SDL2", "SDL2_image", "SDL2_ttf", "SDL2_sound", "openal",
            rt[0], rt[1]
        };
    }

    /** The engine library of the selected runtime (the one that exports SDL_main). */
    @Override
    protected String getMainSharedObject()
    {
        String engine = runtimeLibs(sRuntimeId)[1];
        return getContext().getApplicationInfo().nativeLibraryDir + "/lib" + engine + ".so";
    }

    @Override
    public void loadLibraries()
    {
        Log.i(TAG, "Loading runtime " + sRuntimeId + ": " + Arrays.toString(getLibraries()));
        for (String lib : getLibraries()) {
            try {
                SDL.loadLibrary(lib);
            } catch (UnsatisfiedLinkError e) {
                // Typical message if the SONAME match did not work:
                //   dlopen failed: library "libruby.so" not found: needed by ...
                Log.e(TAG, "Could not load native library \"" + lib + "\" (runtime "
                        + sRuntimeId + "): " + e.getMessage());
                throw e;
            }
        }
    }

    /**
     * The activity is singleTask. If it is still alive (user pressed Home) and
     * AnyPlay asks for a DIFFERENT game, Ruby cannot be re-initialised in the same
     * process, so we close and the next launch starts a fresh process.
     */
    @Override
    protected void onNewIntent(Intent intent)
    {
        super.onNewIntent(intent);

        sNativeLibDir = getApplicationInfo().nativeLibraryDir;
        String zip = intent.getStringExtra(EXTRA_GAME_ZIP);
        boolean zipLaunch = zip != null && !zip.isEmpty();
        String dir = intent.getStringExtra(EXTRA_GAME_DIR);
        String mode = intent.getStringExtra(EXTRA_RUNTIME_MODE);
        boolean anyplayLaunch = zipLaunch || (dir != null && !dir.isEmpty()) || (mode != null && !mode.isEmpty());

        boolean otherGame;
        String wantedDir;
        ZipGameInfo wantedZip = null;
        if (zipLaunch) {
            // A zip game is "the same game" only when it is the same zip file.
            String running = sGameZip == null ? null : new File(sGameZip).getAbsolutePath();
            otherGame = running == null || !new File(zip).getAbsolutePath().equals(running);
            if (!otherGame) {
                wantedZip = sZipInfo;
                wantedDir = GAME_PATH;
            } else {
                File save = pickSaveDir(this, new File(zip), intent.getStringExtra(EXTRA_SAVE_DIR));
                wantedDir = save == null ? GAME_PATH : save.getAbsolutePath();
                try {
                    wantedZip = ZipGameInfo.read(new File(zip), intent.getStringExtra(EXTRA_GAME_ZIP_ROOT));
                } catch (Exception e) {
                    Log.w(TAG, "onNewIntent: could not read zip " + zip + ": " + e);
                }
            }
        } else {
            otherGame = dir != null && !dir.isEmpty()
                    && !new File(dir).getAbsolutePath().equals(GAME_PATH);
            wantedDir = (dir != null && !dir.isEmpty()) ? dir : GAME_PATH;
            if (!otherGame) wantedZip = sZipInfo;   // e.g. a USB intent while a zip game runs
        }

        // Same rules as selectRubyRuntime() (resolveRuntime), applied to the game of the
        // NEW launch. Only checked for a real AnyPlay launch, so e.g. a USB-attach
        // intent never closes a running game.
        String wanted = anyplayLaunch ? resolveRuntime(mode, wantedDir, wantedZip).id : sRuntimeId;
        boolean otherRuntime = anyplayLaunch && !wanted.equals(sRuntimeId);

        if (otherGame || otherRuntime) {
            Log.i(TAG, "New launch needs a fresh process (otherGame=" + otherGame
                    + ", otherRuntime=" + otherRuntime + ", running=" + sRuntimeId + ", wanted=" + wanted + ")");
            Toast.makeText(this, "Closing the running game. Open the new game again.", Toast.LENGTH_LONG).show();
            finish(); // onDestroy() exits the process, so the next launch is clean
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        // Must be before super.onCreate(): picks the game folder before SDL starts.
        //
        // FIX: the "All files access" check used to come AFTER the zip had been read, so on
        // a fresh install the zip (e.g. on the SD card) was opened without permission
        // (EACCES) and the save folder could not be written (EPERM). Now it is checked FIRST.
        // If it is missing we open the settings page, close, and the user presses play again.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            mNeedAllFiles = true;
            mLaunchError = "Allow \"All files access\" for AnyPlay RGSS Plugin, then press play again";
        } else if (!applyGameZipFromIntent(getIntent())) {
            applyGameDirFromIntent(getIntent());
        }
        sNativeLibDir = getApplicationInfo().nativeLibraryDir;
        if (mLaunchError == null) {
            selectRubyRuntime(getIntent());
        }

        super.onCreate(savedInstanceState);

        if (mLaunchError != null) {
            // The zip cannot be played directly (missing, encrypted archive inside, ...).
            Log.e(TAG, "Launch aborted: " + mLaunchError);
            Toast.makeText(this, mLaunchError, Toast.LENGTH_LONG).show();
            if (mNeedAllFiles && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    Intent perm = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    perm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(perm);
                } catch (Exception e) {
                    Log.w(TAG, "Could not open the All files access page: " + e);
                    try {
                        Intent all = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        all.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(all);
                    } catch (Exception ignored) { }
                }
            }
            finish();
            return;
        }

        mStorageManager = (StorageManager) getSystemService(STORAGE_SERVICE);

        // Get main OBB filepath
        final String obbPrefix = "main"; // "main", "patch"
        final int obbVersion = 1;
        OBB_MAIN_FILENAME = getObbDir() + "/" + obbPrefix + "." + obbVersion + "." + getPackageName() + ".obb";

        // Get Debug flag
        try {
            ActivityInfo actInfo = getPackageManager().getActivityInfo(this.getComponentName(), PackageManager.GET_META_DATA);
            DEBUG = actInfo.metaData.getBoolean("mkxp_debug");
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Failed to set debug flag: " + e);
            e.printStackTrace();
        }

        hideSystemUi();

        // AnyPlay overlay on top of the game surface
        sInstance = this;
        if (mLayout != null) {
            mUi = new AnyPlayUi(this, mLayout, GAME_PATH);
        }
    }

    /**
     * Called from native code (graphics.cpp) when the game draws its first frame.
     * Removes the loading spinner.
     */
    @SuppressWarnings("unused")
    public static void onGameFirstFrame()
    {
        MainActivity a = sInstance;
        if (a != null && a.mUi != null) a.mUi.onFirstFrame();
    }

    /**
     * Real full screen: hides the status bar and the gesture/navigation bar
     * (they reappear for a moment when the user swipes from the edge) and draws
     * under the camera notch. Without this the game area was cut at the top/bottom.
     */
    private void hideSystemUi()
    {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onPause()
    {
        if (mUi != null) mUi.onPause();
        super.onPause();
    }

    @Override
    protected void onStart()
    {
        super.onStart();

        if (!mStarted) {
            // Check for main OBB file
            if (!mCustomGameDir && new File(OBB_MAIN_FILENAME).exists()) {
                Log.v(TAG, "Main OBB file found, starting with main OBB mount");

                // Try to mount main OBB file
                mStorageManager.mountObb(OBB_MAIN_FILENAME, null, obbListener);
            } else {
                Log.v(TAG, "Main OBB file not found, starting without main OBB mount");

                // Run from default game directory
                runSDLThread();
            }
        } else {
            // onStart: Resume SDL thread
            runSDLThread();
        }
    }

    @Override
    protected void onDestroy()
    {
        if (mUi != null) mUi.onDestroy();
        sInstance = null;
        super.onDestroy();

        // HACK: Exiting the JVM (process) since Ruby does not likes when we
        // trying to re-initialize Ruby VM in mkxp-z (JNI native library)
        // that leads to segmentation fault, even we have cleanup the Ruby VM.
        System.exit(0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent evt)
    {
        // Back button: show the "Close game?" dialog (same as the AnyPlay app)
        if (evt.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (evt.getAction() == KeyEvent.ACTION_UP && mUi != null) {
                mUi.onBack();
            }
            return true;
        }
        return super.dispatchKeyEvent(evt);
    }

    /**
     * This method is for arguments for launching native mkxp-z.
     * 
     * @return arguments for the mkxp-z
     */
    @Override
    protected String[] getArguments()
    {
        String[] args;

        if (DEBUG) {
            // Arguments in Debug mode
            args = new String[] { "debug" };
        } else {
            // Arguments in normal mode
            args = new String[] {};
        }

        return args;
    }

    /**
     * This static method is used in native mkxp-z. (see systemImpl.cpp)
     * This method returns a string of current device locale tag. (e.g. "en_US")
     * 
     * @return string of locale tag
     */
    @SuppressWarnings("unused")
    private static String getSystemLanguage()
    {
        return Locale.getDefault().toString();
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method returns a boolean indicating that the device has a vibrator or not.
     * 
     * @return boolean
     */
    @SuppressWarnings("unused")
    private static boolean hasVibrator()
    {
        Vibrator vib = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        return vib.hasVibrator();
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method makes device vibrating with given milliseconds duration.
     * 
     * @param duration milliseconds duration of vibration
     */
    @SuppressWarnings("unused")
    private static void vibrate(int duration)
    {
        Vibrator vib = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.EFFECT_HEAVY_CLICK));
        } else {
            vib.vibrate(duration);
        }
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method turns off the current device vibration.
     */
    @SuppressWarnings("unused")
    private static void vibrateStop()
    {
        Vibrator vib = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        vib.cancel();
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method returns a boolean indicating the app is in multi window mode or not.
     * (Multi-window mode supports from Android 7.0 Nougat (API 24) and higher.)
     * 
     * @param activity current MainActivity instance
     * @return boolean
     */
    @SuppressWarnings("unused")
    private static boolean inMultiWindow(Activity activity)
    {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode();
    }
}
