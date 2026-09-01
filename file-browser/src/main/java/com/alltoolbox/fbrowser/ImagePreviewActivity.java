package com.alltoolbox.fbrowser;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.alltoolbox.core.task.TaskExecutor;

import java.io.File;

/**
 * 图片预览：从文件浏览器直传路径，支持双指缩放与拖拽平移。
 * 通过 {@link #EXTRA_PATH} 接收文件路径。
 */
public class ImagePreviewActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    private ImageView imageView;
    private TextView infoText;

    private float scale = 1f;
    private float translateX = 0f, translateY = 0f;
    private float lastFocusX = 0f, lastFocusY = 0f;
    private int mode = NONE;
    private static final int NONE = 0, DRAG = 1, ZOOM = 2;

    private ScaleGestureDetector scaleDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_preview);

        imageView = findViewById(R.id.preview_image);
        infoText = findViewById(R.id.preview_info);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) {
            finish();
            return;
        }
        File f = new File(path);
        setTitle(f.getName());
        load(f);

        imageView.setImageMatrix(new Matrix());
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scale *= detector.getScaleFactor();
                scale = Math.max(0.2f, Math.min(scale, 8f));
                applyMatrix();
                return true;
            }
        });

        imageView.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mode = DRAG;
                    lastFocusX = event.getX();
                    lastFocusY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mode == DRAG && scaleDetector.isInProgress() && event.getPointerCount() > 1) {
                        break; // 缩放手势时不做单指拖拽
                    }
                    if (mode == DRAG) {
                        float dx = event.getX() - lastFocusX;
                        float dy = event.getY() - lastFocusY;
                        translateX += dx;
                        translateY += dy;
                        lastFocusX = event.getX();
                        lastFocusY = event.getY();
                        applyMatrix();
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    mode = ZOOM;
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    mode = DRAG;
                    break;
                case MotionEvent.ACTION_UP:
                    mode = NONE;
                    break;
            }
            return true;
        });
    }

    private void load(File f) {
        TaskExecutor.get().io().execute(() -> {
            final Bitmap bitmap = BitmapFactory.decodeFile(f.getAbsolutePath());
            runOnUiThread(() -> {
                if (bitmap == null) {
                    infoText.setText(R.string.preview_error);
                    Toast.makeText(this, R.string.preview_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                imageView.setImageBitmap(bitmap);
                infoText.setText(bitmap.getWidth() + " × " + bitmap.getHeight()
                        + "  ·  " + (f.length() / 1024) + " KB");
            });
        });
    }

    private void applyMatrix() {
        Matrix m = new Matrix();
        m.postTranslate(translateX, translateY);
        m.postScale(scale, scale, imageView.getWidth() / 2f, imageView.getHeight() / 2f);
        imageView.setImageMatrix(m);
    }
}