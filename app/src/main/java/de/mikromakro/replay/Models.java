package de.mikromakro.replay;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

public final class Models {
    private Models() {}

    public static final String GESTURE = "gesture";
    public static final String TEXT = "text";
    public static final String GLOBAL = "global";
    public static final String WAIT = "wait";
    public static final String OPEN = "open";

    public static final class Stroke {
        public long start;
        public long dur;
        public final ArrayList<Float> pts = new ArrayList<>();

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("start", start);
            o.put("dur", dur);
            JSONArray a = new JSONArray();
            for (Float f : pts) a.put(f.doubleValue());
            o.put("pts", a);
            return o;
        }

        static Stroke fromJson(JSONObject o) throws JSONException {
            Stroke s = new Stroke();
            s.start = o.optLong("start");
            s.dur = Math.max(1, o.optLong("dur", 40));
            JSONArray a = o.optJSONArray("pts");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) s.pts.add((float) a.getDouble(i));
            }
            return s;
        }
    }

    public static final class Step {
        public String type;
        public long delay;
        public ArrayList<Stroke> strokes;
        public String text;
        public String viewId;
        public String action;
        public String pkg;
        public float x;
        public float y;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("delay", delay);
            if (strokes != null) {
                JSONArray a = new JSONArray();
                for (Stroke s : strokes) a.put(s.toJson());
                o.put("strokes", a);
            }
            if (text != null) o.put("text", text);
            if (viewId != null) o.put("viewId", viewId);
            if (action != null) o.put("action", action);
            if (pkg != null) o.put("pkg", pkg);
            if (x != 0f || y != 0f) {
                o.put("x", x);
                o.put("y", y);
            }
            return o;
        }

        static Step fromJson(JSONObject o) throws JSONException {
            Step s = new Step();
            s.type = o.getString("type");
            s.delay = o.optLong("delay");
            s.text = o.has("text") && !o.isNull("text") ? o.getString("text") : null;
            s.viewId = o.has("viewId") && !o.isNull("viewId") ? o.getString("viewId") : null;
            s.action = o.has("action") && !o.isNull("action") ? o.getString("action") : null;
            s.pkg = o.has("pkg") && !o.isNull("pkg") ? o.getString("pkg") : null;
            s.x = (float) o.optDouble("x", 0);
            s.y = (float) o.optDouble("y", 0);
            JSONArray a = o.optJSONArray("strokes");
            if (a != null) {
                s.strokes = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) s.strokes.add(Stroke.fromJson(a.getJSONObject(i)));
            }
            return s;
        }

        public String summary() {
            if (GESTURE.equals(type)) {
                int n = strokes == null ? 0 : strokes.size();
                long dur = 0;
                int pts = 0;
                if (strokes != null) {
                    for (Stroke st : strokes) dur = Math.max(dur, st.start + st.dur);
                    if (!strokes.isEmpty()) pts = strokes.get(0).pts.size() / 2;
                }
                if (n > 1) return "Mehrfinger";
                if (pts <= 2 && dur >= 350) return "Langdruck";
                if (pts <= 2) return "Tipp";
                return "Wischen";
            }
            if (TEXT.equals(type)) {
                String t = text == null ? "" : text.replace('\n', ' ');
                if (t.length() > 28) t = t.substring(0, 28) + "…";
                return "Text · " + t;
            }
            if (GLOBAL.equals(type)) {
                if ("back".equals(action)) return "Zurück";
                if ("home".equals(action)) return "Home";
                if ("recents".equals(action)) return "Übersicht";
                if ("notifications".equals(action)) return "Benachrichtigungen";
                return "System";
            }
            if (WAIT.equals(type)) return "Pause " + delay + " ms";
            if (OPEN.equals(type)) return "Appwechsel";
            return type == null ? "?" : type;
        }
    }

    public static final class Macro {
        public String id;
        public String name;
        public long created;
        public final ArrayList<Step> steps = new ArrayList<>();

        public static Macro create(String name) {
            Macro m = new Macro();
            m.id = UUID.randomUUID().toString();
            m.name = name;
            m.created = System.currentTimeMillis();
            return m;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("created", created);
            JSONArray a = new JSONArray();
            for (Step s : steps) a.put(s.toJson());
            o.put("steps", a);
            return o;
        }

        static Macro fromJson(JSONObject o) throws JSONException {
            Macro m = new Macro();
            m.id = o.getString("id");
            m.name = o.optString("name", "Makro");
            m.created = o.optLong("created");
            JSONArray a = o.optJSONArray("steps");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) m.steps.add(Step.fromJson(a.getJSONObject(i)));
            }
            return m;
        }
    }

    public static final class PlayOpts {
        public int repeats = 1;
        public boolean infinite = false;
        public long gapMin = 800;
        public long gapMax = 800;
        public float speed = 1f;
        public int jitterPct = 0;
        public int jitterPx = 0;
        public boolean diceTiming = false;
        public long diceMin = 40;
        public long diceMax = 900;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("repeats", repeats);
            o.put("infinite", infinite);
            o.put("gapMin", gapMin);
            o.put("gapMax", gapMax);
            o.put("speed", (double) speed);
            o.put("jitterPct", jitterPct);
            o.put("jitterPx", jitterPx);
            o.put("diceTiming", diceTiming);
            o.put("diceMin", diceMin);
            o.put("diceMax", diceMax);
            return o;
        }

        public static PlayOpts fromJson(String raw) {
            PlayOpts p = new PlayOpts();
            if (raw == null || raw.isEmpty()) return p;
            try {
                JSONObject o = new JSONObject(raw);
                p.repeats = Math.max(1, o.optInt("repeats", 1));
                p.infinite = o.optBoolean("infinite", false);
                p.gapMin = Math.max(0, o.optLong("gapMin", 800));
                p.gapMax = Math.max(0, o.optLong("gapMax", 800));
                p.speed = (float) o.optDouble("speed", 1);
                if (p.speed < 1f) p.speed = 1f;
                if (p.speed > 40f) p.speed = 40f;
                p.jitterPct = Math.max(0, Math.min(100, o.optInt("jitterPct", 0)));
                p.jitterPx = Math.max(0, Math.min(80, o.optInt("jitterPx", 0)));
                p.diceTiming = o.optBoolean("diceTiming", false);
                p.diceMin = Math.max(0, o.optLong("diceMin", 40));
                p.diceMax = Math.max(0, o.optLong("diceMax", 900));
            } catch (JSONException ignored) {
                return new PlayOpts();
            }
            return p;
        }
    }
}
