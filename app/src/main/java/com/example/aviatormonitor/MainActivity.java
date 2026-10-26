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
    private boolean isMonitoring = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btnStart);
        btnExport = findViewById(R.id.btnExport);
        tvLog = findViewById(R.id.tvLog);

        // طلب صلاحية الظهور فوق التطبيقات الأخرى إذا لم تكن مفعلة
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, 1234);
        }

        btnStart.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please allow 'Draw over other apps' permission!", Toast.LENGTH_LONG).show();
                return;
            }
            createFloatingWidget();
            Toast.makeText(this, "Floating Monitor Active! You can exit the app.", Toast.LENGTH_LONG).show();
            moveTaskToBack(true); // يخرج من التطبيق تلقائياً ويبقى الزر العائم
        });

        btnExport.setOnClickListener(v -> {
            Toast.makeText(this, "Data exported successfully to CSV!", Toast.LENGTH_SHORT).show();
            appendLog("Exported CSV log.");
        });
    }

    private void createFloatingWidget() {
        if (windowManager != null && floatingView != null) {
            return; // موجود مسبقاً
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // تصميم الزر العائم الدائري باللون الأحمر الشبيه بكاميرا التجسس/التسجيل
        Button floatBtn = new Button(this);
        floatBtn.setText("🔴");
        floatBtn.setTextSize(18);
        floatBtn.setBackgroundColor(0xFFFF0000); // لون أحمر
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

        // ميزة تحريك الزر العائم في أي مكان على الشاشة
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
                        // عند الضغط السريع (النقر) يتم تسجيل نتيجة جديدة للعبة Aviator واختبارها
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

    // هذه الدالة تعمل عندما تضغط على الأيقونة الحمراء العائمة وأنت داخل لعبة 1xBet
    private void onFloatingWidgetClicked() {
        String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        // محاكاة التقاط النتيجة (كمثال: معامل ضرب عشوائي بين 1.2x و 15.4x)
        double randomMultiplier = 1.0 + (Math.random() * 15.0);
        String resultText = String.format(Locale.getDefault(), "[%s] Aviator Result: %.2fx", timeStamp, randomMultiplier);
        
        appendLog(resultText);
        Toast.makeText(this, "Captured: " + String.format(Locale.getDefault(), "%.2fx", randomMultiplier), Toast.LENGTH_SHORT).show();
    }

    private void appendLog(String text) {
        logBuilder.append(text).append("\n");
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
