// SPDX-License-Identifier: AGPL-3.0-only
package app.chatcapture.android;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes the ordered v0.6 capture format without interpreting message text. */
final class CaptureArchive {
    static final int MAX_CAPTURE_BYTES = 128 * 1024 * 1024;

    private CaptureArchive() {}

    static String plainText(List<CaptureItem> items) {
        StringBuilder text = new StringBuilder();
        boolean endsWithNewline = false;
        for (CaptureItem item : items) {
            if (!item.isText()) continue;
            text.append(item.text);
            if (!item.text.isEmpty()) endsWithNewline = item.text.endsWith("\n");
            if (!endsWithNewline) {
                text.append('\n');
                endsWithNewline = true;
            }
        }
        return text.toString();
    }

    static void writeZip(List<CaptureItem> items, OutputStream destination) throws IOException {
        if (items.isEmpty()) throw new IOException("剪贴板中没有可导出的节点");
        if (items.size() > 10_000) throw new IOException("节点数量超过上限");

        List<byte[]> assets = new ArrayList<>();
        List<String> assetNames = new ArrayList<>();
        List<String> assetFiles = new ArrayList<>();
        StringBuilder itemLines = new StringBuilder();
        int textCount = 0;
        long payloadBytes = 0;
        for (CaptureItem item : items) {
            if (item.isText()) {
                byte[] bytes = item.text.getBytes(StandardCharsets.UTF_8);
                payloadBytes += bytes.length;
                if (payloadBytes > MAX_CAPTURE_BYTES) throw new IOException("捕获内容超过 128 MiB");
                itemLines.append("{\"t\":\"text\",\"v\":").append(jsonString(item.text)).append("}\n");
                textCount++;
            } else {
                if (item.data == null) throw new IOException("捕获中包含无法读取的节点");
                payloadBytes += item.data.length;
                if (payloadBytes > MAX_CAPTURE_BYTES) throw new IOException("捕获内容超过 128 MiB");
                String extension = safeExtension(item.extension);
                String filename = "asset_" + (assets.size() + 1) + "." + extension;
                assetFiles.add(filename);
                String originalName = item.name == null || item.name.isEmpty() ? filename : item.name;
                assets.add(item.data);
                assetNames.add(originalName);
                itemLines.append("{\"t\":\"asset\",\"file\":\"assets/")
                        .append(filename).append("\",\"name\":")
                        .append(jsonString(originalName)).append("}\n");
            }
        }

        StringBuilder manifest = new StringBuilder();
        manifest.append("{\"exportedAt\":").append(jsonString(timestamp()))
                .append(",\"device\":\"Android\",\"wechat\":\"unknown\",\"textItems\":")
                .append(textCount).append(",\"assets\":[");
        for (int i = 0; i < assets.size(); i++) {
            if (i > 0) manifest.append(',');
            manifest.append("{\"file\":").append(jsonString(assetFiles.get(i)))
                    .append(",\"originalName\":").append(jsonString(assetNames.get(i)))
                    .append(",\"status\":\"ok\"}");
        }
        manifest.append("]}");

        try (ZipOutputStream zip = new ZipOutputStream(destination, StandardCharsets.UTF_8)) {
            put(zip, "items.jsonl", itemLines.toString().getBytes(StandardCharsets.UTF_8));
            put(zip, "plain.txt", plainText(items).getBytes(StandardCharsets.UTF_8));
            put(zip, "manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < assets.size(); i++) {
                put(zip, "assets/" + assetFiles.get(i), assets.get(i));
            }
        }
    }

    static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (ch < 0x20) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    else escaped.append(ch);
            }
        }
        return escaped.append('"').toString();
    }

    private static void put(ZipOutputStream zip, String name, byte[] value) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(315532800000L);
        zip.putNextEntry(entry);
        zip.write(value);
        zip.closeEntry();
    }

    private static String safeExtension(String extension) {
        if (extension != null && extension.matches("[A-Za-z0-9]{1,12}")) {
            return extension.toLowerCase(Locale.ROOT);
        }
        return "bin";
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
