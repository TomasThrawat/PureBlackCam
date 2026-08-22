package com.pureblack.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pure Black Cam — minimalist, fully offline camera.
 *
 * No INTERNET permission is declared anywhere in the manifest, so this app
 * is physically unable to make network calls: nothing it captures can ever
 * leave the device. Photos and video are written only to local MediaStore
 * (Pictures/PureBlackCam, Movies/PureBlackCam).
 *
 * Just 2 real functions:
 *  1) Camera  -> photo capture + video recording (720p/60fps or 1080p/60fps)
 *  2) Flashlight -> torch toggle
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: Button
    private lateinit var btnRecord: Button
    private lateinit var btnFlash: Button
    private lateinit var btnRes: Button
    private lateinit var statusText: TextView

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var isRecording = false
    private var torchOn = false
    private var use1080p = false // false = 720p/60fps, true = 1080p/60fps

    private lateinit var cameraExecutor: ExecutorService

    private val hasAudioPermission: Boolean
        get() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private val requiredPermissions: Array<String>
        get() = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            startCamera()
        } else {
            Toast.makeText(this, getString(R.string.permissions_needed), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        btnCapture = findViewById(R.id.btnCapture)
        btnRecord = findViewById(R.id.btnRecord)
        btnFlash = findViewById(R.id.btnFlash)
        btnRes = findViewById(R.id.btnRes)
        statusText = findViewById(R.id.statusText)

        cameraExecutor = Executors.newSingleThreadExecutor()

        updateResButtonLabel()

        btnCapture.setOnClickListener { takePhoto() }
        btnRecord.setOnClickListener { toggleRecording() }
        btnFlash.setOnClickListener { toggleFlash() }
        btnRes.setOnClickListener { toggleResolution() }

        if (hasPermissions()) {
            startCamera()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    // ---------- Camera setup ----------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val targetQuality = if (use1080p) Quality.FHD else Quality.HD
        val qualitySelector = QualitySelector.from(
            targetQuality,
            FallbackStrategy.lowerQualityOrHigherThan(targetQuality)
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()

        // Request 60fps via Camera2Interop. This is a request, not a guarantee:
        // if the sensor/ISP on this device can't do 60fps at the chosen
        // resolution, the camera HAL silently falls back to its nearest
        // supported range (commonly 30fps on budget devices).
        val videoCaptureBuilder = VideoCapture.Builder(recorder)
        Camera2Interop.Extender(videoCaptureBuilder)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(60, 60)
            )
        videoCapture = videoCaptureBuilder.build()

        try {
            camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                videoCapture
            )
            // Re-apply torch state after rebind (e.g. after switching resolution).
            if (torchOn && camera?.cameraInfo?.hasFlashUnit() == true) {
                camera?.cameraControl?.enableTorch(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "bindToLifecycle failed", e)
            Toast.makeText(this, getString(R.string.camera_start_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Photo ----------

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = fileName("IMG")
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PureBlackCam")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@MainActivity, getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    Toast.makeText(this@MainActivity, getString(R.string.capture_failed), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ---------- Video ----------

    private fun toggleRecording() {
        if (isRecording) {
            activeRecording?.stop()
            activeRecording = null
            return
        }

        val capture = videoCapture ?: return
        val name = fileName("VID")
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/PureBlackCam")
            }
        }
        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        var pending = capture.output.prepareRecording(this, outputOptions)
        if (hasAudioPermission) {
            pending = pending.withAudioEnabled()
        }

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isRecording = true
                    btnRecord.text = getString(R.string.btn_record_stop)
                    setResAndFlashEnabled(false)
                    statusText.visibility = android.view.View.VISIBLE
                    statusText.text = getString(R.string.status_recording)
                }
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    btnRecord.text = getString(R.string.btn_record_start)
                    setResAndFlashEnabled(true)
                    statusText.visibility = android.view.View.INVISIBLE
                    if (!event.hasError()) {
                        Toast.makeText(this, getString(R.string.video_saved), Toast.LENGTH_SHORT).show()
                    } else {
                        Log.e(TAG, "Video capture error: ${event.error}")
                        Toast.makeText(this, getString(R.string.capture_failed), Toast.LENGTH_SHORT).show()
                    }
                }
                else -> Unit
            }
        }
    }

    private fun setResAndFlashEnabled(enabled: Boolean) {
        btnRes.isEnabled = enabled
        btnRes.alpha = if (enabled) 1f else 0.4f
    }

    // ---------- Flashlight ----------

    private fun toggleFlash() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit() != true) {
            Toast.makeText(this, getString(R.string.camera_start_failed), Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        btnFlash.text = getString(if (torchOn) R.string.btn_flash_on else R.string.btn_flash_off)
    }

    // ---------- Resolution ----------

    private fun toggleResolution() {
        if (isRecording) return
        use1080p = !use1080p
        updateResButtonLabel()
        bindUseCases()
    }

    private fun updateResButtonLabel() {
        btnRes.text = getString(if (use1080p) R.string.res_1080 else R.string.res_720)
    }

    // ---------- Helpers ----------

    private fun fileName(prefix: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        return "${prefix}_$stamp"
    }

    override fun onDestroy() {
        super.onDestroy()
        activeRecording?.stop()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "PureBlackCam"
    }
}
