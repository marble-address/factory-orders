package com.marbleaddress.visits;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/* بعد إعادة تشغيل الموبايل أو تحديث التطبيق أو لو الخدمة اتقفلت: ترجع تشتغل لو الدوام لسه مفتوح */
public class TrackerReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) { TrackerStore.kick(c, false); TrackerWorker.schedule(c); }
}
