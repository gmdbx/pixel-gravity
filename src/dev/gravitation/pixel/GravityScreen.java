package dev.gravitation.pixel;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

final class GravityScreen implements SensorEventListener, Choreographer.FrameCallback {
    private static final float GRAVITY_SCALE = 330f;
    private static final float SHAKE_ACCELERATION_THRESHOLD = 13.5f; // m/s²

    private static final long APP_LAUNCH_GRACE_MS = 2000;

    final Activity activity;
    private final View root;
    private final View workspace;
    private final View hotseat;
    private final Class<?> iconType;
    private final View appDrawer;
    private final View overview;
    private final HomeState homeState;
    private final SensorManager sensors;
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Scene scene = new Scene();
    private final float density;
    private final Rect arena = new Rect();
    private final float[] filtered = new float[3];
    private boolean initialized;
    private boolean active;
    private boolean returning;
    private boolean aboveThreshold;
    private long previousHit;
    private long lastToggle;
    private long lastFrame;
    private float elapsedReturn;
    private final int touchSlop;
    private boolean trackingTouch;
    private boolean tap;
    private float downX, downY;
    private Body pressedBody;
    private Window.Callback originalCallback;
    private Window.Callback touchCallback;
    private boolean launchingApp;
    private boolean launchPaused;
    private boolean resumedAfterLaunch;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable finishLaunchGrace = new Runnable() {
        @Override public void run() {
            if (launchPaused) {
                choreographer.removeFrameCallback(GravityScreen.this);
                return;
            }
            launchingApp = false;
            resumedAfterLaunch = false;
            if (onHome()) returning = true;
            else reset();
        }
    };

    GravityScreen(Activity activity) throws ReflectiveOperationException {
        this.activity = activity;
        root = activity.getWindow().getDecorView();
        density = root.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        workspace = findView("workspace");
        hotseat = findView("hotseat");
        ClassLoader loader = activity.getClassLoader();
        iconType = loader.loadClass("com.android.launcher3.BubbleTextView");
        appDrawer = findView("apps_view");
        overview = findView("overview_panel");
        homeState = resolveHomeState(loader);
        sensors = activity.getSystemService(SensorManager.class);
    }

    private View findView(String name) {
        int id = root.getResources().getIdentifier(name, "id", activity.getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }

    void startListening() {
        Window window = activity.getWindow();
        originalCallback = window.getCallback();
        if (originalCallback == null) return;
        touchCallback = (Window.Callback) Proxy.newProxyInstance(
                GravityScreen.class.getClassLoader(), new Class<?>[]{Window.Callback.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (method.getName().equals("dispatchTouchEvent")
                                && handleTouch((MotionEvent) args[0])) {
                            return true;
                        }
                        try {
                            return method.invoke(originalCallback, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    }
                });
        window.setCallback(touchCallback);
        Log.i(Entry.TAG, "Launcher window touch callback installed");
        registerAccelerometer();
    }

    private void registerAccelerometer() {
        Sensor accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null || !sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)) {
            Log.w(Entry.TAG, "Accelerometer unavailable");
        }
    }

    boolean pauseForLaunch() {
        if (!launchingApp) return false;
        launchPaused = true;
        sensors.unregisterListener(this);
        return true;
    }

    boolean resumeAfterLaunch() {
        if (!launchPaused || !active) return false;
        launchPaused = false;
        resumedAfterLaunch = true;
        initialized = false;
        previousHit = 0;
        lastFrame = 0;
        registerAccelerometer();
        mainHandler.removeCallbacks(finishLaunchGrace);
        mainHandler.postDelayed(finishLaunchGrace, APP_LAUNCH_GRACE_MS);
        choreographer.removeFrameCallback(this);
        choreographer.postFrameCallback(this);
        Log.i(Entry.TAG, "Resumed gravity after app launch");
        return true;
    }

    void close() {
        Window window = activity.getWindow();
        if (touchCallback != null && window.getCallback() == touchCallback) {
            window.setCallback(originalCallback);
        }
        sensors.unregisterListener(this);
        reset();
    }

    private interface HomeState {
        boolean isNormal() throws ReflectiveOperationException;
    }

    private HomeState resolveHomeState(ClassLoader loader) {
        try {
            Class<?> launcherState = loader.loadClass("com.android.launcher3.LauncherState");
            Object normal = launcherState.getField("NORMAL").get(null);
            for (Method method : activity.getClass().getMethods()) {
                if (method.getName().equals("isInState") && method.getReturnType() == boolean.class
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(normal)) {
                    Log.i(Entry.TAG, "Home state via Launcher.isInState");
                    return new HomeState() {
                        @Override public boolean isNormal() throws ReflectiveOperationException {
                            return (boolean) method.invoke(activity, normal);
                        }
                    };
                }
            }
            Object manager = activity.getClass().getMethod("getStateManager").invoke(activity);
            try {
                Method getter = manager.getClass().getMethod("getState");
                Log.i(Entry.TAG, "Home state via StateManager.getState");
                return new HomeState() {
                    @Override public boolean isNormal() throws ReflectiveOperationException {
                        return getter.invoke(manager) == normal;
                    }
                };
            } catch (NoSuchMethodException missingGetter) {
                Field state = manager.getClass().getDeclaredField("mState");
                state.setAccessible(true);
                Log.i(Entry.TAG, "Home state via StateManager.mState");
                return new HomeState() {
                    @Override public boolean isNormal() throws ReflectiveOperationException {
                        return state.get(manager) == normal;
                    }
                };
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            Log.i(Entry.TAG, "Home state via launcher view visibility");
            return null;
        }
    }

    private boolean isWorkspaceVisible() {
        return isVisible(workspace) && isVisible(hotseat)
                && !isVisible(appDrawer) && !isVisible(overview);
    }

    private static boolean isVisible(View view) {
        return view != null && view.isShown() && view.getAlpha() > 0.1f
                && view.getGlobalVisibleRect(new Rect());
    }

    private boolean onHome() {
        if (workspace == null || !workspace.isShown() || !root.hasWindowFocus()) return false;
        try {
            return homeState == null ? isWorkspaceVisible() : homeState.isNormal();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return isWorkspaceVisible();
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onSensorChanged(SensorEvent event) {
        float impulseSquared = 0;
        for (int axis = 0; axis < 3; axis++) {
            float raw = event.values[axis];
            filtered[axis] = initialized ? filtered[axis] * 0.86f + raw * 0.14f : raw;
            float impulse = raw - filtered[axis];
            impulseSquared += impulse * impulse;
        }
        initialized = true;
        boolean hit = impulseSquared > SHAKE_ACCELERATION_THRESHOLD * SHAKE_ACCELERATION_THRESHOLD;
        long time = event.timestamp / 1_000_000;
        if (!launchingApp && hit && !aboveThreshold && time - lastToggle > 950) {
            if (previousHit != 0 && time - previousHit < 390) {
                lastToggle = time;
                previousHit = 0;
                try {
                    if (active) returning = true;
                    else if (onHome()) begin();
                } catch (RuntimeException failure) {
                    reset();
                    Log.w(Entry.TAG, "Could not start gravity", failure);
                }
            } else {
                previousHit = time;
            }
        }
        aboveThreshold = hit;
    }

    private void begin() {
        arena.set(0, 0, root.getWidth(), root.getHeight());
        WindowInsets insets = root.getRootWindowInsets();
        if (insets != null) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            arena.left += bars.left;
            arena.right -= bars.right;
            arena.top += bars.top;
            arena.bottom -= bars.bottom;
        }
        if (arena.isEmpty()) return;
        int[] rootPosition = new int[2];
        root.getLocationOnScreen(rootPosition);
        collect(workspace, rootPosition);
        collect(hotseat, rootPosition);
        if (scene.bodies.isEmpty()) {
            Log.w(Entry.TAG, "No visible BubbleTextView icons");
            return;
        }
        for (Body body : scene.bodies) body.view.setAlpha(0f);
        scene.setBounds(0, 0, root.getWidth(), root.getHeight());
        root.getOverlay().add(scene);
        active = true;
        Log.i(Entry.TAG, "Gravity started with " + scene.bodies.size() + " icons");
        choreographer.postFrameCallback(this);
    }

    private void collect(View view, int[] rootPosition) {
        if (view == null || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0
                || scene.bodies.size() >= 48) return;
        if (iconType.isInstance(view)) {
            int width = view.getWidth();
            int height = view.getHeight();
            if (width < 1 || height < 1 || width > 256 * density || height > 256 * density) return;
            Rect visible = new Rect();
            if (!view.getGlobalVisibleRect(visible) || visible.width() < width * 0.8f
                    || visible.height() < height * 0.8f) return;
            int[] xy = new int[2];
            view.getLocationOnScreen(xy);
            float centerX = xy[0] - rootPosition[0] + width / 2f;
            float centerY = xy[1] - rootPosition[1] + height / 2f;
            if (!arena.contains((int) centerX, (int) centerY)) return;
            try {
                scene.bodies.add(new Body(view, root.isHardwareAccelerated(), centerX, centerY, density));
            } catch (RuntimeException failure) {
                Log.w(Entry.TAG, "Could not capture launcher icon", failure);
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collect(group.getChildAt(index), rootPosition);
            }
        }
    }

    @Override
    public void doFrame(long time) {
        if (!active || (launchPaused && root.getWindowVisibility() != View.VISIBLE)) return;
        try {
            if ((!launchingApp && !onHome()) || !root.isAttachedToWindow()
                    || scene.getBounds().width() != root.getWidth()
                    || scene.getBounds().height() != root.getHeight()) {
                reset();
                return;
            }
            float dt = lastFrame == 0 ? 1f / 60f : Math.min((time - lastFrame) / 1e9f, 0.032f);
            lastFrame = time;
            if (returning) {
                elapsedReturn += dt;
                float fraction = Math.min(1, dt / Math.max(dt, 0.24f - elapsedReturn));
                for (Body body : scene.bodies) {
                    body.x += (body.homeX - body.x) * fraction;
                    body.y += (body.homeY - body.y) * fraction;
                }
                if (elapsedReturn >= 0.24f) {
                    reset();
                    return;
                }
            } else {
                animate(dt);
            }
            scene.invalidateSelf();
            choreographer.postFrameCallback(this);
        } catch (RuntimeException failure) {
            reset();
            Log.w(Entry.TAG, "Gravity animation stopped", failure);
        }
    }

    private void animate(float dt) {
        float x = -filtered[0], y = filtered[1];
        int rotation = root.getDisplay().getRotation();
        float gx = x;
        float gy = y;
        switch (rotation) {
            case Surface.ROTATION_90:
                gx = y;
                gy = -x;
                break;
            case Surface.ROTATION_180:
                gx = -x;
                gy = -y;
                break;
            case Surface.ROTATION_270:
                gx = -y;
                gy = x;
                break;
        }
        float step = dt / 3;
        for (int part = 0; part < 3; part++) {
            for (Body body : scene.bodies) {
                body.vx = (body.vx + gx * GRAVITY_SCALE * density * step) * 0.987f;
                body.vy = (body.vy + gy * GRAVITY_SCALE * density * step) * 0.987f;
                body.x += body.vx * step;
                body.y += body.vy * step;
            }
            for (int i = 0; i < scene.bodies.size(); i++) {
                Body body = scene.bodies.get(i);
                for (int j = 0; j < i; j++) body.collide(scene.bodies.get(j));
                body.constrain(arena);
            }
        }
    }

    boolean handleTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            trackingTouch = active;
            if (!trackingTouch) return false;
            downX = event.getRawX();
            downY = event.getRawY();
            int[] origin = new int[2];
            root.getLocationOnScreen(origin);
            pressedBody = findBodyAt(downX - origin[0], downY - origin[1]);
            tap = true;
            return true;
        }
        if (!trackingTouch) return false;

        float dx = event.getRawX() - downX;
        float dy = event.getRawY() - downY;
        if (action == MotionEvent.ACTION_POINTER_DOWN || dx * dx + dy * dy > touchSlop * touchSlop) {
            tap = false;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            Body selected = action == MotionEvent.ACTION_UP && tap && active
                    && (!launchingApp || resumedAfterLaunch) && onHome() ? pressedBody : null;
            trackingTouch = false;
            pressedBody = null;
            if (selected != null && selected.view.isAttachedToWindow()
                    && selected.view.isClickable() && selected.view.isEnabled()
                    && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
                launchingApp = true;
                returning = false;
                elapsedReturn = 0;
                resumedAfterLaunch = false;
                mainHandler.removeCallbacks(finishLaunchGrace);
                mainHandler.postDelayed(finishLaunchGrace, APP_LAUNCH_GRACE_MS);
                boolean handled = false;
                try {
                    handled = selected.view.performClick();
                } finally {
                    if (!handled) reset();
                }
                Log.i(Entry.TAG, "Animated icon tap handled: " + handled);
            }
        }
        return true;
    }

    private Body findBodyAt(float x, float y) {
        // Match the draw order where the bounds of two moving icons overlap.
        for (int index = scene.bodies.size() - 1; index >= 0; index--) {
            Body body = scene.bodies.get(index);
            if (Math.abs(x - body.x) <= body.width / 2f && Math.abs(y - body.y) <= body.height / 2f) {
                return body;
            }
        }
        return null;
    }

    private void reset() {
        mainHandler.removeCallbacks(finishLaunchGrace);
        launchingApp = false;
        launchPaused = false;
        resumedAfterLaunch = false;
        pressedBody = null;
        choreographer.removeFrameCallback(this);
        root.getOverlay().remove(scene);
        for (Body body : scene.bodies) {
            if (body.view.getAlpha() == 0f) body.view.setAlpha(body.alpha);
        }
        scene.bodies.clear();
        active = false;
        returning = false;
        elapsedReturn = 0;
        lastFrame = 0;
    }

    private static final class Scene extends Drawable {
        final ArrayList<Body> bodies = new ArrayList<>();
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

        @Override public void draw(Canvas canvas) {
            for (Body body : bodies) {
                float left = body.x - body.width / 2f;
                float top = body.y - body.height / 2f;
                if (body.renderNode == null) {
                    canvas.drawBitmap(body.bitmap, left, top, paint);
                } else if (canvas.isHardwareAccelerated()) {
                    int save = canvas.save();
                    canvas.translate(left, top);
                    canvas.drawRenderNode(body.renderNode);
                    canvas.restoreToCount(save);
                }
            }
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class Body {
        final View view;
        final Bitmap bitmap;
        final RenderNode renderNode;
        final int width, height;
        final float alpha, homeX, homeY, radius;
        float x, y, vx, vy;

        Body(View view, boolean hardwareAccelerated, float centerX, float centerY, float density) {
            this.view = view;
            width = view.getWidth();
            height = view.getHeight();
            if (hardwareAccelerated) {
                // Themed Pixel Launcher icons may contain hardware-backed bitmaps.
                bitmap = null;
                renderNode = new RenderNode("PixelGravityIcon");
                renderNode.setPosition(0, 0, width, height);
                Canvas recording = renderNode.beginRecording();
                try {
                    view.draw(recording);
                } finally {
                    renderNode.endRecording();
                }
            } else {
                renderNode = null;
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                view.draw(new Canvas(bitmap));
            }
            alpha = view.getAlpha();
            homeX = x = centerX;
            homeY = y = centerY;
            radius = Math.min(27 * density, Math.min(width, height) / 2f);
            vx = (float) (Math.random() - 0.5) * 260 * density;
            vy = (float) (Math.random() - 0.5) * 260 * density;
        }

        void constrain(Rect bounds) {
            float halfWidth = Math.min(width / 2f, bounds.width() / 2f);
            float halfHeight = Math.min(height / 2f, bounds.height() / 2f);
            float minX = bounds.left + halfWidth, maxX = bounds.right - halfWidth;
            float minY = bounds.top + halfHeight, maxY = bounds.bottom - halfHeight;
            if (x < minX) { x = minX; vx = Math.abs(vx) * 0.65f; }
            if (x > maxX) { x = maxX; vx = -Math.abs(vx) * 0.65f; }
            if (y < minY) { y = minY; vy = Math.abs(vy) * 0.65f; }
            if (y > maxY) { y = maxY; vy = -Math.abs(vy) * 0.65f; }
        }

        void collide(Body other) {
            float dx = x - other.x, dy = y - other.y;
            float reach = radius + other.radius;
            float squared = dx * dx + dy * dy;
            if (squared >= reach * reach) return;
            float distance = (float) Math.sqrt(squared);
            float nx = distance > 0 ? dx / distance : 1;
            float ny = distance > 0 ? dy / distance : 0;
            float separation = (reach - distance) / 2;
            x += nx * separation;
            y += ny * separation;
            other.x -= nx * separation;
            other.y -= ny * separation;
            float relativeSpeed = (vx - other.vx) * nx + (vy - other.vy) * ny;
            if (relativeSpeed >= 0) return;
            float impulse = -relativeSpeed * 0.8f;
            vx += impulse * nx;
            vy += impulse * ny;
            other.vx -= impulse * nx;
            other.vy -= impulse * ny;
        }
    }
}
