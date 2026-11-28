package com.marbleaddress.visits;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/* خدمة تسجيل الموقع: شغالة لوحدها في أندرويد (مش جوه صفحة التطبيق) طول الدوام لحد «إنهاء» أو 11 بالليل */
public class TrackerService extends Service {
    static volatile boolean running = false;
    static final long EVERY = 120000;

    private HandlerThread thread; private Handler h; private final Handler main = new Handler(Looper.getMainLooper());
    private FusedLocationProviderClient fused; private LocationCallback cb;
    private LocationManager lm; private LocationListener ll;
    private PowerManager.WakeLock wl;
    private long lastP = 0, lastPoll = 0; private boolean once = false, stopping = false;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("mv-track"); thread.start(); h = new Handler(thread.getLooper());
    }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        if (!startFg()) { stopAll(); return START_NOT_STICKY; }
        running = true; stopping = false;
        boolean open = in != null && in.getBooleanExtra("open", false);
        once = !TrackerStore.enabled(this);
        if (once && !open) { stopAll(); return START_NOT_STICKY; }
        if (!once) {
            if (wl == null) { PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE); wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marble:track"); wl.setReferenceCounted(false); }
            if (!wl.isHeld()) wl.acquire(14 * 3600000L);
            if (fused == null && lm == null) startUpdates();
        }
        if (open) oneShot("o");
        h.removeCallbacks(tick); h.postDelayed(tick, once ? 40000 : 5000);
        return once ? START_NOT_STICKY : START_STICKY;
    }

    private boolean startFg() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("mv_track", "تسجيل الموقع", NotificationManager.IMPORTANCE_LOW));
            PendingIntent pi = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new NotificationCompat.Builder(this, "mv_track")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentTitle("قسم المشاريع").setContentText("بيتم تسجيل موقعك أثناء الدوام")
                    .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).setContentIntent(pi).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION); else startForeground(7, n);
            return true;
        } catch (Exception e) { return false; }
    }

    @SuppressLint("MissingPermission")
    private void startUpdates() {
        try {
            fused = LocationServices.getFusedLocationProviderClient(this);
            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, EVERY)
                    .setMinUpdateIntervalMillis(60000).setMaxUpdateDelayMillis(EVERY).setWaitForAccurateLocation(false).build();
            cb = new LocationCallback() { @Override public void onLocationResult(LocationResult r) { Location l = r.getLastLocation(); if (l != null) onLoc(l, "p"); } };
            fused.requestLocationUpdates(req, cb, thread.getLooper()).addOnFailureListener(e -> { fused = null; startLm(); });
        } catch (Exception e) { fused = null; startLm(); }
    }

    @SuppressLint("MissingPermission")
    private void startLm() {   // موبايلات من غير خدمات جوجل
        try {
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            ll = l -> onLoc(l, "p");
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, EVERY, 0, ll, thread.getLooper());
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, EVERY, 0, ll, thread.getLooper());
        } catch (Exception e) { }
    }

    @SuppressLint("MissingPermission")
    private void oneShot(String k) {
        try {
            if (fused == null) fused = LocationServices.getFusedLocationProviderClient(this);
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(l -> { if (l != null) onLoc(l, k); else lastKnown(k); })
                    .addOnFailureListener(e -> lastKnown(k));
        } catch (Exception e) { lastKnown(k); }
    }

    @SuppressLint("MissingPermission")
    private void lastKnown(String k) {
        try {
            LocationManager m = (LocationManager) getSystemService(LOCATION_SERVICE); Location best = null;
            for (String p : m.getProviders(true)) { Location l = m.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; }
            if (best != null && System.currentTimeMillis() - best.getTime() < 10 * 60000) onLoc(best, k);
        } catch (Exception e) { }
    }

    private synchronized void onLoc(Location l, String k) {
        long now = System.currentTimeMillis();
        if ("p".equals(k) && now - lastP < EVERY - 20000) return;
        if ("p".equals(k) && l.getTime() > 0 && now - l.getTime() > 10 * 60000) return;   // موقع قديم
        lastP = now;
        TrackerStore.enqueue(this, l.getLatitude(), l.getLongitude(), Math.round(l.getAccuracy()), k, now);
        h.post(() -> TrackerStore.flush(this));
    }

    private final Runnable tick = new Runnable() { @Override public void run() {
        Context c = TrackerService.this;
        if (TrackerStore.enabled(c) && TrackerStore.lateNow(c)) TrackerStore.sp(c).edit().putBoolean("enabled", false).apply();
        TrackerStore.flush(c);
        if (!TrackerStore.enabled(c)) { main.post(() -> stopAll()); return; }
        long now = System.currentTimeMillis();
        if (now - TrackerStore.sp(c).getLong("lastPt", 0) > 5 * 60000) main.post(() -> oneShot("p"));
        if (now - lastPoll >= 110000) { lastPoll = now; TrackerStore.pollNotifs(c); }
        h.postDelayed(this, 60000);
    }};

    private void stopAll() {
        stopping = true; running = false;
        try { h.removeCallbacks(tick); } catch (Exception e) { }
        try { if (fused != null && cb != null) fused.removeLocationUpdates(cb); } catch (Exception e) { }
        try { if (lm != null && ll != null) lm.removeUpdates(ll); } catch (Exception e) { }
        fused = null; cb = null; lm = null; ll = null;
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception e) { }
        try { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true); } catch (Exception e) { }
        stopSelf();
    }

    /* لو المندوب قفل التطبيق من قائمة التطبيقات أو النظام قفل الخدمة: نرجّعها تاني */
    private void scheduleRestart() {
        if (stopping || !TrackerStore.enabled(this)) return;
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(this, 9, new Intent(this, TrackerReceiver.class).setAction("com.marbleaddress.visits.RESTART"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 5000, pi);
        } catch (Exception e) { }
    }
    @Override public void onTaskRemoved(Intent i) { scheduleRestart(); super.onTaskRemoved(i); }
    @Override public void onDestroy() {
        running = false; scheduleRestart();
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception e) { }
        try { thread.quitSafely(); } catch (Exception e) { }
        super.onDestroy();
    }
}
