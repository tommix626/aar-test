// Copyright 2019 The MediaPipe Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.example.aartest;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.mediapipe.components.CameraHelper;
import com.google.mediapipe.components.CameraXPreviewHelper;
import com.google.mediapipe.components.ExternalTextureConverter;
import com.google.mediapipe.components.FrameProcessor;
import com.google.mediapipe.components.PermissionHelper;
import com.google.mediapipe.components.TextureFrameConsumer;
import com.google.mediapipe.framework.AndroidAssetUtil;
import com.google.mediapipe.framework.AppTextureFrame;
import com.google.mediapipe.glutil.EglManager;

import java.util.Iterator;

class LifeCycleDummy implements LifecycleOwner {
    private LifecycleRegistry lifecycleRegistry;
    public LifeCycleDummy() {
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.markState(Lifecycle.State.CREATED);
    }
    public void doOnResume() {
        lifecycleRegistry.markState(Lifecycle.State.RESUMED);
    }
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }
}

/** Main activity of MediaPipe basic app. */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private static final String INPUT_NUM_FACES_SIDE_PACKET_NAME = "num_faces";
    private static final String OUTPUT_LANDMARKS_STREAM_NAME = "multi_face_landmarks";
    // Max number of faces to detect/process.
    private static final int NUM_FACES = 1;

    // Flips the camera-preview frames vertically by default, before sending them into FrameProcessor
    // to be processed in a MediaPipe graph, and flips the processed frames back when they are
    // displayed. This maybe needed because OpenGL represents images assuming the image origin is at
    // the bottom-left corner, whereas MediaPipe in general assumes the image origin is at the
    // top-left corner.
    // NOTE: use "flipFramesVertically" in manifest metadata to override this behavior.
    private static final boolean FLIP_FRAMES_VERTICALLY = true;

    // Number of output frames allocated in ExternalTextureConverter.
    // NOTE: use "converterNumBuffers" in manifest metadata to override number of buffers. For
    // example, when there is a FlowLimiterCalculator in the graph, number of buffers should be at
    // least `max_in_flight + max_in_queue + 1` (where max_in_flight and max_in_queue are used in
    // FlowLimiterCalculator options). That's because we need buffers for all the frames that are in
    // flight/queue plus one for the next frame from the camera.
    private static final int NUM_BUFFERS = 2;

    static {
        // Load all native libraries needed by the app.
        System.loadLibrary("mediapipe_jni");
        try {
            System.loadLibrary("opencv_java3");
        } catch (java.lang.UnsatisfiedLinkError e) {
            // Some example apps (e.g. template matching) require OpenCV 4.
            System.loadLibrary("opencv_java4");
        }
    }

    // Sends camera-preview frames into a MediaPipe graph for processing, and displays the processed
    // frames onto a {@link Surface}.
    protected FrameProcessor processor;
    // Handles camera access via the {@link CameraX} Jetpack support library.
    protected CameraXPreviewHelper cameraHelper;

    // {@link SurfaceTexture} where the camera-preview frames can be accessed.
    private SurfaceTexture previewFrameTexture;
    // {@link SurfaceView} that displays the camera-preview frames processed by a MediaPipe graph.
    private SurfaceView previewDisplayView;

    // Creates and manages an {@link EGLContext}.
    private EglManager eglManager;
    // Converts the GL_TEXTURE_EXTERNAL_OES texture from Android camera into a regular texture to be
    // consumed by {@link FrameProcessor} and the underlying MediaPipe graph.
    private ExternalTextureConverter converter;

    // ApplicationInfo for retrieving metadata defined in the manifest.
    private ApplicationInfo applicationInfo;

    //delay test headrot
    Handler handler;
    Runnable runnable;
    int delay = 1600;


    LifeCycleDummy lc_dum;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getContentViewLayoutResId());

        try {
            applicationInfo =
                    getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Cannot find application info: " + e);
        }

        previewDisplayView = new SurfaceView(this);
        setupPreviewDisplayView();

        // Initialize asset manager so that MediaPipe native libraries can access the app assets, e.g.,
        // binary graphs.
        AndroidAssetUtil.initializeNativeAssetManager(this);
        eglManager = new EglManager(null);
        processor =
                new FrameProcessor(this,eglManager.getNativeContext());
        processor.getVideoSurfaceOutput().setFlipY(true);
        PermissionHelper.checkAndRequestCameraPermissions(this);

        //facemeshgpu extends

        //already implement in Frameprocessor
//        AndroidPacketCreator packetCreator = processor.getPacketCreator();
//        Map<String, Packet> inputSidePackets = new HashMap<>();
//        inputSidePackets.put(INPUT_NUM_FACES_SIDE_PACKET_NAME, packetCreator.createInt32(NUM_FACES));
//        processor.setInputSidePackets(inputSidePackets);
        lc_dum = new LifeCycleDummy();
        handler = new Handler();

    }


    // Used to obtain the content view for this application. If you are extending this class, and
    // have a custom layout, override this method and return the custom layout.
    protected int getContentViewLayoutResId() {
        return R.layout.activity_main;
    }

    public boolean isCameraUsebyApp() {
        Camera camera = null;
        try {
            camera = Camera.open();
        } catch (RuntimeException e) {
            return true;
        } finally {
            if (camera != null) camera.release();
        }
        return false;
    }
    @Override
    protected void onResume() {
        super.onResume();
        converter =
                new ExternalTextureConverter(
                        eglManager.getContext(),
                        applicationInfo.metaData.getInt("converterNumBuffers", NUM_BUFFERS));
        converter.setFlipY(
                applicationInfo.metaData.getBoolean("flipFramesVertically", FLIP_FRAMES_VERTICALLY));
        converter.setConsumer(processor);
        if (PermissionHelper.cameraPermissionsGranted(this)) {
            startCamera();
        }

        handler.postDelayed(runnable = new Runnable() {
            public void run() {
                handler.postDelayed(runnable, delay);
                previewFrameTexture.updateTexImage();
                float turn=processor.mediapipe_api_get_turn();
                float tilt=processor.mediapipe_api_get_tilt();
                float nod=processor.mediapipe_api_get_nod();
                Log.println(7,"MainActivity","Head turn,tilt,nod="+Float.toString(turn)+" "+Float.toString(tilt)+" "+Float.toString(nod));

                converter.thread.onFrameAvailable(previewFrameTexture);
//                Iterator iter = converter.thread.consumers.iterator();
//
//                while(iter.hasNext()) {
//                    TextureFrameConsumer consumer = (TextureFrameConsumer) iter.next();
//                    if (consumer != null) {
//                        consumer.onNewFrame(previewFrameTexture);
//                    }
//                }
            }
        }, delay);
        lc_dum.doOnResume();
        Log.println(7,"MainActivity","Custom OnResume Ends.");
    }

    @Override
    protected void onPause() {
        super.onPause();
        converter.close();

        // Hide preview display until we re-open the camera again.
        previewDisplayView.setVisibility(View.GONE);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    protected void onCameraStarted(SurfaceTexture surfaceTexture) {
        previewFrameTexture = surfaceTexture;
        // Make the display view visible to start showing the preview. This triggers the
        // SurfaceHolder.Callback added to (the holder of) previewDisplayView.
        previewDisplayView.setVisibility(View.VISIBLE);
    }

    protected Size cameraTargetResolution() {
        return null; // No preference and let the camera (helper) decide.
    }

    public void startCamera() {
        cameraHelper = new CameraXPreviewHelper();
        cameraHelper.setOnCameraStartedListener(
                surfaceTexture -> {
                    onCameraStarted(surfaceTexture);
                });
        CameraHelper.CameraFacing cameraFacing =
                applicationInfo.metaData.getBoolean("cameraFacingFront", false)
                        ? CameraHelper.CameraFacing.FRONT
                        : CameraHelper.CameraFacing.BACK;
        cameraHelper.startCamera(this, cameraFacing, /*unusedSurfaceTexture=*/ null, cameraTargetResolution());
        //add converter reading the camerainput
        converter.setSurfaceTexture(previewFrameTexture, 0, 0);
//      onPreviewDisplaySurfaceChanged(null,0,100,100); //all the parameter not in used
    }


    protected Size computeViewSize(int width, int height) {
        return new Size(width, height);
    }

    protected void onPreviewDisplaySurfaceChanged(
            SurfaceHolder holder, int format, int width, int height) {
        // (Re-)Compute the ideal size of the camera-preview display (the area that the
        // camera-preview frames get rendered onto, potentially with scaling and rotation)
        // based on the size of the SurfaceView that contains the display.
        Size viewSize = computeViewSize(width, height);
        Size displaySize = cameraHelper.computeDisplaySizeFromViewSize(viewSize);
        boolean isCameraRotated = cameraHelper.isCameraRotated();

        // Connect the converter to the camera-preview frames as its input (via
        // previewFrameTexture), and configure the output width and height as the computed
        // display size.
        converter.setSurfaceTextureAndAttachToGLContext(
                previewFrameTexture,
                isCameraRotated ? displaySize.getHeight() : displaySize.getWidth(),
                isCameraRotated ? displaySize.getWidth() : displaySize.getHeight());
        //temp
        /*if (converter.thread.surfaceTexture != null) {
            converter.thread.setOnFrameAvailableListener(converter.thread);
        }

        converter.thread.onFrameAvailable(converter.thread.surfaceTexture);

        Iterator iter = converter.thread.consumers.iterator();

        while(iter.hasNext()) {
            TextureFrameConsumer consumer = (TextureFrameConsumer)iter.next();
            if (consumer != null) {
                consumer.onNewFrame(previewFrameTexture);
            }
        }*/
    }

    private void setupPreviewDisplayView() {
        previewDisplayView.setVisibility(View.GONE);
        //ViewGroup viewGroup = findViewById(R.id.preview_display_layout);
        //viewGroup.addView(previewDisplayView);

        previewDisplayView
                .getHolder()
                .addCallback(
                        new SurfaceHolder.Callback() {
                            @Override
                            public void surfaceCreated(SurfaceHolder holder) {
                                processor.getVideoSurfaceOutput().setSurface(holder.getSurface());
                            }

                            @Override
                            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                                onPreviewDisplaySurfaceChanged(holder, format, width, height);
                                Log.println(7,"preview","surface Changed");
                            }

                            @Override
                            public void surfaceDestroyed(SurfaceHolder holder) {
                                processor.getVideoSurfaceOutput().setSurface(null);
                            }
                        });
    }

    //AAR API to UE4
    public float AAR_get_turn(){
        float turn=processor.mediapipe_api_get_turn();
        return turn;
    }
    public float AAR_get_tilt(){
        float tilt=processor.mediapipe_api_get_tilt();
        return tilt;
    }
    public float AAR_get_nod(){
        float nod=processor.mediapipe_api_get_nod();
        return nod;
    }

    public void AAR_get_head_rotation(float[] rotation){
        rotation[0]=processor.mediapipe_api_get_turn();
        rotation[1]=processor.mediapipe_api_get_tilt();
        rotation[2]=processor.mediapipe_api_get_nod();
    }
}
