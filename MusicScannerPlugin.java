package com.maqa.pro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
    name = "MusicScanner",
    permissions = {
        @Permission(alias = "audio", strings = { Manifest.permission.READ_MEDIA_AUDIO }),
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE }),
        @Permission(alias = "notif", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
)
public class MusicScannerPlugin extends Plugin {

    private String alias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio" : "storage";
    }

    /* ---------- Müzik taraması ---------- */
    @PluginMethod
    public void scan(PluginCall call) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) doScan(call);
        else requestPermissionForAlias(alias(), call, "permResult");
    }

    @PermissionCallback
    private void permResult(PluginCall call) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) doScan(call);
        else call.reject("Müzik izni verilmedi");
    }

    private void doScan(PluginCall call) {
        Uri collection = Build.VERSION.SDK_INT >= 29
            ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] cols = {
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATE_ADDED
        };
        String where = "(" + MediaStore.Audio.Media.IS_MUSIC + " != 0 OR " + MediaStore.Audio.Media.DURATION + " >= 90000) AND "
            + MediaStore.Audio.Media.DURATION + " >= 30000 AND " + MediaStore.Audio.Media.IS_RINGTONE + " = 0 AND "
            + MediaStore.Audio.Media.IS_NOTIFICATION + " = 0 AND " + MediaStore.Audio.Media.IS_ALARM + " = 0";

        JSArray songs = new JSArray();
        try (Cursor c = getContext().getContentResolver().query(
                collection, cols, where, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    JSObject s = new JSObject();
                    s.put("id", id);
                    s.put("title", c.getString(1));
                    s.put("artist", c.getString(2));
                    s.put("albumId", c.getLong(3));
                    s.put("added", c.getLong(4));
                    s.put("uri", ContentUris.withAppendedId(collection, id).toString());
                    songs.put(s);
                }
            }
        } catch (Exception e) {
            call.reject("Tarama hatası: " + e.getMessage());
            return;
        }
        JSObject res = new JSObject();
        res.put("songs", songs);
        call.resolve(res);
    }

    /* ---------- Kulaklık çıkınca durdur ---------- */
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) { notifyListeners("noisy", new JSObject()); }
    };

    @Override
    public void load() {
        ContextCompat.registerReceiver(getContext(), noisy,
            new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void handleOnDestroy() {
        try { getContext().unregisterReceiver(noisy); } catch (Exception e) { }
    }

    /* ---------- Arka planda çalma (ön plan servisi) ---------- */
    @PluginMethod
    public void nowPlaying(PluginCall call) {
        boolean playing = Boolean.TRUE.equals(call.getBoolean("playing", true));
        if (Build.VERSION.SDK_INT >= 33 && playing && getPermissionState("notif") == PermissionState.PROMPT) {
            requestPermissionForAlias("notif", call, "notifResult");
        } else {
            applyService(call);
        }
    }

    @PermissionCallback
    private void notifResult(PluginCall call) {
        applyService(call);
    }

    private void applyService(PluginCall call) {
        Intent i = new Intent(getContext(), MusicService.class);
        try {
            if (Boolean.TRUE.equals(call.getBoolean("playing", true))) {
                i.putExtra("title", call.getString("title", ""));
                i.putExtra("artist", call.getString("artist", ""));
                ContextCompat.startForegroundService(getContext(), i);
            } else {
                getContext().stopService(i);
            }
        } catch (Exception e) { /* arka plandan başlatma kısıtı: sessizce geç */ }
        call.resolve();
    }

    public static class MusicService extends Service {
        private PowerManager.WakeLock wl;

        @Override
        public IBinder onBind(Intent intent) { return null; }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            String title = intent != null ? intent.getStringExtra("title") : "";
            String artist = intent != null ? intent.getStringExtra("artist") : "";
            if (Build.VERSION.SDK_INT >= 26) {
                getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel("maqa", "Müzik çalıyor", NotificationManager.IMPORTANCE_LOW));
            }
            Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new NotificationCompat.Builder(this, "maqa")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title).setContentText(artist)
                .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(1, n);
            if (wl == null) {
                wl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "maqa:play");
                wl.acquire(6 * 60 * 60 * 1000L);
            }
            return START_NOT_STICKY;
        }

        @Override
        public void onTaskRemoved(Intent rootIntent) { stopSelf(); }

        @Override
        public void onDestroy() {
            if (wl != null && wl.isHeld()) wl.release();
            super.onDestroy();
        }
    }
}
