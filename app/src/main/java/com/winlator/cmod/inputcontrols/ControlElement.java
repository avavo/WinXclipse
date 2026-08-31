package com.winlator.cmod.inputcontrols;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Color;

import androidx.core.graphics.ColorUtils;

import com.winlator.cmod.core.CubicBezierInterpolator;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

public class ControlElement {
    public static final float STICK_DEAD_ZONE = 0.15f;
    public static final float DPAD_DEAD_ZONE = 0.3f;
    public static final float STICK_SENSITIVITY = 3.0f;
    public static final float TRACKPAD_MIN_SPEED = 0.8f;
    public static final float TRACKPAD_MAX_SPEED = 20.0f;
    public static final byte TRACKPAD_ACCELERATION_THRESHOLD = 4;
    public static final short BUTTON_MIN_TIME_TO_KEEP_PRESSED = 300;
    public enum Type {
        BUTTON, D_PAD, RANGE_BUTTON, STICK, TRACKPAD;

        public static String[] names() {
            Type[] types = values();
            String[] names = new String[types.length];
            for (int i = 0; i < types.length; i++) names[i] = types[i].name().replace("_", "-");
            return names;
        }
    }
    public enum Shape {
        CIRCLE, RECT, ROUND_RECT, SQUARE;

        public static String[] names() {
            Shape[] shapes = values();
            String[] names = new String[shapes.length];
            for (int i = 0; i < shapes.length; i++) names[i] = shapes[i].name().replace("_", " ");
            return names;
        }
    }
    public enum Range {
        FROM_A_TO_Z(26), FROM_0_TO_9(10), FROM_F1_TO_F12(12), FROM_NP0_TO_NP9(10);
        public final byte max;

        Range(int max) {
            this.max = (byte)max;
        }

        public static String[] names() {
            Range[] ranges = values();
            String[] names = new String[ranges.length];
            for (int i = 0; i < ranges.length; i++) names[i] = ranges[i].name().replace("_", " ");
            return names;
        }
    }
    private final InputControlsView inputControlsView;
    private Type type = Type.BUTTON;
    private Shape shape = Shape.CIRCLE;
    private Binding[] bindings = {Binding.NONE, Binding.NONE, Binding.NONE, Binding.NONE};
    private float scale = 1.0f;
    private short x;
    private short y;
    private boolean selected = false;
    private boolean toggleSwitch = false;
    private int currentPointerId = -1;
    private final Rect boundingBox = new Rect();
    private boolean[] states = new boolean[4];
    private boolean boundingBoxNeedsUpdate = true;
    private String text = "";
    private byte iconId;
    private Range range;
    private byte orientation;
    private PointF currentPosition;
    private RangeScroller scroller;
    private CubicBezierInterpolator interpolator;
    private Object touchTime;

    private final PointF touchDownOrigin = new PointF();

    /* reusable render state: keeps the 60 fps overlay allocation-free */
    private static final int SHADER_SLOT_BODY = 0;
    private static final int SHADER_SLOT_KNOB = 1;
    private final RadialGradient[] cachedShaders = new RadialGradient[2];
    private final boolean[] cachedShaderPressed = new boolean[2];
    private final float[] cachedShaderRadius = new float[]{-1f, -1f};
    private final Matrix shaderMatrix = new Matrix();
    private final RectF petalRect = new RectF();
    private final Rect iconSrcRect = new Rect();
    private final Rect iconDstRect = new Rect();
    private String displayTextCache;
    /* text-layout cache: measureText is expensive and the label/width rarely change */
    private String cachedTextMetricsKey;
    private float cachedTextSizeValue = -1f;
    private static final int HALO_ALPHA = 100;
    private static final float HALO_RADIUS = 35f;

    public ControlElement(InputControlsView inputControlsView) {
        this.inputControlsView = inputControlsView;
    }

    private void reset() {
        setBinding(Binding.NONE);
        scroller = null;

        if (type == Type.D_PAD || type == Type.STICK) {
            bindings[0] = Binding.KEY_W;
            bindings[1] = Binding.KEY_D;
            bindings[2] = Binding.KEY_S;
            bindings[3] = Binding.KEY_A;
        }
        else if (type == Type.TRACKPAD) {
            bindings[0] = Binding.MOUSE_MOVE_UP;
            bindings[1] = Binding.MOUSE_MOVE_RIGHT;
            bindings[2] = Binding.MOUSE_MOVE_DOWN;
            bindings[3] = Binding.MOUSE_MOVE_LEFT;
        }
        else if (type == Type.RANGE_BUTTON) {
            scroller = new RangeScroller(inputControlsView, this);
        }

        text = "";
        iconId = 0;
        range = null;
        displayTextCache = null;
        boundingBoxNeedsUpdate = true;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
        reset();
    }

    public int getBindingCount() {
        return bindings.length;
    }

    public void setBindingCount(int bindingCount) {
        bindings = new Binding[bindingCount];
        setBinding(Binding.NONE);
        states = new boolean[bindingCount];
        boundingBoxNeedsUpdate = true;
    }

    public Shape getShape() {
        return shape;
    }

    public void setShape(Shape shape) {
        this.shape = shape;
        boundingBoxNeedsUpdate = true;
    }

    public Range getRange() {
        return range != null ? range : Range.FROM_A_TO_Z;
    }

    public void setRange(Range range) {
        this.range = range;
    }

    public byte getOrientation() {
        return orientation;
    }

    public void setOrientation(byte orientation) {
        this.orientation = orientation;
        boundingBoxNeedsUpdate = true;
    }

    public boolean isToggleSwitch() {
        return toggleSwitch;
    }

    public void setToggleSwitch(boolean toggleSwitch) {
        this.toggleSwitch = toggleSwitch;
    }

    public Binding getBindingAt(int index) {
        return index < bindings.length ? bindings[index] : Binding.NONE;
    }

    public void setBindingAt(int index, Binding binding) {
        if (index >= bindings.length) {
            int oldLength = bindings.length;
            bindings = Arrays.copyOf(bindings, index+1);
            Arrays.fill(bindings, oldLength, bindings.length, Binding.NONE);
            states = new boolean[bindings.length];
            boundingBoxNeedsUpdate = true;
        }
        if (index == 0 && bindings[0] != binding) displayTextCache = null;
        bindings[index] = binding;
    }

    public void setBinding(Binding binding) {
        Arrays.fill(bindings, binding);
        displayTextCache = null;
    }

    public Binding[] snapshotBindings() {
        return bindings.clone();
    }

    public void restoreBindings(Binding[] saved) {
        if (saved != null && saved.length == bindings.length) {
            System.arraycopy(saved, 0, bindings, 0, saved.length);
            displayTextCache = null;
        }
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = scale;
        boundingBoxNeedsUpdate = true;
    }

    public short getX() {
        return x;
    }

    public void setX(int x) {
        this.x = (short)x;
        boundingBoxNeedsUpdate = true;
    }

    public short getY() {
        return y;
    }

    public void setY(int y) {
        this.y = (short)y;
        boundingBoxNeedsUpdate = true;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text != null ? text : "";
        displayTextCache = null;
    }

    public byte getIconId() {
        return iconId;
    }

    public void setIconId(int iconId) {
        this.iconId = (byte)iconId;
    }

    public Rect getBoundingBox() {
        if (boundingBoxNeedsUpdate) computeBoundingBox();
        return boundingBox;
    }

    private Rect computeBoundingBox() {
        int snappingSize = inputControlsView.getSnappingSize();
        int halfWidth = 0;
        int halfHeight = 0;

        switch (type) {
            case BUTTON:
                switch (shape) {
                    case RECT:
                    case ROUND_RECT:
                        halfWidth = snappingSize * 4;
                        halfHeight = snappingSize * 2;
                        break;
                    case SQUARE:
                        halfWidth = (int)(snappingSize * 2.5f);
                        halfHeight = (int)(snappingSize * 2.5f);
                        break;
                    case CIRCLE:
                        halfWidth = snappingSize * 3;
                        halfHeight = snappingSize * 3;
                        break;
                }
                break;
            case D_PAD: {
                halfWidth = snappingSize * 7;
                halfHeight = snappingSize * 7;
                break;
            }
            case TRACKPAD:
            case STICK: {
                halfWidth = snappingSize * 6;
                halfHeight = snappingSize * 6;
                break;
            }
            case RANGE_BUTTON: {
                halfWidth = snappingSize * ((bindings.length * 4) / 2);
                halfHeight = snappingSize * 2;

                if (orientation == 1) {
                    int tmp = halfWidth;
                    halfWidth = halfHeight;
                    halfHeight = tmp;
                }
                break;
            }
        }

        halfWidth *= scale;
        halfHeight *= scale;
        boundingBox.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
        boundingBoxNeedsUpdate = false;
        return boundingBox;
    }



    private String getDisplayText() {
        /* cached: called for every labelled button on every frame */
        if (displayTextCache != null) return displayTextCache;
        if (text != null && !text.isEmpty()) {
            displayTextCache = text;
            return displayTextCache;
        }
        else {
            Binding binding = getBindingAt(0);
            String text = binding.toString().replace("NUMPAD ", "NP").replace("BUTTON ", "");
            if (text.length() > 7) {
                String[] parts = text.split(" ");
                StringBuilder sb = new StringBuilder();
                for (String part : parts) sb.append(part.charAt(0));
                displayTextCache = (binding.isMouse() ? "M" : "")+ sb;
            }
            else displayTextCache = text;
            return displayTextCache;
        }
    }

    private static float getTextSizeForWidth(Paint paint, String text, float desiredWidth) {
        final byte testTextSize = 48;
        paint.setTextSize(testTextSize);
        return testTextSize * desiredWidth / paint.measureText(text);
    }

    private static String getRangeTextForIndex(Range range, int index) {
        String text = "";
        switch (range) {
            case FROM_A_TO_Z:
                text = String.valueOf((char)(65 + index));
                break;
            case FROM_0_TO_9:
                text = String.valueOf((index + 1) % 10);
                break;
            case FROM_F1_TO_F12:
                text = "F"+(index + 1);
                break;
            case FROM_NP0_TO_NP9:
                text = "NP"+((index + 1) % 10);
                break;
        }
        return text;
    }

    public void draw(Canvas canvas) {
        int snappingSize = inputControlsView.getSnappingSize();
        Paint paint = inputControlsView.getPaint();
        int primaryColor = inputControlsView.getPrimaryColor();

        int fillColor = ColorUtils.setAlphaComponent(primaryColor, 70);

        paint.setColor(selected ? inputControlsView.getSecondaryColor() : primaryColor);
        paint.setStyle(Paint.Style.STROKE);
        float strokeWidth = snappingSize * 0.10f;
        paint.setStrokeWidth(strokeWidth);
        Rect boundingBox = getBoundingBox();

        switch (type) {
            case BUTTON: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                boolean pressed = isEngaged();
                float halfStroke = strokeWidth * 0.5f;

                /* Light glassy body with radial gradient */
                paint.setStyle(Paint.Style.FILL);
                applyBodyShader(paint, SHADER_SLOT_BODY, cx, cy,
                        Math.max(boundingBox.width(), boundingBox.height()) * 0.7f, pressed);
                switch (shape) {
                    case CIRCLE:
                        canvas.drawCircle(cx, cy, boundingBox.width() * 0.5f - halfStroke, paint);
                        break;
                    case RECT: {
                         float r = snappingSize * 0.90f * scale;
                         canvas.drawRoundRect( boundingBox.left + halfStroke, boundingBox.top + halfStroke,
                           boundingBox.right - halfStroke, boundingBox.bottom - halfStroke, r, r, paint);
                         break;
}
                    case ROUND_RECT: {
                        float r = boundingBox.height() * 0.5f - halfStroke;
                        canvas.drawRoundRect(boundingBox.left + halfStroke, boundingBox.top + halfStroke,
                                boundingBox.right - halfStroke, boundingBox.bottom - halfStroke, r, r, paint);
                        break;
                    }
                    case SQUARE: {
                        float r = snappingSize * 0.75f * scale;
                        canvas.drawRoundRect(boundingBox.left + halfStroke, boundingBox.top + halfStroke,
                                boundingBox.right - halfStroke, boundingBox.bottom - halfStroke, r, r, paint);
                        break;
                    }
                }
                paint.setShader(null);

                /* thin light border */
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(selected ? inputControlsView.getSecondaryColor()
                        : inputControlsView.getControlBorderColor(pressed));
                paint.setStrokeWidth(strokeWidth);
                switch (shape) {
                    case CIRCLE:
                        canvas.drawCircle(cx, cy, boundingBox.width() * 0.5f, paint);
                        break;
                    case RECT: {
                        float r = snappingSize * 0.75f * scale;
                        canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, r, r, paint);
                        break;
                    }
                    case ROUND_RECT: {
                        float r = boundingBox.height() * 0.5f;
                        canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, r, r, paint);
                        break;
                    }
                    case SQUARE: {
                        float r = snappingSize * 0.75f * scale;
                        canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, r, r, paint);
                        break;
                    }
                }

                if (iconId > 0) {
                    drawIcon(canvas, cx, cy, boundingBox.width(), boundingBox.height(), iconId);
                }
                else {
                    String text = getDisplayText();
                    paint.setFakeBoldText(true);
                    float targetWidth = boundingBox.width() - strokeWidth * 2;
                    String metricsKey = text + "|" + ((int) targetWidth);
                    if (!metricsKey.equals(cachedTextMetricsKey)) {
                        cachedTextSizeValue = Math.min(getTextSizeForWidth(paint, text, targetWidth), snappingSize * 2 * scale);
                        cachedTextMetricsKey = metricsKey;
                    }
                    paint.setTextSize(cachedTextSizeValue);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(primaryColor);
                    canvas.drawText(text, x, (y - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                    paint.setFakeBoldText(false);
                }
                break;
            }
            case D_PAD: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                float halfStroke = strokeWidth * 0.5f;
                float size = Math.min(boundingBox.width(), boundingBox.height());
                float petalWidth = size * 0.32f;
                float gap = size * 0.070f;
                float cornerRadius = petalWidth * 0.45f;
                boolean engaged = isEngaged();

                RectF petal = petalRect;
                petal.set(cx - petalWidth * 0.5f, boundingBox.top + halfStroke,
                        cx + petalWidth * 0.5f, cy - gap);
                drawDPadPetal(canvas, paint, snappingSize, petal, cornerRadius,
                        engaged && states[0], selected, (byte)0, primaryColor);

                petal.set(cx - petalWidth * 0.5f, cy + gap,
                        cx + petalWidth * 0.5f, boundingBox.bottom - halfStroke);
                drawDPadPetal(canvas, paint, snappingSize, petal, cornerRadius,
                        engaged && states[2], selected, (byte)2, primaryColor);

                petal.set(boundingBox.left + halfStroke, cy - petalWidth * 0.5f,
                        cx - gap, cy + petalWidth * 0.5f);
                drawDPadPetal(canvas, paint, snappingSize, petal, cornerRadius,
                        engaged && states[3], selected, (byte)3, primaryColor);

                petal.set(cx + gap, cy - petalWidth * 0.5f,
                        boundingBox.right - halfStroke, cy + petalWidth * 0.5f);
                drawDPadPetal(canvas, paint, snappingSize, petal, cornerRadius,
                        engaged && states[1], selected, (byte)1, primaryColor);
                break;
            }
            case RANGE_BUTTON: {
                Range range = getRange();
                int oldColor = paint.getColor();
                float radius = snappingSize * 0.75f * scale;

                if (isEngaged()) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(fillColor);
                    canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                }

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(oldColor);
                canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);

                float elementSize = scroller.getElementSize();
                float minTextSize = snappingSize * 2 * scale;
                float scrollOffset = scroller.getScrollOffset();
                byte[] rangeIndex = scroller.getRangeIndex();
                Path path = inputControlsView.getPath();
                path.reset();

                if (orientation == 0) {
                    float lineTop = boundingBox.top + strokeWidth * 0.5f;
                    float lineBottom = boundingBox.bottom - strokeWidth * 0.5f;
                    float startX = boundingBox.left;
//                    canvas.drawRoundRect(startX, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);

                    canvas.save();
                    path.addRoundRect(startX, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, Path.Direction.CW);
                    canvas.clipPath(path);
                    startX -= scrollOffset % elementSize;

                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        int index = i % range.max;
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(oldColor);

                        if (startX > boundingBox.left && startX  < boundingBox.right) canvas.drawLine(startX, lineTop, startX, lineBottom, paint);
                        String text = getRangeTextForIndex(range, index);

                        if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(primaryColor);
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, elementSize - strokeWidth * 2), minTextSize));
                            paint.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text, startX + elementSize * 0.5f, (y - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                        }
                        startX += elementSize;
                    }

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(oldColor);
                    canvas.restore();
                }
                else {
                    float lineLeft = boundingBox.left + strokeWidth * 0.5f;
                    float lineRight = boundingBox.right - strokeWidth * 0.5f;
                    float startY = boundingBox.top;
//                    canvas.drawRoundRect(boundingBox.left, startY, boundingBox.right, boundingBox.bottom, radius, radius, paint);

                    canvas.save();
                    path.addRoundRect(boundingBox.left, startY, boundingBox.right, boundingBox.bottom, radius, radius, Path.Direction.CW);
                    canvas.clipPath(inputControlsView.getPath());
                    startY -= scrollOffset % elementSize;

                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(oldColor);

                        if (startY > boundingBox.top && startY < boundingBox.bottom) canvas.drawLine(lineLeft, startY, lineRight, startY, paint);
                        int index = i % range.max;
                        String text = getRangeTextForIndex(range, index);

                        if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(primaryColor);
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2), minTextSize));
                            paint.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text, x, startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f), paint);
                        }
                        startY += elementSize;
                    }

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(oldColor);
                    canvas.restore();
                }
                break;
            }
            case STICK: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                float radius = boundingBox.width() * 0.5f - strokeWidth * 0.5f;
                boolean pressed = isEngaged();

                /* dark base with radial gradient */
                paint.setStyle(Paint.Style.FILL);
                applyBodyShader(paint, SHADER_SLOT_BODY, cx, cy, radius, pressed);
                canvas.drawCircle(cx, cy, radius, paint);
                paint.setShader(null);

                /* thin light border ring */
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(selected ? inputControlsView.getSecondaryColor()
                        : inputControlsView.getControlBorderColor(pressed));
                canvas.drawCircle(cx, cy, radius, paint);

                /* tick marks at N/S/E/W inside the rim */
                float tickInner = radius * 0.76f;
                float tickOuter = radius * 0.88f;
                paint.setStrokeWidth(strokeWidth * 1.1f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(primaryColor);
                canvas.drawLine(cx, cy - tickOuter, cx, cy - tickInner, paint);
                canvas.drawLine(cx + tickOuter, cy, cx + tickInner, cy, paint);
                canvas.drawLine(cx, cy + tickOuter, cx, cy + tickInner, paint);
                canvas.drawLine(cx - tickOuter, cy, cx - tickInner, cy, paint);

                /* knob */
                PointF position = getCurrentPosition();
                float thumbRadius = snappingSize * 3.5f * scale;

                paint.setStyle(Paint.Style.FILL);
                applyBodyShader(paint, SHADER_SLOT_KNOB, position.x, position.y, thumbRadius * 1.4f, pressed);
                canvas.drawCircle(position.x, position.y, thumbRadius, paint);
                paint.setShader(null);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setStrokeWidth(strokeWidth);
                paint.setColor(selected ? inputControlsView.getSecondaryColor()
                        : inputControlsView.getControlBorderColor(pressed));
                canvas.drawCircle(position.x, position.y, thumbRadius + strokeWidth * 0.25f, paint);
                break;
            }

            case TRACKPAD: {
                float radius = boundingBox.height() * 0.15f;
                float halfStroke = strokeWidth * 0.5f;
                boolean pressed = isEngaged();

                paint.setStyle(Paint.Style.FILL);
                applyBodyShader(paint, SHADER_SLOT_BODY, boundingBox.centerX(), boundingBox.centerY(),
                        Math.max(boundingBox.width(), boundingBox.height()) * 0.7f, pressed);
                canvas.drawRoundRect(boundingBox.left + halfStroke, boundingBox.top + halfStroke,
                        boundingBox.right - halfStroke, boundingBox.bottom - halfStroke, radius, radius, paint);
                paint.setShader(null);

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(selected ? inputControlsView.getSecondaryColor()
                        : inputControlsView.getControlBorderColor(pressed));
                canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                break;
            }
        }
    }

    private void applyBodyShader(Paint paint, int slot, float cx, float cy, float radius, boolean pressed) {
        RadialGradient shader = cachedShaders[slot];
        if (shader == null || cachedShaderPressed[slot] != pressed || cachedShaderRadius[slot] != radius) {
            shader = new RadialGradient(0, 0, radius,
                    inputControlsView.getControlBodyCenterColor(pressed),
                    inputControlsView.getControlBodyEdgeColor(pressed),
                    Shader.TileMode.CLAMP);
            cachedShaders[slot] = shader;
            cachedShaderPressed[slot] = pressed;
            cachedShaderRadius[slot] = radius;
        }
        /* the gradient is built around the origin; translate it into place */
        shaderMatrix.setTranslate(cx, cy);
        shader.setLocalMatrix(shaderMatrix);
        paint.setShader(shader);
    }

    /** One rounded d-pad petal with an outward chevron arrow (directions: 0 up, 1 right, 2 down, 3 left). */
    private void drawDPadPetal(Canvas canvas, Paint paint, int snappingSize, RectF petal,
                               float cornerRadius, boolean pressed, boolean selected,
                               byte direction, int arrowColor) {
        /* body */
        paint.setStyle(Paint.Style.FILL);
        applyBodyShader(paint, SHADER_SLOT_BODY, petal.centerX(), petal.centerY(),
                Math.max(petal.width(), petal.height()) * 0.7f, pressed);
        canvas.drawRoundRect(petal, cornerRadius, cornerRadius, paint);
        paint.setShader(null);

        //Halo
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.WHITE);
        paint.setAlpha(100);
        paint.setShadowLayer(35, 0, 0, Color.WHITE);
         canvas.drawRoundRect(petal, cornerRadius, cornerRadius, paint);
        paint.clearShadowLayer();
        

        /* thin light border */
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(snappingSize * 0.10f);
        paint.setColor(selected ? inputControlsView.getSecondaryColor()
                : inputControlsView.getControlBorderColor(pressed));
        canvas.drawRoundRect(petal, cornerRadius, cornerRadius, paint);

        /* outward chevron arrow */
        float cx = petal.centerX();
        float cy = petal.centerY();
        float arm = Math.min(petal.width(), petal.height()) * 0.24f;
        float arrowOffset = Math.min(petal.width(), petal.height()) * 0.20f;
        Path path = inputControlsView.getPath();
        path.reset();
        switch (direction) {
            case 0: //UP
                path.moveTo(cx - arm, cy + arm * 0.6f - arrowOffset);
                path.lineTo(cx, cy - arm * 0.6f - arrowOffset);
                path.lineTo(cx + arm, cy + arm * 0.6f - arrowOffset);
                break;
            case 1: //RIGHT
                path.moveTo(cx - arm * 0.6f + arrowOffset, cy - arm);
                path.lineTo(cx + arm * 0.6f + arrowOffset, cy);
                path.lineTo(cx - arm * 0.6f + arrowOffset, cy + arm);
                break;
            case 2: //DOWN
                path.moveTo(cx - arm, cy - arm * 0.6f + arrowOffset);
                path.lineTo(cx, cy + arm * 0.6f + arrowOffset);
                path.lineTo(cx + arm, cy - arm * 0.6f + arrowOffset);
                break;
            default: //LEFT
                path.moveTo(cx + arm * 0.6f - arrowOffset, cy - arm);
                path.lineTo(cx - arm * 0.6f - arrowOffset, cy);
                path.lineTo(cx + arm * 0.6f - arrowOffset, cy + arm);
                break;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(arrowColor);
        canvas.drawPath(path, paint);
    }

    private void drawIcon(Canvas canvas, float cx, float cy, float width, float height, int iconId) {
        Paint paint = inputControlsView.getPaint();
        Bitmap icon = inputControlsView.getIcon((byte)iconId);
        paint.setColorFilter(inputControlsView.getColorFilter());
        int margin = (int)(inputControlsView.getSnappingSize() * (shape == Shape.CIRCLE || shape == Shape.SQUARE ? 2.0f : 1.0f) * scale);
        int halfSize = (int)((Math.min(width, height) - margin) * 0.5f);

        iconSrcRect.set(0, 0, icon.getWidth(), icon.getHeight());
        iconDstRect.set((int)(cx - halfSize), (int)(cy - halfSize), (int)(cx + halfSize), (int)(cy + halfSize));
        canvas.drawBitmap(icon, iconSrcRect, iconDstRect, paint);
        paint.setColorFilter(null);
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject elementJSONObject = new JSONObject();
            elementJSONObject.put("type", type.name());
            elementJSONObject.put("shape", shape.name());

            JSONArray bindingsJSONArray = new JSONArray();
            for (Binding binding : bindings) bindingsJSONArray.put(binding.name());

            elementJSONObject.put("bindings", bindingsJSONArray);
            elementJSONObject.put("scale", Float.valueOf(scale));
            elementJSONObject.put("x", (float)x / inputControlsView.getMaxWidth());
            elementJSONObject.put("y", (float)y / inputControlsView.getMaxHeight());
            elementJSONObject.put("toggleSwitch", toggleSwitch);
            elementJSONObject.put("text", text);
            elementJSONObject.put("iconId", iconId);

            if (type == Type.RANGE_BUTTON && range != null) {
                elementJSONObject.put("range", range.name());
                if (orientation != 0) elementJSONObject.put("orientation", orientation);
            }
            return elementJSONObject;
        }
        catch (JSONException e) {
            return null;
        }
    }

    public boolean containsPoint(float x, float y) {
        return getBoundingBox().contains((int)(x + 0.5f), (int)(y + 0.5f));
    }

    private boolean isKeepButtonPressedAfterMinTime() {
        Binding binding = getBindingAt(0);
        return !toggleSwitch && (binding == Binding.GAMEPAD_BUTTON_L3 || binding == Binding.GAMEPAD_BUTTON_R3);
    }

    public boolean handleTouchDown(int pointerId, float x, float y) {
        /* reject if another finger already owns this element */
        if (currentPointerId != -1 || !containsPoint(x, y)) return false;

        currentPointerId = pointerId;

        /* ---------- Button and Range-Button ---------- */
        if (type == Type.BUTTON || type == Type.RANGE_BUTTON) {
            if (isKeepButtonPressedAfterMinTime()) touchTime = System.currentTimeMillis();

            if (!toggleSwitch || !selected) {                      // press only when not latched
                inputControlsView.handleInputEvent(getBindingAt(0), true);
            }

            if (type == Type.RANGE_BUTTON) scroller.handleTouchDown(x, y);

            inputControlsView.invalidate();
            return true;
        }

        /* ---------- Stick ---------- */
        if (type == Type.STICK) {
            // Record where the finger landed so relative motion is smooth.
            touchDownOrigin.set(x, y);

            // Force neutral value on first frame.
            handleTouchMove(pointerId, x, y);

            // Visually centre the knob.
            Rect bb = getBoundingBox();
            setCurrentPosition(bb.centerX(), bb.centerY());

            inputControlsView.invalidate();
            return true;
        }

        /* ---------- Track-pad, D-pad, etc. ---------- */
        if (type == Type.TRACKPAD) {
            if (currentPosition == null) currentPosition = new PointF();
            currentPosition.set(x, y);
        }
        return handleTouchMove(pointerId, x, y);
    }

    public boolean handleTouchMove(int pointerId, float x, float y) {
        if (pointerId == currentPointerId && (type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD)) {
            float deltaX, deltaY;
            Rect boundingBox = getBoundingBox();
            float radius = boundingBox.width() * 0.5f;
            TouchpadView touchpadView =  inputControlsView.getTouchpadView();

            if (type == Type.TRACKPAD) {
                if (currentPosition == null) currentPosition = new PointF();
                float[] deltaPoint = touchpadView.computeDeltaPoint(currentPosition.x, currentPosition.y, x, y);
                deltaX = deltaPoint[0];
                deltaY = deltaPoint[1];
                currentPosition.set(x, y);
            }
            else {
                float localX = x - boundingBox.left;
                float localY = y - boundingBox.top;
                float offsetX = localX - radius;
                float offsetY = localY - radius;

                float distance = Mathf.lengthSq(radius - localX, radius - localY);
                if (distance > radius * radius) {
                    float angle = (float)Math.atan2(offsetY, offsetX);
                    offsetX = (float)(Math.cos(angle) * radius);
                    offsetY = (float)(Math.sin(angle) * radius);
                }

                deltaX = Mathf.clamp(offsetX / radius, -1, 1);
                deltaY = Mathf.clamp(offsetY / radius, -1, 1);

                float magnitude = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                if (magnitude > 1.0f) {
                    deltaX /= magnitude;
                    deltaY /= magnitude;
                }
            }

            if (type == Type.STICK) {
                // --- START OF STICK-SPECIFIC LOGIC ---

                // 1. Calculate offset from the initial touch point for relative movement.
                float offsetX = x - touchDownOrigin.x;
                float offsetY = y - touchDownOrigin.y;

                // 2. Constrain the movement to a circular area.
                float distanceSq = offsetX * offsetX + offsetY * offsetY;
                if (distanceSq > radius * radius) {
                    float magnitude = (float) Math.sqrt(distanceSq);
                    offsetX = (offsetX / magnitude) * radius;
                    offsetY = (offsetY / magnitude) * radius;
                }

                // 3. Update the visual position of the stick's knob.
                if (currentPosition == null) currentPosition = new PointF();
                currentPosition.x = boundingBox.centerX() + offsetX;
                currentPosition.y = boundingBox.centerY() + offsetY;

                // 4. Calculate the final -1.0 to 1.0 logical values.
                deltaX = offsetX / radius;
                deltaY = offsetY / radius;

                // 5. Send the input events to the game.
                final boolean[] states = {deltaY <= -STICK_DEAD_ZONE, deltaX >= STICK_DEAD_ZONE, deltaY >= STICK_DEAD_ZONE, deltaX <= -STICK_DEAD_ZONE};

                for (byte i = 0; i < 4; i++) {
                    float value = (i == 1 || i == 3) ? deltaX : deltaY;
                    Binding binding = getBindingAt(i);
                    if (binding.isGamepad()) {
                        value = Mathf.clamp(Math.max(0, Math.abs(value) - 0.01f) * Mathf.sign(value) * STICK_SENSITIVITY, -1, 1);
                        inputControlsView.handleInputEvent(binding, true, value);
                        this.states[i] = true;
                    } else {
                        boolean state = binding.isMouseMove() ? (states[i] || states[(i + 2) % 4]) : states[i];
                        inputControlsView.handleInputEvent(binding, state, value);
                        this.states[i] = state;
                    }
                }

                inputControlsView.invalidate();
            }
            else if (type == Type.TRACKPAD) {
                final boolean[] states = {deltaY <= -TRACKPAD_MIN_SPEED, deltaX >= TRACKPAD_MIN_SPEED, deltaY >= TRACKPAD_MIN_SPEED, deltaX <= -TRACKPAD_MIN_SPEED};
                int cursorDx = 0;
                int cursorDy = 0;

                for (byte i = 0; i < 4; i++) {
                    float value = (i == 1 || i == 3 ? deltaX : deltaY);
                    Binding binding = getBindingAt(i);
                    if (binding.isGamepad()) {
                        if (interpolator == null) interpolator = new CubicBezierInterpolator();
                        if (Math.abs(value) > TRACKPAD_ACCELERATION_THRESHOLD) value *= STICK_SENSITIVITY;
                        interpolator.set(0.075f, 0.95f, 0.45f, 0.95f);
                        float interpolatedValue = interpolator.getInterpolation(Math.min(1.0f, Math.abs(value / TRACKPAD_MAX_SPEED)));
                        inputControlsView.handleInputEvent(binding, true, Mathf.clamp(interpolatedValue * Mathf.sign(value), -1, 1));
                        this.states[i] = true;
                    }
                    else {
                        if (Math.abs(value) > TouchpadView.CURSOR_ACCELERATION_THRESHOLD) value *= TouchpadView.CURSOR_ACCELERATION;
                        if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                            cursorDx = Mathf.roundPoint(value);
                        }
                        else if (binding == Binding.MOUSE_MOVE_UP || binding == Binding.MOUSE_MOVE_DOWN) {
                            cursorDy = Mathf.roundPoint(value);
                        }
                        else {
                            inputControlsView.handleInputEvent(binding, states[i], value);
                            this.states[i] = states[i];
                        }
                    }
                }

                if (cursorDx != 0 || cursorDy != 0)  {
                    XServer xServer = inputControlsView.getXServer();
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, cursorDx, cursorDy, 0);
                    else
                        inputControlsView.getXServer().injectPointerMoveDelta(cursorDx, cursorDy);
                }
            }
            else {
                final boolean[] states = {deltaY <= -DPAD_DEAD_ZONE, deltaX >= DPAD_DEAD_ZONE, deltaY >= DPAD_DEAD_ZONE, deltaX <= -DPAD_DEAD_ZONE};

                for (byte i = 0; i < 4; i++) {
                    float value = i == 1 || i == 3 ? deltaX : deltaY;
                    Binding binding = getBindingAt(i);
                    boolean state = binding.isMouseMove() ? (states[i] || states[(i+2)%4]) : states[i];
                    inputControlsView.handleInputEvent(binding, state, value);
                    this.states[i] = state;
                }
            }

            inputControlsView.invalidate();
            return true;
        }
        else if (pointerId == currentPointerId && type == Type.RANGE_BUTTON) {
            scroller.handleTouchMove(x, y);
            return true;
        }
        else return false;
    }

    public boolean handleTouchUp(int pointerId) {
        if (pointerId != currentPointerId) return false;

        /* ========= BUTTON & RANGE_BUTTON ========= */
        if (type == Type.BUTTON || type == Type.RANGE_BUTTON) {
            final Binding binding = getBindingAt(0);
            final long    now     = System.currentTimeMillis();

            /* honour min-hold rule for L3/R3 */
            if (isKeepButtonPressedAfterMinTime() && touchTime != null) {
                long held  = now - (long) touchTime;
                long delay = Math.max(0, BUTTON_MIN_TIME_TO_KEEP_PRESSED - held);
                touchTime  = null;

                inputControlsView.postDelayed(() -> {
                    inputControlsView.handleInputEvent(binding, false);
                    inputControlsView.invalidate();
                }, delay);
            } else {
                // For toggles send release only if we were latched; otherwise always.
                if (!toggleSwitch || selected) {
                    inputControlsView.handleInputEvent(binding, false);
                }
            }

            /* ----- toggle-latch behaviour ----- */
            if (toggleSwitch) selected = !selected;

            if (type == Type.RANGE_BUTTON) {
                scroller.handleTouchUp();
            }

            currentPointerId = -1;
            inputControlsView.invalidate();
            return true;
        }

        /* ========= D-PAD / STICK / TRACKPAD ========= */
        else if (type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD) {
            for (byte i = 0; i < states.length; i++) {
                if (states[i]) inputControlsView.handleInputEvent(getBindingAt(i), false);
                states[i] = false;
            }

            if (type == Type.STICK) {
                inputControlsView.invalidate();   // redraw knob to centre
            }

            if (currentPosition != null) currentPosition = null;
        }

        currentPointerId = -1;
        inputControlsView.invalidate();
        return true;
    }

    public PointF getCurrentPosition() {
        if (currentPosition == null) {
            currentPosition = new PointF(x, y); // Initialize to the center (same as outer circle)
        }
        return currentPosition;
    }

    // New setter for current position to allow resetting
    public void setCurrentPosition(float x, float y) {
        if (currentPosition == null) {
            currentPosition = new PointF();
        }
        currentPosition.set(x, y);
        // Optionally invalidate the view to trigger a redraw
        inputControlsView.invalidate();
    }

    private boolean anyStateActive() {
        for (boolean b : states) if (b) return true;
        return false;
    }

    private boolean isEngaged() {
        if (type == Type.BUTTON || type == Type.RANGE_BUTTON) {
            return currentPointerId != -1 || selected;   // include toggle state
        }
        return currentPointerId != -1 || anyStateActive();
    }


}
