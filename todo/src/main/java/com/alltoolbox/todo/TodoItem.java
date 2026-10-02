package com.alltoolbox.todo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 任务清单数据模型。一个任务包含：标题、说明、是否完成、创建时间、优先级。
 */
public class TodoItem {

    public static final int PRIORITY_HIGH = 2;
    public static final int PRIORITY_NORMAL = 1;
    public static final int PRIORITY_LOW = 0;

    public long id;
    public String title;
    public String note;
    public boolean done;
    public long createdAt;
    public int priority; // 0 低 / 1 普通 / 2 高

    public TodoItem() {
        id = System.currentTimeMillis();
        createdAt = id;
        priority = PRIORITY_NORMAL;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title == null ? "" : title);
        o.put("note", note == null ? "" : note);
        o.put("done", done);
        o.put("createdAt", createdAt);
        o.put("priority", priority);
        return o;
    }

    public static TodoItem fromJson(JSONObject o) throws JSONException {
        TodoItem t = new TodoItem();
        t.id = o.optLong("id");
        t.title = o.optString("title");
        t.note = o.optString("note");
        t.done = o.optBoolean("done");
        t.createdAt = o.optLong("createdAt");
        t.priority = o.optInt("priority");
        return t;
    }
}