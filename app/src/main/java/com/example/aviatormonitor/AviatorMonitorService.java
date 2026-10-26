package com.example.aviatormonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AviatorMonitorService extends Service {

    private WindowManager windowManager;
    private View floatingView;
    private boolean isMonitoring = false;
    private int roundCounter = 1;
    
    private Handler handler = new Handler();
    private String lastDetectedText = "";
    private boolean wasMultiplierActive = false;
    private String lastValidMultiplier = "";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, createNotification());
        createFloatingWidget();
        startSmartMonitoringLoop();
    }

    private Notification createNotification() {
        String channelId = "aviator_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Aviator Region Monitor", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Aviator Monitor Active")
                .setContentText("Floating widget & region detection ready...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    private void createFloatingWidget() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        Button floatBtn = new Button(this);
        floatBtn.setText("🎯 SET");
        floatBtn.setTextSize(14);
        floatBtn.setBackgroundColor(0xFFFF9800); // لون برتقالي لتحديد المنطقة والتحكم
        floatBtn.setTextColor(0xFFFFFFFF);

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 50;
        params.y = 200;

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
                        if (Math.abs(event.getRawX() - initialTouchX) < 10 && Math.abs(event.getRawY() - initialTouchY) < 10) {
                            // الضغطة المفردة تفعل أو توقف المراقبة / أو تفتح وضع تحديد المنطقة
                            toggleMonitoringState(floatBtn);
                        }
                        return true;
                }
                return false;
            }
        });

        floatingView = floatBtn;
        windowManager.addView(floatingView, params);
    }

    private void toggleMonitoringState(Button btn) {
        isMonitoring = !isMonitoring;
        if (isMonitoring) {
            btn.setText("🟢 RUN");
            btn.setBackgroundColor(0xFF00FF00);
            Toast.makeText(this, "Region Monitoring Started!", Toast.LENGTH_SHORT).show();
        } else {
            btn.setText("🎯 SET");
            btn.setBackgroundColor(0xFFFF9800);
            Toast.makeText(this, "Monitoring Paused.", Toast.LENGTH_SHORT).show();
        }
    }

    // خوارزمية الفحص الذكي وكشف الانتقال للعد التنازلي كل نصف ثانية
    private void startSmartMonitoringLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isMonitoring) {
                    checkScreenTransitionAndCapture();
                }
                handler.postDelayed(this, 500); // كل نصف ثانية
            }
        }, 500);
    }

    private void checkScreenTransitionAndCapture() {
        // محاكاة قراءة المنطقة المحددة من الشاشة (OCR Simulation)
        // في التطبيق العملي، هنا يتم التقاط مستطيل الشاشة المحدد وتحليله
        String currentRead = simulateRegionOCR();

        boolean isCountdown = currentRead.contains("s") || currentRead.contains("0.00") || currentRead.equals("countdown");

        if (!isCountdown) {
            // الرقم نشط وموجود (الطائرة تطير أو المضاعف يرتفع)
            wasMultiplierActive = true;
            lastValidMultiplier = currentRead;
        } else {
            // ظهر العد التنازلي أو اختفى الرقم! وهذا يعني انتهاء الجولة فوراً
            if (wasMultiplierActive && !lastValidMultiplier.isEmpty()) {
                double parsed = parseMultiplier(lastValidMultiplier);
                if (parsed > 1.0) {
                    saveConfirmedResult(parsed);
                }
                wasMultiplierActive = false;
                lastValidMultiplier = "";
            }
        }
    }

    private String simulateRegionOCR() {
        // محاكاة لحالات الشاشة (حسب اختبارك): أرقام تصعد ثم ظهور العد التنازلي
        double rand = Math.random();
        if (rand > 0.7) {
            return "countdown"; // مؤشر ظهور العد التنازلي واننهاء الجولة
        } else {
            double val = 1.1 + (rand * 10.0);
            return String.format(Locale.getDefault(), "%.2fx", val);
        }
    }

    private double parseMultiplier(String text) {
        try {
            String clean = text.replace("x", "").trim();
            return Double.parseDouble(clean);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void saveConfirmedResult(double multiplier) {
        String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        AviatorResult result = new AviatorResult(multiplier, timeStamp, roundCounter++, true);
        AppDatabase.getDatabase(getApplicationContext()).resultDao().insert(result);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (windowManager != null && floatingView != null) {
            windowManager.removeView(floatingView);
        }
    }
}
