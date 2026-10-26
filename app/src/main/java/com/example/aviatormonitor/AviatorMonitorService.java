package com.example.aviatormonitor;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AviatorMonitorService extends Service {

    private WindowManager windowManager;
    private View floatingButtonView;
    private CropOverlayView cropOverlayView;
    
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    
    private boolean isMonitoring = false;
    private int roundCounter = 1;
    private Handler handler = new Handler();
    
    private Rect selectedRegion = null;
    private String lastValidMultiplier = "";
    private boolean wasMultiplierActive = false;
    private TextRecognizer textRecognizer;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        textRecognizer = TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("data")) {
            int resultCode = intent.getIntExtra("code", Activity.RESULT_OK);
            Intent data = intent.getParcelableExtra("data");

            startForeground(1, createNotification());
            
            projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (projectionManager != null && data != null) {
                mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                setupVirtualDisplay();
            }
            createFloatingControlWidget();
        }
        return START_NOT_STICKY;
    }

    private Notification createNotification() {
        String channelId = "aviator_real_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Aviator Real Monitor", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Aviator Real Tracker Active")
                .setContentText("Screen capture running. Tap button to set area.")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    private void setupVirtualDisplay() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        int dpi = getResources().getDisplayMetrics().densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);
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

        cropOverlayView = new CropOverlayView(this, rect -> {
            selectedRegion = rect;
            windowManager.removeView(cropOverlayView);
            cropOverlayView = null;
            
            Toast.makeText(getApplicationContext(), "Region Set! Real OCR Monitoring started...", Toast.LENGTH_LONG).show();
            startRealScreenMonitoring();
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

    private void startRealScreenMonitoring() {
        isMonitoring = true;
        if (floatingButtonView instanceof Button) {
            ((Button) floatingButtonView).setText("🟢 LIVE");
            ((Button) floatingButtonView).setBackgroundColor(0xFF4CAF50);
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isMonitoring) {
                    captureAndProcessRealScreen();
                    handler.postDelayed(this, 1000); // فحص كل ثانية حقيقية
                }
            }
        }, 1000);
    }

    private void captureAndProcessRealScreen() {
        if (imageReader == null || selectedRegion == null) return;

        Image image = imageReader.acquireLatestImage();
        if (image == null) return;

        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();

            Bitmap bitmap = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride, image.getHeight(), Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            image.close();

            // قص المنطقة المحددة فقط التي رسمتها أنت
            int cropX = Math.max(0, selectedRegion.left);
            int cropY = Math.max(0, selectedRegion.top);
            int cropW = Math.min(selectedRegion.width(), bitmap.getWidth() - cropX);
            int cropH = Math.min(selectedRegion.height(), bitmap.getHeight() - cropY);

            if (cropW <= 0 || cropH <= 0) return;

            Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH);

            // تحليل النصوص الحقيقية عبر OCR
            InputImage inputImage = InputImage.fromBitmap(croppedBitmap, 0);
            textRecognizer.process(inputImage)
                    .addOnSuccessListener(result -> analyzeOcrText(result.getText()))
                    .addOnFailureListener(e -> {});

        } catch (Exception e) {
            image.close();
        }
    }

    private void analyzeOcrText(String recognizedText) {
        if (recognizedText == null) return;
        String cleanText = recognizedText.trim();

        // هل ظهر العد التنازلي (مثل رقم 1 أو 2 أو 3 أو كلمة s) واختفى المضاعف؟
        boolean isCountdown = cleanText.matches("^[0-9]$") || cleanText.contains("s") || cleanText.isEmpty();

        if (!isCountdown && cleanText.contains("x")) {
            // الرقم أو المضاعف ظاهر ومستمر (مثل 1.18x)
            wasMultiplierActive = true;
            lastValidMultiplier = cleanText;
        } else if (isCountdown && wasMultiplierActive) {
            // ظهر العد التنازلي واختفى المضاعف! -> احفظ آخر رقم صحيح فوراً
            double parsed = parseMultiplier(lastValidMultiplier);
            if (parsed > 1.0) {
                saveConfirmedResult(parsed);
            }
            wasMultiplierActive = false;
            lastValidMultiplier = "";
        }
    }

    private double parseMultiplier(String text) {
        try {
            String clean = text.replace("x", "").replace("X", "").trim();
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
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        if (windowManager != null) {
            if (floatingButtonView != null) windowManager.removeView(floatingButtonView);
            if (cropOverlayView != null) windowManager.removeView(cropOverlayView);
        }
    }
}
