package com.alltoolbox.todo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 任务清单持久化管理：使用 SharedPreferences 以 JSON 数组方式保存全部任务。
 */
public final class TodoManager {

    private static final String PREFS = "alltoolbox_todo";
    private static final String KEY_DATA = "items";

    private static TodoManager instance;
    private final SharedPreferences sp;
    private final List<TodoItem> items = new ArrayList<>();

    private TodoManager(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        reload();
    }

    public static synchronized TodoManager get(Context ctx) {
        if (instance == null) instance = new TodoManager(ctx);
        return instance;
    }

    /** 从磁盘重新加载。 */
    public void reload() {
        items.clear();
        String raw = sp.getString(KEY_DATA, "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                items.add(TodoItem.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        sort();
    }

    /** 按优先级高→低、未完成在前、新→旧排序。 */
    private void sort() {
        Collections.sort(items, (a, b) -> {
            if (a.done != b.done) return a.done ? 1 : -1;
            if (a.priority != b.priority) return b.priority - a.priority;
            return Long.compare(b.createdAt, a.createdAt);
        });
    }

    /** 当前全部任务（已排序）。 */
    public List<TodoItem> getAll() {
        return Collections.unmodifiableList(items);
    }

    /** 未完成任务数。 */
    public int pendingCount() {
        int c = 0;
        for (TodoItem t : items) if (!t.done) c++;
        return c;
    }

    public boolean contains(long id) {
        for (TodoItem t : items) if (t.id == id) return true;
        return false;
    }

    public void add(TodoItem item) {
        items.add(item);
        persist();
    }

    public void update(TodoItem item) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == item.id) {
                items.set(i, item);
                break;
            }
        }
        sort();
        persist();
    }

    public boolean toggle(long id) {
        for (TodoItem t : items) {
            if (t.id == id) {
                t.done = !t.done;
                sort();
                persist();
                return true;
            }
        }
        return false;
    }

    public void delete(long id) {
        items.removeIf(t -> t.id == id);
        persist();
    }

    public void persist() {
        JSONArray arr = new JSONArray();
        try {
            for (TodoItem t : items) arr.put(t.toJson());
        } catch (Exception ignored) {
        }
        sp.edit().putString(KEY_DATA, arr.toString()).apply();
    }
}