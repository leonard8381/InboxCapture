package com.zao.inboxcapture;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 把一条剪贴板文本写成 Obsidian Inbox 里的 Markdown。 */
public final class NoteWriter {
    private static final String TAG = "InboxCapture";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s<>\"'`）】\\]，。！？、；：]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BILIBILI_VIDEO_PATH =
            Pattern.compile("/(?:video/(?:BV[A-Z0-9]+|av[0-9]+)|bangumi/play/(?:ep|ss)[0-9]+)/?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern BILIBILI_SHORT_PATH = Pattern.compile("/[A-Za-z0-9]+/?");
    private static final Pattern EMBED_SHOT_PATTERN =
            Pattern.compile("!\\[\\[([^\\]\\r\\n]+\\.(?:jpg|jpeg|png|webp))\\]\\]", Pattern.CASE_INSENSITIVE);

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
            super("还没有选择 Obsidian Inbox 文件夹");
        }
    }

    private NoteWriter() {}

    public static String folderName(Context context) {
        String raw = Prefs.treeUri(context);
        if (raw.isEmpty()) {
            return "";
        }
        Uri tree = Uri.parse(raw);
        try (Cursor cursor = context.getContentResolver().query(
                tree, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
            // 某些 provider 不支持查询，退回 URI 末段。
        }
        String last = tree.getLastPathSegment();
        if (last == null) {
            return "";
        }
        int colon = last.indexOf(':');
        if (colon >= 0) {
            last = last.substring(colon + 1);
        }
        int slash = last.lastIndexOf('/');
        if (slash >= 0) {
            last = last.substring(slash + 1);
        }
        return last;
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
        return save(context, text, null, null);
    }

    public static Saved save(Context context, String text, String screenshotName) throws Exception {
        return save(context, text, screenshotName, null);
    }

    public static synchronized Saved save(Context context, String text, String screenshotName,
                                          String screenshotUri) throws Exception {
        String rawTree = Prefs.treeUri(context);
        if (rawTree.isEmpty()) {
            throw new NotConfiguredException();
        }
        String url = findSupportedUrl(text);
        if (url == null) {
            throw new IOException("没有识别到支持的链接");
        }
        String platform = platformOf(url);

        Uri tree = Uri.parse(rawTree);
        ContentResolver resolver = context.getContentResolver();
        String treeDocumentId;
        try {
            treeDocumentId = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception exc) {
            throw new IOException("Inbox 文件夹授权已失效，请重新选择");
        }
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocumentId);

        Date now = new Date();
        String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
                + "-" + String.format(Locale.US, "%04x", RANDOM.nextInt(0x10000));
        String displayName = id + ".md";

        Uri created = null;
        Exception firstError = null;
        try {
            created = DocumentsContract.createDocument(resolver, parent, "text/markdown", displayName);
        } catch (Exception exc) {
            firstError = exc;
        }
        if (created == null) {
            try {
                created = DocumentsContract.createDocument(resolver, parent, "text/plain", displayName);
            } catch (Exception exc) {
                firstError = exc;
            }
        }
        if (created == null) {
            throw new IOException("无法创建文件（文件夹可能只读）", firstError);
        }

        String actualName = queryDisplayName(resolver, created);
        if (actualName != null && !actualName.toLowerCase(Locale.US).endsWith(".md")) {
            try {
                Uri renamed = DocumentsContract.renameDocument(resolver, created, displayName);
                if (renamed != null) {
                    created = renamed;
                }
            } catch (Exception exc) {
                ShotStore.deleteUri(resolver, created);
                throw new IOException("当前文件夹不支持 .md 文件", exc);
            }
        }

        String content = buildContent(text, url, platform, id, now, screenshotName);
        try (OutputStream out = resolver.openOutputStream(created, "w")) {
            if (out == null) {
                throw new IOException("无法打开写入流");
            }
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception exc) {
            ShotStore.deleteUri(resolver, created);
            throw exc;
        }

        Prefs.bumpSaved(context);
        Prefs.setLastSaved(context, displayName);
        Prefs.recordCapture(context, platform, extractTitlePreview(text, url), displayName,
                screenshotName != null && !screenshotName.isEmpty(), screenshotUri,
                created.toString(), screenshotName, url);
        Log.i(TAG, "saved " + displayName + " platform=" + platform);
        return new Saved(displayName, platform);
    }

    /** 读取单条灵感在仓库中的完整详情（正文、原文链接、处理状态、截图 URI）。 */
    public static CaptureDetail readCaptureDetail(Context context, Prefs.CaptureRecord rec) {
        if (rec == null) {
            return new CaptureDetail("", "", "", null, false);
        }
        String fallbackUrl = rec.url;
        Uri fallbackShot = rec.shotUri.isEmpty() ? null : Uri.parse(rec.shotUri);

        String rawTree = Prefs.treeUri(context);
        if (rawTree.isEmpty()) {
            return new CaptureDetail(fallbackUrl, "", rec.title, fallbackShot, false);
        }
        ContentResolver resolver = context.getContentResolver();
        Uri tree = Uri.parse(rawTree);
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
                mdUri = findDocument(resolver, tree, treeDocId, rec.fileName);
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

        // 解析截图 URI（若列表里的 shotUri 为空，按嵌入的 ![[xxx.jpg]] 找回）
        Uri shotUri = fallbackShot;
        if (shotUri != null && !documentExists(resolver, shotUri)) {
            shotUri = null;
        }
        if (shotUri == null) {
            String shotName = rec.shotName;
            if (shotName.isEmpty()) {
                Matcher m = EMBED_SHOT_PATTERN.matcher(bodyPart);
                if (m.find()) shotName = m.group(1);
            }
            if (!shotName.isEmpty()) {
                try {
                    shotUri = findDocument(resolver, tree, treeDocId, shotName);
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

    private static String readText(ContentResolver resolver, Uri uri, int maxBytes) {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0 && total < maxBytes) {
                int take = Math.min(n, maxBytes - total);
                out.write(buf, 0, take);
                total += take;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stripYamlQuotes(String s) {
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
            return new DeleteResult(false, false, false, "还没有选择 Obsidian Inbox 目录");
        }
        ContentResolver resolver = context.getContentResolver();
        Uri tree = Uri.parse(rawTree);
        String treeDocId;
        try {
            treeDocId = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception exc) {
            return new DeleteResult(false, false, false, "Inbox 目录授权已失效，请在设置中重新选择");
        }

        // 1. 定位 .md 文档 URI
        Uri mdUri = null;
        if (!rec.docUri.isEmpty()) {
            Uri candidate = Uri.parse(rec.docUri);
            if (documentExists(resolver, candidate)) {
                mdUri = candidate;
            }
        }
        if (mdUri == null && !rec.fileName.isEmpty()) {
            try {
                mdUri = findDocument(resolver, tree, treeDocId, rec.fileName);
            } catch (Exception exc) {
                return new DeleteResult(false, false, false, "读取 Inbox 目录失败：" + safeMsg(exc));
            }
        }

        // 2. 删除前从 .md 内容中解析嵌入的截图文件名（兼容旧版本记录）
        String shotName = rec.shotName;
        if (mdUri != null && (shotName == null || shotName.isEmpty())) {
            String embedded = extractEmbeddedShotName(resolver, mdUri);
            if (embedded != null && !embedded.isEmpty()) {
                shotName = embedded;
            }
        }

        // 3. 删除 .md 文件
        boolean mdDeleted = false;
        boolean mdNotFound = (mdUri == null);
        if (mdUri != null) {
            try {
                mdDeleted = DocumentsContract.deleteDocument(resolver, mdUri);
                if (!mdDeleted) {
                    if (!documentExists(resolver, mdUri)) {
                        mdNotFound = true;
                    } else {
                        return new DeleteResult(false, false, false, "仓库文件删除失败（目录可能只读）");
                    }
                }
            } catch (FileNotFoundException notFound) {
                mdNotFound = true;
            } catch (Exception exc) {
                if (!documentExists(resolver, mdUri)) {
                    mdNotFound = true;
                } else {
                    return new DeleteResult(false, false, false, "删除仓库文件失败：" + safeMsg(exc));
                }
            }
        }

        // 4. 同步删除对应的截图文件
        boolean shotDeleted = false;
        if (!rec.shotUri.isEmpty()) {
            try {
                shotDeleted = DocumentsContract.deleteDocument(resolver, Uri.parse(rec.shotUri));
            } catch (Exception ignored) {
            }
        }
        if (!shotDeleted && shotName != null && !shotName.isEmpty()) {
            try {
                Uri shotDoc = findDocument(resolver, tree, treeDocId, shotName);
                if (shotDoc != null) {
                    shotDeleted = DocumentsContract.deleteDocument(resolver, shotDoc);
                }
            } catch (Exception ignored) {
            }
        }

        Log.i(TAG, "deleteCapture file=" + rec.fileName
                + " mdDeleted=" + mdDeleted + " mdNotFound=" + mdNotFound
                + " shotDeleted=" + shotDeleted);
        return new DeleteResult(mdDeleted, mdNotFound, shotDeleted, null);
    }

    private static boolean documentExists(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(
                uri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Uri findDocument(ContentResolver resolver, Uri tree, String treeId, String name)
            throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        String[] cols = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor c = resolver.query(children, cols, null, null, null)) {
            if (c == null) {
                throw new IOException("目录查询返回空");
            }
            while (c.moveToNext()) {
                if (name.equals(c.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                }
            }
        }
        return null;
    }

    private static String extractEmbeddedShotName(ContentResolver resolver, Uri mdUri) {
        try (InputStream in = resolver.openInputStream(mdUri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0 && total < 32768) {
                out.write(buf, 0, n);
                total += n;
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            Matcher m = EMBED_SHOT_PATTERN.matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception ignored) {
        }
        return null;
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
                        : "\n![[" + screenshotName + "]]\n");
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
