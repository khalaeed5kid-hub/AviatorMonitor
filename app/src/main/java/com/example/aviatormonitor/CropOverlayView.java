package com.example.aviatormonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

public class CropOverlayView extends View {
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
        bgPaint.setColor(Color.argb(140, 0, 0, 0));

        rectPaint = new Paint();
        rectPaint.setColor(Color.YELLOW);
        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(6f);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(45f);
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
            canvas.drawText("Release to set Aviator area", 50, 150, textPaint);
        } else {
            canvas.drawText("Drag finger to select Aviator multiplier area", 50, 150, textPaint);
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

                if (listener != null && selectedRect.width() > 30 && selectedRect.height() > 30) {
                    listener.onRegionSelected(selectedRect);
                }
                return true;
        }
        return false;
    }
}
