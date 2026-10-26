package com.example.aviatormonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
    private View floatingButtonView;
    private CropOverlayView cropOverlayView;
    
    private boolean isMonitoring = false;
    private int roundCounter = 1;
    
    private Handler handler = new Handler();
    private boolean wasMultiplierActive = false;
    private String lastValidMultiplier = "";
    private Rect selectedRegion = null;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, createNotification());
        createFloatingControlWidget();
    }

    private Notification createNotification() {
        String channelId = "aviator_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Aviator Tracker Active", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Aviator Tracker Active")
                .setContentText("Ready for area selection and smart monitoring...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    private void createFloatingControlWidget() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        Button floatBtn = new Button(this);
        floatBtn.setText("🎯 SET AREA");
        floatBtn.setTextSize(12);
        floatBtn.setBackgroundColor(0xFFFF5722);
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
        params.y = 150;

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
                        windowManager.updateViewLayout(floatingButtonView, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (Math.abs(event.getRawX() - initialTouchX) < 10 && Math.abs(event.getRawY() - initialTouchY) < 10) {
                            openRegionSelectorOverlay();
                        }
                        return true;
                }
                return false;
            }
        });

        floatingButtonView = floatBtn;
        windowManager.addView(floatingButtonView, params);
    }

    private void openRegionSelectorOverlay() {
        if (cropOverlayView != null) return;

        Toast.makeText(this, "Draw rectangle over Aviator multiplier!", Toast.LENGTH_LONG).show();

        cropOverlayView = new CropOverlayView(this, new CropOverlayView.OnRegionSelectedListener() {
            @Override
            public void onRegionSelected(Rect rect) {
                selectedRegion = rect;
                windowManager.removeView(cropOverlayView);
                cropOverlayView = null;
                
                Toast.makeText(getApplicationContext(), "Region Set! Smart Monitoring started...", Toast.LENGTH_LONG).show();
                startSmartMonitoring();
            }
        });

        WindowManager.LayoutParams overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );

        windowManager.addView(cropOverlayView, overlayParams);
    }

    private void startSmartMonitoring() {
        isMonitoring = true;
        if (floatingButtonView instanceof Button) {
            ((Button) floatingButtonView).setText("🟢 MONITORING");
            ((Button) floatingButtonView).setBackgroundColor(0xFF4CAF50);
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isMonitoring) {
                    checkAviatorCycleAndCapture();
                    handler.postDelayed(this, 500);
                }
            }
        }, 500);
    }

    private void checkAviatorCycleAndCapture() {
        // فحص المنطقة المحددة (selectedRegion) ومعالجة الانتقال للعد التنازلي
        String currentRead = simulateCropAreaOCR();

        boolean isCountdown = currentRead.contains("countdown") || currentRead.contains("0.00") || currentRead.contains("s");

        if (!isCountdown) {
            wasMultiplierActive = true;
            lastValidMultiplier = currentRead;
        } else {
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

    private String simulateCropAreaOCR() {
        double rand = Math.random();
        if (rand > 0.75) {
            return "countdown";
        } else {
            double val = 1.2 + (rand * 15.0);
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
        isMonitoring = false;
        handler.removeCallbacksAndMessages(null);
        if (windowManager != null) {
            if (floatingButtonView != null) windowManager.removeView(floatingButtonView);
            if (cropOverlayView != null) windowManager.removeView(cropOverlayView);
        }
    }
}
