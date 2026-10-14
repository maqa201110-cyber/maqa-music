package com.maqa.pro;

import android.Manifest;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

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
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class MusicScannerPlugin extends Plugin {

    private String alias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio" : "storage";
    }

    @PluginMethod
    public void scan(PluginCall call) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) {
            doScan(call);
        } else {
            requestPermissionForAlias(alias(), call, "permResult");
        }
    }

    @PermissionCallback
    private void permResult(PluginCall call) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) {
            doScan(call);
        } else {
            call.reject("Müzik izni verilmedi");
        }
    }

    private void doScan(PluginCall call) {
        Uri collection = Build.VERSION.SDK_INT >= 29
            ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        String[] cols = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        };
        // Müzik olanlar, 30 saniyeden kısa zil seslerini ele
        String where = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND "
            + MediaStore.Audio.Media.DURATION + " >= 30000";

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
}
