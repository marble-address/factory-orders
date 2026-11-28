package com.marbleaddress.visits;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.TimeUnit;

/* حارس كل 15 دقيقة: لو الخدمة وقفت والدوام مفتوح يرجّعها، ويبعت أي نقط متأخرة */
public class TrackerWorker extends Worker {
    public TrackerWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    static void schedule(Context c) {
        try {
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("mv-track-guard", ExistingPeriodicWorkPolicy.KEEP,
                    new PeriodicWorkRequest.Builder(TrackerWorker.class, 15, TimeUnit.MINUTES).build());
        } catch (Exception e) { }
    }

    @NonNull @Override public Result doWork() {
        Context c = getApplicationContext();
        if (TrackerStore.enabled(c) && TrackerStore.lateNow(c)) TrackerStore.sp(c).edit().putBoolean("enabled", false).apply();
        if (TrackerStore.enabled(c) && !TrackerService.running) TrackerStore.kick(c, false);
        TrackerStore.flush(c);
        if (TrackerStore.enabled(c) && !TrackerService.running) TrackerStore.pollNotifs(c);
        return Result.success();
    }
}
