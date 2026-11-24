package de.mikromakro.replay;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements MakroService.Listener {
    private TextView statusView;
    private TextView recordView;
    private TextView logView;
    private TextView speedView;
    private LinearLayout macroList;
    private EditText repeatsField;
    private EditText gapMinField;
    private EditText gapMaxField;
    private EditText jitterPctField;
    private EditText jitterPxField;
    private EditText diceMinField;
    private EditText diceMaxField;
    private CheckBox infiniteBox;
    private CheckBox diceBox;
    private SeekBar speedBar;
    private boolean silent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        bindSaved();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MakroService s = MakroService.get();
        if (s != null) s.addListener(this);
        refresh();
    }

    @Override
    protected void onPause() {
        persistOpts();
        MakroService s = MakroService.get();
        if (s != null) s.removeListener(this);
        super.onPause();
    }

    @Override
    public void onChanged() {
        if (isFinishing()) return;
        refresh();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF0E1412);

        LinearLayout root = col();
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        scroll.addView(root);

        TextView kicker = text("AUF DEM GERÄT", 11, 0xFF3DBE8C);
        kicker.setLetterSpacing(0.14f);
        root.addView(kicker);

        TextView title = text("MikroMakro", 32, 0xFFF4FFF8);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(4), 0, 0);
        root.addView(title);

        TextView sub = text(
                "Nimmt Tippen, Wischen und Schreiben pixelgenau auf und spielt genau diesen Ablauf wieder ab. Beliebig oft, mit festen oder zufälligen Pausen. Nichts verlässt das Handy.",
                15, 0xFFB7C9BE);
        sub.setPadding(0, dp(8), 0, dp(16));
        root.addView(sub);

        root.addView(card(serviceCard()));
        root.addView(gap());
        root.addView(card(recordCard()));
        root.addView(gap());
        root.addView(card(playCard()));
        root.addView(gap());
        root.addView(section("Gespeicherte Abläufe"));
        macroList = col();
        root.addView(macroList);
        root.addView(gap());
        root.addView(card(helpCard()));
        root.addView(gap());
        root.addView(section("Protokoll"));
        logView = text("—", 12, 0xFF8FA497);
        logView.setTypeface(Typeface.MONOSPACE);
        root.addView(logView);
        return scroll;
    }

    private View serviceCard() {
        LinearLayout box = col();
        statusView = text("Bedienungshilfe wird geprüft", 16, 0xFFF4FFF8);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(statusView);
        box.addView(gapSmall());
        box.addView(body("Ohne diese Freigabe kann Android keine Taps in anderen Apps abspielen. Die App bleibt sichtbar, ohne Netz, ohne Konto."));
        box.addView(gapSmall());
        Button open = button("Bedienungshilfe öffnen", true);
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        box.addView(open);
        Button battery = button("Akku-Optimierung aus", false);
        battery.setOnClickListener(v -> askBattery());
        box.addView(battery);
        return box;
    }

    private View recordCard() {
        LinearLayout box = col();
        box.addView(section("Aufnahme"));
        recordView = text("Noch kein Ablauf", 14, 0xFFD5E6DB);
        box.addView(recordView);
        box.addView(gapSmall());
        Button rec = button("Aufnahme starten", true);
        rec.setOnClickListener(v -> {
            MakroService s = MakroService.get();
            if (s == null) {
                toastNeed();
                return;
            }
            if (s.isRecording()) s.stopRecord();
            else {
                s.startRecord();
                moveTaskToBack(true);
            }
        });
        box.addView(rec);
        Button stop = button("Aufnahme stoppen", false);
        stop.setOnClickListener(v -> {
            MakroService s = MakroService.get();
            if (s != null) s.stopRecord();
        });
        box.addView(stop);
        return box;
    }

    private View playCard() {
        LinearLayout box = col();
        box.addView(section("Wiedergabe"));
        box.addView(body("Tempo verkürzt nur die Pausen. Tipps bleiben lang genug, damit das System sie erkennt."));

        box.addView(label("Wiederholungen"));
        repeatsField = field("1");
        box.addView(repeatsField);
        infiniteBox = check("Unendlich, bis du HALT tippst");
        box.addView(infiniteBox);

        box.addView(label("Pause zwischen Durchläufen, von / bis (ms)"));
        LinearLayout gaps = row();
        gapMinField = field("800");
        gapMaxField = field("800");
        gaps.addView(gapMinField, weight());
        gaps.addView(spacer());
        gaps.addView(gapMaxField, weight());
        box.addView(gaps);
        box.addView(hint("Gleicher Wert = feste Pause. Unterschied = Zufall dazwischen."));

        box.addView(label("Tempo"));
        speedView = text("1×", 14, 0xFF3DBE8C);
        box.addView(speedView);
        speedBar = new SeekBar(this);
        speedBar.setMax(20);
        speedBar.setProgress(1);
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = Math.max(1, progress);
                speedView.setText(v >= 20 ? "20×  maximal" : v + "×");
                if (fromUser) persistOpts();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        box.addView(speedBar);

        LinearLayout presets = row();
        presets.addView(preset("Original", () -> applyPreset(1, 800, 800, 0, 0, false)), weight());
        presets.addView(spacer());
        presets.addView(preset("Schnell", () -> applyPreset(6, 200, 200, 0, 0, false)), weight());
        presets.addView(spacer());
        presets.addView(preset("Turbo", () -> applyPreset(20, 0, 0, 0, 0, false)), weight());
        box.addView(presets);
        box.addView(gapSmall());
        Button zufall = button("Zufallspausen", false);
        zufall.setOnClickListener(v -> applyPreset(1, 1500, 8000, 45, 6, true));
        box.addView(zufall);

        box.addView(label("Zufall auf den aufgenommenen Pausen (%)"));
        jitterPctField = field("0");
        box.addView(jitterPctField);
        box.addView(label("Positions-Jitter (px, 0 = exakt)"));
        jitterPxField = field("0");
        box.addView(jitterPxField);

        diceBox = check("Pausen pro Schritt neu würfeln");
        box.addView(diceBox);
        LinearLayout dice = row();
        diceMinField = field("40");
        diceMaxField = field("900");
        dice.addView(diceMinField, weight());
        dice.addView(spacer());
        dice.addView(diceMaxField, weight());
        box.addView(dice);
        box.addView(hint("Ersetzt die Originalpausen. Der Ablauf selbst bleibt in derselben Reihenfolge."));

        Button play = button("Ausgewählten Ablauf abspielen", true);
        play.setOnClickListener(v -> {
            MakroService s = MakroService.get();
            if (s == null) {
                toastNeed();
                return;
            }
            Models.PlayOpts opts = collectOpts();
            s.saveOpts(opts);
            s.playActive(opts);
            moveTaskToBack(true);
        });
        box.addView(play);
        Button halt = button("Wiedergabe stoppen", false);
        halt.setOnClickListener(v -> {
            MakroService s = MakroService.get();
            if (s != null) s.stopPlay();
        });
        box.addView(halt);
        watchAll();
        return box;
    }

    private View helpCard() {
        LinearLayout box = col();
        box.addView(section("So läuft es auf dem M30"));
        box.addView(body(
                "1. Bedienungshilfe aktivieren und MikroMakro einschalten.\n"
                        + "2. Falls der Schalter fehlt oder grau ist: Einstellungen → Apps → MikroMakro → Menü → Eingeschränkte Einstellungen zulassen. Danach zurück zur Bedienungshilfe.\n"
                        + "3. Akku-Optimierung für die App ausstellen. Auf Doogee zusätzlich Autostart erlauben, falls das Menü das verlangt.\n"
                        + "4. Ziel-App öffnen. Auf der schwebenden Leiste REC tippen. Jeder Fingerablauf wird aufgenommen und sofort an die App darunter weitergegeben.\n"
                        + "5. Schreiben erkannt die App automatisch und blendet die Fangfläche aus. Text landet als ganzer Eintrag im Feld, Passwörter werden nicht gespeichert.\n"
                        + "6. Zurück, Home, +1s und Weg sitzen auf der Leiste. STOP speichert den Ablauf.\n"
                        + "7. PLAY spielt ihn ab. HALT bricht sofort ab. Wiederholungen und Zufall stellst du hier ein, bevor du startest."));
        return box;
    }

    private void bindSaved() {
        MakroService s = MakroService.get();
        Models.PlayOpts o = s != null ? s.loadOpts() : Models.PlayOpts.fromJson(
                getSharedPreferences("opts", MODE_PRIVATE).getString("json", null));
        silent = true;
        repeatsField.setText(String.valueOf(o.repeats));
        infiniteBox.setChecked(o.infinite);
        gapMinField.setText(String.valueOf(o.gapMin));
        gapMaxField.setText(String.valueOf(o.gapMax));
        jitterPctField.setText(String.valueOf(o.jitterPct));
        jitterPxField.setText(String.valueOf(o.jitterPx));
        diceBox.setChecked(o.diceTiming);
        diceMinField.setText(String.valueOf(o.diceMin));
        diceMaxField.setText(String.valueOf(o.diceMax));
        speedBar.setProgress(Math.max(1, Math.round(o.speed)));
        speedView.setText(Math.round(o.speed) >= 20 ? "20×  maximal" : Math.max(1, Math.round(o.speed)) + "×");
        silent = false;
    }

    private void applyPreset(int speed, long gapMin, long gapMax, int jitterPct, int jitterPx, boolean dice) {
        silent = true;
        speedBar.setProgress(speed);
        speedView.setText(speed >= 20 ? "20×  maximal" : speed + "×");
        gapMinField.setText(String.valueOf(gapMin));
        gapMaxField.setText(String.valueOf(gapMax));
        jitterPctField.setText(String.valueOf(jitterPct));
        jitterPxField.setText(String.valueOf(jitterPx));
        diceBox.setChecked(dice);
        if (dice) {
            diceMinField.setText("30");
            diceMaxField.setText("1400");
        }
        silent = false;
        persistOpts();
    }

    private void watchAll() {
        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { persistOpts(); }
        };
        for (EditText e : new EditText[]{repeatsField, gapMinField, gapMaxField, jitterPctField, jitterPxField, diceMinField, diceMaxField}) {
            e.addTextChangedListener(w);
        }
        infiniteBox.setOnCheckedChangeListener((b, c) -> persistOpts());
        diceBox.setOnCheckedChangeListener((b, c) -> persistOpts());
    }

    private void persistOpts() {
        if (silent || repeatsField == null) return;
        Models.PlayOpts o = collectOpts();
        getSharedPreferences("opts", MODE_PRIVATE).edit().putString("json", json(o)).apply();
        MakroService s = MakroService.get();
        if (s != null) s.saveOpts(o);
    }

    private String json(Models.PlayOpts o) {
        try {
            return o.toJson().toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private Models.PlayOpts collectOpts() {
        Models.PlayOpts o = new Models.PlayOpts();
        o.repeats = clampInt(repeatsField, 1, 9999, 1);
        o.infinite = infiniteBox.isChecked();
        o.gapMin = clampLong(gapMinField, 0, 3_600_000, 0);
        o.gapMax = clampLong(gapMaxField, 0, 3_600_000, o.gapMin);
        o.speed = Math.max(1, speedBar.getProgress());
        o.jitterPct = clampInt(jitterPctField, 0, 100, 0);
        o.jitterPx = clampInt(jitterPxField, 0, 80, 0);
        o.diceTiming = diceBox.isChecked();
        o.diceMin = clampLong(diceMinField, 0, 60_000, 40);
        o.diceMax = clampLong(diceMaxField, 0, 60_000, 900);
        return o;
    }

    private void refresh() {
        MakroService s = MakroService.get();
        boolean enabled = serviceEnabled();
        boolean live = s != null;
        if (!enabled) {
            statusView.setText("Bedienungshilfe ist aus");
        } else if (!live) {
            statusView.setText("Dienst startet … einmal aus- und einschalten");
        } else if (s.isPlaying()) {
            statusView.setText("Wiedergabe läuft");
        } else if (s.isRecording()) {
            statusView.setText("Aufnahme läuft");
        } else {
            statusView.setText("Bereit");
        }
        if (s != null && s.isRecording()) {
            recordView.setText("Aufnahme · " + s.stepCount() + " Schritte");
        } else if (s != null && s.isPlaying()) {
            recordView.setText("Spielt den markierten Ablauf");
        } else {
            recordView.setText(s == null ? "Zuerst die Bedienungshilfe einschalten." : "Bereit für die nächste Aufnahme.");
        }
        if (s != null) {
            List<String> lines = s.logLines();
            if (lines.isEmpty()) logView.setText("—");
            else {
                StringBuilder b = new StringBuilder();
                int start = Math.max(0, lines.size() - 8);
                for (int i = start; i < lines.size(); i++) {
                    if (b.length() > 0) b.append('\n');
                    b.append(lines.get(i));
                }
                logView.setText(b.toString());
            }
        }
        renderMacros(s);
    }

    private void renderMacros(MakroService s) {
        macroList.removeAllViews();
        ArrayList<Models.Macro> all = s == null || s.store() == null ? new ArrayList<>() : s.store().all();
        if (all.isEmpty()) {
            macroList.addView(body("Noch nichts aufgenommen. Nach dem Stopp erscheint der Ablauf hier."));
            return;
        }
        String active = s.getActiveId();
        for (Models.Macro m : all) {
            boolean on = m.id.equals(active);
            LinearLayout card = card(macroRow(m, on, s));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            card.setLayoutParams(lp);
            macroList.addView(card);
        }
    }

    private View macroRow(Models.Macro m, boolean on, MakroService s) {
        LinearLayout box = col();
        TextView name = text((on ? "●  " : "") + m.name, 16, on ? 0xFF3DBE8C : 0xFFF4FFF8);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(name);
        int taps = 0;
        int texts = 0;
        for (Models.Step step : m.steps) {
            if (Models.GESTURE.equals(step.type)) taps++;
            if (Models.TEXT.equals(step.type)) texts++;
        }
        box.addView(hint(m.steps.size() + " Schritte · " + taps + " Gesten · " + texts + " Texte"));
        LinearLayout row = row();
        Button use = mini(on ? "Aktiv" : "Wählen");
        use.setOnClickListener(v -> s.setActiveId(m.id));
        Button play = mini("Play");
        play.setOnClickListener(v -> {
            s.setActiveId(m.id);
            s.saveOpts(collectOpts());
            s.play(m.id, collectOpts());
            moveTaskToBack(true);
        });
        Button ren = mini("Name");
        ren.setOnClickListener(v -> rename(m, s));
        Button del = mini("Weg");
        del.setOnClickListener(v -> confirmDelete(m, s));
        row.addView(use);
        row.addView(play);
        row.addView(ren);
        row.addView(del);
        box.addView(row);
        return box;
    }

    private void rename(Models.Macro m, MakroService s) {
        EditText input = new EditText(this);
        input.setText(m.name);
        input.setTextColor(0xFFF4FFF8);
        input.setSingleLine(true);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("Name")
                .setView(padded(input))
                .setPositiveButton("Sichern", (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (n.isEmpty()) return;
                    m.name = n;
                    s.store().upsert(m);
                    refresh();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void confirmDelete(Models.Macro m, MakroService s) {
        new AlertDialog.Builder(this)
                .setTitle("Ablauf löschen?")
                .setMessage(m.name)
                .setPositiveButton("Löschen", (d, w) -> {
                    s.store().delete(m.id);
                    if (m.id.equals(s.getActiveId())) s.setActiveId(null);
                    refresh();
                })
                .setNegativeButton("Behalten", null)
                .show();
    }

    private View padded(View child) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(18), dp(12), dp(18), dp(4));
        box.addView(child, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void askBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            statusView.setText("Akku-Optimierung ist schon aus");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void toastNeed() {
        statusView.setText("Erst MikroMakro in der Bedienungshilfe einschalten");
    }

    private boolean serviceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String flat = new ComponentName(this, MakroService.class).flattenToString();
        for (String part : enabled.split(":")) {
            if (part.equalsIgnoreCase(flat)) return true;
        }
        return enabled.contains(getPackageName() + "/");
    }

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(0, dp(8), 0, 0);
        return l;
    }

    private LinearLayout card(View inner) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF17211C);
        d.setCornerRadius(dp(18));
        d.setStroke(dp(1), 0x223DBE8C);
        card.setBackground(d);
        if (inner.getParent() instanceof ViewGroup) {
            ((ViewGroup) inner.getParent()).removeView(inner);
        }
        card.addView(inner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView section(String value) {
        TextView t = text(value, 13, 0xFF7D9486);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView body(String value) {
        TextView t = text(value, 14, 0xFFD5E6DB);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private TextView hint(String value) {
        TextView t = text(value, 12, 0xFF8FA497);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private TextView label(String value) {
        TextView t = text(value, 13, 0xFFE8F2EC);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private EditText field(String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(0xFFF4FFF8);
        e.setHintTextColor(0xFF6E8578);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF101815);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), 0xFF2C463A);
        e.setBackground(d);
        return e;
    }

    private CheckBox check(String value) {
        CheckBox c = new CheckBox(this);
        c.setText(value);
        c.setTextColor(0xFFE8F2EC);
        c.setPadding(dp(4), dp(8), dp(4), dp(4));
        return c;
    }

    private Button button(String value, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(value);
        b.setTextSize(15);
        b.setTextColor(primary ? 0xFF07110C : 0xFFE8F2EC);
        b.setMinHeight(dp(48));
        GradientDrawable d = new GradientDrawable();
        d.setColor(primary ? 0xFF3DBE8C : 0xFF24362C);
        d.setCornerRadius(dp(14));
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private Button mini(String value) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(value);
        b.setTextSize(12);
        b.setTextColor(0xFFE8F2EC);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF24362C);
        d.setCornerRadius(dp(12));
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setPadding(dp(4), 0, dp(4), 0);
        return b;
    }

    private Button preset(String value, Runnable run) {
        Button b = mini(value);
        b.setOnClickListener(v -> run.run());
        return b;
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(12)));
        return v;
    }

    private View gapSmall() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(8)));
        return v;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        return v;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int clampInt(EditText e, int min, int max, int fallback) {
        try {
            int n = Integer.parseInt(e.getText().toString().trim());
            return Math.max(min, Math.min(max, n));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static long clampLong(EditText e, long min, long max, long fallback) {
        try {
            long n = Long.parseLong(e.getText().toString().trim());
            return Math.max(min, Math.min(max, n));
        } catch (Exception ex) {
            return fallback;
        }
    }
}
