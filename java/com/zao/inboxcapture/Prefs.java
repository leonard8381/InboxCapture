package com.zao.inboxcapture;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 本地偏好和收纳记录（全部本地 SharedPreferences，不联网）。 */
public final class Prefs {
    private static final String NAME = "inbox_capture";
    private static final String KEY_TREE = "tree_uri";
    private static final String KEY_COUNT = "saved_count";
    private static final String KEY_LAST = "last_saved";

    private static final String KEY_CAPTURE_FEED = "capture_feed_v1";
    private static final String KEY_PLAT_XHS = "plat_xhs";
    private static final String KEY_PLAT_DY = "plat_dy";
    private static final String KEY_PLAT_WX = "plat_wx";
    private static final String KEY_PLAT_BILI = "plat_bili";


    public static final class CaptureRecord {
        public final String platform;
        public final String title;
        public final String fileName;
        public final long timeMs;
        public final boolean hasShot;
        public final String shotUri;
        public final String docUri;
        public final String shotName;
        public final String url;

        public CaptureRecord(String platform, String title, String fileName,
                             long timeMs, boolean hasShot, String shotUri) {
            this(platform, title, fileName, timeMs, hasShot, shotUri, "", "", "");
        }

        public CaptureRecord(String platform, String title, String fileName,
                             long timeMs, boolean hasShot, String shotUri,
                             String docUri, String shotName, String url) {
            this.platform = platform == null ? "" : platform;
            this.title = title == null ? "" : title;
            this.fileName = fileName == null ? "" : fileName;
            this.timeMs = timeMs;
            this.hasShot = hasShot;
            this.shotUri = shotUri == null ? "" : shotUri;
            this.docUri = docUri == null ? "" : docUri;
            this.shotName = shotName == null ? "" : shotName;
            this.url = url == null ? "" : url;
        }
    }


    private Prefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String treeUri(Context context) {
        return prefs(context).getString(KEY_TREE, "");
    }

    public static void setTreeUri(Context context, String value) {
        prefs(context).edit().putString(KEY_TREE, value).apply();
    }

    public static int savedCount(Context context) {
        return prefs(context).getInt(KEY_COUNT, 0);
    }

    public static void bumpSaved(Context context) {
        prefs(context).edit().putInt(KEY_COUNT, savedCount(context) + 1).apply();
    }

    public static String lastSaved(Context context) {
        return prefs(context).getString(KEY_LAST, "");
    }

    public static void setLastSaved(Context context, String value) {
        prefs(context).edit().putString(KEY_LAST, value).apply();
    }

    public static void recordCapture(Context context, String platform, String title,
                                     String fileName, boolean hasShot) {
        recordCapture(context, platform, title, fileName, hasShot, null, null, null, null);
    }

    public static void recordCapture(Context context, String platform, String title,
                                     String fileName, boolean hasShot, String shotUri) {
        recordCapture(context, platform, title, fileName, hasShot, shotUri, null, null, null);
    }

    public static void recordCapture(Context context, String platform, String title,
                                     String fileName, boolean hasShot, String shotUri,
                                     String docUri, String shotName, String url) {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor ed = p.edit();
        if ("xiaohongshu".equals(platform)) {
            ed.putInt(KEY_PLAT_XHS, p.getInt(KEY_PLAT_XHS, 0) + 1);
        } else if ("douyin".equals(platform)) {
            ed.putInt(KEY_PLAT_DY, p.getInt(KEY_PLAT_DY, 0) + 1);
        } else if ("wechat".equals(platform)) {
            ed.putInt(KEY_PLAT_WX, p.getInt(KEY_PLAT_WX, 0) + 1);
        } else if ("bilibili".equals(platform)) {
            ed.putInt(KEY_PLAT_BILI, p.getInt(KEY_PLAT_BILI, 0) + 1);
        }
        try {
            JSONArray old = new JSONArray(p.getString(KEY_CAPTURE_FEED, "[]"));
            JSONArray next = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("p", platform == null ? "" : platform);
            item.put("t", title == null ? "" : title);
            item.put("f", fileName == null ? "" : fileName);
            item.put("ms", System.currentTimeMillis());
            item.put("s", hasShot);
            item.put("u", shotUri == null ? "" : shotUri);
            item.put("d", docUri == null ? "" : docUri);
            item.put("sn", shotName == null ? "" : shotName);
            item.put("url", url == null ? "" : url);
            next.put(item);
            for (int i = 0; i < old.length() && next.length() < 30; i++) {
                next.put(old.get(i));
            }
            ed.putString(KEY_CAPTURE_FEED, next.toString());
        } catch (Exception ignored) {
        }
        ed.apply();
    }

    public static boolean removeCapture(Context context, CaptureRecord target) {
        if (target == null) return false;
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor ed = p.edit();
        boolean removed = false;
        String newFirstFile = "";
        try {
            JSONArray old = new JSONArray(p.getString(KEY_CAPTURE_FEED, "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                boolean match = !removed
                        && target.fileName.equals(o.optString("f", ""))
                        && (target.timeMs == 0L || target.timeMs == o.optLong("ms", 0L));
                if (match) {
                    removed = true;
                    continue;
                }
                if (newFirstFile.isEmpty()) {
                    newFirstFile = o.optString("f", "");
                }
                next.put(o);
            }
            if (removed) {
                ed.putString(KEY_CAPTURE_FEED, next.toString());
            }
        } catch (Exception ignored) {
        }
        if (removed) {
            int count = p.getInt(KEY_COUNT, 0);
            if (count > 0) {
                ed.putInt(KEY_COUNT, count - 1);
            }
            if ("xiaohongshu".equals(target.platform)) {
                ed.putInt(KEY_PLAT_XHS, Math.max(0, p.getInt(KEY_PLAT_XHS, 0) - 1));
            } else if ("douyin".equals(target.platform)) {
                ed.putInt(KEY_PLAT_DY, Math.max(0, p.getInt(KEY_PLAT_DY, 0) - 1));
            } else if ("wechat".equals(target.platform)) {
                ed.putInt(KEY_PLAT_WX, Math.max(0, p.getInt(KEY_PLAT_WX, 0) - 1));
            } else if ("bilibili".equals(target.platform)) {
                ed.putInt(KEY_PLAT_BILI, Math.max(0, p.getInt(KEY_PLAT_BILI, 0) - 1));
            }
            if (target.fileName.equals(p.getString(KEY_LAST, ""))) {
                ed.putString(KEY_LAST, newFirstFile);
            }
            ed.apply();
        }
        return removed;
    }

    public static int platformCount(Context context, String platform) {
        SharedPreferences p = prefs(context);
        if ("xiaohongshu".equals(platform)) return p.getInt(KEY_PLAT_XHS, 0);
        if ("douyin".equals(platform)) return p.getInt(KEY_PLAT_DY, 0);
        if ("wechat".equals(platform)) return p.getInt(KEY_PLAT_WX, 0);
        if ("bilibili".equals(platform)) return p.getInt(KEY_PLAT_BILI, 0);
        return 0;
    }

    public static List<CaptureRecord> recentCaptures(Context context) {
        List<CaptureRecord> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(context).getString(KEY_CAPTURE_FEED, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new CaptureRecord(
                        o.optString("p", ""),
                        o.optString("t", ""),
                        o.optString("f", ""),
                        o.optLong("ms", 0L),
                        o.optBoolean("s", false),
                        o.optString("u", ""),
                        o.optString("d", ""),
                        o.optString("sn", ""),
                        o.optString("url", "")
                ));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

}
