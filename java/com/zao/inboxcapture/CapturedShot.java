package com.zao.inboxcapture;

import java.io.File;

/** 等待确认收纳的本地截图。 */
public final class CapturedShot {
    public final File file;

    public CapturedShot(File file) {
        this.file = file;
    }
}
