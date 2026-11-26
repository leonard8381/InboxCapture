package com.zao.inboxcapture;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

/** 删除截图的 SAF 文档；不依赖 Android 11 的截图 API。 */
public final class ShotStore {
    private ShotStore() {}

    public static void delete(Context context, CapturedShot shot) {
        if (shot == null) {
            return;
        }
        deleteUri(context.getContentResolver(), shot.uri);
    }

    static void deleteUri(ContentResolver resolver, Uri uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri);
        } catch (Exception ignored) {
            // 删除失败就留下，不影响收纳。
        }
    }
}
