package com.alltoolbox.todo;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * 任务列表适配器。点击行进入编辑，勾选切换完成态，长按删除。
 */
public class TodoAdapter extends RecyclerView.Adapter<TodoAdapter.VH> {

    private final TodoActivity activity;
    private final TodoManager manager;

    public TodoAdapter(TodoActivity activity, TodoManager manager) {
        this.activity = activity;
        this.manager = manager;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_todo, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        List<TodoItem> list = manager.getAll();
        TodoItem item = list.get(position);

        h.check.setChecked(item.done);
        h.check.setOnClickListener(v -> activity.onToggle(item.id));

        h.title.setText(item.title);
        if (item.done) {
            h.title.setPaintFlags(h.title.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            h.title.setAlpha(0.5f);
        } else {
            h.title.setPaintFlags(h.title.getPaintFlags() & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            h.title.setAlpha(1f);
        }

        if (item.note != null && !item.note.isEmpty()) {
            h.note.setVisibility(View.VISIBLE);
            h.note.setText(item.note);
        } else {
            h.note.setVisibility(View.GONE);
        }

        h.priority.setText(priorityText(item));
        h.priority.setTextColor(priorityColor(item));

        h.itemView.setOnClickListener(v -> activity.onEdit(item.id));
        h.itemView.setOnLongClickListener(v -> {
            activity.onDelete(item.id);
            return true;
        });
    }

    private String priorityText(TodoItem item) {
        switch (item.priority) {
            case TodoItem.PRIORITY_HIGH:
                return activity.getString(R.string.todo_priority_high);
            case TodoItem.PRIORITY_LOW:
                return activity.getString(R.string.todo_priority_low);
            default:
                return activity.getString(R.string.todo_priority_normal);
        }
    }

    private int priorityColor(TodoItem item) {
        switch (item.priority) {
            case TodoItem.PRIORITY_HIGH:
                return 0xFFE53935;
            case TodoItem.PRIORITY_LOW:
                return 0xFF9E9E9E;
            default:
                return 0xFF1E88E5;
        }
    }

    @Override
    public int getItemCount() {
        return manager.getAll().size();
    }

    static class VH extends RecyclerView.ViewHolder {
        CheckBox check;
        TextView title, note, priority;

        VH(View v) {
            super(v);
            check = v.findViewById(R.id.check_done);
            title = v.findViewById(R.id.tv_title);
            note = v.findViewById(R.id.tv_note);
            priority = v.findViewById(R.id.tv_priority);
        }
    }
}