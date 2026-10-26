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

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, createNotification());
        createFloatingWidget();
    }

    private Notification createNotification() {
        String channelId = "aviator_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Aviator Monitor", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Aviator Monitor Active")
                .setContentText("Service is running stably...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    private void createFloatingWidget() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        Button floatBtn = new Button(this);
        floatBtn.setText("🔴 OFF");
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
                            toggleState(floatBtn);
                        }
                        return true;
                }
                return false;
            }
        });

        floatingView = floatBtn;
        windowManager.addView(floatingView, params);
    }

    private void toggleState(Button btn) {
        isMonitoring = !isMonitoring;
        if (isMonitoring) {
            btn.setText("🟢 ON");
            btn.setBackgroundColor(0xFF00FF00);
            Toast.makeText(this, "Monitor Running", Toast.LENGTH_SHORT).show();
            startLoop();
        } else {
            btn.setText("🔴 OFF");
            btn.setBackgroundColor(0xFFFF0000);
            Toast.makeText(this, "Monitor Paused", Toast.LENGTH_SHORT).show();
            handler.removeCallbacksAndMessages(null);
        }
    }

    private void startLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isMonitoring) {
                    // تسجيل محاكاة النتيجة عند انتهاء الجولة لضمان عمل السجل وقاعدة البيانات بدقة
                    double simulatedMultiplier = 1.50 + (Math.random() * 3.0);
                    saveResult(simulatedMultiplier);
                    handler.postDelayed(this, 5000); // كل 5 ثوانٍ جولة للتجربة
                }
            }
        }, 5000);
    }

    private void saveResult(double multiplier) {
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
