package de.mikromakro.replay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MakroService extends AccessibilityService {
    public interface Listener {
        void onChanged();
    }

    private static MakroService instance;

    public static MakroService get() {
        return instance;
    }

    private static final int MAX_STEPS = 8000;
    private static final String PREFS = "opts";

    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private final SparseArray<ArrayList<float[]>> pointers = new SparseArray<>();
    private final Random rnd = new Random();
    private final Runnable commitTextRunnable = this::commitPendingText;

    private Context ui;
    private Handler handler;

    private Context ui() {
        if (ui == null) ui = new ContextThemeWrapper(this, R.style.AppTheme);
        return ui;
    }
    private MacroStore store;
    private WindowManager wm;
    private View shield;
    private WindowManager.LayoutParams shieldLp;
    private boolean shieldAdded;
    private View panel;
    private WindowManager.LayoutParams panelLp;
    private boolean panelAdded;
    private TextView panelCount;
    private Button bRec;
    private Button bPlay;

    private Models.Macro current;
    private String activeId;
    private boolean recording;
    private boolean playing;
    private boolean typingMode;
    private boolean echoGuard;
    private boolean gestureOpen;
    private boolean pkgInit;
    private String lastPkg;
    private long recordAnchor;
    private long gestureDownAt;
    private int playGen;
    private int stepIndex;
    private int runIndex;
    private Models.Macro playingMacro;
    private Models.PlayOpts playOpts = new Models.PlayOpts();

    private String pendingText;
    private String pendingViewId;
    private float pendingX;
    private float pendingY;
    private boolean pendingDirty;
    private long ignoreUntil;
    private boolean passwordNoted;

    public boolean isRecording() { return recording; }
    public boolean isPlaying() { return playing; }
    public String getActiveId() { return activeId; }
    public Models.Macro currentMacro() { return current; }
    public MacroStore store() { return store; }

    public int stepCount() {
        if (recording && current != null) return current.steps.size();
        Models.Macro m = store == null ? null : store.byId(activeId);
        return m == null ? 0 : m.steps.size();
    }

    public List<String> logLines() {
        return new ArrayList<>(logs);
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void setActiveId(String id) {
        activeId = id;
        prefs().edit().putString("active", id).apply();
        notifyListeners();
    }

    public Models.PlayOpts loadOpts() {
        return Models.PlayOpts.fromJson(prefs().getString("json", null));
    }

    public void saveOpts(Models.PlayOpts opts) {
        if (opts == null) return;
        try {
            prefs().edit().putString("json", opts.toJson().toString()).apply();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        store = new MacroStore(this);
        activeId = prefs().getString("active", null);
        if (activeId != null && store.byId(activeId) == null) activeId = null;
        if (activeId == null && !store.all().isEmpty()) activeId = store.all().get(0).id;
        showPanel();
        log("Bereit");
        notifyListeners();
    }

    @Override
    public void onInterrupt() {
        stopPlay();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        teardown();
        return false;
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }

    private void teardown() {
        recording = false;
        playing = false;
        playGen++;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        detachShield();
        removePanel();
        if (instance == this) instance = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || fromSelf(event)) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            notePackage(event);
        }
        if (!recording || playing) return;
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            onTextChanged(event);
            return;
        }
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            AccessibilityNodeInfo src = event.getSource();
            try {
                if (src != null && src.isEditable() && !src.isPassword()) enterTypingMode();
            } finally {
                if (src != null) src.recycle();
            }
            return;
        }
        if ((type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
                && typingMode && !echoGuard && SystemClock.elapsedRealtime() >= ignoreUntil) {
            recordClickFallback(event, type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
        }
    }

    public void startRecord() {
        if (playing) stopPlay();
        String stamp = new SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(new Date());
        current = Models.Macro.create("Makro " + stamp);
        activeId = current.id;
        prefs().edit().putString("active", activeId).apply();
        recording = true;
        typingMode = false;
        echoGuard = false;
        gestureOpen = false;
        pkgInit = false;
        lastPkg = null;
        passwordNoted = false;
        pendingDirty = false;
        pointers.clear();
        recordAnchor = SystemClock.elapsedRealtime();
        log("Aufnahme");
        attachShield();
        updatePanel();
        notifyListeners();
    }

    public void stopRecord() {
        if (!recording) return;
        gestureOpen = false;
        pointers.clear();
        if (handler != null) handler.removeCallbacks(commitTextRunnable);
        commitPendingText();
        recording = false;
        typingMode = false;
        echoGuard = false;
        detachShield();
        if (current != null && current.steps.isEmpty()) {
            store.delete(current.id);
            log("Leer, nichts gespeichert");
        } else if (current != null) {
            store.upsert(current);
            activeId = current.id;
            log("Gespeichert · " + current.steps.size() + " Schritte");
        }
        updatePanel();
        notifyListeners();
    }

    public void undo() {
        Models.Macro m = current != null ? current : (store == null ? null : store.byId(activeId));
        if (m == null || m.steps.isEmpty()) return;
        m.steps.remove(m.steps.size() - 1);
        if (m.steps.isEmpty()) store.delete(m.id);
        else store.upsert(m);
        log("Rückgängig");
        updatePanel();
        notifyListeners();
    }

    public void pressGlobal(String action) {
        int code = globalCode(action);
        if (code != 0) {
            echoGuard = true;
            performGlobalAction(code);
            if (handler != null) handler.postDelayed(() -> echoGuard = false, 450);
        }
        if (!recording || current == null) return;
        if (handler != null) handler.removeCallbacks(commitTextRunnable);
        commitPendingText();
        Models.Step s = new Models.Step();
        s.type = Models.GLOBAL;
        s.action = action;
        long now = SystemClock.elapsedRealtime();
        s.delay = Math.max(0, now - recordAnchor);
        recordAnchor = now;
        addStep(s);
    }

    public void insertWait(long ms) {
        if (!recording || current == null) {
            log("Pause nur während der Aufnahme");
            return;
        }
        if (handler != null) handler.removeCallbacks(commitTextRunnable);
        commitPendingText();
        Models.Step s = new Models.Step();
        s.type = Models.WAIT;
        s.delay = Math.max(0, ms);
        addStep(s);
    }

    public void playActive(Models.PlayOpts opts) {
        if (recording) stopRecord();
        String id = activeId;
        if ((id == null || store.byId(id) == null) && store != null && !store.all().isEmpty()) {
            id = store.all().get(0).id;
        }
        play(id, opts);
    }

    public void play(String id, Models.PlayOpts opts) {
        if (store == null) return;
        if (recording) stopRecord();
        Models.Macro m = store.byId(id);
        if (m == null || m.steps.isEmpty()) {
            log("Nichts zum Abspielen");
            notifyListeners();
            return;
        }
        activeId = m.id;
        prefs().edit().putString("active", activeId).apply();
        if (opts != null) {
            playOpts = opts;
            saveOpts(opts);
        } else {
            playOpts = loadOpts();
        }
        playGen++;
        int gen = playGen;
        playing = true;
        playingMacro = m;
        stepIndex = 0;
        runIndex = 0;
        detachShield();
        log("Start · " + m.name);
        updatePanel();
        notifyListeners();
        runStep(gen);
    }

    public void stopPlay() {
        if (!playing && playGen == 0) return;
        playGen++;
        boolean was = playing;
        playing = false;
        playingMacro = null;
        if (was) log("Stopp");
        updatePanel();
        notifyListeners();
    }

    private void runStep(int gen) {
        if (gen != playGen || !playing) return;
        Models.Macro m = playingMacro;
        if (m == null) {
            finishPlay(gen);
            return;
        }
        if (stepIndex >= m.steps.size()) {
            runIndex++;
            int limit = playOpts.infinite ? Integer.MAX_VALUE : Math.max(1, playOpts.repeats);
            if (runIndex >= limit) {
                finishPlay(gen);
                return;
            }
            long a = Math.min(playOpts.gapMin, playOpts.gapMax);
            long b = Math.max(playOpts.gapMin, playOpts.gapMax);
            long gap = a >= b ? a : a + (long) (rnd.nextDouble() * (b - a));
            log("Durchlauf " + runIndex + " · Pause " + gap + " ms");
            notifyListeners();
            handler.postDelayed(() -> {
                if (gen != playGen) return;
                stepIndex = 0;
                runStep(gen);
            }, gap);
            return;
        }
        Models.Step step = m.steps.get(stepIndex);
        long wait = scaledDelay(step);
        handler.postDelayed(() -> {
            if (gen != playGen) return;
            execute(step, gen, () -> {
                if (gen != playGen) return;
                stepIndex++;
                runStep(gen);
            });
        }, wait);
    }

    private void finishPlay(int gen) {
        if (gen != playGen) return;
        playing = false;
        playingMacro = null;
        log("Fertig");
        updatePanel();
        notifyListeners();
    }

    private long scaledDelay(Models.Step step) {
        if (step == null) return 0;
        if (Models.WAIT.equals(step.type) && !playOpts.diceTiming) {
            float sp = playOpts.speed < 1f ? 1f : playOpts.speed;
            return Math.max(0, (long) (step.delay / sp));
        }
        long base;
        if (playOpts.diceTiming) {
            long a = Math.min(playOpts.diceMin, playOpts.diceMax);
            long b = Math.max(playOpts.diceMin, playOpts.diceMax);
            base = a >= b ? a : a + (long) (rnd.nextDouble() * (b - a));
        } else {
            float sp = playOpts.speed < 1f ? 1f : playOpts.speed;
            base = (long) (step.delay / sp);
            if (playOpts.jitterPct > 0) {
                double swing = playOpts.jitterPct / 100.0;
                double f = 1.0 + (rnd.nextDouble() * 2.0 - 1.0) * swing;
                if (f < 0) f = 0;
                base = (long) (base * f);
            }
        }
        return Math.max(0, base);
    }

    private void execute(Models.Step step, int gen, Runnable done) {
        if (gen != playGen) return;
        try {
            if (Models.GESTURE.equals(step.type)) {
                GestureDescription g = buildGesture(step, true);
                if (g == null) {
                    done.run();
                    return;
                }
                boolean ok = dispatchGesture(g, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        handler.post(done);
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        handler.post(done);
                    }
                }, handler);
                if (!ok) {
                    log("Geste abgelehnt");
                    done.run();
                }
                return;
            }
            if (Models.TEXT.equals(step.type)) {
                tryText(step, gen, 0, done);
                return;
            }
            if (Models.GLOBAL.equals(step.type)) {
                int code = globalCode(step.action);
                if (code != 0) performGlobalAction(code);
                handler.postDelayed(done, 90);
                return;
            }
            if (Models.OPEN.equals(step.type)) {
                openPkg(step.pkg);
                handler.postDelayed(done, 350);
                return;
            }
        } catch (Exception e) {
            log("Fehler · " + e.getClass().getSimpleName());
        }
        done.run();
    }

    private void tryText(Models.Step step, int gen, int attempt, Runnable done) {
        if (gen != playGen) return;
        if (injectText(step) || attempt >= 8) {
            if (attempt >= 8) log("Textfeld nicht gefunden");
            handler.postDelayed(done, 40);
            return;
        }
        handler.postDelayed(() -> tryText(step, gen, attempt + 1, done), 140);
    }

    private boolean injectText(Models.Step step) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo target = null;
        AccessibilityNodeInfo focus = null;
        try {
            focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null && focus.isEditable() && !focus.isPassword()) target = focus;
            if (target == null && step.viewId != null && !step.viewId.isEmpty()) {
                List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(step.viewId);
                if (list != null) {
                    for (AccessibilityNodeInfo n : list) {
                        if (n != null && n.isEditable() && !n.isPassword()) {
                            target = n;
                            break;
                        }
                    }
                }
            }
            if (target == null) target = findEditableNear(root, step.x, step.y, 0);
            if (target == null) return false;
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    step.text == null ? "" : step.text);
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } finally {
            root.recycle();
        }
    }

    private AccessibilityNodeInfo findEditableNear(AccessibilityNodeInfo node, float x, float y, int depth) {
        if (node == null || depth > 18) return null;
        if (node.isEditable() && !node.isPassword()) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.contains((int) x, (int) y) || distToRect(r, x, y) < 96f) return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo hit = findEditableNear(c, x, y, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private static float distToRect(Rect r, float x, float y) {
        float cx = Math.max(r.left, Math.min(x, r.right));
        float cy = Math.max(r.top, Math.min(y, r.bottom));
        float dx = x - cx;
        float dy = y - cy;
        return (float) Math.hypot(dx, dy);
    }

    private void openPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                log("App nicht startbar");
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            log("Appwechsel fehlgeschlagen");
        }
    }

    private void onTextChanged(AccessibilityEvent event) {
        if (echoGuard || SystemClock.elapsedRealtime() < ignoreUntil) return;
        AccessibilityNodeInfo src = event.getSource();
        try {
            if (src != null && src.isPassword()) {
                if (!passwordNoted) {
                    passwordNoted = true;
                    log("Passwortfeld übersprungen");
                }
                return;
            }
            CharSequence cs = null;
            if (event.getText() != null && !event.getText().isEmpty()) cs = event.getText().get(0);
            if ((cs == null || cs.length() == 0) && src != null) cs = src.getText();
            pendingText = cs == null ? "" : cs.toString();
            pendingDirty = true;
            if (src != null) {
                pendingViewId = src.getViewIdResourceName();
                Rect r = new Rect();
                src.getBoundsInScreen(r);
                pendingX = r.centerX();
                pendingY = r.centerY();
                if (src.isEditable()) enterTypingMode();
            }
        } finally {
            if (src != null) src.recycle();
        }
        if (handler != null) {
            handler.removeCallbacks(commitTextRunnable);
            handler.postDelayed(commitTextRunnable, 450);
        }
    }

    private void commitPendingText() {
        if (!pendingDirty || current == null) return;
        pendingDirty = false;
        Models.Step s = new Models.Step();
        s.type = Models.TEXT;
        s.text = pendingText == null ? "" : pendingText;
        s.viewId = pendingViewId;
        s.x = pendingX;
        s.y = pendingY;
        long now = SystemClock.elapsedRealtime();
        s.delay = Math.max(0, now - recordAnchor);
        recordAnchor = now;
        addStep(s);
    }

    private void recordClickFallback(AccessibilityEvent event, boolean longClick) {
        AccessibilityNodeInfo src = event.getSource();
        if (src == null) return;
        try {
            if (src.isEditable()) return;
            Rect r = new Rect();
            src.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) return;
            Models.Step s = new Models.Step();
            s.type = Models.GESTURE;
            long now = SystemClock.elapsedRealtime();
            s.delay = Math.max(0, now - recordAnchor);
            recordAnchor = now;
            Models.Stroke st = new Models.Stroke();
            st.start = 0;
            st.dur = longClick ? 520 : 45;
            st.pts.add((float) r.centerX());
            st.pts.add((float) r.centerY());
            s.strokes = new ArrayList<>();
            s.strokes.add(st);
            addStep(s);
            leaveTypingMode();
        } finally {
            src.recycle();
        }
    }

    private void enterTypingMode() {
        if (typingMode) return;
        typingMode = true;
        detachShield();
        log("Schreiben");
        updatePanel();
    }

    private void leaveTypingMode() {
        if (!typingMode) return;
        typingMode = false;
        if (recording && !gestureOpen && !playing) attachShield();
    }

    private void notePackage(AccessibilityEvent event) {
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        if (ignorePkg(pkg)) return;
        if (!pkgInit) {
            lastPkg = pkg;
            pkgInit = true;
            return;
        }
        if (pkg.equals(lastPkg)) return;
        lastPkg = pkg;
        if (!recording || playing || current == null || echoGuard) return;
        Models.Step s = new Models.Step();
        s.type = Models.OPEN;
        s.pkg = pkg;
        long now = SystemClock.elapsedRealtime();
        s.delay = Math.max(0, now - recordAnchor);
        recordAnchor = now;
        addStep(s);
    }

    private boolean ignorePkg(String pkg) {
        if (pkg == null) return true;
        if (pkg.equals(getPackageName())) return true;
        if (pkg.equals("com.android.systemui") || pkg.equals("android")) return true;
        String l = pkg.toLowerCase(Locale.US);
        return l.contains("inputmethod") || l.contains("keyboard") || l.contains(".ime")
                || l.contains("honeyboard") || l.contains("swiftkey") || l.contains("gboard");
    }

    private boolean fromSelf(AccessibilityEvent event) {
        CharSequence p = event.getPackageName();
        return p != null && getPackageName().contentEquals(p);
    }

    private void onShieldTouch(MotionEvent ev) {
        if (!recording || playing || typingMode) return;
        int action = ev.getActionMasked();
        long now = SystemClock.elapsedRealtime();
        if (!gestureOpen && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)) {
            gestureOpen = true;
            gestureDownAt = now;
            pointers.clear();
        }
        if (!gestureOpen) return;
        int count = ev.getPointerCount();
        for (int i = 0; i < count; i++) {
            addSample(ev.getPointerId(i), now - gestureDownAt, rawX(ev, i), rawY(ev, i));
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            long delay = Math.max(0, gestureDownAt - recordAnchor);
            gestureOpen = false;
            commitGesture(delay);
        }
    }

    private void addSample(int id, long t, float x, float y) {
        ArrayList<float[]> list = pointers.get(id);
        if (list == null) {
            list = new ArrayList<>();
            pointers.put(id, list);
        }
        if (!list.isEmpty()) {
            float[] prev = list.get(list.size() - 1);
            float dx = x - prev[1];
            float dy = y - prev[2];
            if (t - (long) prev[0] < 14 && (dx * dx + dy * dy) < 25f) return;
            if (list.size() >= 90) return;
        }
        list.add(new float[]{t, x, y});
    }

    private void commitGesture(long delay) {
        if (current == null) {
            pointers.clear();
            return;
        }
        Models.Step step = new Models.Step();
        step.type = Models.GESTURE;
        step.delay = delay;
        step.strokes = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < pointers.size(); i++) {
            ArrayList<float[]> samples = pointers.valueAt(i);
            if (samples == null || samples.isEmpty()) continue;
            float[] first = samples.get(0);
            float[] last = samples.get(samples.size() - 1);
            Models.Stroke st = new Models.Stroke();
            st.start = (long) first[0];
            st.dur = Math.max(30, (long) last[0] - (long) first[0]);
            if (samples.size() == 1) st.dur = Math.max(40, st.dur);
            for (float[] s : samples) {
                st.pts.add(s[1]);
                st.pts.add(s[2]);
            }
            total = Math.max(total, st.start + st.dur);
            step.strokes.add(st);
        }
        pointers.clear();
        if (step.strokes.isEmpty()) return;
        addStep(step);
        replayEcho(step, total);
    }

    private void replayEcho(Models.Step step, long total) {
        echoGuard = true;
        detachShield();
        GestureDescription g;
        try {
            g = buildGesture(step, false);
        } catch (Exception e) {
            g = null;
        }
        if (g == null) {
            finishEcho();
            return;
        }
        boolean ok;
        try {
            ok = dispatchGesture(g, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    handler.post(() -> finishEcho());
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    handler.post(() -> finishEcho());
                }
            }, handler);
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            log("Echo nicht möglich");
            finishEcho();
            return;
        }
        long guard = Math.min(8000, Math.max(500, total + 600));
        handler.postDelayed(() -> {
            if (echoGuard) finishEcho();
        }, guard);
    }

    private void finishEcho() {
        if (!echoGuard) return;
        echoGuard = false;
        ignoreUntil = SystemClock.elapsedRealtime() + 280;
        recordAnchor = SystemClock.elapsedRealtime();
        if (recording && !typingMode && !playing && !gestureOpen) attachShield();
    }

    private GestureDescription buildGesture(Models.Step step, boolean applyOpts) {
        if (step.strokes == null || step.strokes.isEmpty()) return null;
        int[] scr = screenSize();
        float jx = 0f;
        float jy = 0f;
        float speed = 1f;
        if (applyOpts && playOpts != null) {
            speed = playOpts.speed < 1f ? 1f : playOpts.speed;
            if (playOpts.jitterPx > 0) {
                int p = playOpts.jitterPx;
                jx = rnd.nextInt(p * 2 + 1) - p;
                jy = rnd.nextInt(p * 2 + 1) - p;
            }
        }
        GestureDescription.Builder b = new GestureDescription.Builder();
        int added = 0;
        for (Models.Stroke st : step.strokes) {
            if (st.pts.size() < 2) continue;
            float x0 = clamp(st.pts.get(0) + jx, 1, scr[0] - 2);
            float y0 = clamp(st.pts.get(1) + jy, 1, scr[1] - 2);
            Path path = new Path();
            path.moveTo(x0, y0);
            float lx = x0;
            float ly = y0;
            boolean moved = false;
            int pairs = st.pts.size() / 2;
            int stride = Math.max(1, pairs / 48);
            for (int p = 1; p < pairs; p += stride) {
                int i = p * 2;
                if (i + 1 >= st.pts.size()) break;
                float x = clamp(st.pts.get(i) + jx, 1, scr[0] - 2);
                float y = clamp(st.pts.get(i + 1) + jy, 1, scr[1] - 2);
                if (Math.hypot(x - lx, y - ly) < 2f) continue;
                path.lineTo(x, y);
                lx = x;
                ly = y;
                moved = true;
            }
            int last = (pairs - 1) * 2;
            if (last >= 2 && last + 1 < st.pts.size()) {
                float x = clamp(st.pts.get(last) + jx, 1, scr[0] - 2);
                float y = clamp(st.pts.get(last + 1) + jy, 1, scr[1] - 2);
                if (Math.hypot(x - lx, y - ly) >= 2f) {
                    path.lineTo(x, y);
                    moved = true;
                }
            }
            long dur = (long) (Math.max(1, st.dur) / speed);
            long min = moved ? 45 : 30;
            if (dur < min) dur = min;
            if (!moved && st.dur >= 320) dur = Math.max(dur, (long) (st.dur / speed));
            if (dur > 59000) dur = 59000;
            long start = (long) (Math.max(0, st.start) / speed);
            b.addStroke(new GestureDescription.StrokeDescription(path, start, dur));
            added++;
            if (added >= 10) break;
        }
        if (added == 0) return null;
        return b.build();
    }

    private void addStep(Models.Step s) {
        if (current == null || s == null) return;
        if (current.steps.size() >= MAX_STEPS) {
            log("Grenze erreicht");
            stopRecord();
            return;
        }
        current.steps.add(s);
        store.upsert(current);
        log(current.steps.size() + " · " + s.summary());
        updatePanel();
        notifyListeners();
    }

    private void attachShield() {
        if (!recording || playing || typingMode) return;
        ensureWindows();
        if (!shieldAdded) {
            try {
                wm.addView(shield, shieldLp);
                shieldAdded = true;
            } catch (Exception e) {
                log("Overlay blockiert");
                shieldAdded = false;
                return;
            }
        }
        bringPanelToFront();
    }

    private void detachShield() {
        if (!shieldAdded || shield == null || wm == null) return;
        try {
            wm.removeView(shield);
        } catch (Exception ignored) {
        }
        shieldAdded = false;
    }

    private void showPanel() {
        ensureWindows();
        if (panelAdded) {
            updatePanel();
            return;
        }
        panel = buildPanel();
        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.x = dp(12);
        panelLp.y = dp(96);
        try {
            wm.addView(panel, panelLp);
            panelAdded = true;
            updatePanel();
        } catch (Exception e) {
            log("Leiste nicht sichtbar");
        }
    }

    private void removePanel() {
        if (!panelAdded || panel == null || wm == null) return;
        try {
            wm.removeView(panel);
        } catch (Exception ignored) {
        }
        panelAdded = false;
    }

    private void bringPanelToFront() {
        if (!panelAdded || panel == null) return;
        try {
            wm.removeView(panel);
            wm.addView(panel, panelLp);
        } catch (Exception ignored) {
        }
    }

    private View buildPanel() {
        LinearLayout box = new LinearLayout(ui());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0121A16);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x553DBE8C);
        box.setBackground(bg);

        TextView handle = label("ziehen");
        handle.setPadding(dp(8), dp(4), dp(8), dp(6));
        handle.setOnTouchListener((v, e) -> dragPanel(e));
        box.addView(handle);

        panelCount = label("0");
        panelCount.setGravity(Gravity.CENTER);
        box.addView(panelCount);

        bRec = pill("REC", 0xFF3DBE8C);
        bRec.setOnClickListener(v -> {
            if (recording) stopRecord();
            else startRecord();
        });
        box.addView(bRec);

        bPlay = pill("PLAY", 0xFF2A3A32);
        bPlay.setOnClickListener(v -> {
            if (playing) stopPlay();
            else playActive(loadOpts());
        });
        box.addView(bPlay);

        LinearLayout row1 = row();
        Button undo = mini("Weg");
        undo.setOnClickListener(v -> undo());
        Button wait = mini("+1s");
        wait.setOnClickListener(v -> insertWait(1000));
        row1.addView(undo);
        row1.addView(wait);
        box.addView(row1);

        LinearLayout row2 = row();
        Button back = mini("Zurück");
        back.setOnClickListener(v -> pressGlobal("back"));
        Button home = mini("Home");
        home.setOnClickListener(v -> pressGlobal("home"));
        row2.addView(back);
        row2.addView(home);
        box.addView(row2);
        return box;
    }

    private boolean dragPanel(MotionEvent e) {
        if (panelLp == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                panel.setTag(RTag.of(e.getRawX(), e.getRawY(), panelLp.x, panelLp.y));
                return true;
            case MotionEvent.ACTION_MOVE:
                Object tag = panel.getTag();
                if (!(tag instanceof RTag)) return true;
                RTag t = (RTag) tag;
                panelLp.x = Math.max(0, t.sx + Math.round(e.getRawX() - t.x));
                panelLp.y = Math.max(0, t.sy + Math.round(e.getRawY() - t.y));
                try {
                    wm.updateViewLayout(panel, panelLp);
                } catch (Exception ignored) {
                }
                return true;
            default:
                return true;
        }
    }

    private static final class RTag {
        final float x;
        final float y;
        final int sx;
        final int sy;

        RTag(float x, float y, int sx, int sy) {
            this.x = x;
            this.y = y;
            this.sx = sx;
            this.sy = sy;
        }

        static RTag of(float x, float y, int sx, int sy) {
            return new RTag(x, y, sx, sy);
        }
    }

    private void updatePanel() {
        if (bRec == null) return;
        bRec.setText(recording ? "STOP" : "REC");
        bRec.setBackground(rounded(recording ? 0xFFE25B4A : 0xFF3DBE8C));
        bPlay.setText(playing ? "HALT" : "PLAY");
        bPlay.setTextColor(playing ? 0xFF07110C : 0xFFF4FFF8);
        bPlay.setBackground(rounded(playing ? 0xFFE2B15A : 0xFF24362C));
        if (panel != null) panel.setKeepScreenOn(playing || recording);
        if (panelCount != null) {
            int n = current != null && (recording || playing) ? current.steps.size() : stepCount();
            String mode = playing ? "läuft" : (recording ? (typingMode ? "Text" : "REC") : "bereit");
            panelCount.setText(mode + " · " + n);
        }
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(ui());
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private Button pill(String text, int color) {
        Button b = new Button(ui());
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(0xFF07110C);
        b.setTextSize(13);
        b.setMinHeight(dp(42));
        b.setMinimumHeight(dp(42));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(132), dp(42));
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(rounded(color));
        return b;
    }

    private Button mini(String text) {
        Button b = new Button(ui());
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(0xFFE8F2EC);
        b.setTextSize(11);
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(36));
        lp.topMargin = dp(6);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(rounded(0xFF24362C));
        return b;
    }

    private TextView label(String text) {
        TextView t = new TextView(ui());
        t.setText(text);
        t.setTextColor(0xFF9BB0A4);
        t.setTextSize(11);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(12));
        return d;
    }

    private void ensureWindows() {
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (shield != null) return;
        shield = new View(this) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                onShieldTouch(event);
                return true;
            }
        };
        shield.setClickable(true);
        shield.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        shieldLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        shieldLp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            shieldLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    private int[] screenSize() {
        ensureWindows();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            return new int[]{Math.max(2, b.width()), Math.max(2, b.height())};
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return new int[]{Math.max(2, dm.widthPixels), Math.max(2, dm.heightPixels)};
    }

    private static float rawX(MotionEvent ev, int index) {
        return ev.getRawX() - ev.getX() + ev.getX(index);
    }

    private static float rawY(MotionEvent ev, int index) {
        return ev.getRawY() - ev.getY() + ev.getY(index);
    }

    private static float clamp(float v, float min, float max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, v));
    }

    private static int globalCode(String action) {
        if ("back".equals(action)) return GLOBAL_ACTION_BACK;
        if ("home".equals(action)) return GLOBAL_ACTION_HOME;
        if ("recents".equals(action)) return GLOBAL_ACTION_RECENTS;
        if ("notifications".equals(action)) return GLOBAL_ACTION_NOTIFICATIONS;
        return 0;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void log(String line) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(new Date());
        logs.addLast(stamp + "  " + line);
        while (logs.size() > 40) logs.removeFirst();
    }

    private void notifyListeners() {
        if (handler == null) return;
        handler.post(() -> {
            ArrayList<Listener> copy = new ArrayList<>(listeners);
            for (Listener l : copy) {
                try {
                    l.onChanged();
                } catch (Exception ignored) {
                }
            }
        });
    }
}
