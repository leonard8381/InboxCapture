package com.zao.inboxcapture;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将一条收纳保存为 Markdown 和可选的截图。 */
public final class NoteWriter {
    private static final String TAG = "InboxCapture";
    static final String FOLDER_NAME = "test";
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s<>\"'`）】\\]，。！？、；：]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BILIBILI_VIDEO_PATH =
            Pattern.compile("/(?:video/(?:BV[A-Z0-9]+|av[0-9]+)|bangumi/play/(?:ep|ss)[0-9]+)/?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern BILIBILI_SHORT_PATH = Pattern.compile("/[A-Za-z0-9]+/?");
    private static final Pattern EMBED_SHOT_PATTERN = Pattern.compile(
            "!\\[\\[([^\\]\\r\\n]+\\.(?:jpg|jpeg|png|webp))\\]\\]"
                    + "|!\\[[^\\]\\r\\n]*\\]\\(([^)\\r\\n]+\\.(?:jpg|jpeg|png|webp))\\)",
            Pattern.CASE_INSENSITIVE);

    public static final class CaptureDetail {
        public final String url;
        public final String status;
        public final String body;
        public final Uri shotDocUri;
        public final boolean foundInVault;

        CaptureDetail(String url, String status, String body, Uri shotDocUri, boolean foundInVault) {
            this.url = url == null ? "" : url;
            this.status = status == null ? "" : status;
            this.body = body == null ? "" : body;
            this.shotDocUri = shotDocUri;
            this.foundInVault = foundInVault;
        }
    }

    public static final class DeleteResult {
        public final boolean mdDeleted;
        public final boolean mdNotFound;
        public final boolean shotDeleted;
        public final String error;

        DeleteResult(boolean mdDeleted, boolean mdNotFound, boolean shotDeleted, String error) {
            this.mdDeleted = mdDeleted;
            this.mdNotFound = mdNotFound;
            this.shotDeleted = shotDeleted;
            this.error = error;
        }
    }

    public static final class Saved {
        public final String fileName;
        public final String platform;

        Saved(String fileName, String platform) {
            this.fileName = fileName;
            this.platform = platform;
        }
    }

    public static final class NotConfiguredException extends Exception {
        NotConfiguredException() {
            super("请先选择 test 文件夹");
        }
    }

    private NoteWriter() {}

    public static String folderName(Context context) {
        String raw = Prefs.treeUri(context);
        return raw.isEmpty() ? "" : folderName(context, Uri.parse(raw));
    }

    static String folderName(Context context, Uri tree) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(tree);
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
            String name = queryDisplayName(context.getContentResolver(), document);
            return name == null ? "" : name;
        } catch (IllegalArgumentException exc) {
            return "";
        }
    }

    static Uri captureTree(Context context) {
        String raw = Prefs.treeUri(context);
        if (raw.isEmpty()) return null;
        Uri tree = Uri.parse(raw);
        return FOLDER_NAME.equals(folderName(context, tree)) ? tree : null;
    }

    public static String platformOf(String url) {
        if (url == null) {
            return "";
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception ignored) {
            return "";
        }
        String host = uri.getHost();
        if (host == null) {
            return "";
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("douyin.com") || host.endsWith(".douyin.com")) {
            return "douyin";
        }
        if (host.equals("xiaohongshu.com") || host.endsWith(".xiaohongshu.com")
                || host.equals("xhslink.com") || host.endsWith(".xhslink.com")
                || host.equals("xhslink.cn") || host.endsWith(".xhslink.cn")) {
            return "xiaohongshu";
        }
        if (host.equals("weixin.qq.com") || host.endsWith(".weixin.qq.com")) {
            return "wechat";
        }
        String scheme = uri.getScheme();
        if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && uri.getRawUserInfo() == null) {
            String path = uri.getRawPath();
            if (host.equals("b23.tv") && BILIBILI_SHORT_PATH.matcher(path).matches()) {
                return "bilibili";
            }
            if ((host.equals("bilibili.com") || host.endsWith(".bilibili.com"))
                    && BILIBILI_VIDEO_PATH.matcher(path).matches()) {
                return "bilibili";
            }
        }
        return "";
    }

    public static String platformLabel(String platform) {
        if ("douyin".equals(platform)) {
            return "抖音";
        }
        if ("xiaohongshu".equals(platform)) {
            return "小红书";
        }
        if ("wechat".equals(platform)) {
            return "公众号";
        }
        if ("bilibili".equals(platform)) {
            return "B 站";
        }
        return platform;
    }

    public static String findSupportedUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String url = trimUrl(matcher.group());
            if (!platformOf(url).isEmpty()) {
                return url;
            }
        }
        return null;
    }

    private static String trimUrl(String url) {
        int end = url.length();
        String trailing = ".,;:!?)]}>，。！？、；：）】》」』…";
        while (end > 0 && trailing.indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    public static Saved save(Context context, String text) throws Exception {
        return save(context, text, null);
    }

    public static synchronized Saved save(Context context, String text, CapturedShot shot) throws Exception {
        Uri tree = captureTree(context);
        if (tree == null) {
            throw new NotConfiguredException();
        }
        String url = findSupportedUrl(text);
        if (url == null) {
            throw new IOException("没有识别到支持的链接");
        }
        String platform = platformOf(url);
        ContentResolver resolver = context.getContentResolver();

        Date now = new Date();
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat timeFormat = new SimpleDateFormat("HHmmss", Locale.US);
        timeFormat.setTimeZone(dayFormat.getTimeZone());
        String day = dayFormat.format(now);
        String id = day + "_" + timeFormat.format(now) + "_"
                + UUID.randomUUID().toString().substring(0, 8);
        String displayName = id + ".md";
        String relativeName = day + "/" + displayName;

        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri parent = findDocument(resolver, tree, rootId, day, DocumentsContract.Document.MIME_TYPE_DIR);
        if (parent == null) {
            Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
            parent = createNamedDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, day);
        }

        Uri image = null;
        Uri note = null;
        String imageName = shot == null ? null : id + ".jpg";
        try {
            if (shot != null) {
                image = createNamedDocument(resolver, parent, "image/jpeg", imageName);
                try (InputStream input = new FileInputStream(shot.file);
                     OutputStream output = openOutput(resolver, image)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                }
            }
            try {
                note = createNamedDocument(resolver, parent, "text/markdown", displayName);
            } catch (IOException unsupportedMarkdown) {
                note = createNamedDocument(resolver, parent, "text/plain", displayName);
            }
            try (OutputStream output = openOutput(resolver, note)) {
                String content = buildContent(text, url, platform, id, now, imageName);
                output.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception exc) {
            if (note != null) ShotStore.deleteUri(resolver, note);
            if (image != null) ShotStore.deleteUri(resolver, image);
            throw exc;
        }

        Prefs.bumpSaved(context);
        Prefs.setLastSaved(context, relativeName);
        Prefs.recordCapture(context, platform, extractTitlePreview(text, url), relativeName,
                image != null, image == null ? null : image.toString(),
                note.toString(), imageName, url);
        Log.i(TAG, "saved " + relativeName + " platform=" + platform);
        return new Saved(relativeName, platform);
    }

    private static OutputStream openOutput(ContentResolver resolver, Uri uri) throws IOException {
        OutputStream output = resolver.openOutputStream(uri, "w");
        if (output == null) {
            throw new IOException("无法打开文件写入流");
        }
        return output;
    }

    private static Uri createNamedDocument(ContentResolver resolver, Uri parent, String mimeType,
                                           String name) throws IOException {
        Uri created = DocumentsContract.createDocument(resolver, parent, mimeType, name);
        if (created == null) {
            throw new IOException("无法创建 " + name);
        }
        String actualName = queryDisplayName(resolver, created);
        if (actualName == null || name.equals(actualName)) {
            return created;
        }
        Uri document = created;
        try {
            Uri renamed = DocumentsContract.renameDocument(resolver, created, name);
            if (renamed != null) {
                document = renamed;
                if (name.equals(queryDisplayName(resolver, document))) {
                    return document;
                }
            }
        } catch (Exception exc) {
            ShotStore.deleteUri(resolver, document);
            throw new IOException("无法使用文件名 " + name, exc);
        }
        ShotStore.deleteUri(resolver, document);
        throw new IOException("无法使用文件名 " + name);
    }

    /** 读取单条灵感在仓库中的完整详情（正文、原文链接、处理状态、截图 URI）。 */
    public static CaptureDetail readCaptureDetail(Context context, Prefs.CaptureRecord rec) {
        if (rec == null) {
            return new CaptureDetail("", "", "", null, false);
        }
        String fallbackUrl = rec.url;
        Uri fallbackShot = null;

        String rawTree = Prefs.treeUri(context);
        if (rawTree.isEmpty()) {
            return new CaptureDetail(fallbackUrl, "", rec.title, fallbackShot, false);
        }
        ContentResolver resolver = context.getContentResolver();
        Uri tree = Uri.parse(rawTree);
        if (!rec.docUri.isEmpty() && !inTree(Uri.parse(rec.docUri), tree)) {
            return new CaptureDetail(fallbackUrl, "", rec.title, null, false);
        }
        if (!rec.shotUri.isEmpty() && inTree(Uri.parse(rec.shotUri), tree)) {
            fallbackShot = Uri.parse(rec.shotUri);
        }
        String treeDocId;
        try {
            treeDocId = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception exc) {
            return new CaptureDetail(fallbackUrl, "", rec.title, fallbackShot, false);
        }

        Uri mdUri = null;
        if (!rec.docUri.isEmpty()) {
            Uri candidate = Uri.parse(rec.docUri);
            if (documentExists(resolver, candidate)) {
                mdUri = candidate;
            }
        }
        if (mdUri == null && !rec.fileName.isEmpty()) {
            try {
                mdUri = findRelativeDocument(resolver, tree, treeDocId, rec.fileName);
            } catch (Exception ignored) {
            }
        }
        if (mdUri == null) {
            return new CaptureDetail(fallbackUrl, "", rec.title, fallbackShot, false);
        }

        String raw = readText(resolver, mdUri, 65536);
        if (raw == null) {
            return new CaptureDetail(fallbackUrl, "", rec.title, fallbackShot, false);
        }

        String parsedUrl = fallbackUrl;
        String status = "";
        String bodyPart = raw;

        if (raw.startsWith("---\n") || raw.startsWith("---\r\n")) {
            int start = raw.indexOf('\n') + 1;
            int end = raw.indexOf("\n---", start);
            if (end > start) {
                String fm = raw.substring(start, end);
                String[] lines = fm.split("\\r?\\n");
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("url:")) {
                        String val = stripYamlQuotes(trimmed.substring(4).trim());
                        if (!val.isEmpty()) parsedUrl = val;
                    } else if (trimmed.startsWith("status:")) {
                        status = stripYamlQuotes(trimmed.substring(7).trim());
                    }
                }
                int afterFence = raw.indexOf('\n', end + 4);
                bodyPart = (afterFence >= 0) ? raw.substring(afterFence + 1) : "";
            }
        }

        // 兜底：若 frontmatter 未取到链接，从正文里提取支持的链接
        if (parsedUrl.isEmpty()) {
            String found = findSupportedUrl(bodyPart);
            if (found != null) parsedUrl = found;
        }

        // 解析截图 URI（列表 URI 缺失时，从笔记中的图片链接找回）
        Uri shotUri = fallbackShot;
        if (shotUri != null && !documentExists(resolver, shotUri)) {
            shotUri = null;
        }
        if (shotUri == null) {
            String shotName = rec.shotName;
            if (shotName.isEmpty()) {
                shotName = embeddedShotName(bodyPart);
            }
            if (simpleName(shotName)) {
                try {
                    shotUri = findRelativeDocument(resolver, tree, treeDocId, siblingPath(rec.fileName, shotName));
                } catch (Exception ignored) {
                }
            }
        }

        // 整理展示正文：去掉图片占位语法和空标题段落，保留原始内容与后续整理报告
        String cleanedBody = cleanBodyForPreview(bodyPart);
        if (cleanedBody.isEmpty()) {
            cleanedBody = rec.title;
        }
        return new CaptureDetail(parsedUrl, status, cleanedBody, shotUri, true);
    }

    static String readText(ContentResolver resolver, Uri uri, int maxBytes) {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while (total < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - total))) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    static String stripYamlQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return s;
    }

    private static String cleanBodyForPreview(String body) {
        String s = EMBED_SHOT_PATTERN.matcher(body).replaceAll("");
        s = s.replace("## 原始内容", "").replace("## 补充截图 / 备注", "");
        s = s.replaceAll("\\r\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        return s;
    }

    /** 同步删除仓库里的 .md 笔记以及对应的自动截图文件。 */
    public static DeleteResult deleteCapture(Context context, Prefs.CaptureRecord rec) {
        if (rec == null) {
            return new DeleteResult(false, true, false, null);
        }
        String rawTree = Prefs.treeUri(context);
        if (rawTree.isEmpty()) {
            return new DeleteResult(false, false, false, "请先选择 test 文件夹");
        }
        ContentResolver resolver = context.getContentResolver();
        Uri tree = Uri.parse(rawTree);
        String treeDocId;
        try {
            treeDocId = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception exc) {
            return new DeleteResult(false, false, false, "收纳目录授权已失效，请在设置中重新选择");
        }

        if (!rec.docUri.isEmpty() && !inTree(Uri.parse(rec.docUri), tree)) {
            return new DeleteResult(false, false, false, "收纳目录已更换，请刷新列表");
        }
        try {
            Uri mdUri = findRelativeDocument(resolver, tree, treeDocId, rec.fileName);
            if (mdUri == null) return new DeleteResult(false, true, false, null);
            CaptureHistory.Note note = CaptureHistory.parse(readText(resolver, mdUri, 96 * 1024));
            String name = rec.fileName.substring(rec.fileName.lastIndexOf('/') + 1);
            boolean sameNote = note != null && (rec.captureId.isEmpty()
                    ? name.equals(note.id + ".md") : rec.captureId.equals(note.id));
            if (!sameNote) return new DeleteResult(false, false, false, "笔记已更改，请刷新列表");
            Uri photo = null;
            if (CaptureHistory.ownedShot(note)) {
                photo = findRelativeDocument(resolver, tree, treeDocId, siblingPath(rec.fileName, note.shot));
                // Legacy screenshots have an independent ID. Keep any file also used by another capture.
                if (photo != null && !note.shot.equals(note.id + ".jpg")) {
                    for (Prefs.CaptureRecord other : CaptureHistory.load(context)) {
                        if (!other.fileName.equals(rec.fileName) && (photo.toString().equals(other.shotUri)
                                || siblingPath(rec.fileName, note.shot).equals(
                                        siblingPath(other.fileName, other.captureId + ".jpg")))) {
                            photo = null;
                            break;
                        }
                    }
                }
            }
            if (!DocumentsContract.deleteDocument(resolver, mdUri)) {
                return new DeleteResult(false, false, false, "笔记删除失败");
            }
            boolean shotDeleted = false;
            if (photo != null) {
                try {
                    shotDeleted = DocumentsContract.deleteDocument(resolver, photo);
                } catch (Exception exc) {
                    Log.w(TAG, "无法删除配套截图", exc);
                }
            }
            return new DeleteResult(true, false, shotDeleted, null);
        } catch (FileNotFoundException missing) {
            return new DeleteResult(false, true, false, null);
        } catch (Exception exc) {
            return new DeleteResult(false, false, false, "删除仓库文件失败：" + safeMsg(exc));
        }
    }

    private static boolean documentExists(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(
                uri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String siblingPath(String relativeName, String name) {
        int separator = relativeName.lastIndexOf('/');
        return separator < 0 ? name : relativeName.substring(0, separator + 1) + name;
    }

    static boolean simpleName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    private static boolean inTree(Uri document, Uri tree) {
        return DocumentsContract.isTreeUri(document) && tree.getAuthority().equals(document.getAuthority())
                && DocumentsContract.getTreeDocumentId(tree).equals(DocumentsContract.getTreeDocumentId(document));
    }

    private static Uri findRelativeDocument(ContentResolver resolver, Uri tree, String rootId,
                                            String relativePath) throws IOException {
        String[] parts = relativePath.split("/", -1);
        String parentId = rootId;
        for (int i = 0; i < parts.length; i++) {
            if (!simpleName(parts[i])) return null;
            boolean last = i == parts.length - 1;
            Uri doc = findDocument(resolver, tree, parentId, parts[i],
                    last ? null : DocumentsContract.Document.MIME_TYPE_DIR);
            if (doc == null || last) return doc;
            parentId = DocumentsContract.getDocumentId(doc);
        }
        return null;
    }

    private static Uri findDocument(ContentResolver resolver, Uri tree, String treeId, String name,
                                    String mimeType) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        String[] cols = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor c = resolver.query(children, cols, null, null, null)) {
            if (c == null) {
                throw new IOException("目录查询返回空");
            }
            while (c.moveToNext()) {
                String actualType = c.getString(2);
                boolean matchesType = mimeType == null
                        ? actualType != null && !DocumentsContract.Document.MIME_TYPE_DIR.equals(actualType)
                        : mimeType.equals(actualType);
                if (name.equals(c.getString(1)) && matchesType) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                }
            }
        }
        return null;
    }

    static String embeddedShotName(String text) {
        Matcher match = EMBED_SHOT_PATTERN.matcher(text);
        if (!match.find()) return "";
        return match.group(1) != null ? match.group(1) : match.group(2);
    }

    private static String safeMsg(Exception exc) {
        String m = exc.getMessage();
        return (m == null || m.isEmpty()) ? exc.getClass().getSimpleName() : m;
    }

    public static String extractTitlePreview(String text, String url) {
        if (text == null) return "";
        String cleaned = text;
        if (url != null && !url.isEmpty()) {
            cleaned = cleaned.replace(url, " ");
        }
        cleaned = cleaned.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return url == null ? "" : url;
        }
        return cleaned.length() > 64 ? cleaned.substring(0, 64) + "…" : cleaned;
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
            // 名字查询失败不影响写入。
        }
        return null;
    }

    private static String buildContent(String text, String url, String platform, String id,
                                        Date now, String screenshotName) {
        String created = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(now);
        String trimmed = text.trim();
        if (trimmed.length() > 20000) {
            trimmed = trimmed.substring(0, 20000);
        }
        return "---\n"
                + "id: " + id + "\n"
                + "created: " + created + "\n"
                + "source: vivo\n"
                + "platform: " + platform + "\n"
                + "url: " + yaml(url) + "\n"
                + "status: pending\n"
                + "report:\n"
                + "---\n\n"
                + "## 原始内容\n\n"
                + trimmed + "\n\n"
                + "## 补充截图 / 备注\n"
                + (screenshotName == null || screenshotName.isEmpty()
                        ? ""
                        : "\n![截图](" + screenshotName + ")\n");
    }

    private static String yaml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needsQuote = value.startsWith("-") || value.startsWith("?") || value.startsWith(":")
                || value.startsWith("[") || value.startsWith("{") || value.startsWith("#")
                || value.contains(": ") || value.contains(" #") || value.contains("\n")
                || !value.equals(value.trim());
        if (!needsQuote) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
