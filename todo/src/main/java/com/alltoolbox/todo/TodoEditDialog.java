package com.alltoolbox.todo;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 新建/编辑任务的对话框。编辑时保存原 id，删除后可移除该任务。
 */
public class TodoEditDialog {

    public interface OnSaved {
        void onSaved();
    }

    private final Context context;
    private final TodoManager manager;
    private final TodoItem existing;
    private OnSaved onSaved;

    public TodoEditDialog(Context context, TodoManager manager, TodoItem existing) {
        this.context = context;
        this.manager = manager;
        this.existing = existing;
    }

    public void setOnSaved(OnSaved onSaved) {
        this.onSaved = onSaved;
    }

    public void show() {
        View root = LayoutInflater.from(context).inflate(R.layout.dialog_todo_edit, null);
        EditText etTitle = root.findViewById(R.id.et_title);
        EditText etNote = root.findViewById(R.id.et_note);
        RadioGroup rgPriority = root.findViewById(R.id.rg_priority);
        TextView tvPriority = root.findViewById(R.id.tv_priority_label);

        if (existing != null) {
            etTitle.setText(existing.title);
            etNote.setText(existing.note == null ? "" : existing.note);
            int id = existing.priority == TodoItem.PRIORITY_HIGH ? R.id.rb_high
                    : existing.priority == TodoItem.PRIORITY_LOW ? R.id.rb_low : R.id.rb_normal;
            rgPriority.check(id);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(context);
        b.setTitle(existing == null ? R.string.todo_new_title : R.string.todo_edit_title);
        b.setView(root);
        b.setNegativeButton(R.string.todo_cancel, null);
        b.setPositiveButton(R.string.todo_save, (d, w) -> save(etTitle, etNote, rgPriority));
        if (existing != null) {
            b.setNeutralButton(R.string.todo_delete, (d, w) -> {
                manager.delete(existing.id);
                if (onSaved != null) onSaved.onSaved();
            });
        }
        b.show();
    }

    private void save(EditText etTitle, EditText etNote, RadioGroup rgPriority) {
        String title = etTitle.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(context, R.string.todo_required, Toast.LENGTH_SHORT).show();
            return;
        }
        int priority = TodoItem.PRIORITY_NORMAL;
        int checked = rgPriority.getCheckedRadioButtonId();
        if (checked == R.id.rb_high) priority = TodoItem.PRIORITY_HIGH;
        else if (checked == R.id.rb_low) priority = TodoItem.PRIORITY_LOW;

        TodoItem item = (existing != null) ? existing : new TodoItem();
        item.title = title;
        item.note = etNote.getText().toString().trim();
        item.priority = priority;

        if (existing != null) manager.update(item);
        else manager.add(item);

        if (onSaved != null) onSaved.onSaved();
    }
}