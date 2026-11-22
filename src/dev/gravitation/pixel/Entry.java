package dev.gravitation.pixel;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PatternMatcher;
import android.os.Process;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class Entry implements IXposedHookLoadPackage {
    private static final String TARGET = "com.google.android.apps.nexuslauncher";
    private static final String MODULE = "dev.gravitation.pixel";
    static final String TAG = "PixelGravity";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam target) {
        if (!TARGET.equals(target.packageName) || !TARGET.equals(target.processName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.thisObject;
                    if (TARGET.equals(app.getPackageName())) {
                        Lifecycle lifecycle = new Lifecycle();
                        app.registerActivityLifecycleCallbacks(lifecycle);
                        watchForModuleUpdate(app, lifecycle);
                        Log.i(TAG, "Loaded in Pixel Launcher");
                    }
                }
            });
    }

    private static void watchForModuleUpdate(Application app, Lifecycle lifecycle) {
        IntentFilter updateFilter = new IntentFilter(Intent.ACTION_PACKAGE_REPLACED);
        updateFilter.addDataScheme("package");
        updateFilter.addDataSchemeSpecificPart(MODULE, PatternMatcher.PATTERN_LITERAL);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_PACKAGE_REPLACED.equals(intent.getAction())
                        || intent.getData() == null
                        || !MODULE.equals(intent.getData().getSchemeSpecificPart())) return;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        if (lifecycle.screen != null) lifecycle.closeScreen(lifecycle.screen.activity);
                    } finally {
                        Log.i(TAG, "Restarting Pixel Launcher after Pixel Gravity update");
                        Process.killProcess(Process.myPid());
                    }
                }, 750);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, updateFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, updateFilter);
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not watch for Pixel Gravity package updates", failure);
        }
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        private GravityScreen screen;

        @Override
        public void onActivityResumed(Activity activity) {
            if (!activity.getClass().getName().equals(TARGET + ".NexusLauncherActivity")) return;
            try {
                if (screen != null && screen.activity == activity && screen.resumeAfterLaunch()) {
                    return;
                }
                if (screen != null) closeScreen(screen.activity);
                screen = new GravityScreen(activity);
                screen.startListening();
            } catch (ReflectiveOperationException | RuntimeException failure) {
                Log.w(TAG, "Unsupported launcher layout or lifecycle", failure);
                closeScreen(activity);
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (screen != null && screen.activity == activity && !screen.pauseForLaunch()) {
                closeScreen(activity);
            }
        }

        private void closeScreen(Activity activity) {
            if (screen != null && screen.activity == activity) {
                screen.close();
                screen = null;
            }
        }

        @Override public void onActivityDestroyed(Activity activity) { closeScreen(activity); }
        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityPreStarted(Activity activity) {
            if (screen != null && screen.activity == activity) screen.prepareLaunchReturn();
        }
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {
            if (screen != null && screen.activity == activity) screen.parkLaunchScene();
        }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    }
}
