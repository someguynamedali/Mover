package com.me.musicmover;

import android.content.Intent;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "MediaCtl")
public class MediaPlugin extends Plugin {
    @Override public void load() {
        AudioService.cb = a -> notifyListeners("action", new JSObject().put("a", a));
    }

    @PluginMethod public void update(PluginCall c) {
        Intent i = new Intent(getContext(), AudioService.class).setAction("UPDATE")
            .putExtra("title", c.getString("title", ""))
            .putExtra("artist", c.getString("artist", ""))
            .putExtra("playing", c.getBoolean("playing", true));
        getContext().startForegroundService(i);
        c.resolve();
    }

    @PluginMethod public void stop(PluginCall c) {
        getContext().startService(new Intent(getContext(), AudioService.class).setAction("STOP"));
        c.resolve();
    }
}
