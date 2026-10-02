package com.alltoolbox.todo;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 任务清单主界面：列表 + 新增。
 */
public class TodoActivity extends AppCompatActivity {

    private TodoManager manager;
    private TodoAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_todo);
        setTitle(R.string.todo_title);

        manager = TodoManager.get(this);

        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TodoAdapter(this, manager);
        recycler.setAdapter(adapter);

        findViewById(R.id.empty_view).setOnClickListener(v -> showEditor(null));
        findViewById(R.id.fab_add).setOnClickListener(v -> showEditor(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        manager.reload();
        adapter.notifyDataSetChanged();
        updateEmptyView();
    }

    private void updateEmptyView() {
        boolean empty = manager.getAll().isEmpty();
        findViewById(R.id.empty_view).setVisibility(empty ? View.VISIBLE : View.GONE);
        setTitle(getString(R.string.todo_title) + (empty ? "" : "（" + manager.pendingCount() + "）"));
    }

    private void showEditor(TodoItem item) {
        TodoEditDialog dialog = new TodoEditDialog(this, manager, item);
        dialog.setOnSaved(this::updateEmptyView);
        dialog.show();
    }

    void onToggle(long id) {
        manager.toggle(id);
        adapter.notifyDataSetChanged();
    }

    void onEdit(long id) {
        for (TodoItem t : manager.getAll()) {
            if (t.id == id) {
                showEditor(t);
                return;
            }
        }
    }

    void onDelete(long id) {
        manager.delete(id);
        adapter.notifyDataSetChanged();
        updateEmptyView();
    }
}