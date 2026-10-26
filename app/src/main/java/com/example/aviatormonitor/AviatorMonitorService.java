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
import androidx.core.app.NotificationCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AviatorMonitorService extends Service {

    private WindowManager windowManager;
    private View floatingView;
    private boolean isMonitoring = false;
    private int roundCounter = 1;
    
    // متغيرات خوارزمية الثبات (فحص كل 0.5 ثانية وتكرار 3 مرات)
    private Handler handler = new Handler();
    private String lastDetectedText = "";
    private int stableCount = 0;
    private double lastSavedMultiplier = 0.0;

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
            NotificationChannel channel = new NotificationChannel(channelId, "Aviator Smart Monitor", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Aviator Smart Monitor Active")
                .setContentText("Smart OCR & Stable Logic running...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    private void createFloatingWidget() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        Button floatBtn = new Button(this);
        floatBtn.setText("🔴 ON");
        floatBtn.setTextSize(14);
        floatBtn.setBackgroundColor(0xFFFF0000);
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
            btn.setText("🟢 ACTIVE");
            btn.setBackgroundColor(0xFF00FF00);
        } else {
            btn.setText("🔴 PAUSED");
            btn.setBackgroundColor(0xFFFF0000);
        }
    }

    // خوارزمية الفحص الذكي كل 500 ملي ثانية (نصف ثانية)
    private void startSmartMonitoringLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isMonitoring) {
                    processScreenOCRAndStability();
                }
                handler.postDelayed(this, 500); // تكرار الفحص كل نصف ثانية
            }
        }, 500);
    }

    private void processScreenOCRAndStability() {
        // محاكاة قراءة الشاشة وتحليل الكاميرا/OCR (يمكن ربطها لاحقاً بالتقاط الشاشة الفعلي)
        // هنا نقوم بتطبيق منطق الثبات الذي اكتشفته: إذا ثبت الرقم لـ 3 دورات فحص متتالية (ثانيتين) يُعتبر نهائياً
        
        String simulatedCurrentDetected = simulateOCRRead();

        if (simulatedCurrentDetected.equals(lastDetectedText)) {
            stableCount++;
            // إذا تكرر الرقم في 3 فترات فحص متطابقة (ثانيتين تقريباً) ولم يتم حفظه مسبقاً
            if (stableCount >= 3) {
                double parsedMultiplier = parseMultiplier(simulatedCurrentDetected);
                if (parsedMultiplier > 0 && parsedMultiplier != lastSavedMultiplier) {
                    saveConfirmedResult(parsedMultiplier);
                    lastSavedMultiplier = parsedMultiplier;
                }
            }
        } else {
            // الرقم يتغير (الطائرة تطير والجولة مستمرة)
            lastDetectedText = simulatedCurrentDetected;
            stableCount = 1;
        }
    }

    private String simulateOCRRead() {
        // محاكاة مؤقتة للقراءة البصرية (ستستبدل بالتقاط MediaProjection الفعلي)
        double val = 1.0 + (Math.random() * 8.0);
        return String.format(Locale.getDefault(), "%.2fx", val);
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
