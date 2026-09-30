// SPDX-License-Identifier: AGPL-3.0-only
package app.chatcapture.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity {
    private static final int CREATE_DOCUMENT_REQUEST = 4101;
    private static final int PREVIEW_NODE_LIMIT = 200;
    private static final int PREVIEW_TEXT_LIMIT = 60_000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private LinearLayout preview;
    private TextView status;
    private Button zipButton;
    private Button textButton;
    private Button reshareButton;
    private boolean busy;
    private PendingExport pendingExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
        renderRecentExport();
    }

    private void buildScreen() {
        int padding = dp(16);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);

        TextView title = new TextView(this);
        title.setText("聊天捕获");
        title.setTextSize(26);
        title.setPadding(0, 0, 0, dp(8));
        content.addView(title);

        TextView instructions = new TextView(this);
        instructions.setText("在微信中多选并复制聊天记录，回到这里选择导出方式。图文 ZIP 保留可读取的文字和媒体；纯文本 TXT 只拼接文字节点。导出后可通过系统分享面板转发。\n\n"
                + "仅在点击导出按钮时读取剪贴板，不会监听剪贴板变化。");
        instructions.setTextSize(16);
        instructions.setPadding(0, 0, 0, dp(12));
        content.addView(instructions);

        zipButton = new Button(this);
        zipButton.setText("粘贴并导出图文 ZIP");
        zipButton.setOnClickListener(view -> startExport(false));
        content.addView(zipButton);

        textButton = new Button(this);
        textButton.setText("粘贴并导出纯文本");
        textButton.setOnClickListener(view -> startExport(true));
        content.addView(textButton);

        status = new TextView(this);
        status.setText("导出文件保存在下载/ChatCapture；分享由你手动选择接收应用。");
        status.setTextSize(14);
        status.setPadding(0, dp(8), 0, dp(8));
        content.addView(status);

        reshareButton = new Button(this);
        reshareButton.setText("重新分享最近导出");
        reshareButton.setVisibility(View.GONE);
        reshareButton.setOnClickListener(view -> reshareLastExport());
        content.addView(reshareButton);

        TextView previewTitle = new TextView(this);
        previewTitle.setText("本次预览");
        previewTitle.setTextSize(20);
        previewTitle.setPadding(0, dp(16), 0, dp(8));
        content.addView(previewTitle);

        preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        content.addView(preview);

        setContentView(scroll);
    }

    /** Reads the clipboard only from an explicit foreground button action. */
    private void startExport(boolean textOnly) {
        if (busy) return;
        ClipData clip;
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clip = clipboard == null || !clipboard.hasPrimaryClip() ? null : clipboard.getPrimaryClip();
        } catch (RuntimeException exception) {
            showError("当前无法读取剪贴板。请让本应用保持前台后重试。");
            return;
        }
        if (clip == null || clip.getItemCount() == 0) {
            showError("剪贴板为空或当前不可读取。");
            return;
        }
        if (clip.getItemCount() > 10_000) {
            showError("节点数量超过上限，请分批复制。");
            return;
        }

        List<ClipboardNode> nodes = new ArrayList<>();
        for (int index = 0; index < clip.getItemCount(); index++) {
            ClipData.Item source = clip.getItemAt(index);
            CharSequence value = source.getText();
            String text = value == null ? null : value.toString();
            if (textOnly) {
                if (text != null) nodes.add(new ClipboardNode(index + 1, text, null));
            } else {
                nodes.add(new ClipboardNode(index + 1, text, source.getUri()));
            }
        }
        if (nodes.isEmpty()) {
            showError(textOnly ? "剪贴板中没有可导出的文字节点。" : "剪贴板没有可导出的节点。");
            return;
        }

        setBusy(true);
        status.setText(textOnly ? "正在准备纯文本文件…" : "正在读取节点并打包媒体…");
        worker.execute(() -> createTemporaryExport(nodes, textOnly));
    }

    private void createTemporaryExport(List<ClipboardNode> nodes, boolean textOnly) {
        File temporary = null;
        try {
            List<CaptureItem> items = new ArrayList<>();
            AtomicLong payloadBytes = new AtomicLong();
            for (ClipboardNode node : nodes) {
                // Like iOS, treat text and URI values on one clipboard item as alternate
                // representations of that item; the text representation takes precedence.
                if (textOnly || node.text != null || isTextWebUri(node.uri)) {
                    String value = node.text != null ? node.text : node.uri.toString();
                    addText(items, value, payloadBytes);
                } else if (isContentUri(node.uri)) {
                    String name = displayName(node.uri, node.index);
                    String mime = getContentResolver().getType(node.uri);
                    byte[] data = readAsset(node.uri, payloadBytes);
                    items.add(CaptureItem.asset(data, name, extension(name, mime)));
                } else if (node.text != null) {
                    addText(items, node.text, payloadBytes);
                } else {
                    throw new IOException("第 " + node.index + " 个节点无法读取；未生成不完整导出文件。");
                }
            }
            if (items.isEmpty()) throw new IOException("没有可导出的节点。");

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String kind = textOnly ? "text" : "full";
            String filename = "chat_capture_" + kind + "_" + stamp + "_"
                    + UUID.randomUUID().toString().substring(0, 8) + (textOnly ? ".txt" : ".zip");
            String mime = textOnly ? "text/plain" : "application/zip";
            temporary = new File(getCacheDir(), filename);
            if (textOnly) {
                try (OutputStream output = new FileOutputStream(temporary)) {
                    output.write(CaptureArchive.plainText(items).getBytes(StandardCharsets.UTF_8));
                }
            } else {
                try (OutputStream output = new FileOutputStream(temporary)) {
                    CaptureArchive.writeZip(items, output);
                }
            }

            File exportFile = temporary;
            mainHandler.post(() -> {
                renderPreview(items, textOnly);
                status.setText("已准备 " + nodes.size() + " 个节点，正在保存导出文件…");
                publishExport(exportFile, filename, mime);
            });
        } catch (Exception exception) {
            if (temporary != null) temporary.delete();
            String message = exception.getMessage() == null ? "导出失败。" : exception.getMessage();
            mainHandler.post(() -> {
                setBusy(false);
                showError(message);
            });
        }
    }

    private static void addText(List<CaptureItem> items, String value, AtomicLong total) throws IOException {
        long newTotal = total.addAndGet(value.getBytes(StandardCharsets.UTF_8).length);
        if (newTotal > CaptureArchive.MAX_CAPTURE_BYTES) throw new IOException("捕获内容超过 128 MiB，请分批导出。");
        items.add(CaptureItem.text(value));
    }

    private byte[] readAsset(Uri uri, AtomicLong total) throws IOException {
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("无法读取媒体节点；未生成不完整 ZIP。");
        try (InputStream source = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) != -1) {
                long newTotal = total.addAndGet(count);
                if (newTotal > CaptureArchive.MAX_CAPTURE_BYTES) {
                    throw new IOException("媒体总大小超过 128 MiB，请分批复制。");
                }
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private String displayName(Uri uri, int index) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String name = cursor.getString(column);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (RuntimeException ignored) {
        }
        String segment = uri.getLastPathSegment();
        return segment == null || segment.isEmpty() ? "item_" + index + ".bin" : segment;
    }

    private static String extension(String name, String mime) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String suffix = name.substring(dot + 1);
            if (suffix.matches("[A-Za-z0-9]{1,12}")) return suffix;
        }
        String mapped = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        return mapped == null ? "bin" : mapped;
    }

    private static boolean isContentUri(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        return "content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme);
    }

    private static boolean isTextWebUri(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private void publishExport(File file, String filename, String mime) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            worker.execute(() -> {
                try {
                    Uri uri = saveToDownloads(file, filename, mime);
                    mainHandler.post(() -> finishExport(file, uri, filename, mime));
                } catch (Exception exception) {
                    file.delete();
                    String message = exception.getMessage() == null ? "无法保存导出文件。" : exception.getMessage();
                    mainHandler.post(() -> {
                        setBusy(false);
                        showError(message);
                    });
                }
            });
        } else {
            pendingExport = new PendingExport(file, filename, mime);
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            create.addCategory(Intent.CATEGORY_OPENABLE);
            create.setType(mime);
            create.putExtra(Intent.EXTRA_TITLE, filename);
            create.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                startActivityForResult(create, CREATE_DOCUMENT_REQUEST);
            } catch (RuntimeException exception) {
                pendingExport = null;
                file.delete();
                setBusy(false);
                showError("无法打开系统文件选择器。");
            }
        }
    }

    @android.annotation.TargetApi(29)
    private Uri saveToDownloads(File source, String filename, String mime) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/ChatCapture");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri destination = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (destination == null) throw new IOException("系统没有创建导出文件。");
        try {
            copyFile(source, destination);
            ContentValues published = new ContentValues();
            published.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (getContentResolver().update(destination, published, null, null) != 1) {
                throw new IOException("无法完成导出文件保存。");
            }
            return destination;
        } catch (Exception exception) {
            getContentResolver().delete(destination, null, null);
            if (exception instanceof IOException) throw (IOException) exception;
            throw new IOException("无法保存导出文件。", exception);
        }
    }

    @Override
    @Deprecated
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CREATE_DOCUMENT_REQUEST) return;
        PendingExport pending = pendingExport;
        pendingExport = null;
        if (pending == null) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pending.file.delete();
            setBusy(false);
            status.setText("已取消保存，没有创建导出文件。");
            return;
        }
        Uri destination = data.getData();
        int grants = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(destination, grants);
        } catch (RuntimeException ignored) {
        }
        worker.execute(() -> {
            try {
                copyFile(pending.file, destination);
                mainHandler.post(() -> finishExport(pending.file, destination,
                        pending.filename, pending.mime));
            } catch (Exception exception) {
                pending.file.delete();
                mainHandler.post(() -> {
                    setBusy(false);
                    showError("无法写入所选文件。");
                });
            }
        });
    }

    private void copyFile(File source, Uri destination) throws IOException {
        OutputStream output = getContentResolver().openOutputStream(destination, "w");
        if (output == null) throw new IOException("无法打开导出目标。");
        try (InputStream input = new FileInputStream(source); OutputStream target = output) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) target.write(buffer, 0, count);
            target.flush();
        }
    }

    private void finishExport(File temporary, Uri uri, String filename, String mime) {
        temporary.delete();
        getPreferences(MODE_PRIVATE).edit()
                .putString("recent_uri", uri.toString())
                .putString("recent_name", filename)
                .putString("recent_mime", mime)
                .apply();
        setBusy(false);
        status.setText("已保存到 " + (Build.VERSION.SDK_INT >= 29
                ? "下载/ChatCapture" : "你选择的位置") + "；可以重新分享。");
        renderRecentExport();
        share(uri, filename, mime);
    }

    private void renderRecentExport() {
        String name = getPreferences(MODE_PRIVATE).getString("recent_name", null);
        reshareButton.setVisibility(name == null ? View.GONE : View.VISIBLE);
        if (name != null) reshareButton.setText("重新分享：" + name);
    }

    private void reshareLastExport() {
        String uri = getPreferences(MODE_PRIVATE).getString("recent_uri", null);
        String name = getPreferences(MODE_PRIVATE).getString("recent_name", "导出文件");
        String mime = getPreferences(MODE_PRIVATE).getString("recent_mime", "application/octet-stream");
        if (uri == null) return;
        share(Uri.parse(uri), name, mime);
    }

    private void share(Uri uri, String filename, String mime) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, filename);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "分享导出文件"));
        } catch (RuntimeException exception) {
            showError("文件已保存，但无法打开系统分享面板。");
        }
    }

    private void renderPreview(List<CaptureItem> items, boolean textOnly) {
        preview.removeAllViews();
        int shown = 0;
        int textChars = 0;
        for (CaptureItem item : items) {
            if (shown >= PREVIEW_NODE_LIMIT || textChars >= PREVIEW_TEXT_LIMIT) break;
            TextView label = new TextView(this);
            label.setText("节点 " + (shown + 1));
            label.setTextSize(12);
            label.setPadding(0, dp(8), 0, dp(4));
            preview.addView(label);
            if (item.isText()) {
                String value = item.text;
                int remaining = PREVIEW_TEXT_LIMIT - textChars;
                if (value.length() > remaining) value = value.substring(0, remaining) + "…（预览截断）";
                textChars += value.length();
                TextView body = new TextView(this);
                body.setText(value);
                body.setTextIsSelectable(true);
                body.setTextSize(15);
                preview.addView(body);
            } else {
                TextView file = new TextView(this);
                file.setText((textOnly ? "已跳过媒体：" : "媒体：") + item.name
                        + "（" + item.data.length + " 字节）");
                preview.addView(file);
                if (!textOnly && isImage(item.extension)) addImagePreview(item.data);
            }
            shown++;
        }
        if (items.size() > shown) {
            TextView more = new TextView(this);
            more.setText("其余 " + (items.size() - shown) + " 个节点已导出，预览省略。");
            more.setPadding(0, dp(8), 0, dp(8));
            preview.addView(more);
        }
        status.setText((textOnly ? "纯文本预览" : "图文预览") + "：" + items.size() + " 个节点。");
    }

    private void addImagePreview(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while ((bounds.outWidth / options.inSampleSize) * (bounds.outHeight / options.inSampleSize) > 1_500_000) {
            options.inSampleSize *= 2;
        }
        Bitmap image = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (image == null) return;
        ImageView view = new ImageView(this);
        view.setImageBitmap(image);
        view.setAdjustViewBounds(true);
        view.setMaxHeight(dp(280));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(280)));
    }

    private static boolean isImage(String extension) {
        String value = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        return value.equals("jpg") || value.equals("jpeg") || value.equals("png")
                || value.equals("gif") || value.equals("webp") || value.equals("heic")
                || value.equals("heif");
    }

    private void setBusy(boolean value) {
        busy = value;
        zipButton.setEnabled(!value);
        textButton.setEnabled(!value);
    }

    private void showError(String message) {
        status.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private static final class ClipboardNode {
        final int index;
        final String text;
        final Uri uri;

        ClipboardNode(int index, String text, Uri uri) {
            this.index = index;
            this.text = text;
            this.uri = uri;
        }
    }

    private static final class PendingExport {
        final File file;
        final String filename;
        final String mime;

        PendingExport(File file, String filename, String mime) {
            this.file = file;
            this.filename = filename;
            this.mime = mime;
        }
    }
}
