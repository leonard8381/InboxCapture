package com.zao.inboxcapture;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 本地偏好、训练历史、收纳流水（全部本地 SharedPreferences，不联网）。 */
public final class Prefs {
    private static final String NAME = "inbox_capture";
    private static final String KEY_TREE = "tree_uri";
    private static final String KEY_COUNT = "saved_count";
    private static final String KEY_LAST = "last_saved";

    private static final String KEY_CAPTURE_FEED = "capture_feed_v1";
    private static final String KEY_PLAT_XHS = "plat_xhs";
    private static final String KEY_PLAT_DY = "plat_dy";
    private static final String KEY_PLAT_WX = "plat_wx";

    private static final String KEY_WORKOUTS = "workout_history_v1";
    private static final String KEY_DRAFT = "workout_draft_v1";
    private static final String KEY_CUSTOM_EX = "custom_exercises_v1";
    private static final String KEY_WEEKLY_GOAL = "weekly_goal_v1";
    private static final String KEY_REST_SEC = "rest_seconds_v1";
    private static final String KEY_BODY_WEIGHT = "body_weight_kg_v1";
    private static final String KEY_BODY_WEIGHT_DATE = "body_weight_date_v1";
    private static final String KEY_BAR_WEIGHTS = "bar_weights_v1";

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

    public static final class SetItem {
        public double weightKg;
        public int reps;
        public boolean done;

        public SetItem(double weightKg, int reps, boolean done) {
            this.weightKg = weightKg;
            this.reps = reps;
            this.done = done;
        }
    }

    public static final class ExerciseBlock {
        public String name;
        public final List<SetItem> sets = new ArrayList<>();

        public ExerciseBlock(String name) {
            this.name = name;
        }
    }

    public static final class WorkoutRecord {
        public String date;         // yyyy-MM-dd
        public long timestampMs;
        public String title;
        public String warmup;       // e.g. 泡沫轴滚背、最伟大拉伸、青蛙趴
        public int durationSec;
        public int totalSets;
        public double totalVolumeKg;
        public String prSummary;
        public final List<ExerciseBlock> exercises = new ArrayList<>();
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

    public static int weeklyGoal(Context context) {
        int v = prefs(context).getInt(KEY_WEEKLY_GOAL, 2);
        return Math.max(1, Math.min(7, v));
    }

    public static void setWeeklyGoal(Context context, int goal) {
        prefs(context).edit().putInt(KEY_WEEKLY_GOAL, Math.max(1, Math.min(7, goal))).apply();
    }

    public static int restSeconds(Context context) {
        int v = prefs(context).getInt(KEY_REST_SEC, 90);
        return Math.max(30, Math.min(300, v));
    }

    public static void setRestSeconds(Context context, int sec) {
        prefs(context).edit().putInt(KEY_REST_SEC, Math.max(30, Math.min(300, sec))).apply();
    }

    public static double latestBodyWeight(Context context) {
        String s = prefs(context).getString(KEY_BODY_WEIGHT, "");
        if (s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s);
        } catch (Exception ignored) {
            return 0;
        }
    }

    public static String latestBodyWeightDate(Context context) {
        return prefs(context).getString(KEY_BODY_WEIGHT_DATE, "");
    }

    public static void setLatestBodyWeight(Context context, double kg, String date) {
        prefs(context).edit()
                .putString(KEY_BODY_WEIGHT, String.format(Locale.US, "%.1f", kg))
                .putString(KEY_BODY_WEIGHT_DATE, date == null ? "" : date)
                .apply();
    }

    /** 记住每个杠铃动作的空杆重量（默认 10kg，可在 10 / 15 / 20kg 间切换）。 */
    public static double barWeightFor(Context context, String exerciseName, double defaultKg) {
        if (exerciseName == null || exerciseName.isEmpty()) return defaultKg;
        try {
            JSONObject map = new JSONObject(prefs(context).getString(KEY_BAR_WEIGHTS, "{}"));
            if (map.has(exerciseName)) {
                double v = map.optDouble(exerciseName, defaultKg);
                if (v >= 0 && v <= 40) return v;
            }
        } catch (Exception ignored) {
        }
        return defaultKg;
    }

    public static void setBarWeightFor(Context context, String exerciseName, double kg) {
        if (exerciseName == null || exerciseName.isEmpty()) return;
        try {
            JSONObject map = new JSONObject(prefs(context).getString(KEY_BAR_WEIGHTS, "{}"));
            map.put(exerciseName, kg);
            prefs(context).edit().putString(KEY_BAR_WEIGHTS, map.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static List<String> customExercises(Context context) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(context).getString(KEY_CUSTOM_EX, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String n = arr.optString(i, "").trim();
                if (!n.isEmpty()) out.add(n);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void addCustomExercise(Context context, String name) {
        if (name == null) return;
        String clean = name.trim();
        if (clean.isEmpty()) return;
        List<String> cur = customExercises(context);
        if (cur.contains(clean)) return;
        cur.add(0, clean);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < cur.size() && i < 30; i++) {
            arr.put(cur.get(i));
        }
        prefs(context).edit().putString(KEY_CUSTOM_EX, arr.toString()).apply();
    }

    public static void saveDraft(Context context, String json) {
        prefs(context).edit().putString(KEY_DRAFT, json == null ? "" : json).apply();
    }

    public static String loadDraft(Context context) {
        return prefs(context).getString(KEY_DRAFT, "");
    }

    public static void clearDraft(Context context) {
        prefs(context).edit().remove(KEY_DRAFT).apply();
    }

    public static void saveWorkoutRecord(Context context, WorkoutRecord rec) {
        try {
            List<WorkoutRecord> all = workoutHistory(context);
            all.add(0, rec);
            Collections.sort(all, new Comparator<WorkoutRecord>() {
                @Override
                public int compare(WorkoutRecord a, WorkoutRecord b) {
                    String da = a.date == null ? "" : a.date;
                    String db = b.date == null ? "" : b.date;
                    int cmp = db.compareTo(da);
                    if (cmp != 0) return cmp;
                    return Long.compare(b.timestampMs, a.timestampMs);
                }
            });
            JSONArray next = new JSONArray();
            for (int i = 0; i < all.size() && i < 40; i++) {
                next.put(toJson(all.get(i)));
            }
            prefs(context).edit().putString(KEY_WORKOUTS, next.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static Set<String> trainedDateSet(Context context) {
        Set<String> dates = new HashSet<>();
        for (WorkoutRecord rec : workoutHistory(context)) {
            if (rec.date != null && !rec.date.isEmpty()) {
                dates.add(rec.date);
            }
        }
        return dates;
    }

    public static List<WorkoutRecord> workoutHistory(Context context) {
        List<WorkoutRecord> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(context).getString(KEY_WORKOUTS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                WorkoutRecord rec = fromJson(arr.getJSONObject(i));
                if (rec != null) out.add(rec);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static List<SetItem> lastExerciseSets(Context context, String exerciseName) {
        if (exerciseName == null || exerciseName.trim().isEmpty()) return null;
        List<WorkoutRecord> history = workoutHistory(context);
        for (WorkoutRecord rec : history) {
            for (ExerciseBlock block : rec.exercises) {
                if (exerciseName.equals(block.name) && !block.sets.isEmpty()) {
                    return block.sets;
                }
            }
        }
        return null;
    }

    public static double bestWeightFor(Context context, String exerciseName) {
        double best = 0;
        List<WorkoutRecord> history = workoutHistory(context);
        for (WorkoutRecord rec : history) {
            for (ExerciseBlock block : rec.exercises) {
                if (exerciseName.equals(block.name)) {
                    for (SetItem s : block.sets) {
                        if (s.weightKg > best) best = s.weightKg;
                    }
                }
            }
        }
        return best;
    }

    public static Set<Integer> trainedWeekdaysThisWeek(Context context) {
        Set<Integer> days = new HashSet<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Map<String, Integer> weekDates = new HashMap<>();
        Calendar cal = Calendar.getInstance();
        cal.setFirstDayOfWeek(Calendar.MONDAY);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int offsetFromMon = (dow == Calendar.SUNDAY) ? 6 : (dow - Calendar.MONDAY);
        cal.add(Calendar.DAY_OF_YEAR, -offsetFromMon);
        for (int i = 0; i < 7; i++) {
            weekDates.put(sdf.format(cal.getTime()), i);
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        for (WorkoutRecord rec : workoutHistory(context)) {
            Integer idx = weekDates.get(rec.date);
            if (idx != null) days.add(idx);
        }
        return days;
    }

    public static int daysSinceLastWorkout(WorkoutRecord latest) {
        if (latest == null || latest.date == null || latest.date.isEmpty()) return -1;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            long lastMs = sdf.parse(latest.date).getTime();
            String todayStr = sdf.format(Calendar.getInstance().getTime());
            long todayMs = sdf.parse(todayStr).getTime();
            return (int) Math.max(0, Math.round((todayMs - lastMs) / 86_400_000.0));
        } catch (Exception ignored) {
            return -1;
        }
    }

    public static JSONObject toJson(WorkoutRecord rec) throws Exception {
        JSONObject o = new JSONObject();
        o.put("date", rec.date == null ? "" : rec.date);
        o.put("ts", rec.timestampMs);
        o.put("title", rec.title == null ? "" : rec.title);
        o.put("warm", rec.warmup == null ? "" : rec.warmup);
        o.put("dur", rec.durationSec);
        o.put("sets", rec.totalSets);
        o.put("vol", rec.totalVolumeKg);
        o.put("pr", rec.prSummary == null ? "" : rec.prSummary);
        JSONArray exArr = new JSONArray();
        for (ExerciseBlock b : rec.exercises) {
            JSONObject bo = new JSONObject();
            bo.put("n", b.name);
            JSONArray sArr = new JSONArray();
            for (SetItem s : b.sets) {
                JSONObject so = new JSONObject();
                so.put("w", s.weightKg);
                so.put("r", s.reps);
                so.put("d", s.done);
                sArr.put(so);
            }
            bo.put("s", sArr);
            exArr.put(bo);
        }
        o.put("ex", exArr);
        return o;
    }

    public static WorkoutRecord fromJson(JSONObject o) {
        if (o == null) return null;
        WorkoutRecord rec = new WorkoutRecord();
        rec.date = o.optString("date", "");
        rec.timestampMs = o.optLong("ts", 0L);
        rec.title = o.optString("title", "自由训练");
        rec.warmup = o.optString("warm", "");
        rec.durationSec = o.optInt("dur", 0);
        rec.totalSets = o.optInt("sets", 0);
        rec.totalVolumeKg = o.optDouble("vol", 0);
        rec.prSummary = o.optString("pr", "");
        JSONArray exArr = o.optJSONArray("ex");
        if (exArr != null) {
            for (int i = 0; i < exArr.length(); i++) {
                JSONObject bo = exArr.optJSONObject(i);
                if (bo == null) continue;
                ExerciseBlock b = new ExerciseBlock(bo.optString("n", ""));
                JSONArray sArr = bo.optJSONArray("s");
                if (sArr != null) {
                    for (int j = 0; j < sArr.length(); j++) {
                        JSONObject so = sArr.optJSONObject(j);
                        if (so == null) continue;
                        b.sets.add(new SetItem(
                                so.optDouble("w", 0),
                                so.optInt("r", 0),
                                so.optBoolean("d", true)
                        ));
                    }
                }
                rec.exercises.add(b);
            }
        }
        return rec;
    }
}
