package com.winlator.renderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;

// import com.winlator.R;
// import com.winlator.XrActivity;
import app.gamenative.R;
import com.winlator.math.Mathf;
import com.winlator.math.XForm;
import com.winlator.renderer.material.CursorMaterial;
import com.winlator.renderer.material.ShaderMaterial;
import com.winlator.renderer.material.ShadowMaterial;
import com.winlator.renderer.material.WindowMaterial;
import com.winlator.widget.XServerView;
import com.winlator.xserver.Bitmask;
import com.winlator.xserver.Cursor;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowAttributes;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;

import java.util.ArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GLRenderer implements GLSurfaceView.Renderer, WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener {
    public final XServerView xServerView;
    private final XServer xServer;
    private final VertexAttribute quadVertices = new VertexAttribute("position", 2);
    private final float[] tmpXForm1 = XForm.getInstance();
    private final float[] tmpXForm2 = XForm.getInstance();
    private final CursorMaterial cursorMaterial = new CursorMaterial();
    private final WindowMaterial windowMaterial = new WindowMaterial();
    private final ShadowMaterial shadowMaterial = new ShadowMaterial();
    public final ViewTransformation viewTransformation = new ViewTransformation();
    private final Drawable rootCursorDrawable;
    private final ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private String forceFullscreenWMClass = null;
    private boolean fullscreen = false;
    private boolean toggleFullscreen = false;
    private boolean viewportNeedsUpdate = true;
    private boolean cursorVisible = true;
    private boolean screenOffsetYRelativeToCursor = false;
    private String[] unviewableWMClasses = null;
    private float magnifierZoom = 1.0f;
    private boolean magnifierEnabled = true;
    private int surfaceWidth;
    private int surfaceHeight;
    private boolean sceneInitialized = false;
    private final EffectComposer effectComposer;
    private Bitmap backdropBitmap;
    private int backdropTextureId = 0;
    private boolean backdropTextureDirty = false;

    public GLRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        this.effectComposer = new EffectComposer(this);
        rootCursorDrawable = createRootCursorDrawable();

        quadVertices.put(new float[]{
                0.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 0.0f,
                1.0f, 1.0f
        });

        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GPUImage.checkIsSupported();

        GLES20.glFrontFace(GLES20.GL_CCW);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        effectComposer.invalidateGLResources();
        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            // iterate all known drawables; if you don't have a central list,
            // call this during updateScene() for each window's content.
            android.util.SparseArray<Drawable> sa = xServer.drawableManager.all(); // adjust type if needed
            for (int i = 0; i < sa.size(); i++) {
                Drawable d = sa.valueAt(i);
                if (d != null) d.getTexture().invalidate(); // sets textureId=0 so next draw re-creates
            }
            rootCursorDrawable.getTexture().invalidate();
            backdropTextureId = 0;
            backdropTextureDirty = backdropBitmap != null;
        }
        updateScene();
        xServerView.requestRender();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        // if (XrActivity.isSupported()) {
        //     XrActivity activity = XrActivity.getInstance();
        //     activity.init();
        //     width = activity.getWidth();
        //     height = activity.getHeight();
        //     GLES20.glViewport(0, 0, width, height);
        //     magnifierEnabled = false;
        // }

        surfaceWidth = width;
        surfaceHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        viewportNeedsUpdate = true;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (toggleFullscreen) {
            fullscreen = !fullscreen;
            toggleFullscreen = false;
            viewportNeedsUpdate = true;
        }

        if (effectComposer.hasEffects()) {
            effectComposer.render();
        }
        else {
            drawScene();
        }
    }

    void drawScene() {
        boolean xrFrame = false;
        // if (XrActivity.isSupported()) xrFrame = XrActivity.getInstance().beginFrame(XrActivity.getImmersive(), XrActivity.getSBS());

        if (viewportNeedsUpdate && magnifierEnabled) {
            applyCurrentViewport();
            viewportNeedsUpdate = false;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        renderBackdrop();
        renderViewportShadow();
        if (magnifierEnabled) applyCurrentViewport();

        if (magnifierEnabled) {
            float pointerX = 0;
            float pointerY = 0;
            float magnifierZoom = !screenOffsetYRelativeToCursor ? this.magnifierZoom : 1.0f;

            if (magnifierZoom != 1.0f) {
                pointerX = Mathf.clamp(xServer.pointer.getX() * magnifierZoom - xServer.screenInfo.width * 0.5f, 0, xServer.screenInfo.width * Math.abs(1.0f - magnifierZoom));
            }

            if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) {
                float scaleY = magnifierZoom != 1.0f ? Math.abs(1.0f - magnifierZoom) : 0.5f;
                float offsetY = xServer.screenInfo.height * (screenOffsetYRelativeToCursor ? 0.25f : 0.5f);
                pointerY = Mathf.clamp(xServer.pointer.getY() * magnifierZoom - offsetY, 0, xServer.screenInfo.height * scaleY);
            }

            XForm.makeTransform(tmpXForm2, -pointerX, -pointerY, magnifierZoom, magnifierZoom, 0);
        }
        else {
            if (!fullscreen) {
                int pointerY = 0;
                if (screenOffsetYRelativeToCursor) {
                    short halfScreenHeight = (short)(xServer.screenInfo.height / 2);
                    pointerY = Mathf.clamp(xServer.pointer.getY() - halfScreenHeight / 2, 0, halfScreenHeight);
                }

                XForm.makeTransform(tmpXForm2, viewTransformation.sceneOffsetX, viewTransformation.sceneOffsetY - pointerY, viewTransformation.sceneScaleX, viewTransformation.sceneScaleY, 0);

                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                GLES20.glScissor(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
            }
            else XForm.identity(tmpXForm2);
        }

        renderWindows();
        if (cursorVisible) renderCursor();

        if (!magnifierEnabled && !fullscreen) GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

        if (xrFrame) {
            // XrActivity.getInstance().endFrame();
            // XrActivity.updateControllers();
            xServerView.requestRender();
        }
    }

    @Override
    public void onMapWindow(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUnmapWindow(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onChangeWindowZOrder(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowGeometry(final Window window, boolean resized) {
        if (resized) {
            xServerView.queueEvent(this::updateScene);
        }
        else xServerView.queueEvent(() -> updateWindowPosition(window));
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) xServerView.requestRender();
    }

    @Override
    public void onPointerMove(short x, short y) {
        xServerView.requestRender();
    }

    private void renderDrawable(Drawable drawable, int x, int y, ShaderMaterial material) {
        renderDrawable(drawable, x, y, material, false);
    }

    private void renderDrawable(Drawable drawable, int x, int y, ShaderMaterial material, boolean forceFullscreen) {
        if (drawable == null) return;
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable(drawable);

            if (forceFullscreen) {
                short newHeight = (short)Math.min(xServer.screenInfo.height, ((float)xServer.screenInfo.width / drawable.width) * drawable.height);
                short newWidth = (short)(((float)newHeight / drawable.height) * drawable.width);
                XForm.set(tmpXForm1, (xServer.screenInfo.width - newWidth) * 0.5f, (xServer.screenInfo.height - newHeight) * 0.5f, newWidth, newHeight);
            }
            else XForm.set(tmpXForm1, x, y, drawable.width, drawable.height);

            XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
            GLES20.glUniform1i(material.getUniformLocation("texture"), 0);
            GLES20.glUniform1fv(material.getUniformLocation("xform"), tmpXForm1.length, tmpXForm1, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    private void applyCurrentViewport() {
        if (fullscreen) {
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        }
        else {
            GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
        }
    }

    private void ensureBackdropTexture() {
        if (backdropBitmap == null) return;

        if (backdropTextureId == 0) {
            int[] textureIds = new int[1];
            GLES20.glGenTextures(1, textureIds, 0);
            backdropTextureId = textureIds[0];
            backdropTextureDirty = true;
        }

        if (!backdropTextureDirty) return;

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backdropTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, backdropBitmap, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        backdropTextureDirty = false;
    }

    private void deleteBackdropTexture() {
        if (backdropTextureId == 0) return;
        int[] textureIds = new int[]{backdropTextureId};
        GLES20.glDeleteTextures(1, textureIds, 0);
        backdropTextureId = 0;
    }

    private void renderBackdrop() {
        if (backdropBitmap == null || surfaceWidth <= 0 || surfaceHeight <= 0) return;

        ensureBackdropTexture();
        if (backdropTextureId == 0) return;

        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        windowMaterial.use();
        GLES20.glUniform2f(windowMaterial.getUniformLocation("viewSize"), surfaceWidth, surfaceHeight);
        quadVertices.bind(windowMaterial.programId);

        float scale = Math.max((float) surfaceWidth / backdropBitmap.getWidth(), (float) surfaceHeight / backdropBitmap.getHeight());
        float drawWidth = backdropBitmap.getWidth() * scale;
        float drawHeight = backdropBitmap.getHeight() * scale;
        float drawX = (surfaceWidth - drawWidth) * 0.5f;
        float drawY = (surfaceHeight - drawHeight) * 0.5f;

        XForm.set(tmpXForm1, drawX, drawY, drawWidth, drawHeight);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backdropTextureId);
        GLES20.glUniform1i(windowMaterial.getUniformLocation("texture"), 0);
        GLES20.glUniform1fv(windowMaterial.getUniformLocation("xform"), tmpXForm1.length, tmpXForm1, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        quadVertices.disable();
    }

    private void renderShadowQuad(float x, float y, float width, float height, float leftWeight, float rightWeight, float topWeight, float bottomWeight, float alpha) {
        if (width <= 0.0f || height <= 0.0f || alpha <= 0.0f) return;

        XForm.set(tmpXForm1, x, y, width, height);
        shadowMaterial.setUniformVec4("edgeWeights", leftWeight, rightWeight, topWeight, bottomWeight);
        shadowMaterial.setUniformFloat("shadowAlpha", alpha);
        GLES20.glUniform1fv(shadowMaterial.getUniformLocation("xform"), tmpXForm1.length, tmpXForm1, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
    }

    private void renderViewportShadow() {
        if (fullscreen || surfaceWidth <= 0 || surfaceHeight <= 0) return;
        if (viewTransformation.viewWidth >= surfaceWidth && viewTransformation.viewHeight >= surfaceHeight) return;

        float sideShadowSize = Math.max(22.0f, 34.0f * viewTransformation.aspect);
        float verticalShadowSize = Math.max(26.0f, 42.0f * viewTransformation.aspect);
        float sideShadowAlpha = 0.50f;
        float verticalShadowAlpha = 0.58f;
        float left = viewTransformation.viewOffsetX;
        float top = viewTransformation.viewOffsetY;
        float right = left + viewTransformation.viewWidth;
        float bottom = top + viewTransformation.viewHeight;

        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        shadowMaterial.use();
        shadowMaterial.setUniformVec2("viewSize", surfaceWidth, surfaceHeight);
        quadVertices.bind(shadowMaterial.programId);

        renderShadowQuad(left - sideShadowSize, top, sideShadowSize, viewTransformation.viewHeight, 1.0f, 0.0f, 0.0f, 0.0f, sideShadowAlpha);
        renderShadowQuad(right, top, sideShadowSize, viewTransformation.viewHeight, 0.0f, 1.0f, 0.0f, 0.0f, sideShadowAlpha);
        renderShadowQuad(left, top - verticalShadowSize, viewTransformation.viewWidth, verticalShadowSize, 0.0f, 0.0f, 1.0f, 0.0f, verticalShadowAlpha);
        renderShadowQuad(left, bottom, viewTransformation.viewWidth, verticalShadowSize, 0.0f, 0.0f, 0.0f, 1.0f, verticalShadowAlpha);

        quadVertices.disable();
    }

    private void renderWindows() {
        windowMaterial.use();
        GLES20.glUniform2f(windowMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(windowMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            for (RenderableWindow window : renderableWindows) {
                renderDrawable(window.content, window.rootX, window.rootY, windowMaterial, window.forceFullscreen);
            }
        }

        quadVertices.disable();
    }

    private void renderCursor() {
        cursorMaterial.use();
        GLES20.glUniform2f(cursorMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(cursorMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            Window pointWindow = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pointWindow != null ? pointWindow.attributes.getCursor() : null;
            short x = xServer.pointer.getClampedX();
            short y = xServer.pointer.getClampedY();

            if (cursor != null) {
                if (cursor.isVisible()) renderDrawable(cursor.cursorImage, x - cursor.hotSpotX, y - cursor.hotSpotY, cursorMaterial);
            }
            else renderDrawable(rootCursorDrawable, x, y, cursorMaterial);
        }

        quadVertices.disable();
    }

    public void setBackdropBitmap(Bitmap bitmap) {
        xServerView.queueEvent(() -> {
            if (backdropBitmap != null && backdropBitmap != bitmap && !backdropBitmap.isRecycled()) {
                backdropBitmap.recycle();
            }
            backdropBitmap = bitmap;
            deleteBackdropTexture();
            backdropTextureDirty = bitmap != null;
        });
        xServerView.requestRender();
    }

    public void clearBackdrop() {
        xServerView.queueEvent(() -> {
            if (backdropBitmap != null && !backdropBitmap.isRecycled()) {
                backdropBitmap.recycle();
            }
            backdropBitmap = null;
            deleteBackdropTexture();
            backdropTextureDirty = false;
        });
        xServerView.requestRender();
    }

    public void toggleFullscreen() {
        toggleFullscreen = true;
        xServerView.requestRender();
    }

    private Drawable createRootCursorDrawable() {
        Context context = xServerView.getContext();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
        return Drawable.fromBitmap(bitmap);
    }

    private void updateScene() {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            renderableWindows.clear();
            collectRenderableWindows(xServer.windowManager.rootWindow, xServer.windowManager.rootWindow.getX(), xServer.windowManager.rootWindow.getY());
        }
    }

    private void collectRenderableWindows(Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;
        if (window != xServer.windowManager.rootWindow) {
            boolean viewable = true;

            if (unviewableWMClasses != null) {
                String wmClass = window.getClassName();
                for (String unviewableWMClass : unviewableWMClasses) {
                    if (wmClass.contains(unviewableWMClass)) {
                        if (window.attributes.isEnabled()) window.disableAllDescendants();
                        viewable = false;
                        break;
                    }
                }
            }

            if (viewable) {
                if (forceFullscreenWMClass != null) {
                    short width = window.getWidth();
                    short height = window.getHeight();
                    boolean forceFullscreen= false;

                    if (width >= 320 && height >= 200 && width < xServer.screenInfo.width && height < xServer.screenInfo.height) {
                        Window parent = window.getParent();
                        boolean parentHasWMClass = parent.getClassName().contains(forceFullscreenWMClass);
                        boolean hasWMClass = window.getClassName().contains(forceFullscreenWMClass);
                        if (hasWMClass) {
                            forceFullscreen = !parentHasWMClass && window.getChildCount() == 0;
                        }
                        else {
                            short borderX = (short)(parent.getWidth() - width);
                            short borderY = (short)(parent.getHeight() - height);
                            if (parent.getChildCount() == 1 && borderX > 0 && borderY > 0 && borderX <= 12) {
                                forceFullscreen = true;
                                removeRenderableWindow(parent);
                            }
                        }
                    }

                    renderableWindows.add(new RenderableWindow(window.getContent(), x, y, forceFullscreen));
                }
                else renderableWindows.add(new RenderableWindow(window.getContent(), x, y));
            }
        }

        for (Window child : window.getChildren()) {
            collectRenderableWindows(child, child.getX() + x, child.getY() + y);
        }
    }

    private void removeRenderableWindow(Window window) {
        for (int i = 0; i < renderableWindows.size(); i++) {
            if (renderableWindows.get(i).content == window.getContent()) {
                renderableWindows.remove(i);
                break;
            }
        }
    }

    private void updateWindowPosition(Window window) {
        for (RenderableWindow renderableWindow : renderableWindows) {
            if (renderableWindow.content == window.getContent()) {
                renderableWindow.rootX = window.getRootX();
                renderableWindow.rootY = window.getRootY();
                break;
            }
        }
    }

    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
        xServerView.requestRender();
    }

    public boolean isCursorVisible() {
        return cursorVisible;
    }

    public boolean isScreenOffsetYRelativeToCursor() {
        return screenOffsetYRelativeToCursor;
    }

    public void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor) {
        this.screenOffsetYRelativeToCursor = screenOffsetYRelativeToCursor;
        xServerView.requestRender();
    }

    public String getForceFullscreenWMClass() {
        return forceFullscreenWMClass;
    }

    public void setForceFullscreenWMClass(String forceFullscreenWMClass) {
        this.forceFullscreenWMClass = forceFullscreenWMClass;
    }

    public String[] getUnviewableWMClasses() {
        return unviewableWMClasses;
    }

    public void setUnviewableWMClasses(String... unviewableWMNames) {
        this.unviewableWMClasses = unviewableWMNames;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public float getMagnifierZoom() {
        return magnifierZoom;
    }

    public void setMagnifierZoom(float magnifierZoom) {
        this.magnifierZoom = magnifierZoom;
        xServerView.requestRender();
    }

    public int getSurfaceWidth() {
        return surfaceWidth;
    }

    public int getSurfaceHeight() {
        return surfaceHeight;
    }

    public VertexAttribute getQuadVertices() {
        return quadVertices;
    }

    public void setViewportNeedsUpdate(boolean viewportNeedsUpdate) {
        this.viewportNeedsUpdate = viewportNeedsUpdate;
    }

    public EffectComposer getEffectComposer() {
        return effectComposer;
    }
}
