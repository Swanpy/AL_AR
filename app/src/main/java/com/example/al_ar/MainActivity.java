package com.example.ar_cube;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.view.View;

import com.google.ar.core.Anchor;
import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.SessionPausedException;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1;
    private static final String TAG = "AR_Cube";

    private GLSurfaceView surfaceView;
    private FrameLayout container;
    private Session arSession;
    private boolean qrScanned = false;
    private Anchor qrAnchor = null;
    private boolean sessionResumed = false;
    private boolean sessionInitialized = false;

    private int cameraProgram;
    private int cubeProgram;
    private int cameraTextureId;

    private FloatBuffer cameraVertexBuffer;
    private FloatBuffer cameraTexCoordBuffer;
    private FloatBuffer cubeVertexBuffer;

    private float[] fixedAnchorMatrix = new float[16]; // Для хранения фиксированной матрицы якоря
    private float[] modelMatrix = new float[16];       // Для матрицы модели
    private float[] viewMatrix = new float[16];        // Для матрицы вида
    private float[] projectionMatrix = new float[16];  // Для проекционной матрицы

    private final float[] cameraVertices = {
            -1.0f, -1.0f, 0.0f,
            1.0f, -1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 0.0f
    };

    private final float[] cameraTexCoords = {
            1.0f, 1.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            0.0f, 0.0f
    };

    private final float[] cubeVertices = {
            -0.2f, -0.2f, -0.2f,
            0.2f, -0.2f, -0.2f,
            -0.2f, 0.2f, -0.2f,
            0.2f, 0.2f, -0.2f,
            -0.2f, -0.2f, 0.2f,
            0.2f, -0.2f, 0.2f,
            -0.2f, 0.2f, 0.2f,
            0.2f, 0.2f, 0.2f
    };

    private final short[] cubeIndices = {
            0, 1, 2, 2, 1, 3,
            4, 6, 5, 5, 6, 7,
            0, 2, 4, 4, 2, 6,
            1, 5, 3, 3, 5, 7,
            2, 3, 6, 6, 3, 7,
            0, 4, 1, 1, 4, 5
    };

    private final float[] mvpMatrix = new float[16];
    private final float[] defaultViewMatrix = new float[16];
    private final float[] anchorMatrix = new float[16];

    private ShortBuffer cubeIndexBuffer;
    private ImageView floatingCube;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        container = new FrameLayout(this);
        surfaceView = new GLSurfaceView(this);
        container.addView(surfaceView);

        LayoutInflater inflater = LayoutInflater.from(this);
        floatingCube = (ImageView) inflater.inflate(R.layout.floating_cube, container, false);
        floatingCube.setVisibility(View.GONE);
        container.addView(floatingCube);

        setContentView(container);

        surfaceView.setEGLContextClientVersion(3);
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        initBuffers();
        checkCameraPermission();
    }

    private void initBuffers() {
        ByteBuffer bb = ByteBuffer.allocateDirect(cameraVertices.length * 4);
        bb.order(ByteOrder.nativeOrder());
        cameraVertexBuffer = bb.asFloatBuffer();
        cameraVertexBuffer.put(cameraVertices).position(0);

        ByteBuffer tb = ByteBuffer.allocateDirect(cameraTexCoords.length * 4);
        tb.order(ByteOrder.nativeOrder());
        cameraTexCoordBuffer = tb.asFloatBuffer();
        cameraTexCoordBuffer.put(cameraTexCoords).position(0);

        ByteBuffer cb = ByteBuffer.allocateDirect(cubeVertices.length * 4);
        cb.order(ByteOrder.nativeOrder());
        cubeVertexBuffer = cb.asFloatBuffer();
        cubeVertexBuffer.put(cubeVertices).position(0);

        ByteBuffer ib = ByteBuffer.allocateDirect(cubeIndices.length * 2);
        ib.order(ByteOrder.nativeOrder());
        cubeIndexBuffer = ib.asShortBuffer();
        cubeIndexBuffer.put(cubeIndices).position(0);
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            initializeSession();
            startQRScanner();
        }
    }

    private void initializeSession() {
        try {
            if (arSession == null) {
                arSession = new Session(this);
                sessionInitialized = true;
            }
        } catch (UnavailableArcoreNotInstalledException e) {
            Log.e(TAG, "ARCore not installed", e);
            showToast("Please install ARCore");
        } catch (UnavailableApkTooOldException e) {
            Log.e(TAG, "APK too old", e);
            showToast("Please update ARCore");
        } catch (UnavailableSdkTooOldException e) {
            Log.e(TAG, "SDK too old", e);
            showToast("Please update the app");
        } catch (UnavailableDeviceNotCompatibleException e) {
            Log.e(TAG, "Device not compatible", e);
            showToast("This device doesn't support AR");
        } catch (UnavailableException e) {
            Log.e(TAG, "AR session could not be created", e);
            showToast("AR unavailable");
        }
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
    }

    private void startQRScanner() {
        runOnUiThread(() -> {
            IntentIntegrator integrator = new IntentIntegrator(MainActivity.this);
            integrator.setOrientationLocked(false);
            integrator.setPrompt("Scan any QR code to show the cube");
            integrator.initiateScan();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) {
                qrScanned = true;
                showToast("QR Code Scanned!");

                if (arSession != null && sessionResumed) {
                    try {
                        Frame frame = arSession.update();
                        Camera camera = frame.getCamera();
                        if (camera.getTrackingState() == TrackingState.TRACKING) {
                            // Create anchor at current camera position
                            qrAnchor = arSession.createAnchor(camera.getPose());
                            runOnUiThread(() -> floatingCube.setVisibility(View.VISIBLE));
                        }
                    } catch (CameraNotAvailableException | SessionPausedException e) {
                        Log.e(TAG, "Error during AR frame update", e);
                    }
                }
            } else {
                showToast("Scan cancelled");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (arSession != null && sessionInitialized) {
            try {
                arSession.resume();
                sessionResumed = true;
            } catch (CameraNotAvailableException e) {
                Log.e(TAG, "Camera not available", e);
                sessionResumed = false;
                showToast("Camera not available");
            }
        }
        surfaceView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        surfaceView.onPause();
        if (arSession != null) {
            arSession.pause();
            sessionResumed = false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeSession();
                startQRScanner();
            } else {
                Log.e(TAG, "Camera permission not granted");
                showToast("Camera permission required");
            }
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        if (arSession != null) {
            try {
                int[] textures = new int[1];
                GLES30.glGenTextures(1, textures, 0);
                cameraTextureId = textures[0];

                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);

                arSession.setCameraTextureName(cameraTextureId);
            } catch (Exception e) {
                Log.e(TAG, "Error setting up camera texture", e);
            }
        }

        compileShaders();
        initMatrices();
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
    }

    private void compileShaders() {
        cameraProgram = createProgram(
                "attribute vec4 vPosition;" +
                        "attribute vec2 aTexCoord;" +
                        "varying vec2 vTexCoord;" +
                        "void main() {" +
                        "  vTexCoord = aTexCoord;" +
                        "  gl_Position = vPosition;" +
                        "}",
                "#extension GL_OES_EGL_image_external : require\n" +
                        "precision mediump float;" +
                        "varying vec2 vTexCoord;" +
                        "uniform samplerExternalOES sTexture;" +
                        "void main() {" +
                        "  gl_FragColor = texture2D(sTexture, vTexCoord);" +
                        "}");

        cubeProgram = createProgram(
                "uniform mat4 uMVPMatrix;" +
                        "attribute vec4 vPosition;" +
                        "void main() {" +
                        "  gl_Position = uMVPMatrix * vPosition;" +
                        "}",
                "precision mediump float;" +
                        "void main() {" +
                        "  gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);" +
                        "}");
    }

    private void initMatrices() {
        Matrix.setIdentityM(modelMatrix, 0);
        Matrix.setLookAtM(defaultViewMatrix, 0,
                0, 0, 1,
                0, 0, 0,
                0, 1, 0);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES30.glViewport(0, 0, width, height);
        float ratio = (float) width / height;
        Matrix.perspectiveM(projectionMatrix, 0, 45, ratio, 0.1f, 100.0f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);

        if (arSession != null && sessionResumed) {
            try {
                Frame frame = arSession.update();
                Camera camera = frame.getCamera();

                if (camera.getTrackingState() == TrackingState.TRACKING) {
                    camera.getViewMatrix(viewMatrix, 0);

                    if (qrScanned && qrAnchor != null && qrAnchor.getTrackingState() == TrackingState.TRACKING) {
                        qrAnchor.getPose().toMatrix(anchorMatrix, 0);

                        // Обновляем положение куба
                        runOnUiThread(() -> floatingCube.setVisibility(View.VISIBLE));

                        // Рисуем AR-объекты
                        drawCube();
                    }
                }

                // Рендерим фид с камеры
                drawCameraFeed();

                // Отключаем тест глубины для отображения квадрата поверх
                GLES30.glDisable(GLES30.GL_DEPTH_TEST);

                // Отображаем квадрат поверх изображения
                drawOverlaySquare();

                // Включаем тест глубины обратно
                GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            } catch (CameraNotAvailableException | SessionPausedException e) {
                Log.e(TAG, "Error during AR frame update", e);
            }
        }
    }

    private void drawOverlaySquare() {
        if (cubeProgram == 0 || cameraProgram == 0) {
            Log.e(TAG, "Cube shader program or camera program not initialized.");
            return;
        }

        GLES30.glUseProgram(cubeProgram);

        // Устанавливаем матрицу модели для отображения квадрата на фиксированном месте
        Matrix.setIdentityM(modelMatrix, 0);

        // Смещаем квадрат в центр экрана (можно изменить координаты для других положений)
        Matrix.translateM(modelMatrix, 0, 0.0f, 0.0f, -0.5f);

        // Масштабируем квадрат для лучшей видимости
        Matrix.scaleM(modelMatrix, 0, 0.3f, 0.3f, 0.3f);

        // Объединяем все матрицы (проекция * вид * модель)
        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0);
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0);

        // Передаём итоговую матрицу в шейдер
        int mvpMatrixHandle = GLES30.glGetUniformLocation(cubeProgram, "uMVPMatrix");
        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);

        // Устанавливаем данные вершин
        int positionHandle = GLES30.glGetAttribLocation(cubeProgram, "vPosition");
        GLES30.glEnableVertexAttribArray(positionHandle);
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, cubeVertexBuffer);

        // Рисуем квадрат как массив индексов
        GLES30.glDrawElements(
                GLES30.GL_TRIANGLES,
                cubeIndices.length,
                GLES30.GL_UNSIGNED_SHORT,
                cubeIndexBuffer
        );

        // Отключаем атрибут после завершения отрисовки
        GLES30.glDisableVertexAttribArray(positionHandle);

        Log.d(TAG, "Square successfully rendered!");
    }


    private int createProgram(String vertexShaderCode, String fragmentShaderCode) {
        int vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode);

        int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vertexShader);
        GLES30.glAttachShader(program, fragmentShader);
        GLES30.glLinkProgram(program);

        return program;
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, shaderCode);
        GLES30.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compilation error: " + GLES30.glGetShaderInfoLog(shader));
            GLES30.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private void drawCameraFeed() {
        if (arSession == null || cameraProgram == 0) return;

        GLES30.glUseProgram(cameraProgram);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);

        int positionHandle = GLES30.glGetAttribLocation(cameraProgram, "vPosition");
        GLES30.glEnableVertexAttribArray(positionHandle);
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, cameraVertexBuffer);

        int texCoordHandle = GLES30.glGetAttribLocation(cameraProgram, "aTexCoord");
        GLES30.glEnableVertexAttribArray(texCoordHandle);
        GLES30.glVertexAttribPointer(texCoordHandle, 2, GLES30.GL_FLOAT, false, 0, cameraTexCoordBuffer);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        GLES30.glDisableVertexAttribArray(positionHandle);
        GLES30.glDisableVertexAttribArray(texCoordHandle);
    }

    private void drawCube() {
        if (cubeProgram == 0 || qrAnchor == null || qrAnchor.getTrackingState() != TrackingState.TRACKING) {
            return;
        }

        GLES30.glUseProgram(cubeProgram);

        // Получаем текущую позу якоря
        qrAnchor.getPose().toMatrix(anchorMatrix, 0);

        // Устанавливаем матрицу модели для куба
        Matrix.setIdentityM(modelMatrix, 0);

        // Перемещаем куб в позицию якоря
        Matrix.multiplyMM(modelMatrix, 0, anchorMatrix, 0, modelMatrix, 0);

        // Масштабируем куб для визуализации
        Matrix.scaleM(modelMatrix, 0, 0.2f, 0.2f, 0.2f);

        // Вычисляем итоговую матрицу MVP (Model-View-Projection)
        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0);
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0);

        // Передаём матрицу MVP в шейдер
        int mvpMatrixHandle = GLES30.glGetUniformLocation(cubeProgram, "uMVPMatrix");
        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);

        // Устанавливаем атрибуты вершин
        int positionHandle = GLES30.glGetAttribLocation(cubeProgram, "vPosition");
        GLES30.glEnableVertexAttribArray(positionHandle);
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, cubeVertexBuffer);

        // Рисуем куб
        GLES30.glDrawElements(
                GLES30.GL_TRIANGLES,
                cubeIndices.length,
                GLES30.GL_UNSIGNED_SHORT,
                cubeIndexBuffer
        );

        GLES30.glDisableVertexAttribArray(positionHandle);
        Log.d(TAG, "Cube successfully rendered!");
    }
}