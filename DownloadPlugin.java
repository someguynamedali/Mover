package com.me.musicmover;

import android.content.Intent;
import android.os.Build;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

@CapacitorPlugin(name = "DownloadCtl")
public class DownloadPlugin extends Plugin {
    private static DownloadPlugin instance;
    @Override public void load() { instance = this; }

    public static void emit(String event, JSONObject data) {
        DownloadPlugin p = instance;
        if (p == null) return;
        try {
            JSObject obj = new JSObject(data.toString());
            p.notifyListeners(event, obj);
        } catch (Exception e) { android.util.Log.e("MusicMover", "Event delivery failed", e); }
    }

    @PluginMethod public void enqueue(PluginCall call) {
        try {
            JSONObject item = new JSONObject();
            item.put("id", call.getString("id"));
            item.put("title", call.getString("title", "Трек"));
            item.put("artist", call.getString("artist", ""));
            item.put("url", call.getString("url"));
            Intent i = new Intent(getContext(), DownloadService.class).setAction(DownloadService.ACTION_ENQUEUE)
                .putExtra("item", item.toString());
            if (Build.VERSION.SDK_INT >= 26) getContext().startForegroundService(i); else getContext().startService(i);
            call.resolve();
        } catch (Exception e) { call.reject("Не удалось поставить трек в очередь", e); }
    }

    @PluginMethod public void getCompleted(PluginCall call) {
        try {
            File dir = new File(getContext().getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "MusicMover");
            JSArray arr = new JSArray();
            if (dir.exists()) {
                File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
                if (files != null) for (File f : files) {
                    try (FileInputStream in = new FileInputStream(f)) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
                        arr.put(new JSObject(b.toString("UTF-8")));
                    } catch (Exception ignored) { }
                }
            }
            JSObject out = new JSObject(); out.put("items", arr); call.resolve(out);
        } catch (Exception e) { call.reject("Не удалось получить завершённые загрузки", e); }
    }

    @PluginMethod public void readCompleted(PluginCall call) {
        try {
            String name = call.getString("file");
            if (name == null || name.contains("/") || name.contains("\\")) { call.reject("Некорректное имя файла"); return; }
            File dir = new File(getContext().getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "MusicMover");
            File f = new File(dir, name);
            if (!f.isFile()) { call.reject("Файл загрузки не найден"); return; }
            try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream b = new ByteArrayOutputStream()) {
                byte[] buf = new byte[64 * 1024]; int n;
                while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
                JSObject out = new JSObject(); out.put("data", Base64.encodeToString(b.toByteArray(), Base64.NO_WRAP)); call.resolve(out);
            }
        } catch (Exception e) { call.reject("Не удалось прочитать аудиофайл", e); }
    }

    @PluginMethod public void ackCompleted(PluginCall call) {
        try {
            String name = call.getString("file");
            if (name == null || name.contains("/") || name.contains("\\")) { call.reject("Некорректное имя файла"); return; }
            File dir = new File(getContext().getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "MusicMover");
            File audio = new File(dir, name); audio.delete();
            String base = name.endsWith(".m4a") ? name.substring(0, name.length() - 4) : name;
            new File(dir, base + ".json").delete();
            call.resolve();
        } catch (Exception e) { call.reject("Не удалось завершить импорт", e); }
    }

    @PluginMethod public void clearQueue(PluginCall call) {
        Intent i = new Intent(getContext(), DownloadService.class).setAction(DownloadService.ACTION_CLEAR);
        if (Build.VERSION.SDK_INT >= 26) getContext().startForegroundService(i); else getContext().startService(i);
        call.resolve();
    }
}
