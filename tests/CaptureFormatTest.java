// SPDX-License-Identifier: AGPL-3.0-only
package app.chatcapture.android;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public final class CaptureFormatTest {
    public static void main(String[] args) throws Exception {
        List<CaptureItem> items = Arrays.asList(
                CaptureItem.text("甲\n2026-01-01 10:00\n看这张图"),
                CaptureItem.text("\n乙\n2026-01-01 10:00\n[图片]\n"),
                CaptureItem.asset(new byte[]{0, 1, 2, 3}, "截图.png", "png"),
                CaptureItem.text("文件后续\t\"内容\"\\尾" + (char) 1 + "\n"),
                CaptureItem.asset("sample file".getBytes(StandardCharsets.UTF_8), "附件.txt", "txt")
        );

        String expectedPlain = "甲\n2026-01-01 10:00\n看这张图\n\n乙\n2026-01-01 10:00\n[图片]\n"
                + "文件后续\t\"内容\"\\尾" + (char) 1 + "\n";
        String plain = CaptureArchive.plainText(items);
        if (!expectedPlain.equals(plain)) throw new AssertionError("Text nodes were not joined in order");
        if (plain.contains("截图.png") || plain.contains("sample file")) {
            throw new AssertionError("Text export included an asset payload");
        }
        if (!CaptureArchive.plainText(Arrays.asList(items.get(2), items.get(4))).isEmpty()) {
            throw new AssertionError("Asset-only text export should be empty");
        }

        try (FileOutputStream output = new FileOutputStream(args[0])) {
            CaptureArchive.writeZip(items, output);
        }
        System.out.println("Android text and ZIP fixture written");
    }
}
