package com.example.hadesshark.voicecamera;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    public static final int REQ_SPEECH_TO_TEXT = 0;
    public static final int REQ_TTS_DATA_CHECK = 1;

    private FloatingActionButton mMicBtn;
    private TextureView mTextureView;
    private TextToSpeech mTTS;

    private String option;

    private CameraManager manager;
    private CameraDevice mCameraDeice;
    private String mCameraId;
    private CameraCaptureSession mCameraCaptureSession;
    private CaptureRequest mCaptureRequest;
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private Handler mCameraHandler;

    private HandlerThread mCameraThread;
    private ImageReader mImageReader;
    private Size mCaptureSize;
    private Boolean Flash;
    private Boolean isOpenFlash = false;
//    private Camera mCamera = Camera.open();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 取消功能欄
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        mTextureView = this.findViewById(R.id.textureView);
        mMicBtn = this.findViewById(R.id.MicButton);

        mMicBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                lockFocus();
            }
        });

        // 這樣寫就可以不用 implements
        mTTS = new TextToSpeech(this, mInitListener);
    }

    @Override
    protected void onResume() {
        super.onResume();

        startCameraThread();
        if (!mTextureView.isAvailable()) {
            mTextureView.setSurfaceTextureListener(mTextureListener);
        } else {
            startPreview();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mTTS != null) {
            mTTS.shutdown();
        }

        if (mCameraCaptureSession != null) {
            mCameraCaptureSession.close();
            mCameraCaptureSession = null;
        }

        if (mCameraDeice != null) {
            mCameraDeice.close();
            mCameraDeice = null;
        }

        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    private TextureView.SurfaceTextureListener mTextureListener = new
            TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                    setupCamera();
                    openCamera();
                }

                // 這個可以修改預覽方向改變的問題
                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

                }
            };

    // 設定 camera
    private void setupCamera() {
        manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                );
                assert map != null;

                // 閃光燈判斷
                Flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);

                mCaptureSize = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new Comparator<Size>() {
                            @Override
                            public int compare(Size lhs, Size rhs) {
                                return Long.signum((lhs.getWidth() * lhs.getHeight()) -
                                        (rhs.getWidth() * lhs.getHeight()));
                            }
                        });
                setupImageReader();

                mCameraId = cameraId;
                break;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera() {
        manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED) {
                return;
            }
            manager.openCamera(mCameraId, mStateCallback, mCameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 攝像頭相關情況
    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDeice = cameraDevice;
            startPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
            mCameraDeice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            cameraDevice.close();
            mCameraDeice = null;
        }
    };

    /**
     * {@link #mStateCallback} 會一起設定 {@link #mTextureView}
     */
    private void startPreview() {
        SurfaceTexture mSurfaceTexture = mTextureView.getSurfaceTexture();
        Surface previewSurface = new Surface(mSurfaceTexture);

        try {
            mCaptureRequestBuilder = mCameraDeice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(previewSurface);
            mCameraDeice.createCaptureSession(Arrays.asList(previewSurface, mImageReader.getSurface()),
                    mCameraStateCallback,
                    mCameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    // 拍照時圖片處理問題
    private void setupImageReader() {
        mImageReader = ImageReader.newInstance(mCaptureSize.getWidth(), mCaptureSize.getHeight(),
                ImageFormat.JPEG, 2);
        mImageReader.setOnImageAvailableListener(mImageReaderListener, mCameraHandler);
    }

    private ImageReader.OnImageAvailableListener mImageReaderListener = new
            ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader imageReader) {
                    mCameraHandler.post(new imageSever(imageReader.acquireLatestImage()));
                }
            };

    private void startCameraThread() {
        mCameraThread = new HandlerThread("CameraThread");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());
    }

    // ======================= 拍照時使用 =================================
    private void lockFocus() {
        try {
            // 對焦
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // 拍照！！
            mCameraCaptureSession.capture(mCaptureRequestBuilder.build(),
                    mCaptureCallback,
                    mCameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void unLockFocus() {
        try {
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            // 回復預覽狀態
            mCameraCaptureSession.setRepeatingRequest(mCaptureRequest,
                    null,
                    mCameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // ======================= CameraCaptureSession 相關 Callback ============================

    // 關於拍照的的功能
    private CameraCaptureSession.CaptureCallback mCaptureCallback = new
            CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {

                    // 開啟 tts
                    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    startActivityForResult(intent, REQ_SPEECH_TO_TEXT);
                }
            };

    private CameraCaptureSession.StateCallback mCameraStateCallback = new
            CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    try {
                        mCaptureRequest = mCaptureRequestBuilder.build();
                        mCameraCaptureSession = cameraCaptureSession;
                        mCameraCaptureSession.setRepeatingRequest(mCaptureRequest,
                                null,
                                mCameraHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {

                }
            };

    private void capture() {
        try {
            final CaptureRequest.Builder mCaptureBuilder = mCameraDeice.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE
            );
            /**
             * 獲得 {@link mImageReader} 的圖像
             */
            mCaptureBuilder.addTarget(mImageReader.getSurface());
            // TODO: 這裡有點怪
            CameraCaptureSession.CaptureCallback CaptureCallback = new
                    CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                            Toast.makeText(getApplicationContext(), "Image Saved!", Toast.LENGTH_SHORT).show();
                            unLockFocus();
                        }
                    };
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void turnLightOn(Boolean enable) {
        CaptureRequest.Builder builder;
        SurfaceTexture mSurfaceTexture = mTextureView.getSurfaceTexture();
        Surface previewSurface = new Surface(mSurfaceTexture);
        try {
            builder = mCameraDeice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
            builder.addTarget(previewSurface);
            mCameraCaptureSession.capture(builder.build(), null,null);
            isOpenFlash = true;
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

//        manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
//        try {
//            manager.setTorchMode(mCameraId, enable);
//        } catch (CameraAccessException e) {
//            e.printStackTrace();
//        }
        Camera mCamera = Camera.open();
//        Camera.Parameters parameters = mCamera.getParameters();
//        List<String> flashModes = parameters.getSupportedFlashModes();
//        if (!Camera.Parameters.FLASH_MODE_ON.equals(flashModes)) {
//            if (flashModes.contains(Camera.Parameters.FLASH_MODE_ON)) {
//                parameters.setFlashMode(Camera.Parameters.FLASH_MODE_ON);
//                mCamera.setParameters(parameters);
//            }
//        }
    }

    // 語音功能類
    private void checkOption() {


        switch (option) {
            case "拍照":
                Toast.makeText(getApplicationContext(), "Image Saved!", Toast.LENGTH_SHORT).show();

                capture();
                break;
            case "開燈":
                turnLightOn(true);
                break;
            case "關燈":
                turnLightOn(false);
                break;
            default:
                Toast.makeText(this, option, Toast.LENGTH_SHORT).show();
                break;
        }
    }

    //========================= 以下是 tts 的部份 ==================================

    private final TextToSpeech.OnInitListener mInitListener = new
            TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status == TextToSpeech.ERROR) {
                        showToast(R.string.text_TextToSpeechError);
                        return;
                    }

                    int available = mTTS.isLanguageAvailable(Locale.getDefault());

                    if (available == TextToSpeech.LANG_NOT_SUPPORTED) {
                        showToast(R.string.text_LanguageNotSupported);
                        return;
                    }

                    Intent checkIntent = new Intent();
                    checkIntent.setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
                    startActivityForResult(checkIntent, REQ_TTS_DATA_CHECK);
                }
            };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQ_TTS_DATA_CHECK:
                if (resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL) {
                    Intent installIntent = new Intent();
                    installIntent.setAction(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                    startActivity(installIntent);
                }
                break;
            case REQ_SPEECH_TO_TEXT:
                if (resultCode == RESULT_OK) {
                    List<String> list = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    option = list.get(0);

                    // 回到 camera2
                    checkOption();
                }
                break;
        }
    }

    private void showToast(int textId) {
        Toast.makeText(this, textId, Toast.LENGTH_SHORT).show();
    }

    private class imageSever implements Runnable {

        private Image mImage;

        public imageSever(Image image) {
            mImage = image;
        }

        @Override
        public void run() {
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            String path = Environment.getExternalStorageDirectory() + "/DCIM/VoiceCamera/";
            File mImageFile = new File(path);
            if (!mImageFile.exists()) {
                mImageFile.mkdir();
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String fileName = path + "IMG_" + timeStamp + ".jpg";
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(fileName);
                fos.write(data, 0, data.length);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}
