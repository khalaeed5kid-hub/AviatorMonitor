package com.example.aviatormonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AviatorMonitorService extends Service {

    private WindowManager windowManager;
    private View floatingButtonView;
    private CropOverlayView cropOverlayView;
    
    private boolean isSelectingRegion = false;
    private boolean isMonitoring = false;
    private int roundCounter = 1;
    
    private Handler handler = new Handler();
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
        createFloatingControlWidget();
    }

    private Notification createNotification() {
        String channelId = "aviator_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Aviator Real Monitor", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Aviator Tracker Active")
                .setContentText("Ready for region selection and monitoring...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    // 1. إنشاء الأيقونة العائمة للتحكم (زر صغير يتيح لك بدء تحديد المنطقة أو تشغيل المراقبة)
    private void createFloatingControlWidget() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        Button floatBtn = new Button(this);
        floatBtn.setText("🎯 SET AREA");
        floatBtn.setTextSize(12);
        floatBtn.setBackgroundColor(0xFFFF5722); // برتقالي غامق
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
                            // عند النقر: افتح نافذة تحديد المنطقة برسم المربع
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

    // 2. فتح نافذة تحديد المستطيل التفاعلي فوق الشاشة (تشبه الصورة التي أرسلتها)
    private void openRegionSelectorOverlay() {
        if (cropOverlayView != null) return;

        Toast.makeText(this, "Drag your finger to draw a rectangle over Aviator multiplier!", Toast.LENGTH_LONG).show();

        cropOverlayView = new CropOverlayView(this, new CropOverlayView.OnRegionSelectedListener() {
            @Override
            public void onRegionSelected(Rect rect) {
                // تم تحديد المنطقة بنجاح! إزالة نافذة الرسم وبدء المراقبة الذكية
                windowManager.removeView(cropOverlayView);
                cropOverlayView = null;
                
                Toast.makeText(getApplicationContext, "Region Set! Monitoring started...", Toast.LENGTH_SHORT).show();
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

    // 3. خوارزمية المراقبة الذكية والانتقال من المضاعف للعد التنازلي
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
                    handler.postDelayed(this, 500); // فحص كل نصف ثانية
                }
            }
        }, 500);
    }

    private void checkAviatorCycleAndCapture() {
        // محاكاة قراءة الإطار من المربع المحدد
        String currentRead = simulateCropAreaOCR();

        boolean isCountdown = currentRead.contains("countdown") || currentRead.contains("0.00") || currentRead.contains("s");

        if (!isCountdown) {
            // الطائرة تطير والمضاعف ظاهر ونشط
            wasMultiplierActive = true;
            lastValidMultiplier = currentRead;
        } else {
            // ظهر العد التنازلي (انتهت الجولة!) -> خذ آخر رقم مسجل فوراً وادخل السجل
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
        // محاكاة لقراءة المنطقة المحددة
        double rand = Math.random();
        if (rand > 0.75) {
            return "countdown"; // مؤشر انتهاء الجولة وظهور العداد
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

    // --- كلاس فرعي لرسم مستطيل تحديد المنطقة تماماً مثل الصورة التي طلبتها ---
    public static class CropOverlayView extends View {
        private Paint rectPaint, bgPaint, textPaint;
        private float startX, startY, endX, endY;
        private boolean isDrawing = false;
        private OnRegionSelectedListener listener;
        private Rect selectedRect = new Rect();

        public interface OnRegionSelectedListener {
            void onRegionSelected(Rect rect);
        }

        public CropOverlayView(Context context, OnRegionSelectedListener listener) {
            super(context);
            this.listener = listener;

            bgPaint = new Paint();
            bgPaint.setColor(Color.argb(120, 0, 0, 0)); // خلفية شفافة مظلمة

            rectPaint = new Paint();
            rectPaint.setColor(Color.YELLOW); // لون إطار المحدد أصفر ساطع
            rectPaint.setStyle(Paint.Style.STROKE);
            rectPaint.setStrokeWidth(6f);

            textPaint = new Paint();
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(40f);
            textPaint.setAntiAlias(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);

            if (isDrawing || (startX != endX && startY != endY)) {
                float left = Math.min(startX, endX);
                float top = Math.min(startY, endY);
                float right = Math.max(startX, endX);
                float bottom = Math.max(startY, endY);

                canvas.drawRect(left, top, right, bottom, rectPaint);
                canvas.drawText("Tap / Double tap to confirm region", 50, 150, textPaint);
            } else {
                canvas.drawText("Drag your finger to draw a rectangle over Aviator", 50, 150, textPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    startY = event.getY();
                    endX = startX;
                    endY = startY;
                    isDrawing = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    endX = event.getX();
                    endY = event.getY();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    isDrawing = false;
                    selectedRect.set((int) Math.min(startX, endX), (int) Math.min(startY, endY),
                            (int) Math.max(startX, endX), (int) Math.max(startY, endY));
                    invalidate();

                    if (listener != null && selectedRect.width() > 50 && selectedRect.height() > 50) {
                        listener.onRegionSelected(selectedRect);
                    }
                    return true;
            }
            return false;
        }
    }
}
