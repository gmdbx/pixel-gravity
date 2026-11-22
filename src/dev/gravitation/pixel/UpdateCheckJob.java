package dev.gravitation.pixel;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.TimeUnit;

public final class UpdateCheckJob extends JobService {
    private static final int JOB_ID = 1;
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL = "pixel_gravity_updates";
    private Thread worker;

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("UpdateActivity", Context.MODE_PRIVATE);
    }

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        long interval = TimeUnit.MINUTES.toMillis(15);
        JobInfo current = scheduler.getPendingJob(JOB_ID);
        if (current != null && current.getIntervalMillis() == interval) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateCheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(interval)
                .setPersisted(true)
                .build();
        try {
            scheduler.schedule(job);
        } catch (IllegalStateException | SecurityException failure) {
            Log.w("PixelGravity", "Could not schedule background update checks", failure);
        }
    }

    public static final class PackageUpdated extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) schedule(context);
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)) return false;

        worker = new Thread(() -> {
            boolean retry = false;
            String version = null;
            try {
                String current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                version = UpdateActivity.newestRelease(current);
            } catch (Exception failure) {
                Log.w("PixelGravity", "Background release check failed", failure);
                retry = true;
            }
            boolean reschedule = retry;
            String found = version;
            Thread completed = Thread.currentThread();
            getMainExecutor().execute(() -> {
                if (worker != completed) return;
                worker = null;
                try {
                    notifyIfNew(manager, found);
                } finally {
                    jobFinished(params, reschedule);
                }
            });
        }, "PixelGravityUpdateJob");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        return true;
    }

    private void notifyIfNew(NotificationManager manager, String version) {
        SharedPreferences prefs = preferences(this);
        if (version == null || version.equals(prefs.getString("skipped_version", ""))
                || version.equals(prefs.getString("notified_version", ""))) return;
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Обновления Pixel Gravity", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, UpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = getResources().getIdentifier("ic_update_notification", "drawable", getPackageName());
        manager.notify(NOTIFICATION_ID, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(icon)
                .setContentTitle("Доступно обновление Pixel Gravity")
                .setContentText("Версия " + version)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
        prefs.edit().putString("notified_version", version).apply();
    }
}
