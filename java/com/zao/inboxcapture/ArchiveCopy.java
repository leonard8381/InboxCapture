package com.zao.inboxcapture;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Short display copy for the list. The note and its share text stay intact. */
final class ArchiveCopy {
    private static final Pattern DOUYIN = Pattern.compile(
            "^(?:[0-9]+(?:\\.[0-9]+)?\\s*)?复制打开抖音[，,、：:\\s]*(?:看看)?"
                    + "(?:【([^】]+?)的作品】)?[，,：:\\s]*");

    final String title;
    final String source;

    ArchiveCopy(String rawTitle, String platform, String platformLabel) {
        String text = rawTitle.trim();
        String byline = platformLabel;
        if ("douyin".equals(platform)) {
            Matcher match = DOUYIN.matcher(text);
            if (match.find()) {
                if (match.group(1) != null) byline += " · " + match.group(1);
                text = text.substring(match.end()).trim();
            }
        }
        title = text.isEmpty() || text.matches("(?i)https?://\\S+")
                ? platformLabel + "链接" : text;
        source = byline;
    }
}
