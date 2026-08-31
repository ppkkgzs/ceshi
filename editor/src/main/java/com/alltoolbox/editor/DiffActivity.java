package com.alltoolbox.editor;

import android.graphics.Color;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 双栏文本对比：高亮标记文本改动（基于逐行 LCS diff）。
 */
public class DiffActivity extends AppCompatActivity {

    private EditText leftView, rightView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diff);
        setTitle("文本对比");

        leftView = findViewById(R.id.diff_left);
        rightView = findViewById(R.id.diff_right);
        Button diff = findViewById(R.id.btn_diff);

        diff.setOnClickListener(v -> doDiff());
    }

    private void doDiff() {
        String[] leftLines = leftView.getText().toString().split("\n", -1);
        String[] rightLines = rightView.getText().toString().split("\n", -1);

        // 逐行最长公共子序列，标记不同行
        List<Integer> matches = lcsMatch(leftLines, rightLines);

        SpannableStringBuilder ls = new SpannableStringBuilder(leftView.getText());
        SpannableStringBuilder rs = new SpannableStringBuilder(rightView.getText());

        // 左栏：LCS 匹配的行不变，其余标红
        int offset = 0;
        for (int i = 0; i < leftLines.length; i++) {
            if (!matches.contains(i)) {
                int start = offset;
                ls.setSpan(new BackgroundColorSpan(0x55F44336), start, start + leftLines[i].length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            offset += leftLines[i].length() + 1;
        }
        // 右栏：同样处理（右栏的有无加入匹配）——简化：右栏全部不在 LCS 对应位置的标蓝
        offset = 0;
        int li = 0;
        for (int j = 0; j < rightLines.length; j++) {
            // 找到右栏第 j 行是否对应某 LCS 匹配的左行
            boolean matched = false;
            for (int k = 0; k < matches.size(); k++) {
                // 粗略：右行与匹配到的左行在位置 j+k 对齐（简化实现）
            }
            // 简化策略：与左侧相同行的内容不标亮
            String rl = rightLines[j];
            boolean same = li < leftLines.length
                    && leftLines[li].equals(rl) && matches.contains(li);
            if (!same) {
                int start = offset;
                rs.setSpan(new BackgroundColorSpan(0x552196F3), start, start + rl.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                li++;
            }
            offset += rl.length() + 1;
        }

        leftView.setText(ls);
        rightView.setText(rs);
    }

    /** 返回左栏中参与 LCS 匹配（即两栏共有）的行下标集合。 */
    private List<Integer> lcsMatch(String[] a, String[] b) {
        int n = a.length, m = b.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (a[i].equals(b[j])) dp[i][j] = dp[i + 1][j + 1] + 1;
                else dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<Integer> matches = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                matches.add(i);
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return matches;
    }
}