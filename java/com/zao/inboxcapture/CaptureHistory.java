package com.zao.inboxcapture;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Index of captures in the currently selected document tree. Call load off the UI thread. */
final class CaptureHistory {
    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern LEGACY_ID = Pattern.compile("\\d{8}-\\d{6}-[0-9a-fA-F]{4}");
    private static final Pattern DATED_ID = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{6}_[0-9a-fA-F]{8}");
    private static final String ORIGINAL = "\n## 原始内容\n";
    private static final String SCREENSHOT = "\n## 补充截图 / 备注";
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE};

    private static Map<String, CachedNote> previous = new HashMap<>();

    static synchronized List<Prefs.CaptureRecord> load(Context context) throws IOException {
        String selected = Prefs.treeUri(context);
        if (selected.isEmpty()) return Prefs.recentCaptures(context);
        Uri tree = Uri.parse(selected);
        List<Prefs.CaptureRecord> records = new ArrayList<>();
        Map<String, CachedNote> next = new HashMap<>();
        try {
            readDirectory(context.getContentResolver(), tree, DocumentsContract.getTreeDocumentId(tree),
                    "", records, next);
        } catch (SecurityException | IllegalArgumentException exc) {
            throw new IOException("无法读取收纳目录", exc);
        }
        records.sort(Comparator.comparingLong((Prefs.CaptureRecord record) -> record.timeMs)
                .reversed().thenComparing(record -> record.fileName));
        previous = next;
        return records;
    }

    private static void readDirectory(ContentResolver resolver, Uri tree, String id, String prefix,
                                      List<Prefs.CaptureRecord> records, Map<String, CachedNote> next)
            throws IOException {
        checkInterrupted();
        Map<String, String> children = new HashMap<>();
        List<Doc> notes = new ArrayList<>();
        Map<String, String> directories = new HashMap<>();
        Uri listing = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        try (Cursor cursor = resolver.query(listing, COLUMNS, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取收纳目录");
            while (cursor.moveToNext()) {
                checkInterrupted();
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                if (docId == null || !NoteWriter.simpleName(name)) continue;
                if (Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    if ((prefix.isEmpty() && (DAY.matcher(name).matches() || name.equals("详情")))
                            || (DAY.matcher(prefix).matches() && name.equals("详情"))) {
                        directories.put(name, docId);
                    }
                } else {
                    children.put(name, docId);
                    if (name.toLowerCase(Locale.ROOT).endsWith(".md")) {
                        notes.add(new Doc(name, docId, cursor.getLong(3), cursor.getLong(4)));
                    }
                }
            }
        }
        for (Doc doc : notes) {
            checkInterrupted();
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc.id);
            String key = uri.toString();
            CachedNote cached = previous.get(key);
            if (cached == null || doc.modified <= 0 || doc.size <= 0 ||
                    cached.modified != doc.modified || cached.size != doc.size) {
                String raw = NoteWriter.readText(resolver, uri, 96 * 1024);
                if (raw == null || raw.isEmpty()) continue;
                cached = new CachedNote(doc.modified, doc.size, parse(raw));
            }
            next.put(key, cached);
            Note note = cached.note;
            if (note == null) continue;
            String shotId = children.get(note.shot);
            String shotUri = shotId == null ? "" : DocumentsContract.buildDocumentUriUsingTree(tree, shotId).toString();
            String path = prefix.isEmpty() ? doc.name : prefix + "/" + doc.name;
            records.add(new Prefs.CaptureRecord(note.platform, note.title, path, note.time, shotId != null,
                    shotUri, key, note.shot, note.url, note.id));
        }
        for (Map.Entry<String, String> directory : directories.entrySet()) {
            String path = prefix.isEmpty() ? directory.getKey() : prefix + "/" + directory.getKey();
            readDirectory(resolver, tree, directory.getValue(), path, records, next);
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("收纳目录读取已取消");
    }

    static Note parse(String raw) {
        if (raw == null) return null;
        String text = raw.replace("\r\n", "\n");
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (!text.startsWith("---\n")) return null;
        int fence = text.indexOf("\n---\n", 4);
        if (fence < 0) return null;
        Map<String, String> fields = new HashMap<>();
        for (String line : text.substring(4, fence).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) fields.put(line.substring(0, colon), NoteWriter.stripYamlQuotes(line.substring(colon + 1).trim()));
        }
        String id = fields.getOrDefault("id", "");
        String url = fields.getOrDefault("url", "");
        String platform = NoteWriter.platformOf(url);
        if ((!DATED_ID.matcher(id).matches() && !LEGACY_ID.matcher(id).matches()) ||
                platform.isEmpty() || !platform.equals(fields.get("platform"))) return null;
        long time;
        try {
            time = OffsetDateTime.parse(fields.getOrDefault("created", "")).toInstant().toEpochMilli();
        } catch (DateTimeException invalid) {
            return null;
        }
        int bodyStart = text.indexOf(ORIGINAL, fence + 4);
        if (bodyStart < 0) return null;
        bodyStart += ORIGINAL.length();
        int shotStart = text.indexOf(SCREENSHOT, bodyStart);
        String body = text.substring(bodyStart, shotStart < 0 ? text.length() : shotStart);
        String shot = shotStart < 0 ? "" : NoteWriter.embeddedShotName(text.substring(shotStart + SCREENSHOT.length()));
        return new Note(id, time, platform, url, NoteWriter.extractTitlePreview(body.trim(), url),
                NoteWriter.simpleName(shot) ? shot : "");
    }

    static boolean ownedShot(Note note) {
        if (note.shot.equals(note.id + ".jpg")) return true;
        // The original flat-file writer generated a separate timestamped ID for screenshots.
        return LEGACY_ID.matcher(note.id).matches() && note.shot.endsWith(".jpg")
                && LEGACY_ID.matcher(note.shot.substring(0, note.shot.length() - 4)).matches();
    }

    static final class Note {
        final long time;
        final String id, platform, url, title, shot;
        Note(String id, long time, String platform, String url, String title, String shot) {
            this.id = id;
            this.time = time;
            this.platform = platform;
            this.url = url;
            this.title = title;
            this.shot = shot;
        }
    }

    private static final class CachedNote {
        final long modified, size;
        final Note note;
        CachedNote(long modified, long size, Note note) {
            this.modified = modified;
            this.size = size;
            this.note = note;
        }
    }

    private static final class Doc {
        final String name, id;
        final long modified, size;
        Doc(String name, String id, long modified, long size) {
            this.name = name;
            this.id = id;
            this.modified = modified;
            this.size = size;
        }
    }
    private CaptureHistory() {}
}
