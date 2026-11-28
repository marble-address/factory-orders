package com.marbleaddress.visits;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/* تخزين الإعدادات + طابور نقط الموقع + الإرسال لـ Firestore مباشرة + قراءة إشعارات المدير — كله من غير الـ WebView */
final class TrackerStore {
    static final String PREFS = "mv_tracker";
    static final AtomicBoolean FLUSHING = new AtomicBoolean(false);

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static String day(long t) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(t)); }
    static boolean enabled(Context c) { return sp(c).getBoolean("enabled", false) && sp(c).getString("uid", null) != null; }
    static boolean lateNow(Context c) { return Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= sp(c).getInt("stopHour", 23); }

    static void kick(Context c, boolean open) {
        if (!enabled(c) || lateNow(c)) return;
        try { ContextCompat.startForegroundService(c, new Intent(c, TrackerService.class).putExtra("open", open)); } catch (Exception e) { }
    }

    /* ---------- الطابور ---------- */
    static synchronized void enqueue(Context c, double lat, double lng, int acc, String k, long t) {
        try {
            JSONArray q = new JSONArray(sp(c).getString("q", "[]"));
            JSONObject o = new JSONObject();
            o.put("uid", sp(c).getString("uid", "")); o.put("day", day(t)); o.put("t", t);
            o.put("lat", lat); o.put("lng", lng); o.put("acc", acc); o.put("k", k);
            q.put(o);
            if (q.length() > 600) { JSONArray r = new JSONArray(); for (int i = q.length() - 600; i < q.length(); i++) r.put(q.get(i)); q = r; }
            sp(c).edit().putString("q", q.toString()).putLong("lastPt", t).apply();
        } catch (Exception e) { }
    }
    static synchronized JSONArray queue(Context c) {
        try { return new JSONArray(sp(c).getString("q", "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    static synchronized void dropFirst(Context c) {
        JSONArray q = queue(c), r = new JSONArray();
        for (int i = 1; i < q.length(); i++) { try { r.put(q.get(i)); } catch (Exception e) { } }
        sp(c).edit().putString("q", r.toString()).apply();
    }

    /* ---------- HTTP ---------- */
    static final class Resp { int code; String body = ""; }
    static Resp http(String url, String ctype, String body, String bearer) {
        Resp r = new Resp(); HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(15000); h.setReadTimeout(20000); h.setRequestMethod("POST");
            h.setRequestProperty("Referer", "https://marble-address.github.io/");
            if (bearer != null) h.setRequestProperty("Authorization", "Bearer " + bearer);
            h.setDoOutput(true); h.setRequestProperty("Content-Type", ctype);
            try (OutputStream os = h.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
            r.code = h.getResponseCode();
            InputStream is = r.code >= 400 ? h.getErrorStream() : h.getInputStream();
            if (is != null) { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) > 0) b.write(buf, 0, n); r.body = b.toString("UTF-8"); }
        } catch (Exception e) { r.code = -1; r.body = String.valueOf(e); }
        finally { if (h != null) h.disconnect(); }
        return r;
    }

    static synchronized String token(Context c, boolean force) {
        SharedPreferences s = sp(c);
        String tok = s.getString("idTok", null); long exp = s.getLong("idExp", 0);
        if (!force && tok != null && System.currentTimeMillis() < exp - 300000) return tok;
        String rt = s.getString("rt", null), key = s.getString("apiKey", null);
        if (rt == null || key == null) return null;
        try {
            Resp r = http("https://securetoken.googleapis.com/v1/token?key=" + key, "application/x-www-form-urlencoded",
                    "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8"), null);
            if (r.code == 200) {
                JSONObject j = new JSONObject(r.body);
                tok = j.getString("id_token");
                s.edit().putString("idTok", tok).putLong("idExp", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000L)
                        .putString("rt", j.optString("refresh_token", rt)).putString("lastErr", "").apply();
                return tok;
            }
            s.edit().putString("lastErr", "token " + r.code + " " + r.body.substring(0, Math.min(160, r.body.length()))).apply();
        } catch (Exception e) { }
        return null;
    }

    static String base(Context c) { return "projects/" + sp(c).getString("projectId", "marble-visits") + "/databases/(default)/documents"; }
    static JSONObject sv(String v) throws Exception { return new JSONObject().put("stringValue", v); }

    static Resp commit(Context c, String tok, JSONObject w) throws Exception {
        return http("https://firestore.googleapis.com/v1/" + base(c) + ":commit", "application/json",
                new JSONObject().put("writes", new JSONArray().put(w)).toString(), tok);
    }

    /* بيبعت النقط اللي في الطابور بالترتيب؛ لو مفيش نت بتفضل محفوظة وتتبعت بعدين */
    static void flush(Context c) {
        if (!FLUSHING.compareAndSet(false, true)) return;
        try {
            for (int guard = 0; guard < 300; guard++) {
                JSONArray q = queue(c); if (q.length() == 0) break;
                JSONObject it = q.getJSONObject(0);
                String uid = sp(c).getString("uid", "");
                if (!uid.equals(it.optString("uid"))) { dropFirst(c); continue; }
                String tok = token(c, false); if (tok == null) break;
                String name = base(c) + "/track/" + uid + "_" + it.getString("day");
                JSONObject V = new JSONObject().put("mapValue", new JSONObject().put("fields", new JSONObject()
                        .put("t", new JSONObject().put("integerValue", String.valueOf(it.getLong("t"))))
                        .put("lat", new JSONObject().put("doubleValue", it.getDouble("lat")))
                        .put("lng", new JSONObject().put("doubleValue", it.getDouble("lng")))
                        .put("acc", new JSONObject().put("integerValue", String.valueOf(it.optInt("acc"))))
                        .put("k", sv(it.optString("k", "p")))));
                JSONObject append = new JSONObject()
                        .put("transform", new JSONObject().put("document", name).put("fieldTransforms", new JSONArray().put(
                                new JSONObject().put("fieldPath", "points").put("appendMissingElements", new JSONObject().put("values", new JSONArray().put(V))))))
                        .put("currentDocument", new JSONObject().put("exists", true));
                Resp r = commit(c, tok, append);
                if (r.code == 404 || r.code == 400 || r.code == 409) {
                    JSONObject f = new JSONObject().put("uid", sv(uid)).put("name", sv(sp(c).getString("name", "")))
                            .put("day", sv(it.getString("day"))).put("section", sv(sp(c).getString("section", "rp")))
                            .put("points", new JSONObject().put("arrayValue", new JSONObject().put("values", new JSONArray().put(V))));
                    r = commit(c, tok, new JSONObject().put("update", new JSONObject().put("name", name).put("fields", f))
                            .put("currentDocument", new JSONObject().put("exists", false)));
                    if (r.code == 400 || r.code == 409) r = commit(c, tok, append);
                }
                if (r.code >= 200 && r.code < 300) { dropFirst(c); sp(c).edit().putLong("lastSent", System.currentTimeMillis()).apply(); }
                else if (r.code == 401) { token(c, true); break; }
                else if (r.code == 403 || r.code == 400) { sp(c).edit().putString("lastErr", "write " + r.code).apply(); dropFirst(c); }
                else break;
            }
        } catch (Exception e) { } finally { FLUSHING.set(false); }
    }

    /* ---------- إشعارات المدير (من collection notifs) ---------- */
    static long isoTime(String s) {
        try { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.parse(s.substring(0, 19)).getTime(); }
        catch (Exception e) { return 0; }
    }
    static String str(JSONObject fields, String k) {
        JSONObject v = fields.optJSONObject(k); return v == null ? "" : v.optString("stringValue", "");
    }

    static void pollNotifs(Context c) {
        try {
            String uid = sp(c).getString("uid", null); if (uid == null) return;
            String tok = token(c, false); if (tok == null) return;
            JSONArray filters = new JSONArray()
                    .put(new JSONObject().put("fieldFilter", new JSONObject().put("field", new JSONObject().put("fieldPath", "toUid")).put("op", "EQUAL").put("value", sv(uid))))
                    .put(new JSONObject().put("fieldFilter", new JSONObject().put("field", new JSONObject().put("fieldPath", "read")).put("op", "EQUAL").put("value", new JSONObject().put("booleanValue", false))));
            JSONObject q = new JSONObject().put("structuredQuery", new JSONObject()
                    .put("from", new JSONArray().put(new JSONObject().put("collectionId", "notifs")))
                    .put("where", new JSONObject().put("compositeFilter", new JSONObject().put("op", "AND").put("filters", filters)))
                    .put("limit", 30));
            Resp r = http("https://firestore.googleapis.com/v1/" + base(c) + ":runQuery", "application/json", q.toString(), tok);
            if (r.code != 200) return;
            SharedPreferences s = sp(c);
            boolean fcmOn = !s.getString("fcm", "").isEmpty() && uid.equals(s.getString("fcmUid", ""));
            boolean first = !uid.equals(s.getString("polledUid", ""));
            JSONArray shown = new JSONArray(s.getString("shown", "[]"));
            Set<String> set = new HashSet<>(); for (int i = 0; i < shown.length(); i++) set.add(shown.optString(i));
            JSONArray arr = new JSONArray(r.body); long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject d = arr.getJSONObject(i).optJSONObject("document"); if (d == null) continue;
                String nm = d.getString("name"), id = nm.substring(nm.lastIndexOf('/') + 1);
                if (set.contains(id)) continue;
                set.add(id); shown.put(id);
                long ct = isoTime(d.optString("createTime", ""));
                if (now - ct > (first ? 15 * 60000L : 24 * 3600000L)) continue;
                JSONObject fl = d.optJSONObject("fields"); if (fl == null) continue;
                if (fcmOn) continue;   // الإشعار بيوصل من FCM — ما نكررهوش
                show(c, id, str(fl, "title"), str(fl, "body"));
            }
            if (shown.length() > 300) { JSONArray t = new JSONArray(); for (int i = shown.length() - 300; i < shown.length(); i++) t.put(shown.get(i)); shown = t; }
            s.edit().putString("shown", shown.toString()).putString("polledUid", uid).apply();
        } catch (Exception e) { }
    }

    /* بيضيف توكن FCM بتاع الموبايل ده لحساب المندوب (fcmTokens) عشان الإشعارات توصله */
    static void addFcm(Context c, String fcm) {
        try {
            String uid = sp(c).getString("uid", null); if (uid == null || fcm == null || fcm.isEmpty()) return;
            String tok = token(c, false); if (tok == null) return;
            JSONObject w = new JSONObject().put("transform", new JSONObject()
                    .put("document", base(c) + "/users/" + uid)
                    .put("fieldTransforms", new JSONArray().put(new JSONObject().put("fieldPath", "fcmTokens")
                            .put("appendMissingElements", new JSONObject().put("values", new JSONArray().put(sv(fcm)))))));
            Resp r = commit(c, tok, w);
            if (r.code == 200) sp(c).edit().putString("fcmUid", uid).apply();
        } catch (Exception e) { }
    }

    static void show(Context c, String id, String title, String body) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("mv_msgs", "إشعارات المدير", NotificationManager.IMPORTANCE_HIGH));
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(c, "mv_msgs")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title.isEmpty() ? "قسم المشاريع" : title).setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL).setContentIntent(pi).build();
        try { nm.notify(id.hashCode(), n); } catch (Exception e) { }
    }
}
