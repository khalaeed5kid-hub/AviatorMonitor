package com.example.aviatormonitor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Button btnStartService, btnStopService, btnCopy, btnClear;
    private TextView tvStats;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStartService = findViewById(R.id.btnStart);
        btnStopService = findViewById(R.id.btnStop);
        btnCopy = findViewById(R.id.btnExport); // زر النسخ إلى الحافظة
        btnClear = findViewById(R.id.btnClear); // زر حذف السجل
        tvStats = findViewById(R.id.tvLog);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }

        btnStartService.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, AviatorMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Toast.makeText(this, "Smart Monitor Service Started!", Toast.LENGTH_SHORT).show();
            moveTaskToBack(true);
        });

        btnStopService.setOnClickListener(v -> {
            stopService(new Intent(this, AviatorMonitorService.class));
            Toast.makeText(this, "Monitor Service Stopped.", Toast.LENGTH_SHORT).show();
            loadStats();
        });

        btnCopy.setOnClickListener(v -> {
            copyResultsToClipboard();
        });

        btnClear.setOnClickListener(v -> {
            showClearConfirmationDialog();
        });

        loadStats();
    }

    private void loadStats() {
        List<AviatorResult> results = AppDatabase.getDatabase(this).resultDao().getAllResults();
        StringBuilder sb = new StringBuilder();
        sb.append("Total Confirmed Rounds: ").append(results.size()).append("\n\n");
        for (AviatorResult r : results) {
            sb.append("Round ").append(r.roundNumber).append(": ").append(String.format(java.util.Locale.getDefault(), "%.2f", r.multiplier)).append("x [").append(r.timestamp).append("]\n");
        }
        tvStats.setText(sb.toString());
    }

    private void copyResultsToClipboard() {
        List<AviatorResult> results = AppDatabase.getDatabase(this).resultDao().getAllResults();
        if (results.isEmpty()) {
            Toast.makeText(this, "No data to copy!", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (AviatorResult r : results) {
            sb.append("Round ").append(r.roundNumber).append(": ").append(r.multiplier).append("x (").append(r.timestamp).append(")\n");
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Aviator Results", sb.toString());
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "All results copied to clipboard!", Toast.LENGTH_LONG).show();
    }

    private void showClearConfirmationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Clear All Records")
                .setMessage("Are you sure you want to delete all recorded results?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    AppDatabase.getDatabase(this).resultDao().deleteAll();
                    loadStats();
                    Toast.makeText(this, "All records deleted.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("No", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadStats();
    }
}
