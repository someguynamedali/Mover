package com.me.musicmover;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadService extends Service {
    public static final String ACTION_ENQUEUE = "com.me.musicmover.DOWNLOAD_ENQUEUE";
    public static final String ACTION_CLEAR = "com.me.musicmover.DOWNLOAD_CLEAR";
    private static final String PREF = "mm_downloads";
    private static final String KEY_QUEUE = "queue";
    private static final String CHANNEL = "mm_downloads";
    private static final int NOTIFICATION_ID = 42;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;
    private volatile boolean cancelCurrent = false;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Music downloads", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicMover:downloads");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("MusicMover", "Подготовка загрузок", 0, false));
        if (intent != null && ACTION_ENQUEUE.equals(intent.getAction())) {
            String raw = intent.getStringExtra("item");
            if (raw != null) {
                try {
                    JSONObject item = new JSONObject(raw);
                    JSONArray q = readQueue();
                    boolean exists = false;
                    for (int i = 0; i < q.length(); i++) if (item.optString("id").equals(q.getJSONObject(i).optString("id"))) exists = true;
                    if (!exists) q.put(item);
                    writeQueue(q);
                } catch (Exception e) { Log.e("MusicMover", "Bad download item", e); }
            }
        } else if (intent != null && ACTION_CLEAR.equals(intent.getAction())) {
            cancelCurrent = true;
            writeQueue(new JSONArray());
        }
        runQueue();
        return START_STICKY;
    }

    private synchronized void runQueue() {
        if (running) return;
        running = true;
        cancelCurrent = false;
        if (!wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L);
        worker.execute(() -> {
            try {
                while (!cancelCurrent) {
                    JSONArray q = readQueue();
                    if (q.length() == 0) break;
                    JSONObject item = q.optJSONObject(0);
                    if (item == null) { removeFirst(); continue; }
                    Exception failure = null;
                    for (int attempt = 1; attempt <= 3 && !cancelCurrent; attempt++) {
                        try { download(item); failure = null; break; }
                        catch (Exception e) {
                            failure = e;
                            Log.e("MusicMover", "Download attempt " + attempt + " failed", e);
                            if (attempt < 3 && !cancelCurrent) {
                                try { Thread.sleep(attempt * 3000L); }
                                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                            }
                        }
                    }
                    if (failure != null) {
                        try { DownloadPlugin.emit("failed", new JSONObject().put("id", item.optString("id"))
                            .put("title", item.optString("title")).put("error", String.valueOf(failure.getMessage()))); }
                        catch (Exception ignored) { }
                    }
                    removeFirst();
                }
            } catch (Exception e) { Log.e("MusicMover", "Queue error", e); }
            finally {
                running = false;
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                JSONArray left = readQueue();
                if (left.length() == 0) {
                    getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                        notification("MusicMover", "Загрузки завершены", 100, true));
                    stopForeground(false);
                    stopSelf();
                } else if (!cancelCurrent) runQueue();
            }
        });
    }

    private void download(JSONObject item) throws Exception {
        String id = item.getString("id");
        String title = item.optString("title", "Трек");
        String url = item.getString("url");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "MusicMover/1.0 Android");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        long total = c.getContentLengthLong();
        String mime = c.getContentType();
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "MusicMover");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Не удалось создать папку музыки");
        String safe = id.replaceAll("[^a-zA-Z0-9_-]", "_");
        File audio = new File(dir, safe + ".m4a");
        File temp = new File(dir, safe + ".part");
        long done = 0;
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            int lastPercent = -1;
            while ((n = in.read(buffer)) != -1) {
                if (cancelCurrent) throw new Exception("Отменено");
                out.write(buffer, 0, n);
                done += n;
                int percent = total > 0 ? (int) (done * 100 / total) : -1;
                if (percent != lastPercent && (percent < 0 || percent % 5 == 0)) {
                    lastPercent = percent;
                    getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                        notification(title, total > 0 ? (percent + "%") : "Загрузка…", percent, false));
                    DownloadPlugin.emit("progress", new JSONObject().put("id", id).put("title", title)
                        .put("downloaded", done).put("total", total).put("percent", percent));
                }
            }
            out.flush();
        } finally { c.disconnect(); }
        if (!temp.renameTo(audio)) {
            if (audio.exists()) audio.delete();
            if (!temp.renameTo(audio)) throw new Exception("Не удалось сохранить аудиофайл");
        }
        JSONObject meta = new JSONObject(item.toString());
        meta.put("file", audio.getName());
        meta.put("mime", mime == null ? "audio/mp4" : mime);
        File sidecar = new File(dir, safe + ".json");
        try (FileOutputStream out = new FileOutputStream(sidecar)) { out.write(meta.toString().getBytes("UTF-8")); }
        DownloadPlugin.emit("completed", new JSONObject().put("id", id).put("title", title).put("file", audio.getName()));
    }

    private Notification notification(String title, String text, int progress, boolean done) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = launch == null ? null : PendingIntent.getActivity(this, 0, launch,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(text).setOngoing(!done).setOnlyAlertOnce(true)
            .setAutoCancel(done);
        if (pi != null) b.setContentIntent(pi);
        if (!done && progress >= 0) b.setProgress(100, progress, false);
        return b.build();
    }

    private JSONArray readQueue() {
        try { return new JSONArray(getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_QUEUE, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    private void writeQueue(JSONArray q) { getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(KEY_QUEUE, q.toString()).apply(); }
    private void removeFirst() {
        JSONArray q = readQueue(), next = new JSONArray();
        for (int i = 1; i < q.length(); i++) next.put(q.opt(i));
        writeQueue(next);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        worker.shutdown();
        super.onDestroy();
    }
}
