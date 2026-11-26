package com.zao.inboxcapture;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

/** 清理缓存截图和未完成写入的文件。 */
public final class ShotStore {
    private ShotStore() {}

    public static void delete(CapturedShot shot) {
        if (shot != null) {
            shot.file.delete();
        }
    }

    static void deleteUri(ContentResolver resolver, Uri uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri);
        } catch (Exception ignored) {
            // 删除失败就留下，不影响收纳。
        }
    }
}
