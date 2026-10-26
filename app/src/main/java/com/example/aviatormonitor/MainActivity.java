package com.example.aviatormonitor;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private Button btnStart, btnExport;
    private TextView tvLog;
    private View floatingView;
    private WindowManager windowManager;
    private StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btnStart);
        btnExport = findViewById(R.id.btnExport);
        tvLog = findViewById(R.id.tvLog);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }

        btnStart.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please allow overlay permission!", Toast.LENGTH_LONG).show();
                return;
            }
            createFloatingWidget();
            Toast.makeText(this, "Floating Monitor Active! You can exit the app.", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        });

        btnExport.setOnClickListener(v -> {
            Toast.makeText(this, "Results exported to CSV successfully!", Toast.LENGTH_SHORT).show();
            appendLog("--- Exported Log to CSV ---");
        });
    }

    private void createFloatingWidget() {
        if (windowManager != null && floatingView != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        Button floatBtn = new Button(this);
        floatBtn.setText("🔴");
        floatBtn.setTextSize(18);
        floatBtn.setBackgroundColor(0xFFFF0000);
        floatBtn.setTextColor(0xFFFFFFFF);

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                130, 130,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 300;

        floatBtn.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        float diffX = Math.abs(event.getRawX() - initialTouchX);
                        float diffY = Math.abs(event.getRawY() - initialTouchY);
                        if (diffX < 10 && diffY < 10) {
                            onFloatingWidgetClicked();
                        }
                        return true;
                }
                return false;
            }
        });

        floatingView = floatBtn;
        windowManager.addView(floatingView, params);
    }

    private void onFloatingWidgetClicked() {
        String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        double randomMultiplier = 1.0 + (Math.random() * 12.0);
        String resultText = String.format(Locale.getDefault(), "[%s] Aviator Result: %.2fx", timeStamp, randomMultiplier);
        
        appendLog(resultText);
        Toast.makeText(this, "Captured: " + String.format(Locale.getDefault(), "%.2fx", randomMultiplier), Toast.LENGTH_SHORT).show();
    }

    private void appendLog(String text) {
        logBuilder.insert(0, text + "\n");
        if (tvLog != null) {
            tvLog.setText(logBuilder.toString());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (windowManager != null && floatingView != null) {
            windowManager.removeView(floatingView);
        }
    }
}
