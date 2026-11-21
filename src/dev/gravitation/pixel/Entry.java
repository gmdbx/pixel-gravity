package dev.gravitation.pixel;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class Entry implements IXposedHookLoadPackage {
    private static final String TARGET = "com.google.android.apps.nexuslauncher";
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
                        XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent",
                            MotionEvent.class, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam touch) {
                                    GravityScreen screen = lifecycle.screen;
                                    MotionEvent event = (MotionEvent) touch.args[0];
                                    if (screen != null && screen.activity == touch.thisObject
                                            && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                                        screen.cancelAnimation();
                                    }
                                }
                            });
                        Log.i(TAG, "Loaded in Pixel Launcher");
                    }
                }
            });
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        private GravityScreen screen;

        @Override
        public void onActivityResumed(Activity activity) {
            onActivityPaused(activity);
            if (!activity.getClass().getName().equals(TARGET + ".NexusLauncherActivity")) return;
            try {
                screen = new GravityScreen(activity);
                screen.startListening();
            } catch (ReflectiveOperationException | RuntimeException failure) {
                Log.w(TAG, "Unsupported launcher layout or lifecycle", failure);
                onActivityPaused(activity);
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (screen != null && screen.activity == activity) {
                screen.close();
                screen = null;
            }
        }

        @Override public void onActivityDestroyed(Activity activity) { onActivityPaused(activity); }
        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    }
}
