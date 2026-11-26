package com.zao.inboxcapture;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 把当天的训练与体重记录写进同一个 00-Inbox 的「健身-YYYY-MM-DD.md」（带 status: done 供 Worker 跳过）。 */
public final class FitnessStore {
    public static final class Saved {
        public final String fileName;
        public final boolean appended;

        Saved(String fileName, boolean appended) {
            this.fileName = fileName;
            this.appended = appended;
        }
    }

    private FitnessStore() {}

    public static Saved saveSession(Context context, String date, Prefs.WorkoutRecord record,
                                    String note) throws Exception {
        return saveSession(context, date, record, note, false);
    }

    public static Saved saveSession(Context context, String date, Prefs.WorkoutRecord record,
                                    String note, boolean isBackfill) throws Exception {
        if (record == null || record.exercises.isEmpty()) {
            throw new IllegalArgumentException("还没有完成任何一组训练");
        }
        String headingTime = isBackfill
                ? "补录"
                : new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        String section = buildSessionSection(headingTime, record, note);
        return appendSection(context, date, section);
    }

    public static Saved saveBodyWeight(Context context, String date, double kg) throws Exception {
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        String section = "## " + time + " · 体重\n\n- 体重：" + formatKg(kg) + " kg\n";
        return appendSection(context, date, section);
    }

    private static Saved appendSection(Context context, String date, String section) throws Exception {
        String rawTree = Prefs.treeUri(context);
        if (rawTree.isEmpty()) {
            throw new NoteWriter.NotConfiguredException();
        }
        Uri tree = Uri.parse(rawTree);
        ContentResolver resolver = context.getContentResolver();
        String treeId;
        try {
            treeId = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception exc) {
            throw new NoteWriter.NotConfiguredException();
        }
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId);
        String fileName = "健身-" + date + ".md";
        Uri document = findDocument(resolver, tree, treeId, fileName);
        boolean appended = document != null;

        String existing = "";
        if (document != null) {
            existing = readAll(resolver, document);
            if (existing == null) {
                throw new Exception("读取当天记录失败，为避免覆盖，本次未写入");
            }
        }
        String content;
        if (existing.trim().isEmpty()) {
            content = buildHeader(date) + "\n" + section;
        } else {
            while (existing.endsWith("\n")) {
                existing = existing.substring(0, existing.length() - 1);
            }
            content = existing + "\n\n" + section;
        }

        if (document == null) {
            document = createDocument(resolver, parent, fileName);
            if (document == null) {
                throw new Exception("无法创建健身记录文件");
            }
        }
        try (OutputStream out = resolver.openOutputStream(document, "wt")) {
            if (out == null) {
                throw new Exception("无法写入健身记录文件");
            }
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        return new Saved(fileName, appended);
    }

    private static String buildHeader(String date) {
        return "---\n"
                + "type: fitness\n"
                + "date: " + date + "\n"
                + "status: done\n"
                + "---\n\n"
                + "# " + date + " 训练记录\n";
    }

    public static String buildSessionSection(String time, Prefs.WorkoutRecord rec, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(time);
        if (rec.title != null && !rec.title.trim().isEmpty()) {
            sb.append(" · ").append(rec.title.trim());
        }
        if (rec.durationSec > 0) {
            int mins = Math.max(1, Math.round(rec.durationSec / 60f));
            sb.append(" · ").append(mins).append(" 分钟");
        }
        sb.append("\n\n");

        if (rec.warmup != null && !rec.warmup.trim().isEmpty()) {
            sb.append("- 热身：").append(rec.warmup.trim()).append("\n");
        }

        for (Prefs.ExerciseBlock ex : rec.exercises) {
            List<Prefs.SetItem> doneSets = new ArrayList<>();
            for (Prefs.SetItem s : ex.sets) {
                if (s.done) doneSets.add(s);
            }
            if (doneSets.isEmpty()) continue;
            sb.append("- ").append(ex.name).append("：");
            sb.append(formatSets(doneSets));
            sb.append("（").append(doneSets.size()).append(" 组）\n");
        }
        if (rec.prSummary != null && !rec.prSummary.trim().isEmpty()) {
            sb.append("\n> 新纪录：").append(rec.prSummary.trim()).append("\n");
        }
        if (note != null && !note.trim().isEmpty()) {
            sb.append("\n备注：").append(note.trim()).append("\n");
        }
        return sb.toString();
    }

    public static String formatSets(List<Prefs.SetItem> sets) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        boolean firstGroup = true;
        while (i < sets.size()) {
            double w = sets.get(i).weightKg;
            StringBuilder repsPart = new StringBuilder();
            repsPart.append(sets.get(i).reps);
            int j = i + 1;
            while (j < sets.size() && Math.abs(sets.get(j).weightKg - w) < 0.01) {
                repsPart.append(" / ").append(sets.get(j).reps);
                j++;
            }
            if (!firstGroup) sb.append("，");
            firstGroup = false;
            if (w <= 0.01) {
                sb.append("自重 × ").append(repsPart);
            } else {
                sb.append(formatKg(w)).append("kg × ").append(repsPart);
            }
            i = j;
        }
        return sb.toString();
    }

    public static String formatKg(double kg) {
        if (Math.abs(kg - Math.round(kg)) < 0.01) {
            return String.valueOf(Math.round(kg));
        }
        return String.format(Locale.US, "%.1f", kg);
    }

    private static Uri findDocument(ContentResolver resolver, Uri tree, String treeId, String name)
            throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        try (Cursor cursor = resolver.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        }, null, null, null)) {
            if (cursor == null) {
                throw new Exception("无法读取 Inbox 目录，为避免覆盖或重名，本次未写入");
            }
            while (cursor.moveToNext()) {
                String displayName = cursor.getString(1);
                if (name.equals(displayName)) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                }
            }
        }
        return null;
    }

    private static Uri createDocument(ContentResolver resolver, Uri parent, String name) {
        Uri created = null;
        try {
            created = DocumentsContract.createDocument(resolver, parent, "text/markdown", name);
        } catch (Exception ignored) {
            created = null;
        }
        if (created == null) {
            try {
                created = DocumentsContract.createDocument(resolver, parent, "text/plain", name);
            } catch (Exception ignored) {
                created = null;
            }
        }
        if (created == null) {
            return null;
        }
        String actual = queryName(resolver, created);
        if (actual != null && !actual.toLowerCase(Locale.US).endsWith(".md")) {
            try {
                Uri renamed = DocumentsContract.renameDocument(resolver, created, name);
                if (renamed != null) {
                    created = renamed;
                }
            } catch (Exception ignored) {
            }
        }
        return created;
    }

    private static String readAll(ContentResolver resolver, Uri uri) {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String queryName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
