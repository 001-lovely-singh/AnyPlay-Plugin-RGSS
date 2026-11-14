package com.hatkid.mkxpz;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Host test for ZipGameInfo + RuntimeDetector.detectZip. Run with run-zip.sh. */
public class ZipSelfTest {
    static int fails = 0;

    static File zip(String... spec) throws Exception {          // "path" or "path=content"
        File f = Files.createTempFile("zgi", ".zip").toFile();
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f));
        for (String s : spec) {
            int eq = s.indexOf('=');
            String path = eq < 0 ? s : s.substring(0, eq);
            byte[] data = eq < 0 ? new byte[0] : s.substring(eq + 1).replace("\\n", "\n").getBytes(StandardCharsets.ISO_8859_1);
            z.putNextEntry(new ZipEntry(path));
            z.write(data);
            z.closeEntry();
        }
        z.close();
        return f;
    }


    static byte[] zlib(String text) throws Exception {
        java.util.zip.Deflater d = new java.util.zip.Deflater();
        d.setInput(text.getBytes(StandardCharsets.ISO_8859_1)); d.finish();
        byte[] buf = new byte[4096]; int n = d.deflate(buf); d.end();
        return java.util.Arrays.copyOf(buf, n);
    }
    static byte[] exeWith(String ver) {
        String key = "ProductVersion";
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < 300; i++) o.write(0x4d);
        for (char c : key.toCharArray()) { o.write(c); o.write(0); }
        o.write(0); o.write(0);
        for (char c : ver.toCharArray()) { o.write(c); o.write(0); }
        o.write(0); o.write(0);
        return o.toByteArray();
    }
    static File zipBin(String[] names, byte[][] data) throws Exception {
        File f = Files.createTempFile("zgb", ".zip").toFile();
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f));
        for (int i = 0; i < names.length; i++) { z.putNextEntry(new ZipEntry(names[i])); z.write(data[i]); z.closeEntry(); }
        z.close();
        return f;
    }

    static void eq(String name, Object want, Object got) {
        if (!String.valueOf(want).equals(String.valueOf(got))) { fails++; System.out.println("FAIL " + name + ": want " + want + " got " + got); }
        else System.out.println("ok   " + name);
    }

    public static void main(String[] a) throws Exception {
        ZipGameInfo i;

        i = ZipGameInfo.read(zip("MyGame/Game.ini=[Game]\\nScripts=Data\\\\Scripts.rvdata2\\n", "MyGame/Data/Scripts.rvdata2", "MyGame/Graphics/x.png"), null);
        eq("wrapped: root", "MyGame/", i.root);
        eq("wrapped: problem", null, i.problem);
        eq("wrapped: rgss", 3, RuntimeDetector.detectZip(i).rgss);
        eq("wrapped: ini name", "Game.ini", i.iniName);

        i = ZipGameInfo.read(zip("Game.ini=[Game]\\nLibrary=RGSS102E.dll\\n", "Data/Scripts.rxdata"), null);
        eq("top-level: root", "", i.root);
        eq("top-level: rgss", 1, RuntimeDetector.detectZip(i).rgss);

        i = ZipGameInfo.read(zip("a/b/Game.ini=[Game]\\n", "a/Game.ini=[Game]\\nScripts=Data\\\\Scripts.rvdata\\n", "a/Data/Scripts.rvdata"), null);
        eq("shallowest root wins", "a/", i.root);
        eq("shallowest rgss", 2, RuntimeDetector.detectZip(i).rgss);

        i = ZipGameInfo.read(zip("G\\game.INI=[Game]\\nScripts=Data\\\\Scripts.rxdata\\n", "G\\DATA\\scripts.RXDATA"), null);
        eq("backslash+case: root", "G/", i.root);
        eq("backslash+case: rgss", 1, RuntimeDetector.detectZip(i).rgss);
        eq("backslash+case: data files", "[scripts.RXDATA]", i.dataFiles);

        i = ZipGameInfo.read(zip("Game.ini=[Game]\\n", "Game.rgss3a"), null);
        eq("rgss3a inside zip -> problem", true, i.problem != null && i.problem.contains("encrypted"));

        i = ZipGameInfo.read(zip("x/Data/Scripts.rvdata2", "x/Graphics/a.png"), null);
        eq("no ini, Data/Scripts: root", "x/", i.root);
        eq("no ini, Data/Scripts: rgss", 3, RuntimeDetector.detectZip(i).rgss);

        i = ZipGameInfo.read(zip("readme.txt", "pic.png"), null);
        eq("not a game -> problem", true, i.problem != null);

        i = ZipGameInfo.read(zip("MyGame/Game.ini=[Game]\\nScripts=Data\\\\Scripts.rvdata2\\n", "MyGame/Data/Scripts.rvdata2"), "");
        eq("hint \"\" is trusted, nothing there -> problem", true, i.problem != null);

        i = ZipGameInfo.read(zip("MyGame/Game.ini=[Game]\\nScripts=Data\\\\Scripts.rvdata2\\n", "MyGame/Data/Scripts.rvdata2"), "MyGame");
        eq("hint without slash", "MyGame/", i.root);
        eq("hint ok", null, i.problem);

        i = ZipGameInfo.read(zip("mkxp.json={\"rgssVersion\": 2, \"execName\": \"Play\"}", "Play.ini=[Game]\\nScripts=Data\\\\Scripts.rvdata2\\n", "Game.ini=[Game]\\n", "Data/Scripts.rvdata2"), "");
        eq("execName from mkxp.json", "Play", i.execName);
        eq("mkxp.json rgssVersion wins", 2, RuntimeDetector.detectZip(i).rgss);


        // ---- RuntimeProbe on zips (Essentials version, exe ProductVersion)
        byte[] ini = "[Game]\nScripts=Data\\Scripts.rxdata\n".getBytes(StandardCharsets.ISO_8859_1);
        i = ZipGameInfo.read(zipBin(new String[]{"G/Game.ini", "G/Data/Scripts.rxdata"},
                new byte[][]{ini, zlib("ESSENTIALS_VERSION = \"16.2\"")}), null);
        RuntimeProbe.Verdict v = RuntimeProbe.probeRgss1Zip(i);
        eq("probe zip: Essentials 16.2 -> legacy", true, v.legacy);

        i = ZipGameInfo.read(zipBin(new String[]{"Game.ini", "Data/Scripts.rxdata"},
                new byte[][]{ini, zlib("module Essentials\n  VERSION = \"21.1\"\nend")}), null);
        eq("probe zip: Essentials 21.1 -> modern", false, RuntimeProbe.probeRgss1Zip(i).legacy);

        i = ZipGameInfo.read(zipBin(new String[]{"Game.ini", "Data/Scripts.rxdata", "Game.exe"},
                new byte[][]{ini, zlib("puts 1"), exeWith("1.0.0")}), null);
        eq("probe zip: exe ProductVersion 1.0.0 -> legacy", true, RuntimeProbe.probeRgss1Zip(i).legacy);

        i = ZipGameInfo.read(zipBin(new String[]{"Game.ini", "Data/Scripts.rxdata", "Game.exe"},
                new byte[][]{ini, zlib("puts 1"), exeWith("2.3.0")}), null);
        eq("probe zip: exe ProductVersion 2.3.0 -> modern", false, RuntimeProbe.probeRgss1Zip(i).legacy);

        i = ZipGameInfo.read(zipBin(new String[]{"Game.ini", "Data/Scripts.rxdata"}, new byte[][]{ini, zlib("puts 1")}), null);
        eq("probe zip: nothing found -> modern", false, RuntimeProbe.probeRgss1Zip(i).legacy);
        eq("describeZip works", true, RuntimeProbe.describeZip(i).startsWith("folder facts: PLAYED FROM ZIP"));

        System.out.println(fails == 0 ? "ALL OK" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
