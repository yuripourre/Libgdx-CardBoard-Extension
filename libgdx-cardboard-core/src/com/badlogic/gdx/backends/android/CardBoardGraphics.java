/*******************************************************************************
 * Copyright 2015 wei yang(yangweigbh@hotmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.backends.android;

import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.android.surfaceview.GdxEglConfigChooser;
import com.badlogic.gdx.backends.android.surfaceview.ResolutionStrategy;
import com.badlogic.gdx.graphics.Cubemap;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.WindowedMean;
import com.google.vrtoolkit.cardboard.CardboardView;
import com.google.vrtoolkit.cardboard.Eye;
import com.google.vrtoolkit.cardboard.HeadTransform;
import com.google.vrtoolkit.cardboard.Viewport;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

public class CardBoardGraphics extends AndroidGraphics implements Graphics, CardboardView.StereoRenderer {

    private static final String LOG_TAG = "CardBoardGraphics";

    public CardBoardGraphics(AndroidApplicationBase application, AndroidApplicationConfiguration config,
                             ResolutionStrategy resolutionStrategy) {
        this(application, config, resolutionStrategy, true);
    }

    public CardBoardGraphics(AndroidApplicationBase application, AndroidApplicationConfiguration config,
                             ResolutionStrategy resolutionStrategy, boolean focusableView) {
        super(application, config, resolutionStrategy, focusableView);
    }

    protected View createGLSurfaceView(AndroidApplicationBase application, final ResolutionStrategy resolutionStrategy) {
        CardboardView cardboardView = new CardboardView(application.getContext());
        cardboardView.setRestoreGLStateEnabled(false);
        cardboardView.setRenderer((CardboardView.StereoRenderer) this);
        return cardboardView;
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        this.width = width / 2;
        this.height = height / 2;
        updatePpi();
        gl20.glViewport(0, 0, this.width, this.height);
        if (!created) {
            app.getApplicationListener().create();
            created = true;
            synchronized (this) {
                running = true;
            }
        }
        app.getApplicationListener().resize(width, height);
    }

    // It can be solved, changing updatePpi to protected
    private float ppiX = 0;
    private float ppiY = 0;
    private float ppcX = 0;
    private float ppcY = 0;
    private float density = 1;

    private void updatePpi() {
        DisplayMetrics metrics = new DisplayMetrics();
        app.getWindowManager().getDefaultDisplay().getMetrics(metrics);

        ppiX = metrics.xdpi;
        ppiY = metrics.ydpi;
        ppcX = metrics.xdpi / 2.54f;
        ppcY = metrics.ydpi / 2.54f;
        density = metrics.density;
    }

    @Override
    public void onSurfaceCreated(EGLConfig config) {
        setupGL();
        logConfig(config);
        updatePpi();

        Mesh.invalidateAllMeshes(app);
        Texture.invalidateAllTextures(app);
        Cubemap.invalidateAllCubemaps(app);
        ShaderProgram.invalidateAllShaderPrograms(app);
        FrameBuffer.invalidateAllFrameBuffers(app);

        logManagedCachesStatus();

        Display display = app.getWindowManager().getDefaultDisplay();
        this.width = display.getWidth();
        this.height = display.getHeight();
        this.mean = new WindowedMean(5);
        this.lastFrameTime = System.nanoTime();

        gl20.glViewport(0, 0, this.width, this.height);
    }

    private void setupGL() {
        AndroidGL20 candidate = new AndroidGL20();

        String versionString = candidate.glGetString(GL10.GL_VERSION);
        String vendorString = candidate.glGetString(GL10.GL_VENDOR);
        String rendererString = candidate.glGetString(GL10.GL_RENDERER);
        glVersion = new GLVersion(Application.ApplicationType.Android, versionString, vendorString, rendererString);

        if (config.useGL30 && glVersion.getMajorVersion() > 2) {
            if (gl30 != null) return;
            gl20 = gl30 = new AndroidGL30();

            Gdx.gl = gl30;
            Gdx.gl20 = gl30;
            Gdx.gl30 = gl30;
        } else {
            if (gl20 != null) return;
            gl20 = candidate;

            Gdx.gl = candidate;
            Gdx.gl20 = candidate;
        }

        Gdx.app.log(LOG_TAG, "OGL renderer: " + rendererString);
        Gdx.app.log(LOG_TAG, "OGL vendor: " + vendorString);
        Gdx.app.log(LOG_TAG, "OGL version: " + versionString);
        Gdx.app.log(LOG_TAG, "OGL extensions: " + gl20.glGetString(GL10.GL_EXTENSIONS));
    }

    // It can be solved, changing logConfig to protected
    private void logConfig(EGLConfig config) {
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        int r = getAttrib(egl, display, config, EGL10.EGL_RED_SIZE, 0);
        int g = getAttrib(egl, display, config, EGL10.EGL_GREEN_SIZE, 0);
        int b = getAttrib(egl, display, config, EGL10.EGL_BLUE_SIZE, 0);
        int a = getAttrib(egl, display, config, EGL10.EGL_ALPHA_SIZE, 0);
        int d = getAttrib(egl, display, config, EGL10.EGL_DEPTH_SIZE, 0);
        int s = getAttrib(egl, display, config, EGL10.EGL_STENCIL_SIZE, 0);
        int samples = Math.max(getAttrib(egl, display, config, EGL10.EGL_SAMPLES, 0),
                getAttrib(egl, display, config, GdxEglConfigChooser.EGL_COVERAGE_SAMPLES_NV, 0));
        boolean coverageSample = getAttrib(egl, display, config, GdxEglConfigChooser.EGL_COVERAGE_SAMPLES_NV, 0) != 0;

        Gdx.app.log(LOG_TAG, "framebuffer: (" + r + ", " + g + ", " + b + ", " + a + ")");
        Gdx.app.log(LOG_TAG, "depthbuffer: (" + d + ")");
        Gdx.app.log(LOG_TAG, "stencilbuffer: (" + s + ")");
        Gdx.app.log(LOG_TAG, "samples: (" + samples + ")");
        Gdx.app.log(LOG_TAG, "coverage sampling: (" + coverageSample + ")");

        //bufferFormat = new BufferFormat(r, g, b, a, d, s, samples, coverageSample);
    }

    private int getAttrib(EGL10 egl, EGLDisplay display, EGLConfig config, int attrib, int defValue) {
        if (egl.eglGetConfigAttrib(display, config, attrib, value)) {
            return value[0];
        }
        return defValue;
    }

    @Override
    public void onDrawEye(Eye eye) {
        if (!(app.getApplicationListener() instanceof CardBoardApplicationListener)) {
            throw new RuntimeException("should implement CardBoardApplicationListener");
        }
        if (!destroy) {
            ((CardBoardApplicationListener) app.getApplicationListener())
                    .onDrawEye(eye);
        }
    }

    @Override
    public void onFinishFrame(Viewport viewport) {
        if (!(app.getApplicationListener() instanceof CardBoardApplicationListener)) {
            throw new RuntimeException("should implement CardBoardApplicationListener");
        }
        if (!destroy) {
            ((CardBoardApplicationListener) app.getApplicationListener())
                    .onFinishFrame(viewport);
        }
    }

    @Override
    public void onNewFrame(HeadTransform headTransform) {
        super.onDrawFrame(null);

        if (!(app.getApplicationListener() instanceof CardBoardApplicationListener)) {
            throw new RuntimeException("should implement CardBoardApplicationListener");
        }
        if (!destroy) {
            ((CardBoardApplicationListener) app.getApplicationListener())
                    .onNewFrame(headTransform);
        }
    }

    @Override
    public void onRendererShutdown() {
        if (!(app.getApplicationListener() instanceof CardBoardApplicationListener)) {
            throw new RuntimeException("should implement CardBoardApplicationListener");
        }
        ((CardBoardApplicationListener) app.getApplicationListener()).onRendererShutdown();

    }

}
