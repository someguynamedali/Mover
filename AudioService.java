package com.me.musicmover;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class AudioService extends Service {
    public interface Cb { void on(String a); }
    public static Cb cb;
    private static final String CH = "mm_playback";
    private MediaSession session;
    private PowerManager.WakeLock wl;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel c = new NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        session = new MediaSession(this, "MusicMover");
        session.setActive(true);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { send("play"); }
            @Override public void onPause() { send("pause"); }
            @Override public void onSkipToNext() { send("next"); }
            @Override public void onSkipToPrevious() { send("prev"); }
        });
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mm:play");
    }

    private void send(String a) { if (cb != null) cb.on(a); }

    @Override public int onStartCommand(Intent in, int flags, int id) {
        String a = in == null ? null : in.getAction();
        if ("UPDATE".equals(a)) {
            update(in.getStringExtra("title"), in.getStringExtra("artist"), in.getBooleanExtra("playing", true));
        } else if ("STOP".equals(a)) {
            stopForeground(true);
            stopSelf();
        } else if (a != null) {
            send(a);
        }
        return START_NOT_STICKY;
    }

    private Notification.Action act(int icon, String title, String a, int code) {
        PendingIntent pi = PendingIntent.getService(this, code,
            new Intent(this, AudioService.class).setAction(a),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build();
    }

    private void update(String t, String ar, boolean playing) {
        session.setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, t)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, ar).build());
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build());
        Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
        Notification n = new Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(t).setContentText(ar)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(PendingIntent.getActivity(this, 0, li, PendingIntent.FLAG_IMMUTABLE))
            .addAction(act(android.R.drawable.ic_media_previous, "Prev", "prev", 1))
            .addAction(act(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, "Play", "toggle", 2))
            .addAction(act(android.R.drawable.ic_media_next, "Next", "next", 3))
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
            .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);
        if (playing) { if (!wl.isHeld()) wl.acquire(6 * 60 * 60 * 1000L); }
        else if (wl.isHeld()) wl.release();
    }

    @Override public void onDestroy() {
        if (wl != null && wl.isHeld()) wl.release();
        if (session != null) session.release();
        super.onDestroy();
    }
}
