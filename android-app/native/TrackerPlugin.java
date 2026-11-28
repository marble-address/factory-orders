package com.marbleaddress.visits;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.firebase.messaging.FirebaseMessaging;

/* الجسر بين صفحة التطبيق والخدمة الأصلية: start / stop / status */
@CapacitorPlugin(name = "Tracker", permissions = {
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }),
        @Permission(alias = "background", strings = { Manifest.permission.ACCESS_BACKGROUND_LOCATION }),
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
})
public class TrackerPlugin extends Plugin {

    private SharedPreferences sp() { return TrackerStore.sp(getContext()); }

    private boolean askedToday(String k) {
        String d = TrackerStore.day(System.currentTimeMillis());
        if (d.equals(sp().getString("ask_" + k, ""))) return true;
        sp().edit().putString("ask_" + k, d).apply(); return false;
    }

    @PluginMethod public void start(PluginCall call) {
        SharedPreferences.Editor e = sp().edit();
        String uid = call.getString("uid");
        if (uid == null) { call.reject("no uid"); return; }
        if (!uid.equals(sp().getString("uid", null))) e.remove("idTok").remove("idExp").remove("polledUid");
        e.putString("uid", uid).putString("name", call.getString("name", "")).putString("section", call.getString("section", "rp"))
         .putString("apiKey", call.getString("apiKey")).putString("projectId", call.getString("projectId", "marble-visits"))
         .putString("sbUrl", call.getString("sbUrl", "")).putString("sbKey", call.getString("sbKey", ""));
        String rt = call.getString("refreshToken"); if (rt != null && !rt.isEmpty()) e.putString("rt", rt);
        String it = call.getString("idToken"); Long ix = call.getLong("idExp");
        if (it != null && !it.isEmpty() && ix != null) e.putString("idTok", it).putLong("idExp", ix);
        e.putBoolean("_checkedOut", Boolean.TRUE.equals(call.getBoolean("checkedOut", false)))
         .putBoolean("_open", Boolean.TRUE.equals(call.getBoolean("open", true))).apply();
        if (getPermissionState("location") != PermissionState.GRANTED) { requestPermissionForAlias("location", call, "afterLocation"); return; }
        afterLocation(call);
    }

    @PermissionCallback private void afterLocation(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) { call.resolve(status()); return; }
        launch();
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED && !askedToday("n")) {
            requestPermissionForAlias("notifications", call, "afterNotifications"); return;
        }
        afterNotifications(call);
    }

    @PermissionCallback private void afterNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 29 && getPermissionState("background") != PermissionState.GRANTED && !askedToday("b")) {
            requestPermissionForAlias("background", call, "afterBackground"); return;
        }
        afterBackground(call);
    }

    @PermissionCallback private void afterBackground(PluginCall call) {
        try {
            Context c = getContext(); PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(c.getPackageName()) && !askedToday("bat")) {
                Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName()));
                getActivity().startActivity(i);
            }
        } catch (Exception e) { }
        call.resolve(status());
    }

    private void launch() {
        Context c = getContext();
        boolean co = sp().getBoolean("_checkedOut", false), open = sp().getBoolean("_open", true);
        boolean on = !co && !TrackerStore.lateNow(c);
        sp().edit().putBoolean("enabled", on).apply();
        if (on || open) {
            try { ContextCompat.startForegroundService(c, new Intent(c, TrackerService.class).putExtra("open", open)); } catch (Exception e) { }
        }
        TrackerWorker.schedule(c);
    }

    @PluginMethod public void stop(PluginCall call) {
        final Context c = getContext();
        sp().edit().putBoolean("enabled", false).apply();
        try { c.stopService(new Intent(c, TrackerService.class)); } catch (Exception e) { }
        final boolean clear = Boolean.TRUE.equals(call.getBoolean("clear", false));
        new Thread(() -> {
            TrackerStore.flush(c);
            if (clear) sp().edit().remove("uid").remove("rt").remove("idTok").remove("idExp").remove("polledUid").remove("fcmUid").apply();
        }).start();
        call.resolve(status());
    }

    /* إشعارات FCM: بيطلب إذن الإشعارات (مرة في اليوم بالكتير) وبيرجع توكن الموبايل ده */
    @PluginMethod public void pushToken(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED
                && Boolean.TRUE.equals(call.getBoolean("ask", false))
                && (Boolean.TRUE.equals(call.getBoolean("force", false)) || !askedToday("pn"))) {
            requestPermissionForAlias("notifications", call, "afterPushPerm"); return;
        }
        afterPushPerm(call);
    }

    @PermissionCallback private void afterPushPerm(PluginCall call) {
        final boolean granted = Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED;
        PushService.channel(getContext());
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(t -> {
                if (t.isSuccessful() && t.getResult() != null) {
                    sp().edit().putString("fcm", t.getResult()).apply();
                    JSObject o = new JSObject(); o.put("token", t.getResult()); o.put("granted", granted); call.resolve(o);
                } else call.reject("fcm: " + (t.getException() != null ? t.getException().getMessage() : "no token"));
            });
        } catch (Exception e) { call.reject("fcm: " + e.getMessage()); }
    }

    /* الصفحة سجلت التوكن في حساب المستخدم — من هنا الإشعارات بتيجي من FCM بس (من غير تكرار) */
    @PluginMethod public void pushOk(PluginCall call) {
        String uid = call.getString("uid"); if (uid != null) sp().edit().putString("fcmUid", uid).apply();
        call.resolve();
    }

    @PluginMethod public void status(PluginCall call) { call.resolve(status()); }

    private JSObject status() {
        Context c = getContext(); JSObject o = new JSObject();
        o.put("running", TrackerService.running); o.put("enabled", TrackerStore.enabled(c));
        o.put("queue", TrackerStore.queue(c).length());
        o.put("lastPt", sp().getLong("lastPt", 0)); o.put("lastSent", sp().getLong("lastSent", 0));
        o.put("lastErr", sp().getString("lastErr", ""));
        o.put("location", String.valueOf(getPermissionState("location")));
        o.put("background", Build.VERSION.SDK_INT >= 29 ? String.valueOf(getPermissionState("background")) : "granted");
        o.put("notifications", Build.VERSION.SDK_INT >= 33 ? String.valueOf(getPermissionState("notifications")) : "granted");
        try { PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE); o.put("battery", pm.isIgnoringBatteryOptimizations(c.getPackageName())); } catch (Exception e) { }
        o.put("version", 4); o.put("fcm", !sp().getString("fcm", "").isEmpty());
        return o;
    }
}
