package com.alltoolbox.fbrowser;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.Locale;

/**
 * 媒体播放：视频用 VideoView + 控制器；音频用 MediaPlayer + 进度条/时长/播暂停。
 * 通过 {@link #EXTRA_PATH} 与 {@link #EXTRA_TYPE}（"video"/"audio"）接收文件路径。
 */
public class MediaPlayActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_TYPE = "type";
    public static final String TYPE_VIDEO = "video";
    public static final String TYPE_AUDIO = "audio";

    private VideoView videoView;
    private LinearLayout audioPanel;
    private TextView titleText, timeText;
    private SeekBar seekBar;
    private Button playPause;
    private MediaPlayer player;
    private boolean playing = false;
    private boolean userSeeking = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            if (player != null && playing && !userSeeking) {
                int pos = player.getCurrentPosition();
                int dur = player.getDuration();
                seekBar.setProgress(dur > 0 ? pos : 0);
                seekBar.setMax(dur > 0 ? dur : 1);
                timeText.setText(formatMs(pos) + " / " + formatMs(dur));
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_play);

        videoView = findViewById(R.id.media_video);
        audioPanel = findViewById(R.id.media_audio_panel);
        titleText = findViewById(R.id.media_title);
        timeText = findViewById(R.id.media_time);
        seekBar = findViewById(R.id.media_seek);
        playPause = findViewById(R.id.media_play_pause);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String type = getIntent().getStringExtra(EXTRA_TYPE);
        if (path == null) {
            finish();
            return;
        }
        File f = new File(path);
        setTitle(f.getName());
        if (!f.exists()) {
            toastError();
            finish();
            return;
        }

        if (TYPE_VIDEO.equals(type)) {
            playVideo(f);
        } else {
            playAudio(f);
        }
    }

    private void playVideo(File f) {
        audioPanel.setVisibility(View.GONE);
        videoView.setVisibility(View.VISIBLE);
        MediaController ctrl = new MediaController(this);
        videoView.setMediaController(ctrl);
        videoView.setVideoPath(f.getAbsolutePath());
        videoView.setOnErrorListener((mp, what, extra) -> {
            toastError();
            return true;
        });
        videoView.setOnCompletionListener(mp -> finish());
    }

    private void playAudio(File f) {
        videoView.setVisibility(View.GONE);
        audioPanel.setVisibility(View.VISIBLE);
        titleText.setText(f.getName() + "\n" + FileAdapter.formatSize(f.length()));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (player != null) {
                    player.seekTo(sb.getProgress());
                    timeText.setText(formatMs(sb.getProgress()) + " / " + formatMs(player.getDuration()));
                }
            }
        });

        playPause.setOnClickListener(v -> {
            if (player == null) return;
            if (playing) {
                player.pause();
            } else {
                player.start();
            }
            playing = !playing;
            playPause.setText(playing ? R.string.media_pause : R.string.media_play);
        });

        initAudioPlayer(f);
        handler.removeCallbacks(progressUpdater);
        handler.post(progressUpdater);
    }

    private void initAudioPlayer(File f) {
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                int dur = mp.getDuration();
                seekBar.setMax(dur > 0 ? dur : 1);
                timeText.setText("00:00" + " / " + formatMs(dur));
            });
            player.setOnErrorListener((mp, what, extra) -> {
                toastError();
                return true;
            });
            player.setOnCompletionListener(mp -> {
                playing = false;
                playPause.setText(R.string.media_play);
                seekBar.setProgress(seekBar.getMax());
            });
            player.prepareAsync();
        } catch (Exception e) {
            toastError();
        }
    }

    private void toastError() {
        Toast.makeText(this, R.string.media_error, Toast.LENGTH_SHORT).show();
    }

    private static String formatMs(int ms) {
        if (ms < 0) ms = 0;
        int totalSec = ms / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", totalSec / 60, totalSec % 60);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && playing) player.pause();
        if (videoView != null) videoView.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(progressUpdater);
        handler.post(progressUpdater);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (videoView != null) videoView.stopPlayback();
    }
}