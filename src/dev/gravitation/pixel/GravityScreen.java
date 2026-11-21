package dev.gravitation.pixel;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import java.lang.reflect.Method;
import java.util.ArrayList;

final class GravityScreen implements SensorEventListener, Choreographer.FrameCallback {
    final Activity activity;
    private final View root;
    private final View workspace;
    private final View hotseat;
    private final Class<?> iconType;
    private final Method getState;
    private final Object stateManager;
    private final Object normalState;
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

    GravityScreen(Activity activity) throws ReflectiveOperationException {
        this.activity = activity;
        root = activity.getWindow().getDecorView();
        density = root.getResources().getDisplayMetrics().density;
        workspace = findView("workspace");
        hotseat = findView("hotseat");
        ClassLoader loader = activity.getClassLoader();
        iconType = loader.loadClass("com.android.launcher3.BubbleTextView");
        Class<?> launcherState = loader.loadClass("com.android.launcher3.LauncherState");
        normalState = launcherState.getField("NORMAL").get(null);
        stateManager = activity.getClass().getMethod("getStateManager").invoke(activity);
        getState = stateManager.getClass().getMethod("getState");
        sensors = activity.getSystemService(SensorManager.class);
    }

    private View findView(String name) {
        int id = root.getResources().getIdentifier(name, "id", activity.getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }

    void startListening() {
        Sensor accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null || !sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)) {
            Log.w(Entry.TAG, "Accelerometer unavailable");
        }
    }

    void close() {
        sensors.unregisterListener(this);
        reset();
    }

    private boolean onHome() {
        try {
            return workspace != null && workspace.isShown() && root.hasWindowFocus()
                    && getState.invoke(stateManager) == normalState;
        } catch (ReflectiveOperationException failure) {
            return false;
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
        boolean hit = impulseSquared > 85;
        long time = event.timestamp / 1_000_000;
        if (hit && !aboveThreshold && time - lastToggle > 950) {
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
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));
            scene.bodies.add(new Body(view, bitmap, centerX, centerY, density));
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collect(group.getChildAt(index), rootPosition);
            }
        }
    }

    @Override
    public void doFrame(long time) {
        if (!active) return;
        try {
            if (!onHome() || scene.getBounds().width() != root.getWidth()
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
                body.vx = (body.vx + gx * 145 * density * step) * 0.987f;
                body.vy = (body.vy + gy * 145 * density * step) * 0.987f;
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

    void cancelAnimation() {
        if (active) reset();
    }

    private void reset() {
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
                canvas.drawBitmap(body.bitmap, body.x - body.bitmap.getWidth() / 2f,
                        body.y - body.bitmap.getHeight() / 2f, paint);
            }
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class Body {
        final View view;
        final Bitmap bitmap;
        final float alpha, homeX, homeY, radius;
        float x, y, vx, vy;

        Body(View view, Bitmap bitmap, float centerX, float centerY, float density) {
            this.view = view;
            this.bitmap = bitmap;
            alpha = view.getAlpha();
            homeX = x = centerX;
            homeY = y = centerY;
            radius = Math.min(27 * density, Math.min(bitmap.getWidth(), bitmap.getHeight()) / 2f);
            vx = (float) (Math.random() - 0.5) * 260 * density;
            vy = (float) (Math.random() - 0.5) * 260 * density;
        }

        void constrain(Rect bounds) {
            float halfWidth = Math.min(bitmap.getWidth() / 2f, bounds.width() / 2f);
            float halfHeight = Math.min(bitmap.getHeight() / 2f, bounds.height() / 2f);
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
