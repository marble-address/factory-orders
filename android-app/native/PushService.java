package com.marbleaddress.visits;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

/* إشعارات الأندرويد الحقيقية (FCM): بتوصل للمندوب في أي وقت، حتى والتطبيق مقفول وبعد الدوام */
public class PushService extends FirebaseMessagingService {

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("mv_msgs", "إشعارات المدير", NotificationManager.IMPORTANCE_HIGH));
    }

    /* لما التطبيق مفتوح قدام المندوب (أو الرسالة data بس) — بنعرض الإشعار بنفسنا */
    @Override public void onMessageReceived(RemoteMessage m) {
        String title = "", body = "";
        RemoteMessage.Notification n = m.getNotification();
        Map<String, String> d = m.getData();
        if (n != null) { title = n.getTitle() == null ? "" : n.getTitle(); body = n.getBody() == null ? "" : n.getBody(); }
        if (title.isEmpty() && d.containsKey("title")) title = d.get("title");
        if (body.isEmpty() && d.containsKey("body")) body = d.get("body");
        String id = m.getMessageId() == null ? String.valueOf(System.currentTimeMillis()) : m.getMessageId();
        channel(this);
        TrackerStore.show(this, id, title == null ? "" : title, body == null ? "" : body);
    }

    @Override public void onNewToken(String tok) {
        final Context c = getApplicationContext();
        TrackerStore.sp(c).edit().putString("fcm", tok).apply();
        new Thread(() -> TrackerStore.addFcm(c, tok)).start();
    }
}
