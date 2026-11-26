package com.zao.inboxcapture;

import android.net.Uri;

/** 一次自动截图的结果：文件名 + SAF 文档 URI。 */
public final class CapturedShot {
    public final String fileName;
    public final Uri uri;

    public CapturedShot(String fileName, Uri uri) {
        this.fileName = fileName;
        this.uri = uri;
    }
}
