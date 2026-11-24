package de.mikromakro.replay;

import android.content.Context;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class MacroStore {
    private final File file;
    private final ArrayList<Models.Macro> macros = new ArrayList<>();

    public MacroStore(Context ctx) {
        file = new File(ctx.getFilesDir(), "macros.json");
        load();
    }

    public synchronized ArrayList<Models.Macro> all() {
        return macros;
    }

    public synchronized Models.Macro byId(String id) {
        if (id == null) return null;
        for (Models.Macro m : macros) {
            if (id.equals(m.id)) return m;
        }
        return null;
    }

    public synchronized void upsert(Models.Macro m) {
        if (m == null || m.id == null) return;
        for (int i = 0; i < macros.size(); i++) {
            if (m.id.equals(macros.get(i).id)) {
                macros.set(i, m);
                save();
                return;
            }
        }
        macros.add(0, m);
        save();
    }

    public synchronized void delete(String id) {
        if (id == null) return;
        for (int i = 0; i < macros.size(); i++) {
            if (id.equals(macros.get(i).id)) {
                macros.remove(i);
                save();
                return;
            }
        }
    }

    private void load() {
        macros.clear();
        if (!file.exists() || file.length() > 20_000_000L) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            JSONArray a = new JSONArray(new String(buf, 0, n, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                macros.add(Models.Macro.fromJson(a.getJSONObject(i)));
            }
        } catch (Exception ignored) {
            macros.clear();
        }
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Models.Macro m : macros) a.put(m.toJson());
            byte[] data = a.toString().getBytes(StandardCharsets.UTF_8);
            File tmp = new File(file.getParentFile(), "macros.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
                out.flush();
            }
            if (file.exists() && !file.delete()) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                }
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(file)) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                }
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
