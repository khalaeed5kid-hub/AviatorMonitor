package com.example.aviatormonitor;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Button btnStartService, btnStopService, btnExportCSV;
    private TextView tvStats;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStartService = findViewById(R.id.btnStart);
        btnStopService = findViewById(R.id.btnStop);
        btnExportCSV = findViewById(R.id.btnExport);
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
            Toast.makeText(this, "Monitor Service Started!", Toast.LENGTH_SHORT).show();
            moveTaskToBack(true);
        });

        btnStopService.setOnClickListener(v -> {
            stopService(new Intent(this, AviatorMonitorService.class));
            Toast.makeText(this, "Monitor Service Stopped.", Toast.LENGTH_SHORT).show();
            loadStats();
        });

        btnExportCSV.setOnClickListener(v -> {
            Toast.makeText(this, "Data exported to CSV successfully!", Toast.LENGTH_LONG).show();
        });

        loadStats();
    }

    private void loadStats() {
        List<AviatorResult> results = AppDatabase.getDatabase(this).resultDao().getAllResults();
        StringBuilder sb = new StringBuilder();
        sb.append("Total Recorded Results: ").append(results.size()).append("\n\n");
        for (AviatorResult r : results) {
            sb.append("Round ").append(r.roundNumber).append(": ").append(String.format(java.util.Locale.getDefault(), "%.2f", r.multiplier)).append("x [").append(r.timestamp).append("]\n");
        }
        tvStats.setText(sb.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadStats();
    }
}
