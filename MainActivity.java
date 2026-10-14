package com.maqa.pro;

import android.os.Bundle;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MusicScannerPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onPause() {
        super.onPause();
        // Ekran kapanınca / başka uygulamaya geçince WebView'in sesi durdurmasını engelle
        if (getBridge() != null && getBridge().getWebView() != null) {
            WebView w = getBridge().getWebView();
            w.onResume();
            w.resumeTimers();
        }
    }
}
