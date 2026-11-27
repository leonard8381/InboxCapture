package com.zao.inboxcapture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class SyncService extends Service {
    private static final String CHANNEL = "computer_sync";
    private static final int NOTIFICATION_ID = 3107;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Shutdown and startup must remain ordered when activities or services are recreated.
    private static final ScheduledExecutorService ENGINE = Executors.newSingleThreadScheduledExecutor();
    private static boolean visible;
    private static Runnable pendingLeave;
    private ScheduledFuture<?> supervision;
    private boolean foreground;
    private ConnectivityManager connectivity;
    private Network defaultNetwork;
    private int transport;
    private final Runnable reconnect = () -> {
        if (foreground) ENGINE.execute(() -> SyncEngine.routeChanged(getApplicationContext()));
    };
    private final ConnectivityManager.NetworkCallback route = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            if (network.equals(defaultNetwork)) return;
            defaultNetwork = network;
            transport = 0;
            scheduleReconnect();
        }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            if (!network.equals(defaultNetwork)) return;
            int next = bearer(caps);
            if (transport != 0 && next != 0 && transport != next) scheduleReconnect();
            transport = next;
        }
        @Override public void onLost(Network network) {
            if (!network.equals(defaultNetwork)) return;
            defaultNetwork = null;
            transport = 0;
            scheduleReconnect();
        }
    };

    private static int bearer(NetworkCapabilities caps) {
        if (caps == null) return 0;
        int mask = 0;
        for (int type : new int[]{NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN})
            if (caps.hasTransport(type)) mask |= 1 << type;
        return mask;
    }

    private void scheduleReconnect() {
        // A handover can report the old route disappearing just before announcing the replacement.
        MAIN.removeCallbacks(reconnect);
        MAIN.postDelayed(reconnect, 800);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("sync_service", MODE_PRIVATE);
    }

    static boolean isConfigured(Context context) { return prefs(context).getBoolean("paired", false); }
    static boolean background(Context context) { return prefs(context).getBoolean("background", true); }
    static void markPaired(Context context) { prefs(context).edit().putBoolean("paired", true).apply(); }
    static void refreshTransport(Context context) {
        Context app = context.getApplicationContext();
        ENGINE.execute(() -> SyncEngine.pairingChanged(app));
    }
    static void clearPaired(Context context) {
        prefs(context).edit().remove("paired").apply();
        MAIN.post(() -> stopIfHidden(context.getApplicationContext()));
    }

    static void setBackground(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("background", enabled).apply();
        if (!enabled) MAIN.post(() -> stopIfHidden(context.getApplicationContext()));
    }

    /** Call when an already-paired app or the pairing screen becomes visible. */
    static void enter(Context context) {
        Context app = context.getApplicationContext();
        visible = true;
        if (pendingLeave != null) MAIN.removeCallbacks(pendingLeave);
        try {
            app.startForegroundService(new Intent(app, SyncService.class));
        } catch (IllegalStateException | SecurityException denied) {
            SyncEngine.setStartDenied();
        }
    }

    static void leave(Context context) {
        visible = false;
        Context app = context.getApplicationContext();
        if (pendingLeave != null) MAIN.removeCallbacks(pendingLeave);
        // Let the next in-app activity resume before treating this as a background exit.
        pendingLeave = () -> stopIfHidden(app);
        MAIN.postDelayed(pendingLeave, 750);
    }

    private static void stopIfHidden(Context app) {
        if (!visible && (!isConfigured(app) || !background(app)))
            app.stopService(new Intent(app, SyncService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "电脑同步", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent action = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("灵感 · 电脑同步").setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(action).setOnlyAlertOnce(true).setOngoing(true).build();
        try {
            startForeground(NOTIFICATION_ID, notification);
            foreground = true;
        } catch (IllegalStateException | SecurityException denied) {
            SyncEngine.setStartDenied();
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foreground || (intent == null && (!isConfigured(this) || !background(this)))) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (supervision == null) {
            Context app = getApplicationContext();
            connectivity = getSystemService(ConnectivityManager.class);
            defaultNetwork = connectivity.getActiveNetwork();
            transport = defaultNetwork == null ? 0 : bearer(connectivity.getNetworkCapabilities(defaultNetwork));
            connectivity.registerDefaultNetworkCallback(route, MAIN);
            supervision = ENGINE.scheduleWithFixedDelay(() -> SyncEngine.ensureStarted(app),
                    0, 20, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }

    @Override public void onTimeout(int startId, int type) {
        stopSelf();
    }

    @Override public void onDestroy() {
        foreground = false;
        if (connectivity != null) connectivity.unregisterNetworkCallback(route);
        MAIN.removeCallbacks(reconnect);
        if (supervision != null) {
            supervision.cancel(true);
            Context app = getApplicationContext();
            ENGINE.execute(() -> SyncEngine.stop(app));
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
