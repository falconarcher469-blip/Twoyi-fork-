/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.util.DisplayMetrics;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @author weishu
 * @date 2021/10/22.
 */

public final class RomManager {

    private static final String TAG = "RomManager";

    private static final String ROOTFS_NAME = "rootfs.7z";

    private static final String ROM_INFO_FILE = "rom.ini";

    private static final String DEFAULT_INFO = "unknown";

    private static final String LOADER_FILE = "libloader.so";

    private static final String CUSTOM_ROM_FILE_NAME = "rootfs_3rd.7z";

    private RomManager() {
    }

    public static void initRootfs(Context context) {
        File propFile = getVendorPropFile(context);
        String language = Locale.getDefault().getLanguage();
        String country = Locale.getDefault().getCountry();

        Properties properties = new Properties();

        properties.setProperty("persist.sys.language", language);
        properties.setProperty("persist.sys.country", country);

        TimeZone timeZone = TimeZone.getDefault();
        String timeZoneID = timeZone.getID();
        Log.i(TAG, "timezone: " + timeZoneID);
        properties.setProperty("persist.sys.timezone", timeZoneID);

        properties.setProperty("ro.sf.lcd_density", String.valueOf(DisplayMetrics.DENSITY_DEVICE_STABLE));

        try (Writer writer = new FileWriter(propFile)) {
            properties.store(writer, null);
        } catch (IOException ignored) {
        }
    }

    public static void ensureBootFiles(Context context) {

        // <rootdir>/dev/
        File devDir = new File(getRootfsDir(context), "dev");
        ensureDir(new File(devDir, "input"));
        ensureDir(new File(devDir, "socket"));
        ensureDir(new File(devDir, "maps"));

        ensureDir(new File(context.getDataDir(), "socket"));

        createLoaderSymlink(context);

        killOrphanProcess();

        saveLastKmsg(context);
    }

    private static void createLoaderSymlink(Context context) {
        Path loaderSymlink = new File(context.getDataDir(), "loader64").toPath();
        String loaderPath = getLoaderPath(context);
        try {
            Files.deleteIfExists(loaderSymlink);
            Files.createSymbolicLink(loaderSymlink, Paths.get(loaderPath));
        } catch (IOException e) {
            throw new RuntimeException("symlink loader failed.", e);
        }
    }

    private static void killOrphanProcess() {
        Shell shell = ShellUtil.newSh();
        shell.newJob().add("ps -ef | awk '{if($3==1) print $2}' | xargs kill -9").exec();
    }

    private static void saveLastKmsg(Context context) {
        File lastKmsgFile = LogEvents.getLastKmsgFile(context);
        File kmsgFile = LogEvents.getKmsgFile(context);
        try {
            Files.move(kmsgFile.toPath(), lastKmsgFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    public static class RomInfo {
        public String author = DEFAULT_INFO;
        public String version = DEFAULT_INFO;
        public String desc = DEFAULT_INFO;
        public String md5 = "";
        public long code = 0;

        @Override
        public String toString() {
            return "RomInfo{" +
                    "author='" + author + '\'' +
                    ", version='" + version + '\'' +
                    ", md5='" + md5 + '\'' +
                    ", code=" + code +
                    '}';
        }

        public boolean isValid() {
            return this != DEFAULT_ROM_INFO;
        }
    }

    public static final RomInfo DEFAULT_ROM_INFO = new RomInfo();

    public static boolean romExist(Context context) {
        File initFile = new File(getRootfsDir(context), "init");
        return initFile.exists();
    }

    public static boolean needsUpgrade(Context context) {
        RomInfo currentRomInfo = getCurrentRomInfo(context);
        Log.i(TAG, "current rom: " + currentRomInfo);
        if (currentRomInfo.equals(DEFAULT_ROM_INFO)) {
            return true;
        }

        RomInfo romInfoFromAssets = getRomInfoFromAssets(context);
        Log.i(TAG, "asset rom: " + romInfoFromAssets);
        return romInfoFromAssets.code > currentRomInfo.code;
    }

    public static RomInfo getCurrentRomInfo(Context context) {
        File infoFile = new File(getRootfsDir(context), ROM_INFO_FILE);
        try (FileInputStream inputStream = new FileInputStream(infoFile)) {
            return getRomInfo(inputStream);
        } catch (Throwable e) {
            return DEFAULT_ROM_INFO;
        }
    }

    public static String getLoaderPath(Context context) {
        ApplicationInfo applicationInfo = context.getApplicationInfo();
        return new File(applicationInfo.nativeLibraryDir, LOADER_FILE).getAbsolutePath();
    }

    public static RomInfo getRomInfo(File rom) {
        try (SevenZFile zFile = new SevenZFile(rom)) {

            SevenZArchiveEntry entry;

            while ((entry = zFile.getNextEntry()) != null) {
                if (entry.getName().equals("rootfs/rom.ini")) {
                    byte[] content = new byte[(int) entry.getSize()];
                    zFile.read(content, 0, content.length);
                    ByteArrayInputStream bais = new ByteArrayInputStream(content);
                    return getRomInfo(bais);
                }
            }
        } catch (Throwable e) {
            LogEvents.trackError(e);
        }
        return DEFAULT_ROM_INFO;
    }

    public static RomInfo getRomInfoFromAssets(Context context) {
        AssetManager assets = context.getAssets();
        try (InputStream open = assets.open(ROM_INFO_FILE)) {
            return getRomInfo(open);
        } catch (Throwable ignored) {
        }
        return DEFAULT_ROM_INFO;
    }

    public static void extractRootfs(Context context, boolean romExist, boolean needsUpgrade, boolean forceInstall, boolean use3rdRom) {

        // force remove system dir to avoiding wired issues
        removeSystemPartition(context);
        removeVendorPartition(context);

        if (!romExist) {
            // first init
            if (extractRootfsInAssets(context)) {
                patchRootfsFor64BitOnly(context);
                provisionRootAndXposed(context);
            }
            return;
        }

        if (forceInstall) {
            if (use3rdRom) {
                // install 3rd rom
                boolean success = extract3rdRootfs(context);
                if (!success) {
                    showRootfsInstallationFailure(context);
                    return;
                }
            } else {
                // factory reset!!
                if (!extractRootfsInAssets(context)) {
                    showRootfsInstallationFailure(context);
                    return;
                }
            }

            patchRootfsFor64BitOnly(context);
            provisionRootAndXposed(context);

            // force install finish, reset the state.
            AppKV.setBooleanConfig(context, AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        } else {
            if (use3rdRom) {
                Log.w(TAG, "WTF? 3rd ROM must be force install!");
            }
            if (needsUpgrade) {
                Log.i(TAG, "upgrade factory rom..");
                if (!extractRootfsInAssets(context)) {
                    showRootfsInstallationFailure(context);
                } else {
                    patchRootfsFor64BitOnly(context);
                    provisionRootAndXposed(context);
                }
            } else {
                ensureProvisioned(context);
            }
        }
    }

    private static void showRootfsInstallationFailure(Context context) {
        // TODO
    }

    public static void reboot(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        context.getApplicationContext().startActivity(intent);

        shutdown(context);
    }

    public static void shutdown(Context context) {
        try {
            io.twoyi.TwoyiService.stop(context);
        } catch (Throwable ignored) {
        }
        System.exit(0);
        Process.killProcess(Process.myPid());
    }

    public static boolean extract3rdRootfs(Context context) {
        File rootfs3rd = get3rdRootfsFile(context);
        if (!rootfs3rd.exists()) {
            return false;
        }
        int err = extractRootfs(context, rootfs3rd);
        return err == 0;
    }

    public static int extractRootfs(Context context, File rootfs7z) {
        if (rootfs7z == null || !rootfs7z.exists()) {
            Log.e(TAG, "rootfs archive missing: " + rootfs7z);
            return -1;
        }
        File destDir = context.getDataDir();
        Path destRoot = destDir.toPath().toAbsolutePath().normalize();
        byte[] buffer = new byte[32768];
        try (SevenZFile sevenZFile = new SevenZFile(rootfs7z)) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZFile.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                Path targetPath = destRoot.resolve(name).normalize();
                if (!targetPath.startsWith(destRoot)) {
                    throw new IOException("Blocked zip-slip entry: " + name);
                }
                File outFile = targetPath.toFile();
                if (entry.isDirectory()) {
                    ensureDir(outFile);
                    chmodSafe(outFile.getAbsolutePath(), 0755);
                    continue;
                }
                File parent = outFile.getParentFile();
                if (parent != null) {
                    ensureDir(parent);
                }

                int unixMode = entry.getHasWindowsAttributes()
                        ? ((entry.getWindowsAttributes() >>> 16) & 0xFFFF)
                        : 0;
                boolean isSymlink = (unixMode & 0170000) == 0120000;
                if (isSymlink) {
                    byte[] linkBytes = readAllEntryBytes(sevenZFile, entry);
                    String linkTarget = new String(linkBytes, StandardCharsets.UTF_8).trim();
                    Files.deleteIfExists(targetPath);
                    Files.createSymbolicLink(targetPath, Paths.get(linkTarget));
                    continue;
                }

                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                    int read;
                    while ((read = sevenZFile.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
                int perms = unixMode & 07777;
                if (perms == 0) {
                    perms = inferDefaultMode(name);
                }
                chmodSafe(outFile.getAbsolutePath(), perms);
            }
            return 0;
        } catch (Throwable e) {
            Log.e(TAG, "extractRootfs failed", e);
            return -1;
        }
    }

    private static byte[] readAllEntryBytes(SevenZFile sevenZFile, SevenZArchiveEntry entry) throws IOException {
        long size = entry.getSize();
        if (size <= 0 || size > 4096) {
            return new byte[0];
        }
        byte[] data = new byte[(int) size];
        int offset = 0;
        while (offset < data.length) {
            int r = sevenZFile.read(data, offset, data.length - offset);
            if (r < 0) {
                break;
            }
            offset += r;
        }
        return data;
    }

    private static int inferDefaultMode(String entryName) {
        if (entryName.endsWith("/init") || entryName.equals("init")
                || entryName.contains("/bin/") || entryName.contains("/xbin/")
                || entryName.endsWith(".so")) {
            return 0755;
        }
        return 0644;
    }

    private static void chmodSafe(String path, int mode) {
        try {
            Os.chmod(path, mode);
        } catch (Throwable e) {
            IOUtils.setPermissions(path, mode, -1, -1);
        }
    }

    public static void ensureProvisioned(Context context) {
        File marker = new File(getRootfsDir(context), ".aether_provisioned");
        if (!marker.exists() && romExist(context)) {
            patchRootfsFor64BitOnly(context);
            provisionRootAndXposed(context);
        }
    }

    public static void patchRootfsFor64BitOnly(Context context) {
        File rootfsDir = getRootfsDir(context);
        if (!rootfsDir.exists()) {
            return;
        }

        // 1. Patch build.prop / default.prop so guest runs strictly 64-bit zygote64
        File[] propFiles = new File[]{
                new File(rootfsDir, "system/build.prop"),
                new File(rootfsDir, "vendor/build.prop"),
                new File(rootfsDir, "default.prop")
        };
        for (File propFile : propFiles) {
            if (!propFile.exists()) {
                continue;
            }
            patchBuildProp64Only(propFile);
        }

        // 2. Patch init.rc and init.zygote*.rc to eliminate zygote_secondary / app_process32
        File initRc = new File(rootfsDir, "init.rc");
        if (initRc.exists()) {
            String content = IOUtils.readContent(initRc);
            if (content != null) {
                String updated = content
                        .replace("import /init.zygote64_32.rc", "import /init.zygote64.rc")
                        .replace("import /init.zygote32_64.rc", "import /init.zygote64.rc")
                        .replace("import /init.zygote32.rc", "import /init.zygote64.rc");
                if (!updated.equals(content)) {
                    IOUtils.writeContent(initRc, updated + "\n");
                }
            }
        }

        File[] zygoteRcs = new File[]{
                new File(rootfsDir, "init.zygote64_32.rc"),
                new File(rootfsDir, "init.zygote32_64.rc"),
                new File(rootfsDir, "init.zygote32.rc")
        };
        for (File rc : zygoteRcs) {
            if (rc.exists()) {
                String content = IOUtils.readContent(rc);
                if (content != null) {
                    String updated = stripSecondaryZygote(content);
                    IOUtils.writeContent(rc, updated + "\n");
                }
            }
        }

        File zygote64Rc = new File(rootfsDir, "init.zygote64.rc");
        if (!zygote64Rc.exists()) {
            String defaultZygote64 =
                    "service zygote /system/bin/app_process64 -Xzygote /system/bin --zygote --start-system-server\n" +
                    "    class main\n" +
                    "    priority -20\n" +
                    "    user root\n" +
                    "    group root readproc\n" +
                    "    socket zygote stream 660 root system\n" +
                    "    onrestart write /sys/android_power/request_state wake\n" +
                    "    onrestart write /sys/power/state on\n" +
                    "    onrestart restart audioserver\n" +
                    "    onrestart restart cameraserver\n" +
                    "    onrestart restart media\n" +
                    "    onrestart restart netd\n" +
                    "    onrestart restart wificond\n" +
                    "    writepid /dev/cpuset/foreground/tasks\n";
            IOUtils.writeContent(zygote64Rc, defaultZygote64);
            chmodSafe(zygote64Rc.getAbsolutePath(), 0750);
        }

        // 3. Ensure /system/bin/app_process points to app_process64 (never 32-bit)
        File appProcess = new File(rootfsDir, "system/bin/app_process");
        File appProcess64 = new File(rootfsDir, "system/bin/app_process64");
        if (appProcess64.exists()) {
            try {
                Files.deleteIfExists(appProcess.toPath());
                Files.createSymbolicLink(appProcess.toPath(), Paths.get("app_process64"));
            } catch (Throwable e) {
                Log.w(TAG, "Failed to link app_process -> app_process64", e);
            }
        }
        File appProcess32 = new File(rootfsDir, "system/bin/app_process32");
        if (appProcess32.exists()) {
            // Prevent accidental 32-bit execution on Snapdragon 8s Gen 3
            //noinspection ResultOfMethodCallIgnored
            appProcess32.delete();
        }
    }

    private static void patchBuildProp64Only(File propFile) {
        try {
            List<String> lines = Files.readAllLines(propFile.toPath(), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>(lines.size() + 4);
            boolean hasZygote = false;
            boolean hasAbiList = false;
            boolean hasAbiList64 = false;
            boolean hasAbiList32 = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("ro.zygote=")) {
                    out.add("ro.zygote=zygote64");
                    hasZygote = true;
                } else if (trimmed.startsWith("ro.product.cpu.abilist=")) {
                    out.add("ro.product.cpu.abilist=arm64-v8a");
                    hasAbiList = true;
                } else if (trimmed.startsWith("ro.product.cpu.abilist64=")) {
                    out.add("ro.product.cpu.abilist64=arm64-v8a");
                    hasAbiList64 = true;
                } else if (trimmed.startsWith("ro.product.cpu.abilist32=")) {
                    out.add("ro.product.cpu.abilist32=");
                    hasAbiList32 = true;
                } else {
                    out.add(line);
                }
            }
            if (!hasZygote) out.add("ro.zygote=zygote64");
            if (!hasAbiList) out.add("ro.product.cpu.abilist=arm64-v8a");
            if (!hasAbiList64) out.add("ro.product.cpu.abilist64=arm64-v8a");
            if (!hasAbiList32) out.add("ro.product.cpu.abilist32=");
            Files.write(propFile.toPath(), out, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            Log.w(TAG, "patchBuildProp64Only failed for " + propFile, e);
        }
    }

    private static String stripSecondaryZygote(String rcContent) {
        StringBuilder sb = new StringBuilder();
        boolean skippingSecondary = false;
        for (String line : rcContent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("service ") && (trimmed.contains("zygote_secondary") || trimmed.contains("app_process32"))) {
                skippingSecondary = true;
                continue;
            }
            if (skippingSecondary) {
                if (trimmed.isEmpty() || (!line.startsWith(" ") && !line.startsWith("\t"))) {
                    skippingSecondary = false;
                } else {
                    continue;
                }
            }
            sb.append(line.replace("app_process32", "app_process64")).append('\n');
        }
        return sb.toString();
    }

    public static void provisionRootAndXposed(Context context) {
        File rootfsDir = getRootfsDir(context);
        if (!rootfsDir.exists()) {
            return;
        }

        // 1. Extract bundled Root / Riru / LSPosed payload (assets/xp_bundle.zip) if present
        extractXpBundleIfPresent(context, rootfsDir);

        // 2. Ensure Root (su) permissions & daemon service in init.rc
        File xbinSu = new File(rootfsDir, "system/xbin/su");
        if (xbinSu.exists()) {
            chmodSafe(xbinSu.getAbsolutePath(), 06755);
        }
        File binSu = new File(rootfsDir, "system/bin/su");
        if (binSu.exists()) {
            chmodSafe(binSu.getAbsolutePath(), 06755);
        }

        File superuserDir = new File(rootfsDir, "system/priv-app/Superuser");
        ensureDir(superuserDir);
        chmodSafe(superuserDir.getAbsolutePath(), 0755);
        File superuserApk = new File(superuserDir, "Superuser.apk");
        if (superuserApk.exists()) {
            chmodSafe(superuserApk.getAbsolutePath(), 0644);
        }

        File initRc = new File(rootfsDir, "init.rc");
        if (initRc.exists()) {
            String content = IOUtils.readContent(initRc);
            if (content != null && !content.contains("su --daemon") && !content.contains("daemonsu")) {
                String suDaemonBlock = "\n\nservice su_daemon /system/xbin/su --daemon\n" +
                        "    class main\n" +
                        "    user root\n" +
                        "    group root\n" +
                        "    seclabel u:r:init:s0\n";
                IOUtils.writeContent(initRc, content + suDaemonBlock);
            }
        }

        // 3. Ensure Riru & LSPosed directories and file permissions
        File riruDir = new File(rootfsDir, "data/adb/riru");
        File lspdDir = new File(rootfsDir, "data/adb/lspd");
        File riruModulesDir = new File(riruDir, "modules/lspd");
        File lspdPrivDir = new File(rootfsDir, "system/priv-app/LSPosed");
        ensureDir(riruDir);
        ensureDir(lspdDir);
        ensureDir(riruModulesDir);
        ensureDir(lspdPrivDir);
        chmodSafe(riruDir.getAbsolutePath(), 0755);
        chmodSafe(lspdDir.getAbsolutePath(), 0755);
        chmodSafe(riruModulesDir.getAbsolutePath(), 0755);
        chmodSafe(lspdPrivDir.getAbsolutePath(), 0755);

        File memtrack = new File(rootfsDir, "system/lib64/libmemtrack.so");
        if (memtrack.exists()) {
            chmodSafe(memtrack.getAbsolutePath(), 0644);
        }
        File lspdApk = new File(lspdPrivDir, "LSPosed.apk");
        if (lspdApk.exists()) {
            chmodSafe(lspdApk.getAbsolutePath(), 0644);
        }

        // Mark provisioned
        IOUtils.writeContent(new File(rootfsDir, ".aether_provisioned"), "1\n");
    }

    private static void extractXpBundleIfPresent(Context context, File rootfsDir) {
        Path rootfsRoot = rootfsDir.toPath().toAbsolutePath().normalize();
        try (InputStream assetIn = context.getAssets().open("xp_bundle.zip");
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(assetIn))) {
            byte[] buffer = new byte[16384];
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                Path target = rootfsRoot.resolve(name).normalize();
                if (!target.startsWith(rootfsRoot)) {
                    throw new IOException("Blocked zip-slip entry in xp_bundle.zip: " + name);
                }
                File outFile = target.toFile();
                if (entry.isDirectory()) {
                    ensureDir(outFile);
                    chmodSafe(outFile.getAbsolutePath(), 0755);
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null) {
                        ensureDir(parent);
                        chmodSafe(parent.getAbsolutePath(), 0755);
                    }
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                        int read;
                        while ((read = zis.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                        }
                    }
                    if (name.endsWith("/su") || name.equals("system/xbin/su") || name.equals("system/bin/su")) {
                        chmodSafe(outFile.getAbsolutePath(), 06755);
                    } else if (name.endsWith("/daemon") || name.contains("/bin/") || name.contains("/xbin/")) {
                        chmodSafe(outFile.getAbsolutePath(), 0755);
                    } else {
                        chmodSafe(outFile.getAbsolutePath(), 0644);
                    }
                }
                zis.closeEntry();
            }
            Log.i(TAG, "Provisioned xp_bundle.zip into guest rootfs");
        } catch (IOException e) {
            Log.w(TAG, "xp_bundle.zip not found or skipped: " + e.getMessage());
        }
    }

    public static boolean extractRootfsInAssets(Context context) {

        // read assets
        long t1 = SystemClock.elapsedRealtime();
        File rootfs7z = context.getFileStreamPath(ROOTFS_NAME);
        try (InputStream inputStream = new BufferedInputStream(context.getAssets().open(ROOTFS_NAME));
             OutputStream os = new BufferedOutputStream(new FileOutputStream(rootfs7z))) {
            byte[] buffer = new byte[10240];
            int count;
            while ((count = inputStream.read(buffer)) > 0) {
                os.write(buffer, 0, count);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        long t2 = SystemClock.elapsedRealtime();

        int ret = extractRootfs(context, rootfs7z);

        long t3 = SystemClock.elapsedRealtime();

        Log.i(TAG, "extract rootfs, read assets: " + (t2 - t1) + " un7z: " + (t3 - t2) + "ret: " + ret);

        return ret == 0;
    }

    public static File getRootfsDir(Context context) {
        return new File(context.getDataDir(), "rootfs");
    }

    public static File getRomSdcardDir(Context context) {
        return new File(getRootfsDir(context), "sdcard");
    }

    public static File getVendorDir(Context context) {
        return new File(getRootfsDir(context), "vendor");
    }

    public static File getVendorPropFile(Context context) {
        return new File(getVendorDir(context), "default.prop");
    }

    public static File get3rdRootfsFile(Context context) {
        return context.getFileStreamPath(CUSTOM_ROM_FILE_NAME);
    }

    public static boolean isAndroid12() {
        return Build.VERSION.PREVIEW_SDK_INT + Build.VERSION.SDK_INT == Build.VERSION_CODES.S;
    }

    private static void removePartition(Context context, String partition) {
        File rootfsDir = getRootfsDir(context);
        File systemDir = new File(rootfsDir, partition);

        IOUtils.deleteDirectory(systemDir);
    }

    private static void removeSystemPartition(Context context) {
        removePartition(context, "system");
    }

    private static void removeVendorPartition(Context context) {
        removePartition(context, "vendor");
    }

    private static RomInfo getRomInfo(InputStream in) {
        Properties prop = new Properties();
        try {
            prop.load(in);

            RomInfo info = new RomInfo();
            info.author = prop.getProperty("author");
            info.code = Long.parseLong(prop.getProperty("code"));
            info.version = prop.getProperty("version");
            info.desc = prop.getProperty("desc", DEFAULT_INFO);
            info.md5 = prop.getProperty("md5");
            return info;
        } catch (Throwable e) {
            Log.e(TAG, "read rom info err", e);
            return DEFAULT_ROM_INFO;
        }
    }

    private static void ensureDir(File file) {
        if (file.exists()) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        file.mkdirs();
    }
}
