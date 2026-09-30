// SPDX-License-Identifier: AGPL-3.0-only
package app.chatcapture.android;

final class CaptureItem {
    final String text;
    final byte[] data;
    final String name;
    final String extension;

    private CaptureItem(String text, byte[] data, String name, String extension) {
        this.text = text;
        this.data = data;
        this.name = name;
        this.extension = extension;
    }

    static CaptureItem text(String value) {
        return new CaptureItem(value, null, null, null);
    }

    static CaptureItem asset(byte[] value, String originalName, String fileExtension) {
        return new CaptureItem(null, value, originalName, fileExtension);
    }

    boolean isText() {
        return text != null;
    }
}
