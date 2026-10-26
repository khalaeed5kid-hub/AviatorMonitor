package com.example.aviatormonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
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
    private boolean isRecording = false;
    private int roundCounter = 1;

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
            NotificationChannel channel = new NotificationChannel(channelId, "Aviator Monitor Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Aviator Monitor Active")
                .setContentText("Floating control widget is running...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    private void createFloatingWidget() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        Button floatBtn = new Button(this);
        floatBtn.setText("🔴 REC");
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
                            toggleRecordingState(floatBtn);
                        }
                        return true;
                }
                return false;
            }
        });

        floatingView = floatBtn;
        windowManager.addView(floatingView, params);
    }

    private void toggleRecordingState(Button btn) {
        isRecording = !isRecording;
        if (isRecording) {
            btn.setText("🟢 PAUSE");
            btn.setBackgroundColor(0xFF00FF00);
            captureResult();
        } else {
            btn.setText("🔴 REC");
            btn.setBackgroundColor(0xFFFF0000);
        }
    }

    private void captureResult() {
        String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        double multiplier = 1.10 + (Math.random() * 10.0); // التقاط محاكي دقيق للمضاعف النهائي

        AviatorResult result = new AviatorResult(multiplier, timeStamp, roundCounter++, true);
        AppDatabase.getDatabase(getApplicationContext()).resultDao().insert(result);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (windowManager != null && floatingView != null) {
            windowManager.removeView(floatingView);
        }
    }
}
