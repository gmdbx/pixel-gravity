package dev.gravitation.pixel;

import android.app.Activity;
import android.appwidget.AppWidgetHostView;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
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
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

final class GravityScreen implements SensorEventListener, Choreographer.FrameCallback {
    private static final float GRAVITY_SCALE = 330f;
    private static final float MAX_SPEED_DP_PER_SECOND = 950f;
    private static final float DOCK_GAP_DP = 16f;
    private static final float SHAKE_ACCELERATION_THRESHOLD = 13.5f; // m/s²

    private static final long APP_LAUNCH_GRACE_MS = 2000;
    private static final long HOME_RESTORE_TIMEOUT_MS = 2500;
    private static final long PAGE_SETTLE_MS = 65;
    private static final long PAGE_SWITCH_TIMEOUT_MS = 1800;

    final Activity activity;
    private final View root;
    private final View workspace;
    private final View hotseat;
    private final View dockSearch;
    private final int[] launcherBarIds;
    private final Class<?> iconType;
    private final View appDrawer;
    private final View overview;
    private final HomeState homeState;
    private final SensorManager sensors;
    private final Choreographer choreographer = Choreographer.getInstance();
    private Scene scene;
    private Scene returnOverlay;
    private long returnOverlayTime;
    private final float density;
    private final Rect arena = new Rect();
    private final Rect workspaceArena = new Rect();
    private final Rect dockArena = new Rect();
    private final Rect searchArena = new Rect();
    private int boundsWidth, boundsHeight;
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
    private float pressedLocalX, pressedLocalY;
    private Body pressedBody;
    private MotionEvent swipeStart;
    private boolean pagingTouch;
    private long pageSettledAt;
    private long pageSwitchDeadline;
    private int lastPageScroll;
    private View scenePage;
    private int sceneScrollX;
    private int pageBodyCount;
    private int swipeScrollOrigin;
    private int swipePageLeft;
    private View preparedPage;
    private final IdentityHashMap<View, IdentityHashMap<Object, float[]>> pagePositions =
            new IdentityHashMap<>();
    private Window.Callback originalCallback;
    private Window.Callback touchCallback;
    private boolean launchingApp;
    private LaunchSource launchSource;
    private boolean launchPaused;
    private boolean resumedAfterLaunch;
    private ViewTreeObserver launchReturnObserver;
    private ViewTreeObserver workspaceObserver;
    private final ArrayList<ParkedItem> parkedBodies = new ArrayList<>();
    private View parkedPage;
    private int parkedPageBodyCount;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable finishLaunchGrace = new Runnable() {
        @Override public void run() {
            if (launchPaused) {
                parkLaunchScene();
                return;
            }
            if (pageSwitchDeadline != 0) {
                mainHandler.postDelayed(this, PAGE_SETTLE_MS);
                return;
            }
            restoreLaunchSource();
            launchingApp = false;
            resumedAfterLaunch = false;
            if (onHome()) returning = true;
            else reset();
        }
    };

    private final Runnable expireLaunchReturn = new Runnable() {
        @Override public void run() {
            Log.i(Entry.TAG, "Home did not become ready to restore gravity after app launch");
            reset();
        }
    };

    private final ViewTreeObserver.OnPreDrawListener launchReturnListener =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    if (canRestoreLaunchScene()) restoreLaunchScene();
                    if (launchReturnObserver != null && !launchPaused) {
                        root.postInvalidateOnAnimation();
                    }
                    return true;
                }
            };

    private final ViewTreeObserver.OnPreDrawListener workspacePageListener =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    try {
                        syncWorkspacePage();
                        if (active && launchSource == null) {
                            switchReturnRenderer();
                            prepareSceneVisibility();
                        }
                    } catch (RuntimeException failure) {
                        reset();
                        Log.w(Entry.TAG, "Could not follow launcher page change", failure);
                    }
                    return true;
                }
            };

    GravityScreen(Activity activity) throws ReflectiveOperationException {
        this.activity = activity;
        root = activity.getWindow().getDecorView();
        scene = new Scene(false);
        density = root.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        workspace = findView("workspace");
        hotseat = findView("hotseat");
        dockSearch = resolveDockSearch();
        launcherBarIds = new int[]{resourceId("search_container_hotseat"),
                resourceId("search_container_workspace"), resourceId("smartspace_view"),
                resourceId("workspace_smartspace")};
        ClassLoader loader = activity.getClassLoader();
        iconType = loader.loadClass("com.android.launcher3.BubbleTextView");
        appDrawer = findView("apps_view");
        overview = findView("overview_panel");
        homeState = resolveHomeState(loader);
        sensors = activity.getSystemService(SensorManager.class);
    }

    private View findView(String name) {
        int id = resourceId(name);
        return id == 0 ? null : root.findViewById(id);
    }

    private int resourceId(String name) {
        return root.getResources().getIdentifier(name, "id", activity.getPackageName());
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
        workspaceObserver = root.getViewTreeObserver();
        workspaceObserver.addOnPreDrawListener(workspacePageListener);
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

    void parkLaunchScene() {
        if (!launchPaused || !active) return;
        mainHandler.removeCallbacks(finishLaunchGrace);
        stopWatchingLaunchReturn();
        if (scene.pageLayers.isEmpty() && scenePage != null) {
            rememberPage(scenePage, scene.bodies.subList(0, pageBodyCount));
        }
        for (PageLayer layer : scene.pageLayers.values()) {
            rememberPage(layer.page, layer.bodies);
        }
        pagingTouch = false;
        pageSwitchDeadline = 0;
        parkedPage = scenePage;
        parkedPageBodyCount = scene.pageLayers.isEmpty() ? pageBodyCount : 0;
        parkedBodies.clear();
        for (Body body : scene.bodies) parkedBodies.add(new ParkedItem(body));
        removeScene();
    }

    void prepareLaunchReturn() {
        if (parkedBodies.isEmpty()) return;
        if (launchReturnObserver == null || !launchReturnObserver.isAlive()) {
            launchReturnObserver = root.getViewTreeObserver();
            launchReturnObserver.addOnPreDrawListener(launchReturnListener);
        }
        root.invalidate();
    }

    private void stopWatchingLaunchReturn() {
        if (launchReturnObserver != null && launchReturnObserver.isAlive()) {
            launchReturnObserver.removeOnPreDrawListener(launchReturnListener);
        }
        launchReturnObserver = null;
        mainHandler.removeCallbacks(expireLaunchReturn);
    }

    private boolean canRestoreLaunchScene() {
        return root.getWindowVisibility() == View.VISIBLE
                && (isHomeLayout() || isWorkspaceVisible());
    }

    private void restoreLaunchScene() {
        try {
            begin();
            if (!active) return;
            clearParkedBodies();
            stopWatchingLaunchReturn();
            if (!launchPaused) startReturnGrace();
            Log.i(Entry.TAG, "Restored gravity on live launcher views after app return");
        } catch (RuntimeException failure) {
            reset();
            Log.w(Entry.TAG, "Could not restore gravity after app launch", failure);
        }
    }

    boolean resumeAfterLaunch() {
        if (!launchPaused || (!active && parkedBodies.isEmpty())) return false;
        if (active && !scene.liveViews) parkLaunchScene();
        launchPaused = false;
        restoreLaunchSource();
        initialized = false;
        previousHit = 0;
        lastFrame = 0;
        registerAccelerometer();
        mainHandler.removeCallbacks(finishLaunchGrace);
        if (active) {
            startReturnGrace();
        } else {
            if (canRestoreLaunchScene()) restoreLaunchScene();
            if (!active && !parkedBodies.isEmpty()) {
                prepareLaunchReturn();
                mainHandler.postDelayed(expireLaunchReturn, HOME_RESTORE_TIMEOUT_MS);
            }
        }
        return true;
    }

    private void startReturnGrace() {
        resumedAfterLaunch = true;
        returnOverlayTime = SystemClock.uptimeMillis() + 180;
        mainHandler.postDelayed(finishLaunchGrace, APP_LAUNCH_GRACE_MS);
    }

    void close() {
        if (workspaceObserver != null && workspaceObserver.isAlive()) {
            workspaceObserver.removeOnPreDrawListener(workspacePageListener);
        }
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
        return root.hasWindowFocus() && isHomeLayout();
    }

    private boolean isHomeLayout() {
        if (workspace == null || !workspace.isShown()) return false;
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
        if (!launchingApp && !pagingTouch && pageSwitchDeadline == 0 && hit && !aboveThreshold && time - lastToggle > 950) {
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
        scene = new Scene(!parkedBodies.isEmpty());
        int[] rootPosition = new int[2];
        root.getLocationOnScreen(rootPosition);
        scenePage = visiblePage();
        sceneScrollX = workspace != null ? workspace.getScrollX() : 0;
        collect(scenePage != null ? scenePage : workspace, rootPosition, scene.bodies, true);
        if (scenePage == parkedPage) {
            mergeParkedBodies(0, parkedPageBodyCount, 0);
        }
        pageBodyCount = scene.bodies.size();
        collect(hotseat, rootPosition, scene.bodies, true);
        if (root instanceof ViewGroup) collectSearchOutsideWorkspace((ViewGroup) root, rootPosition);
        mergeParkedBodies(parkedPageBodyCount, parkedBodies.size(), pageBodyCount);
        configureGravityBounds(rootPosition[1]);
        IdentityHashMap<Object, float[]> savedPage = pagePositions.get(scenePage);
        if (scene.bodies.isEmpty()) {
            Log.w(Entry.TAG, "No visible BubbleTextView icons");
            return;
        }
        scene.setBounds(0, 0, root.getWidth(), root.getHeight());
        if (!scene.liveViews) root.getOverlay().add(scene);
        for (Body body : scene.bodies) {
            if (!body.restoredFromLaunch && savedPage != null) {
                restorePosition(body, savedPage.get(body.key));
            }
            constrainBody(body);
        }
        prepareSceneVisibility();
        active = true;
        Log.i(Entry.TAG, "Gravity started with " + pageBodyCount + " page items and "
                + (scene.bodies.size() - pageBodyCount) + " shared items");
        choreographer.postFrameCallback(this);
    }

    private void mergeParkedBodies(int from, int to, int firstSceneIndex) {
        for (int index = from; index < to; index++) {
            ParkedItem parked = parkedBodies.get(index);
            View view = parked.view;
            if (view.getRootView() != root || !view.isAttachedToWindow()
                    || view.getVisibility() == View.GONE || view.getWidth() != parked.width
                    || view.getHeight() != parked.height || itemKey(view) != parked.key) continue;

            int existing = firstSceneIndex;
            while (existing < scene.bodies.size() && scene.bodies.get(existing).view != view) {
                existing++;
            }
            Body body;
            if (existing < scene.bodies.size()) {
                body = scene.bodies.get(existing);
            } else {
                try {
                    // Capture live content even when the hotseat is still sliding into view.
                    body = new Body(view, scene.liveViews, root.isHardwareAccelerated(), parked.homeX,
                            parked.homeY, density, parked.rectangular, zoneFor(view));
                    scene.bodies.add(body);
                } catch (RuntimeException failure) {
                    Log.w(Entry.TAG, "Could not redraw a parked launcher item", failure);
                    continue;
                }
            }
            body.homeX = parked.homeX;
            body.homeY = parked.homeY;
            restorePosition(body, parked.motion);
            body.restoredFromLaunch = true;
        }
    }

    private void clearParkedBodies() {
        parkedBodies.clear();
        parkedPage = null;
        parkedPageBodyCount = 0;
    }

    private static Object itemKey(View view) {
        Object tag = view.getTag();
        return tag != null ? tag : view;
    }

    private void switchReturnRenderer() {
        if (!scene.liveViews || !resumedAfterLaunch || !onHome() || returning
                || pagingTouch || pageSwitchDeadline != 0
                || SystemClock.uptimeMillis() < returnOverlayTime) {
            discardReturnOverlay();
            return;
        }

        if (returnOverlay == null) {
            Scene candidate = new Scene(false);
            try {
                for (Body item : scene.bodies) {
                    candidate.bodies.add(new Body(item.view, false, root.isHardwareAccelerated(),
                            item.homeX, item.homeY, density, item.rectangular, item.zone));
                }
            } catch (RuntimeException failure) {
                returnOverlayTime = Long.MAX_VALUE;
                Log.w(Entry.TAG, "Could not prepare the resumed drawing layer", failure);
                return;
            }
            candidate.probing = true;
            candidate.setBounds(scene.getBounds());
            root.getOverlay().add(candidate);
            returnOverlay = candidate;
            root.postInvalidateOnAnimation();
            return;
        }
        if (!returnOverlay.canvasReady) {
            returnOverlay.invalidateSelf();
            return;
        }
        if (returnOverlay.bodies.size() != scene.bodies.size()) {
            discardReturnOverlay();
            return;
        }
        for (int i = 0; i < scene.bodies.size(); i++) {
            if (returnOverlay.bodies.get(i).view != scene.bodies.get(i).view
                    || !returnOverlay.canDraw(returnOverlay.bodies.get(i))) {
                discardReturnOverlay();
                returnOverlayTime = Long.MAX_VALUE;
                return;
            }
        }
        for (int i = 0; i < scene.bodies.size(); i++) {
            Body live = scene.bodies.get(i);
            Body image = returnOverlay.bodies.get(i);
            image.x = live.x;
            image.y = live.y;
            image.vx = live.vx;
            image.vy = live.vy;
            restoreVisibility(live);
        }
        scene.restoreClipping();
        scene = returnOverlay;
        returnOverlay = null;
        scene.probing = false;
        scene.invalidateSelf();
        Log.i(Entry.TAG, "Moved resumed gravity to the unclipped drawing layer");
    }

    private void discardReturnOverlay() {
        if (returnOverlay == null) return;
        root.getOverlay().remove(returnOverlay);
        returnOverlay = null;
    }

    private void prepareSceneVisibility() {
        if (scene.liveViews) scene.keepParentsUnclipped();
        updateOriginalVisibility(scene.bodies);
        for (PageLayer layer : scene.pageLayers.values()) {
            updateOriginalVisibility(layer.bodies);
        }
    }

    private void updateOriginalVisibility(List<Body> bodies) {
        for (Body body : bodies) {
            if (scene.liveViews) {
                if (launchSource == null || launchSource.body != body) scene.translate(body);
            } else if (scene.canDraw(body)) {
                if (body.view.getTransitionAlpha() != 0f) body.view.setTransitionAlpha(0f);
            } else {
                restoreVisibility(body);
            }
        }
    }

    private View visiblePage() {
        if (!(workspace instanceof ViewGroup)) return null;
        ViewGroup pages = (ViewGroup) workspace;
        int center = pages.getScrollX() + pages.getWidth() / 2;
        View nearest = null;
        int distance = Integer.MAX_VALUE;
        for (int i = 0; i < pages.getChildCount(); i++) {
            View page = pages.getChildAt(i);
            if (page.getVisibility() != View.VISIBLE || page.getWidth() == 0) continue;
            int candidate = Math.abs(page.getLeft() + page.getWidth() / 2 - center);
            if (candidate < distance) {
                nearest = page;
                distance = candidate;
            }
        }
        return nearest;
    }

    private View resolveDockSearch() {
        for (Class<?> type = hotseat == null ? null : hotseat.getClass();
                type != null && type != View.class; type = type.getSuperclass()) {
            try {
                Field search = type.getDeclaredField("mQsb");
                search.setAccessible(true);
                Object value = search.get(hotseat);
                return value instanceof View ? (View) value : null;
            } catch (NoSuchFieldException missing) {
                // The field can be declared on the base Hotseat class.
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                break;
            }
        }
        return null;
    }

    private boolean isLauncherBar(View view) {
        int searchId = launcherBarIds[0];
        if (view == dockSearch || (searchId != 0 && view.getId() == searchId)) return true;
        String type = view.getClass().getSimpleName();
        if (type.equals("HotseatQsb") || type.equals("SmartspaceView")
                || type.equals("SmartspaceViewContainer") || type.equals("BcSmartspaceView")) return true;
        int id = view.getId();
        for (int candidate : launcherBarIds) {
            if (candidate != 0 && candidate == id) return true;
        }
        return false;
    }

    private void collectSearchOutsideWorkspace(ViewGroup group, int[] rootPosition) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == workspace || child == hotseat || child == appDrawer || child == overview
                    || child.getVisibility() != View.VISIBLE) continue;
            if (isLauncherBar(child)) {
                collect(child, rootPosition, scene.bodies, true);
            } else if (child instanceof ViewGroup) {
                collectSearchOutsideWorkspace((ViewGroup) child, rootPosition);
            }
        }
    }

    private enum Zone { WORKSPACE, DOCK, SEARCH }

    private Zone zoneFor(View view) {
        int searchId = launcherBarIds[0];
        if (view == dockSearch || (searchId != 0 && view.getId() == searchId)
                || view.getClass().getSimpleName().equals("HotseatQsb")) return Zone.SEARCH;
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == hotseat) return Zone.DOCK;
            if (parent == workspace || parent == root) break;
        }
        return Zone.WORKSPACE;
    }

    private void configureGravityBounds(int rootTop) {
        int width = root.getWidth();
        int height = root.getHeight();
        // The hotseat may still be translated when coming back from an app.
        if (!parkedBodies.isEmpty() && boundsWidth == width && boundsHeight == height) return;
        boundsWidth = width;
        boundsHeight = height;
        workspaceArena.set(arena);
        dockArena.set(arena);
        searchArena.set(arena);

        int dockTop = arena.bottom;
        int searchTop = arena.bottom;
        int tallestDockItem = 0;
        int tallestSearchItem = 0;
        for (Body body : scene.bodies) {
            int top = Math.round(body.homeY - body.height / 2f);
            if (body.zone == Zone.DOCK) {
                dockTop = Math.min(dockTop, top);
                tallestDockItem = Math.max(tallestDockItem, body.height);
            } else if (body.zone == Zone.SEARCH) {
                searchTop = Math.min(searchTop, top);
                tallestSearchItem = Math.max(tallestSearchItem, body.height);
            }
        }
        if (tallestDockItem == 0 && tallestSearchItem == 0) return;

        int gap = Math.max(0, Math.min(Math.round(DOCK_GAP_DP * density),
                (arena.height() - tallestDockItem - tallestSearchItem - 1) / 2));
        int[] position = new int[2];
        if (tallestDockItem > 0 && hotseat != null && hotseat.getHeight() > 0) {
            hotseat.getLocationOnScreen(position);
            int layoutTop = position[1] - rootTop;
            if (layoutTop > arena.centerY()) dockTop = Math.min(dockTop, layoutTop);
        }
        if (tallestSearchItem > 0) {
            int lowestSearchTop = arena.bottom - tallestSearchItem;
            int earliestSearchTop = tallestDockItem > 0
                    ? dockTop + tallestDockItem + gap : arena.top;
            searchArena.top = Math.max(arena.top,
                    Math.min(lowestSearchTop, Math.max(searchTop, earliestSearchTop)));
            dockArena.bottom = searchArena.top - gap;
        }
        if (tallestDockItem > 0) {
            dockArena.top = Math.max(arena.top, Math.min(dockTop, dockArena.bottom - tallestDockItem));
        }
        int panelTop = tallestDockItem > 0 ? dockArena.top : searchArena.top;
        workspaceArena.bottom = Math.max(arena.top + 1, panelTop - gap);
        for (int i = 0; i < pageBodyCount; i++) {
            Body body = scene.bodies.get(i);
            if (!(body.view.getParent() instanceof View)) continue;
            View container = (View) body.view.getParent();
            if (!container.getClass().getSimpleName().equals("ShortcutAndWidgetContainer")) continue;
            container.getLocationOnScreen(position);
            int gridBottom = position[1] - rootTop + container.getHeight();
            if (gridBottom > arena.centerY()) {
                workspaceArena.bottom = Math.min(workspaceArena.bottom, gridBottom);
            }
        }
        Log.i(Entry.TAG, "Workspace bottom " + workspaceArena.bottom + ", dock " + dockArena.top
                + "–" + dockArena.bottom + ", search top " + searchArena.top);
    }

    private void constrainBody(Body body) {
        switch (body.zone) {
            case DOCK: body.constrain(dockArena); break;
            case SEARCH: body.constrain(searchArena); break;
            default: body.constrain(workspaceArena);
        }
    }

    private void restorePosition(Body body, float[] position) {
        if (position == null) return;
        body.x = position[0];
        body.y = position[1];
        body.vx = position[2];
        body.vy = position[3];
    }

    private static boolean isFolderIcon(View view) {
        return view.getClass().getSimpleName().equals("FolderIcon");
    }

    private void collect(View view, int[] rootPosition, ArrayList<Body> bodies,
                         boolean requireVisibleInWindow) {
        if (view == null || view.getVisibility() != View.VISIBLE
                || view.getTransitionAlpha() <= 0 || bodies.size() >= 48
                || (requireVisibleInWindow && view.getAlpha() <= 0)) return;
        boolean rectangular = isLauncherBar(view) || view instanceof AppWidgetHostView;
        if (rectangular || iconType.isInstance(view) || isFolderIcon(view)) {
            if (view.getAlpha() <= 0) return;
            int width = view.getWidth();
            int height = view.getHeight();
            if (width < 1 || height < 1
                    || height > (rectangular ? root.getHeight() : 256 * density)
                    || width > (rectangular ? root.getWidth() : 256 * density)) return;
            Rect visible = new Rect();
            if (requireVisibleInWindow && (!view.getGlobalVisibleRect(visible)
                    || visible.width() < width * 0.8f || visible.height() < height * 0.8f)) return;
            int[] xy = new int[2];
            view.getLocationOnScreen(xy);
            float centerX = xy[0] - rootPosition[0] + width / 2f;
            float centerY = xy[1] - rootPosition[1] + height / 2f;
            if (!arena.contains((int) centerX, (int) centerY)) return;
            try {
                bodies.add(new Body(view, scene.liveViews, root.isHardwareAccelerated(),
                        centerX, centerY, density, rectangular, zoneFor(view)));
            } catch (RuntimeException failure) {
                Log.w(Entry.TAG, "Could not capture launcher item", failure);
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collect(group.getChildAt(index), rootPosition, bodies, requireVisibleInWindow);
            }
        }
    }

    @Override
    public void doFrame(long time) {
        try {
            if (pagingTouch || pageSwitchDeadline != 0) {
                updatePageSwipe(frameDelta(time));
                return;
            }
            if (!active) return;
            boolean sameSize = scene.getBounds().width() == root.getWidth()
                    && scene.getBounds().height() == root.getHeight();
            if (launchPaused && (!sameSize || root.getWindowVisibility() != View.VISIBLE)) {
                parkLaunchScene();
                return;
            }
            if ((!launchingApp && !onHome()) || !root.isAttachedToWindow() || !sameSize) {
                reset();
                return;
            }
            float dt = frameDelta(time);
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
            redrawScene();
            choreographer.postFrameCallback(this);
        } catch (RuntimeException failure) {
            reset();
            Log.w(Entry.TAG, "Gravity animation stopped", failure);
        }
    }

    private float frameDelta(long time) {
        float dt = lastFrame == 0 ? 1f / 60f : Math.min((time - lastFrame) / 1e9f, 0.032f);
        lastFrame = time;
        return dt;
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
        float damping = (float) Math.pow(0.987f, step * 180);
        float maxSpeed = MAX_SPEED_DP_PER_SECOND * density;
        Body opening = launchSource != null ? launchSource.body : null;
        advanceBodies(scene.bodies, opening, gx, gy, step, damping, maxSpeed);
        for (PageLayer layer : scene.pageLayers.values()) {
            if (Math.abs(pageOffset(layer.page)) < root.getWidth()) {
                advanceBodies(layer.bodies, null, gx, gy, step, damping, maxSpeed);
            }
        }
    }

    private void advanceBodies(ArrayList<Body> bodies, Body opening, float gx, float gy,
                               float step, float damping, float maxSpeed) {
        for (int part = 0; part < 3; part++) {
            for (Body body : bodies) {
                if (body == opening) continue;
                body.vx = (body.vx + gx * GRAVITY_SCALE * density * step) * damping;
                body.vy = (body.vy + gy * GRAVITY_SCALE * density * step) * damping;
                float speedSquared = body.vx * body.vx + body.vy * body.vy;
                if (speedSquared > maxSpeed * maxSpeed) {
                    float scale = maxSpeed / (float) Math.sqrt(speedSquared);
                    body.vx *= scale;
                    body.vy *= scale;
                }
                body.x += body.vx * step;
                body.y += body.vy * step;
            }
            for (int i = 0; i < bodies.size(); i++) {
                Body body = bodies.get(i);
                if (body == opening) continue;
                for (int j = 0; j < i; j++) {
                    Body other = bodies.get(j);
                    if (other != opening && body.zone == other.zone) body.collide(other);
                }
            }
            for (Body body : bodies) {
                if (body != opening) constrainBody(body);
            }
        }
    }

    boolean handleTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (pagingTouch) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pagingTouch = false;
                waitForPageToSettle();
            }
            return false;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            if (pageSwitchDeadline != 0) {
                pageSwitchDeadline = 0;
                pagingTouch = true;
                return false;
            }
            recycleSwipeStart();
            trackingTouch = active;
            if (!trackingTouch) return false;
            swipeStart = MotionEvent.obtain(event);
            downX = event.getRawX();
            downY = event.getRawY();
            int[] origin = new int[2];
            root.getLocationOnScreen(origin);
            pressedBody = findBodyAt(downX - origin[0], downY - origin[1]);
            if (pressedBody != null) {
                pressedLocalX = downX - origin[0] - pressedBody.x + pressedBody.width / 2f;
                pressedLocalY = downY - origin[1] - pressedBody.y + pressedBody.height / 2f;
            }
            tap = true;
            return true;
        }
        if (!trackingTouch) return false;

        float dx = event.getRawX() - downX;
        float dy = event.getRawY() - downY;
        if (action == MotionEvent.ACTION_MOVE && tap && active && scenePage != null
                && (!launchingApp || resumedAfterLaunch) && swipeStart != null
                && Math.abs(dx) > touchSlop && Math.abs(dx) > 1.3f * Math.abs(dy)) {
            passPageSwipe();
            return false;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || dx * dx + dy * dy > touchSlop * touchSlop) {
            tap = false;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            Body selected = action == MotionEvent.ACTION_UP && tap && active
                    && (!launchingApp || resumedAfterLaunch) && onHome() ? pressedBody : null;
            trackingTouch = false;
            pressedBody = null;
            recycleSwipeStart();
            if (selected != null && selected.view.isAttachedToWindow() && selected.view.isEnabled()
                    && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
                if (selected.view instanceof AppWidgetHostView) {
                    dispatchWidgetTap(selected.view, event.getEventTime());
                    return true;
                }
                if (!selected.view.isClickable()) return true;
                if (isFolderIcon(selected.view)) {
                    reset();
                    selected.view.performClick();
                    return true;
                }
                launchingApp = true;
                returning = false;
                elapsedReturn = 0;
                resumedAfterLaunch = false;
                mainHandler.removeCallbacks(finishLaunchGrace);
                mainHandler.postDelayed(finishLaunchGrace, APP_LAUNCH_GRACE_MS);
                boolean handled = false;
                try {
                    moveLaunchSource(selected);
                    handled = selected.view.performClick();
                } finally {
                    if (!handled) reset();
                }
                Log.i(Entry.TAG, "Animated icon tap handled: " + handled);
            }
        }
        return true;
    }

    private void dispatchWidgetTap(View widget, long time) {
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN,
                pressedLocalX, pressedLocalY, 0);
        MotionEvent up = MotionEvent.obtain(time, time, MotionEvent.ACTION_UP,
                pressedLocalX, pressedLocalY, 0);
        try {
            if (widget.dispatchTouchEvent(down)) widget.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private void moveLaunchSource(Body body) {
        restoreLaunchSource();
        View view = body.view;
        int[] rootPosition = new int[2];
        int[] viewPosition = new int[2];
        root.getLocationOnScreen(rootPosition);
        view.getLocationOnScreen(viewPosition);
        float[] offset = {
                rootPosition[0] + body.x - body.width / 2f - viewPosition[0],
                rootPosition[1] + body.y - body.height / 2f - viewPosition[1]
        };
        if (view.getParent() instanceof View) {
            Matrix parentToScreen = new Matrix();
            ((View) view.getParent()).transformMatrixToGlobal(parentToScreen);
            Matrix screenToParent = new Matrix();
            if (!parentToScreen.invert(screenToParent)) return;
            screenToParent.mapVectors(offset);
        }
        launchSource = new LaunchSource(body, offset[0], offset[1]);
        // Quickstep reads the source bounds asynchronously after performClick().
        view.setTranslationX(launchSource.targetX);
        view.setTranslationY(launchSource.targetY);
    }

    private void restoreLaunchSource() {
        if (launchSource == null) return;
        LaunchSource source = launchSource;
        launchSource = null;
        View view = source.body.view;
        if (view.getTranslationX() == source.targetX) view.setTranslationX(source.originalX);
        if (view.getTranslationY() == source.targetY) view.setTranslationY(source.originalY);
    }

    private void syncWorkspacePage() {
        if (!active || scenePage == null || launchSource != null
                || root.getWindowVisibility() != View.VISIBLE) return;
        if (pagingTouch || pageSwitchDeadline != 0) {
            prepareNearbyPages();
            return;
        }
        int scrollDelta = workspace.getScrollX() - sceneScrollX;
        View visible = visiblePage();
        if (visible == null || (scrollDelta == 0 && visible == scenePage)) return;

        beginPageSwitch();
        prepareNearbyPages();
        if (visible != scenePage && Math.abs(scrollDelta) >= workspace.getWidth() * 0.75f) {
            // Quickstep can snap straight to the default page on return from an app.
            finishPageSwipe();
        } else {
            waitForPageToSettle();
        }
        redrawScene();
    }

    private void redrawScene() {
        if (scene.liveViews) prepareSceneVisibility();
        else scene.invalidateSelf();
    }

    private void beginPageSwitch() {
        swipeScrollOrigin = sceneScrollX;
        swipePageLeft = scenePage.getLeft();
        PageLayer outgoing = new PageLayer(scenePage);
        outgoing.bodies.addAll(scene.bodies.subList(0, pageBodyCount));
        scene.bodies.subList(0, pageBodyCount).clear();
        scene.pageLayers.put(scenePage, outgoing);
    }

    private void waitForPageToSettle() {
        pageSettledAt = SystemClock.uptimeMillis();
        pageSwitchDeadline = pageSettledAt + PAGE_SWITCH_TIMEOUT_MS;
        lastPageScroll = workspace.getScrollX();
    }

    private void passPageSwipe() {
        beginPageSwitch();
        mainHandler.removeCallbacks(finishLaunchGrace);
        launchingApp = false;
        resumedAfterLaunch = false;
        returning = false;
        elapsedReturn = 0;
        trackingTouch = false;
        pressedBody = null;
        pagingTouch = true;
        prepareNearbyPages();
        try {
            originalCallback.dispatchTouchEvent(swipeStart);
        } finally {
            recycleSwipeStart();
        }
    }

    private int pageOffset(View page) {
        return page.getLeft() - swipePageLeft + swipeScrollOrigin - workspace.getScrollX();
    }

    private void prepareNearbyPages() {
        View current = visiblePage();
        if (current == null || current == preparedPage) return;
        preparedPage = current;
        ViewGroup pages = (ViewGroup) workspace;
        int index = pages.indexOfChild(current);
        for (int i = Math.max(0, index - 1); i <= Math.min(index + 1, pages.getChildCount() - 1); i++) {
            View page = pages.getChildAt(i);
            if (page.getWidth() == 0 || scene.pageLayers.containsKey(page)) continue;
            PageLayer layer = new PageLayer(page);
            int[] origin = new int[2];
            root.getLocationOnScreen(origin);
            origin[0] += pageOffset(page);
            collect(page, origin, layer.bodies, false);
            IdentityHashMap<Object, float[]> positions = pagePositions.get(page);
            scene.pageLayers.put(page, layer);
            for (Body body : layer.bodies) {
                restorePosition(body, positions == null ? null : positions.get(body.key));
                constrainBody(body);
            }
            updateOriginalVisibility(layer.bodies);
        }
    }

    private void updatePageSwipe(float dt) {
        boolean homeVisible = onHome() || (launchingApp
                && root.getWindowVisibility() == View.VISIBLE
                && (isHomeLayout() || isWorkspaceVisible()));
        if (!homeVisible || !root.isAttachedToWindow()) {
            reset();
            return;
        }
        prepareNearbyPages();
        animate(dt);
        if (!pagingTouch) {
            long now = SystemClock.uptimeMillis();
            int currentScroll = workspace.getScrollX();
            if (currentScroll != lastPageScroll) {
                lastPageScroll = currentScroll;
                pageSettledAt = now;
            }
            if (now - pageSettledAt >= PAGE_SETTLE_MS || now >= pageSwitchDeadline) finishPageSwipe();
        }
        redrawScene();
        choreographer.postFrameCallback(this);
    }

    private void finishPageSwipe() {
        View selected = visiblePage();
        PageLayer incoming = scene.pageLayers.get(selected);
        for (PageLayer layer : scene.pageLayers.values()) {
            if (layer == incoming) continue;
            rememberPage(layer.page, layer.bodies);
            for (Body body : layer.bodies) restoreVisibility(body);
        }
        pageBodyCount = incoming == null ? 0 : incoming.bodies.size();
        if (incoming != null) {
            int offset = pageOffset(incoming.page);
            for (Body body : incoming.bodies) {
                body.x += offset;
                body.homeX += offset;
                constrainBody(body);
            }
            scene.bodies.addAll(0, incoming.bodies);
        }
        scene.pageLayers.clear();
        scenePage = selected;
        sceneScrollX = workspace.getScrollX();
        preparedPage = null;
        pageSwitchDeadline = 0;
    }

    private void rememberPage(View page, List<Body> bodies) {
        IdentityHashMap<Object, float[]> positions = new IdentityHashMap<>();
        for (Body body : bodies) positions.put(body.key, new float[]{body.x, body.y, body.vx, body.vy});
        pagePositions.put(page, positions);
    }

    private void recycleSwipeStart() {
        if (swipeStart == null) return;
        swipeStart.recycle();
        swipeStart = null;
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
        pagingTouch = false;
        mainHandler.removeCallbacks(finishLaunchGrace);
        launchingApp = false;
        launchPaused = false;
        resumedAfterLaunch = false;
        pressedBody = null;
        stopWatchingLaunchReturn();
        pageSwitchDeadline = 0;
        recycleSwipeStart();
        pagePositions.clear();
        clearParkedBodies();
        removeScene();
    }

    private void removeScene() {
        discardReturnOverlay();
        restoreLaunchSource();
        choreographer.removeFrameCallback(this);
        root.getOverlay().remove(scene);
        for (Body body : scene.bodies) restoreVisibility(body);
        for (PageLayer layer : scene.pageLayers.values()) {
            for (Body body : layer.bodies) restoreVisibility(body);
        }
        scene.restoreClipping();
        scene.pageLayers.clear();
        scene.bodies.clear();
        preparedPage = null;
        active = false;
        returning = false;
        elapsedReturn = 0;
        lastFrame = 0;
    }

    private void restoreVisibility(Body body) {
        if (body.translation != null) {
            body.translation.restore(body.view);
            body.translation = null;
        }
        if (!scene.liveViews && body.view.getTransitionAlpha() == 0f) {
            body.view.setTransitionAlpha(body.transitionAlpha);
        }
    }

    private static final class ParkedItem {
        final View view;
        final Object key;
        final int width, height;
        final boolean rectangular;
        final float homeX, homeY;
        final float[] motion;

        ParkedItem(Body body) {
            view = body.view;
            key = body.key;
            width = body.width;
            height = body.height;
            rectangular = body.rectangular;
            homeX = body.homeX;
            homeY = body.homeY;
            motion = new float[]{body.x, body.y, body.vx, body.vy};
        }
    }

    private static final class LaunchSource {
        final Body body;
        final float originalX, originalY, targetX, targetY;

        LaunchSource(Body body, float offsetX, float offsetY) {
            this.body = body;
            originalX = body.view.getTranslationX();
            originalY = body.view.getTranslationY();
            targetX = originalX + offsetX;
            targetY = originalY + offsetY;
        }
    }

    private static final class PageLayer {
        final View page;
        final ArrayList<Body> bodies = new ArrayList<>();

        PageLayer(View page) {
            this.page = page;
        }
    }

    private final class Scene extends Drawable {
        final ArrayList<Body> bodies = new ArrayList<>();
        final IdentityHashMap<View, PageLayer> pageLayers = new IdentityHashMap<>();
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        final boolean liveViews;
        final IdentityHashMap<ViewGroup, ClipState> clipping = new IdentityHashMap<>();
        final Matrix parentMatrix = new Matrix();
        final Matrix inverseMatrix = new Matrix();
        final float[] offset = new float[2];
        boolean hardwareCanvas;
        boolean probing;
        boolean canvasReady;

        Scene(boolean liveViews) {
            this.liveViews = liveViews;
            hardwareCanvas = root.isHardwareAccelerated();
        }

        void translate(Body body) {
            View view = body.view;
            if (!view.isAttachedToWindow()) return;
            if (body.translation == null) {
                body.translation = new LiveTranslation(view);
                for (ViewParent parent = view.getParent(); parent instanceof ViewGroup
                        && parent != root; parent = parent.getParent()) {
                    ViewGroup group = (ViewGroup) parent;
                    if (clipping.containsKey(group)) continue;
                    ClipState state = new ClipState();
                    clipping.put(group, state);
                    state.unclip(group);
                }
            }
            offset[0] = body.x - body.homeX;
            offset[1] = body.y - body.homeY;
            if (view.getParent() instanceof View) {
                parentMatrix.reset();
                ((View) view.getParent()).transformMatrixToGlobal(parentMatrix);
                if (parentMatrix.invert(inverseMatrix)) inverseMatrix.mapVectors(offset);
            }
            body.translation.apply(view, offset[0], offset[1]);
        }

        void keepParentsUnclipped() {
            for (ViewGroup group : clipping.keySet()) clipping.get(group).unclip(group);
        }

        void restoreClipping() {
            for (ViewGroup group : clipping.keySet()) clipping.get(group).restore(group);
            clipping.clear();
        }

        boolean canDraw(Body body) {
            return body.renderNode == null
                    ? body.bitmap != null && !body.bitmap.isRecycled()
                    : hardwareCanvas && body.renderNode.hasDisplayList();
        }

        @Override public void draw(Canvas canvas) {
            hardwareCanvas = canvas.isHardwareAccelerated();
            if (probing) {
                canvasReady = true;
                return;
            }
            for (PageLayer layer : pageLayers.values()) {
                int offset = pageOffset(layer.page);
                for (Body body : layer.bodies) drawBody(canvas, body, offset);
            }
            for (Body body : bodies) drawBody(canvas, body, 0);
        }

        private void drawBody(Canvas canvas, Body body, int offset) {
            if (!canDraw(body)) return;
            if (launchSource != null && body == launchSource.body && !root.hasWindowFocus()) return;
            float left = body.x - body.width / 2f + offset;
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
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class ClipState {
        boolean children, padding, outline;
        Rect bounds;

        void unclip(ViewGroup group) {
            // Quickstep can reapply clipping when the close animation finishes.
            if (group.getClipChildren()) {
                children = true;
                group.setClipChildren(false);
            }
            if (group.getClipToPadding()) {
                padding = true;
                group.setClipToPadding(false);
            }
            if (group.getClipToOutline()) {
                outline = true;
                group.setClipToOutline(false);
            }
            Rect current = group.getClipBounds();
            if (current != null) {
                bounds = current;
                group.setClipBounds(null);
            }
        }

        void restore(ViewGroup group) {
            if (children) group.setClipChildren(true);
            if (padding) group.setClipToPadding(true);
            if (outline) group.setClipToOutline(true);
            if (bounds != null && group.getClipBounds() == null) group.setClipBounds(bounds);
        }
    }

    private static final class LiveTranslation {
        private float baseX, baseY, appliedX, appliedY;

        LiveTranslation(View view) {
            baseX = appliedX = view.getTranslationX();
            baseY = appliedY = view.getTranslationY();
        }

        void apply(View view, float dx, float dy) {
            if (view.getTranslationX() != appliedX) baseX = view.getTranslationX();
            if (view.getTranslationY() != appliedY) baseY = view.getTranslationY();
            appliedX = baseX + dx;
            appliedY = baseY + dy;
            view.setTranslationX(appliedX);
            view.setTranslationY(appliedY);
        }

        void restore(View view) {
            if (view.getTranslationX() == appliedX) view.setTranslationX(baseX);
            if (view.getTranslationY() == appliedY) view.setTranslationY(baseY);
        }
    }

    private static final class Body {
        final View view;
        final Bitmap bitmap;
        final RenderNode renderNode;
        final int width, height;
        final boolean rectangular;
        final Zone zone;
        final Object key;
        final float transitionAlpha, radius, bounceThreshold, contactSlop;
        float homeX, homeY, x, y, vx, vy;
        boolean restoredFromLaunch;
        LiveTranslation translation;

        Body(View view, boolean liveView, boolean hardwareAccelerated, float centerX, float centerY,
                float density, boolean rectangular, Zone zone) {
            this.view = view;
            this.rectangular = rectangular;
            this.zone = zone;
            width = view.getWidth();
            height = view.getHeight();
            if (liveView) {
                bitmap = null;
                renderNode = null;
            } else if (hardwareAccelerated) {
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
            key = itemKey(view);
            transitionAlpha = view.getTransitionAlpha();
            homeX = x = centerX;
            homeY = y = centerY;
            radius = Math.min(27 * density, Math.min(width, height) / 2f);
            bounceThreshold = 80 * density;
            contactSlop = 0.5f * density;
            vx = (float) (Math.random() - 0.5) * 130 * density;
            vy = (float) (Math.random() - 0.5) * 130 * density;
        }

        void constrain(Rect bounds) {
            float halfWidth = Math.min(width / 2f, bounds.width() / 2f);
            float halfHeight = Math.min(height / 2f, bounds.height() / 2f);
            float minX = bounds.left + halfWidth, maxX = bounds.right - halfWidth;
            float minY = bounds.top + halfHeight, maxY = bounds.bottom - halfHeight;
            if (x < minX) { x = minX; vx = rebound(-vx); }
            if (x > maxX) { x = maxX; vx = -rebound(vx); }
            if (y < minY) { y = minY; vy = rebound(-vy); }
            if (y > maxY) { y = maxY; vy = -rebound(vy); }
        }

        private float rebound(float impactSpeed) {
            return impactSpeed > bounceThreshold ? impactSpeed * 0.48f : 0;
        }

        void collide(Body other) {
            float dx = x - other.x, dy = y - other.y;
            if (rectangular || other.rectangular) {
                float overlapX = (rectangular ? width / 2f : radius)
                        + (other.rectangular ? other.width / 2f : other.radius) - Math.abs(dx);
                float overlapY = (rectangular ? height / 2f : radius)
                        + (other.rectangular ? other.height / 2f : other.radius) - Math.abs(dy);
                if (overlapX <= 0 || overlapY <= 0) return;
                if (overlapX < overlapY) {
                    separate(other, dx < 0 ? -1 : 1, 0, overlapX / 2f);
                } else {
                    separate(other, 0, dy < 0 ? -1 : 1, overlapY / 2f);
                }
                return;
            }
            float reach = radius + other.radius;
            float squared = dx * dx + dy * dy;
            if (squared >= reach * reach) return;
            float distance = (float) Math.sqrt(squared);
            float nx = distance > 0 ? dx / distance : 1;
            float ny = distance > 0 ? dy / distance : 0;
            separate(other, nx, ny, (reach - distance) / 2);
        }

        private void separate(Body other, float nx, float ny, float separation) {
            float correction = Math.max(0, separation - contactSlop) * 0.8f;
            x += nx * correction;
            y += ny * correction;
            other.x -= nx * correction;
            other.y -= ny * correction;
            float relativeSpeed = (vx - other.vx) * nx + (vy - other.vy) * ny;
            if (relativeSpeed >= 0) return;
            float restitution = -relativeSpeed > bounceThreshold ? 0.40f : 0f;
            float impulse = -relativeSpeed * (1 + restitution) / 2f;
            vx += impulse * nx;
            vy += impulse * ny;
            other.vx -= impulse * nx;
            other.vy -= impulse * ny;
        }
    }
}
