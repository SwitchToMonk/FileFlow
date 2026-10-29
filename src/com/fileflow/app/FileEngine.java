package com.fileflow.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FileFlow's file engine.
 *
 * Everything that touches the filesystem lives here: scanning, organising into
 * folders (by category or by the user's own rules), fuzzy name matching, and the
 * recycle bin (delete → move to a hidden bin with an index, restore, purge).
 *
 * The engine never deletes anything outright unless the caller asks for a purge:
 * every "delete" is a move into the bin, so a mistake is always recoverable.
 */
final class FileEngine {

    static final String PREFS = "fileflow";
    static final String META = ".fileflow";
    static final String BIN = "bin";
    static final String INDEX = "index.json";

    /** category → extensions, in priority order */
    private static final String[][] CATS = {
        {"Images", "jpg jpeg png gif webp bmp heic heif svg avif jfif"},
        {"Videos", "mp4 mkv avi mov 3gp webm m4v flv wmv mpg mpeg"},
        {"Audio", "mp3 m4a wav flac ogg aac opus wma amr mid"},
        {"Documents", "pdf doc docx txt rtf odt xls xlsx csv ppt pptx md epub"},
        {"Archives", "zip rar 7z tar gz bz2 xz iso tgz"},
        {"APKs", "apk apks xapk aab"},
        {"Code", "js ts jsx tsx java kt py c cpp h hpp cs go rs rb php html htm css scss json xml yml yaml sh sql toml ini"},
        {"Fonts", "ttf otf woff woff2"},
    };

    private static final Map<String, String> EXT_CAT = new HashMap<>();
    static {
        for (String[] row : CATS) {
            for (String e : row[1].split(" ")) EXT_CAT.put(e, row[0]);
        }
    }

    private FileEngine() { }

    /* ==================================================================== */
    /* helpers                                                              */
    /* ==================================================================== */

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static File root() {
        return Environment.getExternalStorageDirectory();
    }

    static File metaDir() {
        return new File(root(), META);
    }

    static File binDir() {
        File f = new File(metaDir(), BIN);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    static File targetDir(Context c) {
        String p = prefs(c).getString("baseDir", "");
        if (p == null || p.trim().isEmpty()) p = new File(root(), "FileFlow").getAbsolutePath();
        File f = new File(p);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    static String extOf(String name) {
        int i = name.lastIndexOf('.');
        if (i <= 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(Locale.US);
    }

    static String categoryOf(String name) {
        String e = extOf(name);
        String c = EXT_CAT.get(e);
        return c == null ? "Other" : c;
    }

    static boolean isHiddenName(String n) {
        return n.startsWith(".");
    }

    /** directories the engine must never touch */
    static boolean excluded(File f, File base) {
        String p = f.getAbsolutePath();
        if (p.contains("/Android/") || p.endsWith("/Android")) return true;
        if (p.contains("/" + META) || p.endsWith("/" + META)) return true;
        if (base != null && (p.equals(base.getAbsolutePath()) || p.startsWith(base.getAbsolutePath() + "/"))) return true;
        return false;
    }

    static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1073741824L) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    /* ==================================================================== */
    /* scanning                                                             */
    /* ==================================================================== */

    /** every file the user can see, newest first, capped */
    static List<File> scanFiles(Context c, int max) {
        final File base = targetDir(c);
        List<File> out = new ArrayList<>();
        // Two guards, both needed on real devices:
        //  - dirs: a folder reachable twice (a symlink, or the same tree mounted
        //    under two paths) would otherwise be walked again and list every
        //    file a second time. That is what made rows repeat in the list.
        //  - seen: belt and braces, de-duplicate by canonical path.
        Set<String> dirs = new HashSet<>();
        Set<String> seen = new HashSet<>();
        walk(root(), out, max, base, 0, dirs, seen);
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        return out;
    }

    private static String canonical(File f) {
        try { return f.getCanonicalPath(); } catch (Throwable t) { return f.getAbsolutePath(); }
    }

    private static void walk(File dir, List<File> out, int max, File base, int depth,
                             Set<String> dirs, Set<String> seen) {
        if (out.size() >= max || depth > 6) return;
        if (!dirs.add(canonical(dir))) return;          // already walked this folder
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (out.size() >= max) return;
            String n = f.getName();
            if (isHiddenName(n)) continue;
            if (excluded(f, base)) continue;
            if (f.isDirectory()) walk(f, out, max, base, depth + 1, dirs, seen);
            else if (seen.add(canonical(f))) out.add(f);
        }
    }

    static JSONObject fileJson(File f) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", f.getName());
        o.put("path", f.getAbsolutePath());
        o.put("size", f.length());
        o.put("mtime", f.lastModified());
        o.put("ext", extOf(f.getName()));
        o.put("cat", categoryOf(f.getName()));
        o.put("dir", f.getParentFile() == null ? "" : f.getParentFile().getName());
        o.put("sizeText", human(f.length()));
        return o;
    }

    static JSONArray scanJson(Context c, int max) {
        JSONArray arr = new JSONArray();
        try {
            for (File f : scanFiles(c, max)) arr.put(fileJson(f));
        } catch (Throwable ignored) { }
        return arr;
    }

    /* ==================================================================== */
    /* rules                                                                */
    /* ==================================================================== */

    /** [{type:"ext"|"kw", match:"pdf", folder:"PDFs"}] */
    static JSONArray rules(Context c) {
        try {
            String s = prefs(c).getString("rules", "[]");
            return new JSONArray(s == null || s.isEmpty() ? "[]" : s);
        } catch (Throwable t) { return new JSONArray(); }
    }

    /** the folder a file should go to, or null when nothing matches */
    static String folderFor(Context c, File f) {
        String name = f.getName().toLowerCase(Locale.US);
        String ext = extOf(name);
        JSONArray rs = rules(c);
        for (int i = 0; i < rs.length(); i++) {
            JSONObject r = rs.optJSONObject(i);
            if (r == null) continue;
            String type = r.optString("type", "");
            String match = r.optString("match", "").toLowerCase(Locale.US).trim();
            String folder = r.optString("folder", "").trim();
            if (match.isEmpty() || folder.isEmpty()) continue;
            if ("ext".equals(type)) {
                if (ext.equals(match.replace(".", ""))) return folder;
            } else {
                if (name.contains(match)) return folder;
            }
        }
        return null;
    }

    /* ==================================================================== */
    /* organise                                                             */
    /* ==================================================================== */

    /** unique destination inside dir for a file name */
    static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int i = name.lastIndexOf('.');
        if (i > 0) { base = name.substring(0, i); ext = name.substring(i); }
        for (int n = 1; n < 9999; n++) {
            f = new File(dir, base + " (" + n + ")" + ext);
            if (!f.exists()) return f;
        }
        return f;
    }

    static boolean move(File src, File dst) {
        if (src.equals(dst)) return true;
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (src.renameTo(dst)) return true;
        } catch (Throwable ignored) { }
        // fall back to a copy + delete (different volumes / providers)
        InputStream in = null; OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close(); out = null;
            in.close(); in = null;
            if (src.delete()) return true;
            dst.delete();
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * Organise files into folders. With dryRun it only reports what it *would*
     * do, which is what the preview uses.
     */
    static JSONObject organise(Context c, boolean dryRun, int max) {
        File base = targetDir(c);
        List<File> files = scanFiles(c, max);
        JSONObject res = new JSONObject();
        JSONObject byFolder = new JSONObject();
        JSONArray moved = new JSONArray();
        int count = 0, skipped = 0, failed = 0;
        long bytes = 0;
        for (File f : files) {
            try {
                if (f.getParentFile() != null && f.getParentFile().getAbsolutePath().equals(base.getAbsolutePath())) { skipped++; continue; }
                String folder = folderFor(c, f);
                if (folder == null) folder = categoryOf(f.getName());
                File destDir = new File(base, folder);
                File dest = unique(destDir, f.getName());
                if (dryRun) {
                    count++;
                    bytes += f.length();
                    byFolder.put(folder, byFolder.optInt(folder, 0) + 1);
                    if (moved.length() < 400) {
                        JSONObject m = new JSONObject();
                        m.put("name", f.getName());
                        m.put("from", f.getParent() == null ? "" : f.getParentFile().getName());
                        m.put("to", folder);
                        moved.put(m);
                    }
                    continue;
                }
                if (move(f, dest)) {
                    count++;
                    bytes += f.length();
                    byFolder.put(folder, byFolder.optInt(folder, 0) + 1);
                    if (moved.length() < 400) {
                        JSONObject m = new JSONObject();
                        m.put("name", f.getName());
                        m.put("from", f.getParent() == null ? "" : f.getParentFile().getName());
                        m.put("to", folder);
                        moved.put(m);
                    }
                } else failed++;
            } catch (Throwable t) { failed++; }
        }
        try {
            res.put("count", count);
            res.put("failed", failed);
            res.put("skipped", skipped);
            res.put("bytes", bytes);
            res.put("bytesText", human(bytes));
            res.put("byFolder", byFolder);
            res.put("items", moved);
            res.put("dryRun", dryRun);
        } catch (Throwable ignored) { }
        return res;
    }

    /* ==================================================================== */
    /* fuzzy matching                                                       */
    /* ==================================================================== */

    static String[] tokens(String s) {
        s = s.toLowerCase(Locale.US);
        StringBuilder cur = new StringBuilder();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch)) cur.append(ch);
            else if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    static boolean isSubsequence(String needle, String hay) {
        int j = 0;
        for (int i = 0; i < hay.length() && j < needle.length(); i++) {
            if (hay.charAt(i) == needle.charAt(j)) j++;
        }
        return j == needle.length();
    }

    static double ratio(String a, String b) {
        int la = a.length(), lb = b.length();
        if (la == 0 && lb == 0) return 1;
        if (la == 0 || lb == 0) return 0;
        int[] prev = new int[lb + 1], cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return 1.0 - ((double) prev[lb] / Math.max(la, lb));
    }

    /**
     * How well a file name matches a query. This is the "same name, different
     * words" rule: a query of "gitly apk" scores a full match against
     * "Gitly account sync.apk" because every query word appears in the name.
     */
    static double score(String query, String name) {
        String q = query.toLowerCase(Locale.US).trim();
        if (q.isEmpty()) return 0;
        String n = name.toLowerCase(Locale.US);
        // an exact substring of the whole name is the strongest signal
        if (n.contains(q)) return 1.0;
        String[] qt = tokens(q);
        if (qt.length == 0) return 0;
        String[] nt = tokens(n);
        double sum = 0;
        int strong = 0;
        for (String t : qt) {
            double best = 0;
            for (String cand : nt) {
                if (cand.equals(t)) { best = Math.max(best, 1.0); }
                else if (cand.startsWith(t) || cand.contains(t)) { best = Math.max(best, 0.92); }
                else if (t.length() >= 3 && isSubsequence(t, cand)) { best = Math.max(best, 0.80); }
                else { best = Math.max(best, ratio(t, cand) * 0.85); }
            }
            if (best >= 0.9) strong++;
            sum += best;
        }
        double base = sum / qt.length;
        // reward matching every word
        if (strong == qt.length) base = Math.min(1.0, base + 0.10);
        return base;
    }

    static JSONArray fuzzy(Context c, String query, double threshold, int max) {
        JSONArray out = new JSONArray();
        try {
            List<File> files = scanFiles(c, 6000);
            List<Object[]> hits = new ArrayList<>();
            for (File f : files) {
                double s = score(query, f.getName());
                if (s >= threshold) hits.add(new Object[]{f, s});
            }
            Collections.sort(hits, new Comparator<Object[]>() {
                @Override public int compare(Object[] a, Object[] b) {
                    int byScore = Double.compare((Double) b[1], (Double) a[1]);
                    if (byScore != 0) return byScore;
                    return Long.compare(((File) b[0]).lastModified(), ((File) a[0]).lastModified());
                }
            });
            for (int i = 0; i < hits.size() && i < max; i++) {
                File f = (File) hits.get(i)[0];
                JSONObject o = fileJson(f);
                o.put("score", Math.round(((Double) hits.get(i)[1]) * 100));
                out.put(o);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /* ==================================================================== */
    /* recycle bin                                                          */
    /* ==================================================================== */

    static synchronized JSONArray index(Context c) {
        File f = new File(metaDir(), INDEX);
        if (!f.exists()) return new JSONArray();
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            String s = bo.toString("UTF-8");
            return new JSONArray(s.isEmpty() ? "[]" : s);
        } catch (Throwable t) {
            return new JSONArray();
        } finally { try { if (in != null) in.close(); } catch (Throwable ignored) { } }
    }

    static synchronized void writeIndex(JSONArray arr) {
        File f = new File(metaDir(), INDEX);
        OutputStream out = null;
        try {
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            out = new FileOutputStream(f);
            out.write(arr.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally { try { if (out != null) out.close(); } catch (Throwable ignored) { } }
    }

    /** move files into the bin; nothing is destroyed, so it is always undoable */
    static JSONObject toBin(Context c, JSONArray paths) {
        JSONObject res = new JSONObject();
        int ok = 0, fail = 0;
        JSONArray idx = index(c);
        long bytes = 0;
        for (int i = 0; i < paths.length(); i++) {
            try {
                File src = new File(paths.getString(i));
                if (!src.exists() || src.isDirectory()) { fail++; continue; }
                String id = String.valueOf(System.currentTimeMillis()) + "_" + i;
                File dst = new File(binDir(), id + "__" + src.getName());
                long size = src.length();
                if (!move(src, dst)) { fail++; continue; }
                JSONObject e = new JSONObject();
                e.put("id", id);
                e.put("name", src.getName());
                e.put("origPath", src.getAbsolutePath());
                e.put("binPath", dst.getAbsolutePath());
                e.put("size", size);
                e.put("deletedAt", System.currentTimeMillis());
                e.put("cat", categoryOf(src.getName()));
                e.put("sizeText", human(size));
                idx.put(e);
                ok++;
                bytes += size;
            } catch (Throwable t) { fail++; }
        }
        writeIndex(idx);
        try {
            res.put("ok", ok);
            res.put("failed", fail);
            res.put("bytes", bytes);
            res.put("bytesText", human(bytes));
            res.put("binCount", idx.length());
        } catch (Throwable ignored) { }
        return res;
    }

    static JSONArray listBin(Context c) {
        JSONArray idx = index(c);
        // drop entries whose file has vanished
        JSONArray clean = new JSONArray();
        for (int i = 0; i < idx.length(); i++) {
            JSONObject e = idx.optJSONObject(i);
            if (e == null) continue;
            File f = new File(e.optString("binPath", ""));
            if (f.exists()) clean.put(e);
        }
        if (clean.length() != idx.length()) writeIndex(clean);
        return clean;
    }

    static JSONObject restore(Context c, JSONArray ids) {
        Set<String> want = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) want.add(ids.optString(i));
        JSONArray idx = index(c);
        JSONArray keep = new JSONArray();
        int ok = 0, fail = 0;
        for (int i = 0; i < idx.length(); i++) {
            JSONObject e = idx.optJSONObject(i);
            if (e == null) continue;
            String id = e.optString("id");
            if (!want.contains(id)) { keep.put(e); continue; }
            File src = new File(e.optString("binPath", ""));
            File dst = new File(e.optString("origPath", ""));
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            dst = unique(parent == null ? new File(root(), "Restored") : parent, dst.getName());
            if (src.exists() && move(src, dst)) ok++;
            else { fail++; keep.put(e); }
        }
        writeIndex(keep);
        JSONObject res = new JSONObject();
        try { res.put("ok", ok); res.put("failed", fail); res.put("binCount", keep.length()); } catch (Throwable ignored) { }
        return res;
    }

    static JSONObject purge(Context c, JSONArray ids) {
        Set<String> want = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) want.add(ids.optString(i));
        JSONArray idx = index(c);
        JSONArray keep = new JSONArray();
        int ok = 0;
        for (int i = 0; i < idx.length(); i++) {
            JSONObject e = idx.optJSONObject(i);
            if (e == null) continue;
            if (!want.contains(e.optString("id"))) { keep.put(e); continue; }
            File f = new File(e.optString("binPath", ""));
            if (f.exists()) f.delete();
            ok++;
        }
        writeIndex(keep);
        JSONObject res = new JSONObject();
        try { res.put("ok", ok); res.put("binCount", keep.length()); } catch (Throwable ignored) { }
        return res;
    }

    static JSONObject emptyBin(Context c) {
        JSONArray idx = index(c);
        int ok = 0;
        for (int i = 0; i < idx.length(); i++) {
            JSONObject e = idx.optJSONObject(i);
            if (e == null) continue;
            File f = new File(e.optString("binPath", ""));
            if (f.exists() && f.delete()) ok++;
        }
        writeIndex(new JSONArray());
        JSONObject res = new JSONObject();
        try { res.put("ok", ok); res.put("binCount", 0); } catch (Throwable ignored) { }
        return res;
    }

    /* ==================================================================== */
    /* stats                                                                */
    /* ==================================================================== */

    static JSONObject stats(Context c) {
        JSONObject o = new JSONObject();
        try {
            File r = root();
            long total = r.getTotalSpace();
            long free = r.getFreeSpace();
            o.put("total", total);
            o.put("free", free);
            o.put("used", total - free);
            o.put("totalText", human(total));
            o.put("freeText", human(free));
            o.put("usedText", human(total - free));
            o.put("percent", total > 0 ? Math.round((total - free) * 100.0 / total) : 0);
            o.put("root", r.getAbsolutePath());
            o.put("bin", binDir().getAbsolutePath());
            o.put("base", targetDir(c).getAbsolutePath());
            JSONArray idx = listBin(c);
            long binBytes = 0;
            for (int i = 0; i < idx.length(); i++) binBytes += idx.optJSONObject(i).optLong("size", 0);
            o.put("binCount", idx.length());
            o.put("binBytes", binBytes);
            o.put("binText", human(binBytes));
        } catch (Throwable ignored) { }
        return o;
    }

    /** counts per category, for the dashboard */
    static JSONObject categoryCounts(Context c, int max) {
        JSONObject o = new JSONObject();
        try {
            for (File f : scanFiles(c, max)) {
                String cat = categoryOf(f.getName());
                o.put(cat, o.optInt(cat, 0) + 1);
            }
        } catch (Throwable ignored) { }
        return o;
    }

    /* ==================================================================== */
    /* scheduled (timer) deletions                                          */
    /* ==================================================================== */

    /** [{path, at, name}] — files the user asked us to delete after a delay */
    static synchronized JSONArray scheduled(Context c) {
        try {
            String s = prefs(c).getString("scheduled", "[]");
            return new JSONArray(s == null || s.isEmpty() ? "[]" : s);
        } catch (Throwable t) { return new JSONArray(); }
    }

    static synchronized void saveScheduled(Context c, JSONArray arr) {
        prefs(c).edit().putString("scheduled", arr.toString()).apply();
    }

    static synchronized JSONObject addScheduled(Context c, String path, long at) {
        JSONArray arr = scheduled(c);
        // replace any existing timer for the same file
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e != null && !path.equals(e.optString("path"))) next.put(e);
        }
        JSONObject e = new JSONObject();
        try {
            File f = new File(path);
            e.put("path", path);
            e.put("at", at);
            e.put("name", f.getName());
            e.put("size", f.length());
            e.put("sizeText", human(f.length()));
            next.put(e);
        } catch (Throwable ignored) { }
        saveScheduled(c, next);
        return e;
    }

    static synchronized void removeScheduled(Context c, String path) {
        JSONArray arr = scheduled(c);
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e != null && !path.equals(e.optString("path"))) next.put(e);
        }
        saveScheduled(c, next);
    }

    /** drop entries whose file is already gone */
    static synchronized JSONArray pruneScheduled(Context c) {
        JSONArray arr = scheduled(c);
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            if (new File(e.optString("path")).exists()) next.put(e);
        }
        if (next.length() != arr.length()) saveScheduled(c, next);
        return next;
    }


    /* ==================================================================== */
    /* smart engine                                                         */
    /* ==================================================================== */

    /** content fingerprint: size + MD5 of the first 256 KB */
    static String fingerprint(File f) {
        InputStream in = null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            in = new FileInputStream(f);
            byte[] buf = new byte[65536];
            long left = Math.min(f.length(), 262144);
            int n;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                md.update(buf, 0, n);
                left -= n;
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString() + ":" + f.length();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    static JSONArray recent(Context c, int days, int max) {
        JSONArray out = new JSONArray();
        long cut = System.currentTimeMillis() - days * 86400000L;
        try {
            for (File f : scanFiles(c, 6000)) {
                if (f.lastModified() < cut) continue;
                out.put(fileJson(f));
                if (out.length() >= max) break;
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static JSONObject suggestions(Context c) {
        JSONObject out = new JSONObject();
        try {
            List<File> files = scanFiles(c, 6000);
            long now = System.currentTimeMillis();

            Map<Long, List<File>> bySize = new HashMap<>();
            for (File f : files) {
                long len = f.length();
                if (len < 4096) continue;
                List<File> l = bySize.get(len);
                if (l == null) { l = new ArrayList<>(); bySize.put(len, l); }
                l.add(f);
            }
            Map<String, List<File>> byHash = new HashMap<>();
            for (List<File> bucket : bySize.values()) {
                if (bucket.size() < 2) continue;
                for (File f : bucket) {
                    String h = fingerprint(f);
                    if (h == null) continue;
                    List<File> l = byHash.get(h);
                    if (l == null) { l = new ArrayList<>(); byHash.put(h, l); }
                    l.add(f);
                }
            }
            JSONArray dups = new JSONArray();
            long dupBytes = 0;
            for (List<File> group : byHash.values()) {
                if (group.size() < 2) continue;
                JSONArray g = new JSONArray();
                for (int i = 0; i < group.size(); i++) g.put(fileJson(group.get(i)));
                dupBytes += group.get(0).length() * (group.size() - 1);
                JSONObject o = new JSONObject();
                o.put("size", group.get(0).length());
                o.put("sizeText", human(group.get(0).length()));
                o.put("files", g);
                dups.put(o);
            }

            Map<String, List<File>> byName = new HashMap<>();
            for (File f : files) {
                String k = f.getName().toLowerCase(Locale.US);
                List<File> l = byName.get(k);
                if (l == null) { l = new ArrayList<>(); byName.put(k, l); }
                l.add(f);
            }
            JSONArray same = new JSONArray();
            for (List<File> group : byName.values()) {
                if (group.size() < 2) continue;
                JSONArray g = new JSONArray();
                for (int i = 0; i < group.size(); i++) g.put(fileJson(group.get(i)));
                JSONObject o = new JSONObject();
                o.put("name", group.get(0).getName());
                o.put("files", g);
                same.put(o);
            }

            List<File> sorted = new ArrayList<>(files);
            Collections.sort(sorted, new Comparator<File>() {
                @Override public int compare(File a, File b) { return Long.compare(b.length(), a.length()); }
            });
            JSONArray big = new JSONArray();
            for (int i = 0; i < sorted.size() && i < 12; i++) {
                if (sorted.get(i).length() < 50L * 1024 * 1024) break;
                big.put(fileJson(sorted.get(i)));
            }

            JSONArray shots = new JSONArray();
            JSONArray apks = new JSONArray();
            for (File f : files) {
                String p = f.getAbsolutePath().toLowerCase(Locale.US);
                long age = now - f.lastModified();
                if (p.contains("screenshot") && age > 30L * 86400000L) shots.put(fileJson(f));
                else if ("apk".equals(extOf(f.getName())) && age > 7L * 86400000L) apks.put(fileJson(f));
            }

            JSONArray emptyDirs = new JSONArray();
            File base = targetDir(c);
            collectEmpty(root(), emptyDirs, base, 0);

            out.put("duplicates", dups);
            out.put("duplicateBytes", dupBytes);
            out.put("duplicateText", human(dupBytes));
            out.put("sameName", same);
            out.put("large", big);
            out.put("screenshots", shots);
            out.put("apks", apks);
            out.put("emptyFolders", emptyDirs);
        } catch (Throwable t) {
            try { out.put("error", String.valueOf(t.getMessage())); } catch (Throwable ignored) { }
        }
        return out;
    }

    private static void collectEmpty(File dir, JSONArray out, File base, int depth) {
        if (depth > 6 || out.length() > 60) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        int visible = 0;
        for (File f : kids) {
            if (isHiddenName(f.getName()) || excluded(f, base)) continue;
            visible++;
            if (f.isDirectory()) collectEmpty(f, out, base, depth + 1);
        }
        if (visible == 0 && !dir.equals(root())) {
            try {
                JSONObject o = new JSONObject();
                o.put("path", dir.getAbsolutePath());
                o.put("name", dir.getName());
                out.put(o);
            } catch (Throwable ignored) { }
        }
    }

    /** fuzzy search across storage *and* the recycle bin */
    static JSONArray searchAll(Context c, String query, double threshold, boolean includeBin) {
        JSONArray out = new JSONArray();
        try {
            List<Object[]> hits = new ArrayList<>();
            for (File f : scanFiles(c, 6000)) {
                double s = score(query, f.getName());
                if (s >= threshold) hits.add(new Object[]{f, s, Boolean.FALSE});
            }
            if (includeBin) {
                JSONArray idx = listBin(c);
                for (int i = 0; i < idx.length(); i++) {
                    JSONObject e = idx.optJSONObject(i);
                    if (e == null) continue;
                    double s = score(query, e.optString("name", ""));
                    if (s >= threshold) hits.add(new Object[]{e, s, Boolean.TRUE});
                }
            }
            Collections.sort(hits, new Comparator<Object[]>() {
                @Override public int compare(Object[] a, Object[] b) {
                    return Double.compare((Double) b[1], (Double) a[1]);
                }
            });
            for (int i = 0; i < hits.size() && i < 300; i++) {
                Object[] h = hits.get(i);
                boolean inBin = (Boolean) h[2];
                JSONObject o;
                if (inBin) {
                    JSONObject e = (JSONObject) h[0];
                    o = new JSONObject();
                    o.put("name", e.optString("name"));
                    o.put("path", e.optString("origPath"));
                    o.put("binId", e.optString("id"));
                    o.put("size", e.optLong("size"));
                    o.put("sizeText", e.optString("sizeText"));
                    o.put("cat", e.optString("cat"));
                    o.put("dir", "Recycle bin");
                    o.put("deletedAt", e.optLong("deletedAt"));
                } else {
                    o = fileJson((File) h[0]);
                }
                o.put("inBin", inBin);
                o.put("score", Math.round(((Double) h[1]) * 100));
                out.put(o);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static JSONObject binPaths(Context c) {
        JSONObject o = new JSONObject();
        try {
            JSONArray idx = listBin(c);
            for (int i = 0; i < idx.length(); i++) {
                JSONObject e = idx.optJSONObject(i);
                if (e != null) o.put(e.optString("origPath"), e.optString("id"));
            }
        } catch (Throwable ignored) { }
        return o;
    }

    static String readText(String path, int maxBytes) {
        InputStream in = null;
        try {
            File f = new File(path);
            if (!f.exists() || !f.isFile()) return "";
            long len = Math.min(f.length(), maxBytes);
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) len];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    static JSONObject fileInfo(Context c, String path) {
        JSONObject o = new JSONObject();
        try {
            File f = new File(path);
            String ext = extOf(f.getName());
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime == null) mime = "*/*";
            String kind = "other";
            if (mime.startsWith("image/")) kind = "image";
            else if (mime.startsWith("video/")) kind = "video";
            else if (mime.startsWith("audio/")) kind = "audio";
            else if (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")
                    || mime.contains("javascript") || "md".equals(ext) || "log".equals(ext)
                    || "ini".equals(ext) || "yml".equals(ext) || "yaml".equals(ext)) kind = "text";
            o.put("kind", kind);
            o.put("mime", mime);
            o.put("name", f.getName());
            o.put("size", f.length());
            o.put("sizeText", human(f.length()));
            o.put("exists", f.exists());
        } catch (Throwable ignored) { }
        return o;
    }


    /* ==================================================================== */
    /* folders                                                              */
    /* ==================================================================== */

    /** directories that hold files, with their counts and sizes */
    static JSONArray folders(Context c, int max) {
        JSONArray out = new JSONArray();
        try {
            List<File> files = scanFiles(c, 6000);
            Map<String, long[]> agg = new LinkedHashMap<>();
            Map<String, File> dirs = new HashMap<>();
            for (File f : files) {
                File d = f.getParentFile();
                if (d == null) continue;
                String p = d.getAbsolutePath();
                long[] a = agg.get(p);
                if (a == null) { a = new long[]{0, 0}; agg.put(p, a); dirs.put(p, d); }
                a[0]++;
                a[1] += f.length();
            }
            for (Map.Entry<String, long[]> e : agg.entrySet()) {
                if (out.length() >= max) break;
                File d = dirs.get(e.getKey());
                if (d == null) continue;
                JSONObject o = new JSONObject();
                o.put("path", d.getAbsolutePath());
                o.put("name", d.getName());
                o.put("dir", parentName(d));
                o.put("count", e.getValue()[0]);
                o.put("size", e.getValue()[1]);
                o.put("sizeText", human(e.getValue()[1]));
                out.put(o);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static String parentName(File d) {
        try {
            File p = d.getParentFile();
            return p == null ? "" : p.getName();
        } catch (Throwable t) { return ""; }
    }

    /** recursive delete; returns how much was removed */
    static JSONObject deleteFolder(Context c, String path) {
        JSONObject o = new JSONObject();
        try {
            File dir = new File(path);
            File root = root();
            // never remove the storage root itself or one of our own folders
            if (dir.equals(root) || excluded(dir, targetDir(c)) || !dir.exists()) {
                o.put("ok", 0); o.put("reason", "not allowed");
                return o;
            }
            long[] acc = new long[]{0, 0};
            boolean ok = wipe(dir, acc);
            o.put("ok", ok ? 1 : 0);
            o.put("files", acc[0]);
            o.put("bytes", acc[1]);
            o.put("bytesText", human(acc[1]));
        } catch (Throwable t) {
            try { o.put("ok", 0); } catch (Throwable ignored) { }
        }
        return o;
    }

    private static boolean wipe(File f, long[] acc) {
        try {
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) wipe(k, acc);
            } else {
                long len = f.length();
                if (f.delete()) { acc[0]++; acc[1] += len; }
            }
            return f.delete() || !f.exists();
        } catch (Throwable t) { return false; }
    }

    /** removes every empty folder it can find */
    static JSONObject deleteEmptyFolders(Context c) {
        JSONObject o = new JSONObject();
        try {
            JSONArray found = new JSONArray();
            collectEmpty(root(), found, targetDir(c), 0);
            int removed = 0;
            // deepest first so a parent that only holds empty children also goes
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < found.length(); i++) {
                JSONObject e = found.optJSONObject(i);
                if (e != null) list.add(e);
            }
            Collections.sort(list, new Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    return b.optString("path").length() - a.optString("path").length();
                }
            });
            for (JSONObject e : list) {
                File d = new File(e.optString("path"));
                if (d.exists() && d.delete()) removed++;
            }
            o.put("ok", removed);
        } catch (Throwable t) {
            try { o.put("ok", 0); } catch (Throwable ignored) { }
        }
        return o;
    }

}
