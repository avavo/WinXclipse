package com.winlator.cmod.xserver;

import android.util.SparseArray;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xserver.errors.BadIdChoice;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.ConfigureNotify;
import com.winlator.cmod.xserver.events.ConfigureRequest;
import com.winlator.cmod.xserver.events.DestroyNotify;
import com.winlator.cmod.xserver.events.Event;
import com.winlator.cmod.xserver.events.Expose;
import com.winlator.cmod.xserver.events.MapNotify;
import com.winlator.cmod.xserver.events.MapRequest;
import com.winlator.cmod.xserver.events.ResizeRequest;
import com.winlator.cmod.xserver.events.UnmapNotify;

import java.util.ArrayList;
import java.util.List;

public class WindowManager extends XResourceManager {
    public enum FocusRevertTo {NONE, POINTER_ROOT, PARENT}
    public final Window rootWindow;
    private final SparseArray<Window> windows = new SparseArray<>();
    public final DrawableManager drawableManager;
    private Window focusedWindow;
    private FocusRevertTo focusRevertTo = FocusRevertTo.NONE;
    private final ArrayList<OnWindowModificationListener> onWindowModificationListeners = new ArrayList<>();

    public interface OnWindowModificationListener {
        default void onMapWindow(Window window) {}

        default void onUnmapWindow(Window window) {}

        default void onChangeWindowZOrder(Window window) {}

        default void onUpdateWindowContent(Window window) {}

        default void onUpdateWindowGeometry(Window window, boolean resized) {}

        default void onUpdateWindowAttributes(Window window, Bitmask mask) {}

        default void onModifyWindowProperty(Window window, Property property) {}
    }

    public WindowManager(ScreenInfo screenInfo, DrawableManager drawableManager) {
        this.drawableManager = drawableManager;
        int id = IDGenerator.generate();
        Drawable drawable = drawableManager.createDrawable(id, screenInfo.width, screenInfo.height, drawableManager.getVisual());
        rootWindow = new Window(id, drawable, 0, 0, screenInfo.width, screenInfo.height, null);
        rootWindow.attributes.setMapped(true);
        windows.put(id, rootWindow);
    }

    public void resizeRootWindow(int width, int height) {
        if (rootWindow.getWidth() == width && rootWindow.getHeight() == height) return;
        Drawable oldContent = rootWindow.getContent();
        drawableManager.removeDrawable(oldContent.id);
        Drawable newContent = drawableManager.createDrawable(oldContent.id, (short)width, (short)height, oldContent.visual);
        newContent.setOnDrawListener(() -> triggerOnUpdateWindowContent(rootWindow));
        rootWindow.setContent(newContent);
        rootWindow.setWidth((short)width);
        rootWindow.setHeight((short)height);
        triggerOnUpdateWindowGeometry(rootWindow, true);
        rootWindow.sendEvent(new com.winlator.cmod.xserver.events.Expose(rootWindow));
        // Resize desktop / top-level windows that were created to fill the old desktop
        // (Wine explorer /desktop=shell,WxH). Without this the game's window stays
        // at 1280x720 inside a 640x360 root and the compositor stretches/clips it,
        // causing the "janela bugada" reported for RE2 after live FSR resize.
        Window desktopWindow = null;
        for (int i = 0; i < windows.size(); i++) {
            Window w = windows.valueAt(i);
            if (w == null || w == rootWindow) continue;
            if (!w.attributes.isMapped()) continue;
            Window parent = w.getParent();
            if (parent != rootWindow) continue;
            // Desktop/explorer window that should fill the root: resize it to new root
            if (w.getWidth() == oldContent.width && w.getHeight() == oldContent.height) {
                changeWindowGeometry(w, w.getX(), w.getY(), (short)width, (short)height);
                desktopWindow = w;
            }
        }
        // Also resize the game's window (child of the desktop) that was at the old
        // desktop resolution. This forces DXVK/VKD3D to recreate the swapchain at
        // the new guest size (640x360) so the FPS gain is real and the window
        // fills the screen correctly instead of being clipped or showing black bars.
        // Use isApplicationWindow to catch the actual game window even if its size
        // is slightly off due to decorations or previous FSR steps.
        Window targetParent = desktopWindow != null ? desktopWindow : rootWindow;
        for (int i = 0; i < windows.size(); i++) {
            Window w = windows.valueAt(i);
            if (w == null || w == rootWindow || w == desktopWindow) continue;
            if (!w.attributes.isMapped()) continue;
            if (w.getParent() != targetParent) continue;
            if (w.isApplicationWindow() || (w.getWidth() == oldContent.width && w.getHeight() == oldContent.height)) {
                changeWindowGeometry(w, (short)0, (short)0, (short)width, (short)height);
            }
        }
        // Fallback: any other application window anywhere in the tree that still
        // matches the old desktop size (e.g. override-redirect fullscreen window
        // that is a direct child of root but was missed above due to parent check).
        for (int i = 0; i < windows.size(); i++) {
            Window w = windows.valueAt(i);
            if (w == null || w == rootWindow || w == desktopWindow) continue;
            if (!w.isApplicationWindow()) continue;
            if (!w.attributes.isMapped()) continue;
            if (w.getWidth() == oldContent.width && w.getHeight() == oldContent.height) {
                changeWindowGeometry(w, w.getX(), w.getY(), (short)width, (short)height);
            }
        }
    }

    public Window getWindow(int id) {
        return windows.get(id);
    }

    public Window findWindowWithProcessId(int processId) {
        for (int i = 0; i < windows.size(); i++) {
            Window window = windows.valueAt(i);
            if (window != null && window.getProcessId() == processId) return window;
        }
        return null;
    }

    public void destroyWindow(int id) {
        Window window = getWindow(id);
        if (window != null && rootWindow.id != id) {
            unmapWindow(window);
            removeAllSubwindowsAndWindow(window);
        }
    }

    private void removeAllSubwindowsAndWindow(Window window) {
        List<Window> children = new ArrayList<>(window.getChildren());
        for (Window child : children) removeAllSubwindowsAndWindow(child);

        Window parent = window.getParent();
        window.sendEvent(Event.STRUCTURE_NOTIFY, new DestroyNotify(window, window));
        parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new DestroyNotify(parent, window));
        windows.remove(window.id);
        if (window.isInputOutput()) drawableManager.removeDrawable(window.getContent().id);
        triggerOnFreeResourceListener(window);
        if (window == focusedWindow) revertFocus();
        parent.removeChild(window);
    }

    public void mapWindow(Window window) {
        if (!window.attributes.isMapped()) {
            Window parent = window.getParent();
            if (!parent.hasEventListenerFor(Event.SUBSTRUCTURE_REDIRECT) || window.attributes.isOverrideRedirect()) {
                window.attributes.setMapped(true);
                window.sendEvent(Event.STRUCTURE_NOTIFY, new MapNotify(window, window));
                parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new MapNotify(parent, window));
                window.sendEvent(Event.EXPOSURE, new Expose(window));
                triggerOnMapWindow(window);
            }
            else parent.sendEvent(Event.SUBSTRUCTURE_REDIRECT, new MapRequest(parent, window));
        }
    }

    public void unmapWindow(Window window) {
        if (rootWindow.id != window.id && window.attributes.isMapped()) {
            window.attributes.setMapped(false);
            Window parent = window.getParent();
            window.sendEvent(Event.STRUCTURE_NOTIFY, new UnmapNotify(window, window));
            parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new UnmapNotify(parent, window));
            if (window == focusedWindow) revertFocus();
            triggerOnUnmapWindow(window);
        }
    }

    public Window getFocusedWindow() {
        return focusedWindow;
    }

    public void revertFocus() {
        switch (focusRevertTo) {
            case NONE:
                focusedWindow = null;
                break;
            case POINTER_ROOT:
                focusedWindow = rootWindow;
                break;
            case PARENT:
                if (focusedWindow.getParent() != null) focusedWindow = focusedWindow.getParent();
                break;
        }
    }

    public void setFocus(Window focusedWindow, FocusRevertTo focusRevertTo) {
        this.focusedWindow = focusedWindow;
        this.focusRevertTo = focusRevertTo;
    }

    public FocusRevertTo getFocusRevertTo() {
        return focusRevertTo;
    }

    public Window createWindow(int id, Window parent, short x, short y, short width, short height, WindowAttributes.WindowClass windowClass, Visual visual, byte depth, XClient client) throws XRequestError {
        if (windows.indexOfKey(id) >= 0) throw new BadIdChoice(id);

        boolean isInputOutput = false;
        switch (windowClass) {
            case COPY_FROM_PARENT:
                depth = (depth != 0 || !parent.isInputOutput()) ? depth : parent.getContent().visual.depth;
                isInputOutput = parent.isInputOutput();
                break;
            case INPUT_OUTPUT:
                if (parent.isInputOutput()) {
                    depth = depth == 0 ? parent.getContent().visual.depth : depth;
                    isInputOutput = true;
                } else throw new BadMatch();
                break;
            case INPUT_ONLY:
                isInputOutput = false;
                break;
        }

        if (isInputOutput) {
            visual = visual == null ? parent.getContent().visual : visual;
            if (depth != visual.depth) throw new BadMatch();
        }

        Drawable drawable = null;
        if (isInputOutput) {
            drawable = drawableManager.createDrawable(id, width, height, visual);
            if (drawable == null) throw new BadIdChoice(id);
        }

        final Window window = new Window(id, drawable, x, y, width, height, client);
        window.attributes.setWindowClass(windowClass);
        if (drawable != null) drawable.setOnDrawListener(() -> triggerOnUpdateWindowContent(window));
        windows.put(id, window);
        parent.addChild(window);
        triggerOnCreateResourceListener(window);
        return window;
    }

    private void changeWindowGeometry(Window window, short x, short y, short width, short height) {
        boolean resized = window.getWidth() != width || window.getHeight() != height;
        if (resized && window.hasEventListenerFor(Event.RESIZE_REDIRECT)) {
            window.sendEvent(Event.SUBSTRUCTURE_REDIRECT, new ResizeRequest(window, width, height));
            width = window.getWidth();
            height = window.getHeight();
            resized = false;
        }

        if (resized && window.isInputOutput()) {
            Drawable oldContent = window.getContent();
            drawableManager.removeDrawable(oldContent.id);
            Drawable newContent = drawableManager.createDrawable(oldContent.id, width, height, oldContent.visual);
            newContent.setOnDrawListener(() -> triggerOnUpdateWindowContent(window));
            window.setContent(newContent);
        }

        if (resized || window.getX() != x || window.getY() != y) {
            window.setX(x);
            window.setY(y);
            window.setWidth(width);
            window.setHeight(height);
            triggerOnUpdateWindowGeometry(window, resized);
        }

        if (resized && window.isInputOutput() && window.attributes.isMapped()) {
            window.sendEvent(new Expose(window));
        }
    }

    private void changeWindowZOrder(Window.StackMode stackMode, Window window, Window sibling) {
        Window parent = window.getParent();
        switch (stackMode) {
            case ABOVE:
                parent.moveChildAbove(window, sibling);
                break;
            case BELOW:
                parent.moveChildBelow(window, sibling);
                break;
        }
        triggerOnChangeWindowZOrder(window);
    }

    public void configureWindow(Window window, Bitmask valueMask, XInputStream inputStream) {
        short x = window.getX();
        short y = window.getY();
        short width = window.getWidth();
        short height = window.getHeight();
        short borderWidth = window.getBorderWidth();
        Window sibling = null;
        Window.StackMode stackMode = null;

        for (int index : valueMask) {
            switch (index) {
                case Window.FLAG_X:
                    x = (short)inputStream.readInt();
                    break;
                case Window.FLAG_Y:
                    y = (short)inputStream.readInt();
                    break;
                case Window.FLAG_WIDTH:
                    width = (short)inputStream.readInt();
                    break;
                case Window.FLAG_HEIGHT:
                    height = (short)inputStream.readInt();
                    break;
                case Window.FLAG_BORDER_WIDTH:
                    borderWidth = (short)inputStream.readInt();
                    break;
                case Window.FLAG_SIBLING:
                    sibling = getWindow(inputStream.readInt());
                    break;
                case Window.FLAG_STACK_MODE:
                    int stackModeValue = inputStream.readInt();
                    if (stackModeValue >= 0 && stackModeValue < Window.StackMode.values().length) {
                        stackMode = Window.StackMode.values()[stackModeValue];
                    }
                    break;
            }
        }

        Window parent = window.getParent();
        boolean overrideRedirect = window.attributes.isOverrideRedirect();
        if (!parent.hasEventListenerFor(Event.SUBSTRUCTURE_REDIRECT) || overrideRedirect) {
            changeWindowGeometry(window, x, y, width, height);

            window.setBorderWidth(borderWidth);
            if (stackMode != null) changeWindowZOrder(stackMode, window, sibling);

            Window previousSibling = window.previousSibling();
            window.sendEvent(Event.STRUCTURE_NOTIFY, new ConfigureNotify(window, window, previousSibling, x, y, width, height, borderWidth, overrideRedirect));
            parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new ConfigureNotify(parent, window, previousSibling, x, y, width, height, borderWidth, overrideRedirect));
        }
        else parent.sendEvent(Event.SUBSTRUCTURE_REDIRECT, new ConfigureRequest(parent, window, window.previousSibling(), x, y, width, height, borderWidth, stackMode, valueMask));
    }

    public void reparentWindow(Window window, Window newParent) {
        Window oldParent = window.getParent();
        if (oldParent != null) oldParent.removeChild(window);
        newParent.addChild(window);
    }

    public Window findPointWindow(short rootX, short rootY) {
        return findPointWindow(rootWindow, rootX, rootY);
    }

    private Window findPointWindow(Window window, short rootX, short rootY) {
        if (!(window.attributes.isMapped() && window.containsPoint(rootX, rootY))) return null;
        Window child = window.getChildByCoords(rootX, rootY);
        return child != null ? findPointWindow(child, rootX, rootY) : window;
    }

    public void addOnWindowModificationListener(OnWindowModificationListener onWindowModificationListener) {
        onWindowModificationListeners.add(onWindowModificationListener);
    }

    public void removeOnWindowModificationListener(OnWindowModificationListener onWindowModificationListener) {
        onWindowModificationListeners.remove(onWindowModificationListener);
    }

    private void triggerOnMapWindow(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onMapWindow(window);
        }
    }

    private void triggerOnUnmapWindow(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUnmapWindow(window);
        }
    }

    private void triggerOnChangeWindowZOrder(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onChangeWindowZOrder(window);
        }
    }

    protected void triggerOnUpdateWindowContent(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUpdateWindowContent(window);
        }
    }

    protected void triggerOnUpdateWindowGeometry(Window window, boolean resized) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUpdateWindowGeometry(window, resized);
        }
    }

    public void triggerOnUpdateWindowAttributes(Window window, Bitmask mask) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUpdateWindowAttributes(window, mask);
        }
    }

    public void triggerOnModifyWindowProperty(Window window, Property property) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onModifyWindowProperty(window, property);
        }
    }
}