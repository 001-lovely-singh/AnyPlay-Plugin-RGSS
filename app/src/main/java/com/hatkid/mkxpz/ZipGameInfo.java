package com.hatkid.mkxpz;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What the plugin needs to know about a game that is played straight from a .zip
 * (nothing is extracted). Pure Java, no Android classes, so it can be tested on a
 * normal JDK.
 *
 * Reads only the zip's file list plus two tiny files (Game.ini and mkxp.json):
 *   - root      folder INSIDE the zip that holds Game.ini / Data / Graphics ("" = top,
 *               otherwise "MyGame/" with a trailing slash)
 *   - iniBytes  the game's own Game.ini (the native side reads it from a copy, because
 *               it cannot open a file inside a zip before the file system is up)
 *   - jsonBytes mkxp.json of the game, if there is one
 *   - rootFiles / dataFiles  names used by RuntimeDetector.detectZip()
 *   - problem   null when the zip can be played directly, otherwise a short ASCII
 *               reason why it cannot (the caller shows it and does not start the game)
 */
final class ZipGameInfo
{
    File zipFile = null;          // the zip this was read from
    String root = "";
    String execName = "Game";
    String iniName = null;
    byte[] iniBytes = null;
    byte[] jsonBytes = null;
    final List<String> rootFiles = new ArrayList<String>();
    final List<String> dataFiles = new ArrayList<String>();
    String problem = null;

    private static final int MAX_SMALL = 256 * 1024;

    private ZipGameInfo() {}

    /**
     * @param rootHint folder inside the zip that AnyPlay found ("" = top level), or null
     *                 to detect it here.
     */
    static ZipGameInfo read(File zipFile, String rootHint) throws IOException
    {
        ZipGameInfo info = new ZipGameInfo();
        info.zipFile = zipFile;
        ZipFile zf = new ZipFile(zipFile);
        try {
            List<String> names = new ArrayList<String>();     // normalised, files only
            List<ZipEntry> entries = new ArrayList<ZipEntry>();
            boolean badMethod = false;

            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = normalise(e.getName());
                if (n.isEmpty()) continue;
                names.add(n);
                entries.add(e);
                // PhysFS (the engine's file system) reads only STORED (0) and DEFLATED (8).
                int m = e.getMethod();
                if (m != ZipEntry.STORED && m != ZipEntry.DEFLATED) badMethod = true;
            }

            if (badMethod) {
                info.problem = "zip uses a compression method other than store/deflate";
                return info;
            }

            // ---- which folder inside the zip is the game root? ----
            String root;
            if (rootHint != null) {
                root = normaliseRoot(rootHint);
            } else {
                root = detectRoot(names);
                if (root == null) {
                    info.problem = "no Game.ini / Data/Scripts file found inside the zip";
                    return info;
                }
            }
            info.root = root;

            // ---- mkxp.json first: it may rename the .ini (execName) ----
            int jsonIdx = indexOfIgnoreCase(names, root + "mkxp.json");
            if (jsonIdx >= 0) {
                info.jsonBytes = readSmall(zf, entries.get(jsonIdx));
                String text = RuntimeDetector.stripJsonCommentsForZip(decodeLoose(info.jsonBytes));
                String en2 = RuntimeDetector.execNameFromJson(text);
                if (en2 != null) info.execName = en2;
            }

            int iniIdx = indexOfIgnoreCase(names, root + info.execName + ".ini");
            if (iniIdx >= 0) {
                info.iniName = names.get(iniIdx).substring(root.length());
                info.iniBytes = readSmall(zf, entries.get(iniIdx));
            }

            // ---- names directly in the root and directly in Data/ ----
            String dataPrefixLower = null;
            for (String n : names) {
                if (!n.regionMatches(true, 0, root, 0, root.length())) continue;
                String rest = n.substring(root.length());
                int slash = rest.indexOf('/');
                if (slash < 0) {
                    info.rootFiles.add(rest);
                } else if (dataPrefixLower == null
                        && rest.substring(0, slash).equalsIgnoreCase("data")) {
                    dataPrefixLower = rest.substring(0, slash + 1).toLowerCase(Locale.ROOT);
                }
            }
            if (dataPrefixLower != null) {
                for (String n : names) {
                    if (!n.regionMatches(true, 0, root, 0, root.length())) continue;
                    String rest = n.substring(root.length());
                    if (rest.toLowerCase(Locale.ROOT).startsWith(dataPrefixLower)) {
                        String inner = rest.substring(dataPrefixLower.length());
                        if (inner.indexOf('/') < 0 && !inner.isEmpty()) info.dataFiles.add(inner);
                    }
                }
            }

            // ---- things PhysFS cannot do from inside a zip ----
            for (String f : info.rootFiles) {
                if (RuntimeDetector.rgssFromArchiveName(f) != 0) {
                    info.problem = "game is an encrypted archive (" + f + ") inside the zip";
                    return info;
                }
            }
            if (info.iniBytes == null && info.dataFiles.isEmpty()) {
                info.problem = "no " + info.execName + ".ini and no Data folder under \"" + root + "\"";
                return info;
            }
            return info;
        } finally {
            try { zf.close(); } catch (IOException ignored) { }
        }
    }

    // ----------------------------------------------------------------- root

    /**
     * The shallowest folder that holds Game.ini; if there is none, the shallowest
     * folder that holds Data/Scripts.*. Returns "" (top level) or "Folder/Sub/", or
     * null when the zip does not look like an RPG Maker XP/VX/VX Ace game at all.
     */
    static String detectRoot(List<String> names)
    {
        String best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (String n : names) {
            String base = n.substring(n.lastIndexOf('/') + 1);
            if (!base.equalsIgnoreCase("game.ini")) continue;
            String dir = n.substring(0, n.length() - base.length());
            int depth = depthOf(dir);
            if (depth < bestDepth || (depth == bestDepth && dir.compareToIgnoreCase(best) < 0)) {
                best = dir;
                bestDepth = depth;
            }
        }
        if (best != null) return best;

        for (String n : names) {
            String base = n.substring(n.lastIndexOf('/') + 1);
            if (RuntimeDetector.rgssFromScriptsFile(base) == 0) continue;
            String dir = n.substring(0, n.length() - base.length());      // ".../Data/"
            if (dir.length() < 5 || !dir.toLowerCase(Locale.ROOT).endsWith("data/")) continue;
            String gameDir = dir.substring(0, dir.length() - 5);           // strip "Data/"
            int depth = depthOf(gameDir);
            if (depth < bestDepth || (depth == bestDepth && gameDir.compareToIgnoreCase(best) < 0)) {
                best = gameDir;
                bestDepth = depth;
            }
        }
        return best;
    }

    private static int depthOf(String dir)
    {
        int d = 0;
        for (int i = 0; i < dir.length(); i++) if (dir.charAt(i) == '/') d++;
        return d;
    }

    // -------------------------------------------------------------- helpers

    /** Backslashes to slashes, no leading slash or "./". */
    static String normalise(String name)
    {
        String s = name.replace('\\', '/');
        while (s.contains("//")) s = s.replace("//", "/");
        while (s.startsWith("./")) s = s.substring(2);
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    /** "" or "Folder/Sub/" (trailing slash, no leading slash). */
    static String normaliseRoot(String hint)
    {
        String s = normalise(hint.trim());
        if (s.isEmpty() || s.equals(".")) return "";
        if (!s.endsWith("/")) s = s + "/";
        return s;
    }

    private static int indexOfIgnoreCase(List<String> names, String wanted)
    {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(wanted)) return i;
        }
        return -1;
    }

    private static byte[] readSmall(ZipFile zf, ZipEntry e) throws IOException
    {
        InputStream in = zf.getInputStream(e);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < MAX_SMALL) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    /** Good enough for looking at ASCII keys: UTF-16 BOM aware, otherwise Latin-1. */
    static String decodeLoose(byte[] b)
    {
        return RuntimeDetector.decodeBytes(b);
    }
}
