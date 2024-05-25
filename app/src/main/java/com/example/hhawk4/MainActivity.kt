package com.example.hhawk4

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dji.common.camera.SettingsDefinitions.CameraMode
import dji.common.camera.SettingsDefinitions.ShootPhotoMode
import dji.common.error.DJIError
import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.products.Aircraft
import dji.sdk.products.HandHeld
import dji.sdk.sdkmanager.DJISDKManager
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import dji.common.product.Model
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.opencv.core.CvType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import org.opencv.core.Scalar
import org.opencv.video.KalmanFilter
import org.opencv.core.Core

@Serializable
data class Detection(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val label: String,
    val id: Int, // Add this line
    val confidence: Float // Add confidence if needed
)

data class FrameData(val timestamp: Long, val frame: Mat, var detections: List<Detection>?)

data class TrackedObject(
    val id: Int,
    var bbox: Detection,
    var lost: Boolean = false
)

object TrackingUtils {
    private var nextId = 0

    fun getNextId(): Int {
        return nextId++
    }

    fun calculateIoU(boxA: Detection, boxB: Detection): Double {
        val xA = maxOf(boxA.x, boxB.x)
        val yA = maxOf(boxA.y, boxB.y)
        val xB = minOf(boxA.x + boxA.width, boxB.x + boxB.width)
        val yB = minOf(boxA.y + boxA.height, boxB.y + boxB.height)

        val interArea = maxOf(0, xB - xA) * maxOf(0, yB - yA)

        val boxAArea = boxA.width * boxA.height
        val boxBArea = boxB.width * boxB.height

        return interArea / (boxAArea + boxBArea - interArea).toDouble()
    }
}

class KalmanTracker {
    private val kf = KalmanFilter(4, 2, 0)
    private val statePost = Mat(4, 1, CvType.CV_32F)
    private val meas = Mat(2, 1, CvType.CV_32F)

    init {
        // Transition matrix (A)
        val transitionMatrix = Mat.eye(4, 4, CvType.CV_32F)
        transitionMatrix.put(0, 2, 1.0)
        transitionMatrix.put(1, 3, 1.0)
        kf.set_transitionMatrix(transitionMatrix)

        // Measurement matrix (H)
        val measurementMatrix = Mat.zeros(2, 4, CvType.CV_32F)
        measurementMatrix.put(0, 0, 1.0)
        measurementMatrix.put(1, 1, 1.0)
        kf.set_measurementMatrix(measurementMatrix)

        // Process noise covariance matrix (Q)
        val processNoiseCov = Mat.eye(4, 4, CvType.CV_32F)
        processNoiseCov.setTo(Scalar.all(1e-2))
        kf.set_processNoiseCov(processNoiseCov)

        // Measurement noise covariance matrix (R)
        val measurementNoiseCov = Mat.eye(2, 2, CvType.CV_32F)
        measurementNoiseCov.setTo(Scalar.all(1e-1))
        kf.set_measurementNoiseCov(measurementNoiseCov)

        // Posteriori error estimate covariance matrix (P)
        val errorCovPost = Mat.eye(4, 4, CvType.CV_32F)
        errorCovPost.setTo(Scalar.all(0.1))
        kf.set_errorCovPost(errorCovPost)
    }

    fun predict(): Mat {
        return kf.predict()
    }

    fun correct(measurement: Mat): Mat {
        return kf.correct(measurement)
    }

    fun setInitialState(x: Float, y: Float) {
        statePost.put(0, 0, x.toDouble())
        statePost.put(1, 0, y.toDouble())
        statePost.put(2, 0, 0.0)
        statePost.put(3, 0, 0.0)
        kf.set_statePost(statePost)
    }
}


class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener, View.OnClickListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSION_CODE = 125
        private val REQUIRED_PERMISSION_LIST = arrayOf(
            Manifest.permission.VIBRATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    }

    private val missingPermission = mutableListOf<String>()
    private lateinit var mHandler: Handler
    private var codecManager: DJICodecManager? = null
    private lateinit var videoTextureView: TextureView
    private lateinit var captureBtn: Button
    private lateinit var shootPhotoModeBtn: Button
    private lateinit var recordVideoModeBtn: Button
    private lateinit var recordBtn: ToggleButton
    private lateinit var recordingTime: TextView
    private var receivedVideoDataListener: VideoFeeder.VideoDataListener? = null
    private val client = OkHttpClient()
    private val trackedObjects = mutableListOf<TrackedObject>()
    private val iouThreshold = 0.3 // Threshold for Intersection over Union
    private var frameCount = 0
    private val frameDelay = 6 // Assume a 5-second delay
    private val frameQueue: ConcurrentLinkedQueue<FrameData> = ConcurrentLinkedQueue()
    private val trackers = mutableListOf<KalmanTracker>()
    private var frameProcessingDelay = 5000L // Initial delay in milliseconds
    private val serverUrl = "http://10.42.0.1:5000"
    private lateinit var connectionStatus: TextView
    private lateinit var detectionStatus: TextView
    private var isProcessingFrame = false

    private val colors = arrayOf(
        Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
        Color.CYAN, Color.MAGENTA, Color.LTGRAY, Color.DKGRAY,
        Color.WHITE, Color.GRAY, Color.rgb(255, 165, 0), // Orange
        Color.rgb(255, 20, 147), // DeepPink
        Color.rgb(75, 0, 130), // Indigo
        Color.rgb(173, 216, 230) // LightBlue
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed")
        } else {
            Log.d(TAG, "OpenCV initialization succeeded")
        }

        // Initialize UI elements
        initUi()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkAndRequestPermissions()
        }

        receivedVideoDataListener = VideoFeeder.VideoDataListener { videoBuffer, size ->
            codecManager?.sendDataToDecoder(videoBuffer, size)
        }

        // Initialize TextViews
        connectionStatus = findViewById(R.id.connection_status)
        detectionStatus = findViewById(R.id.detection_status)

        getCameraInstance()?.let { camera ->
            camera.setSystemStateCallback {
                it.let { systemState ->
                    val recordTime = systemState.currentVideoRecordingTimeInSeconds
                    val minutes = (recordTime % 3600) / 60
                    val seconds = recordTime % 60
                    val timeString = String.format("%02d:%02d", minutes, seconds)

                    runOnUiThread {
                        recordingTime.text = timeString
                        if (systemState.isRecording) {
                            recordingTime.visibility = View.VISIBLE
                        } else {
                            recordingTime.visibility = View.INVISIBLE
                        }
                    }
                }
            }
        }
    }
    private fun initUi() {
        videoTextureView = findViewById(R.id.video_previewer_surface)
        recordingTime = findViewById(R.id.timer)
        captureBtn = findViewById(R.id.btn_capture)
        recordBtn = findViewById(R.id.btn_record)
        shootPhotoModeBtn = findViewById(R.id.btn_shoot_photo_mode)
        recordVideoModeBtn = findViewById(R.id.btn_record_video_mode)
        connectionStatus = findViewById(R.id.connection_status)
        detectionStatus = findViewById(R.id.detection_status) // Ensure this is in your layout

        videoTextureView.surfaceTextureListener = this

        captureBtn.setOnClickListener(this)
        shootPhotoModeBtn.setOnClickListener(this)
        recordVideoModeBtn.setOnClickListener(this)

        recordingTime.visibility = View.INVISIBLE

        recordBtn.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startRecord()
            } else {
                stopRecord()
            }
        }

        // Set OnClickListener for the "Open" button
        val btnOpen = findViewById<Button>(R.id.btn_open)
        if (btnOpen != null) {
            btnOpen.setOnClickListener {
                checkServerConnection()
            }
        } else {
            Log.e(TAG, "Button 'btn_open' not found in layout")
        }
    }
    private fun stopRecord() {
        val camera = getCameraInstance() ?: return
        camera.stopRecordVideo {
            if (it == null) {
                showToast("Stop Recording: Success")
            } else {
                showToast("Stop Recording: Error ${it.description}")
            }
        }
    }

    private fun startRecord() {
        val camera = getCameraInstance() ?: return
        camera.startRecordVideo {
            if (it == null) {
                showToast("Record Video: Success")
            } else {
                showToast("Record Video Error: ${it.description}")
            }
        }
    }

    private fun captureAction() {
        val camera = getCameraInstance() ?: return
        camera.setShootPhotoMode(ShootPhotoMode.SINGLE) { djiError ->
            if (djiError == null) {
                lifecycleScope.launch {
                    camera.startShootPhoto { djiErrorSecond ->
                        if (djiErrorSecond == null) {
                            showToast("Take photo: success")
                        } else {
                            showToast("Take Photo Failure: ${djiErrorSecond?.description}")
                        }
                    }
                }
            }
        }
    }

    private fun switchCameraMode(cameraMode: CameraMode) {
        val camera = getCameraInstance() ?: return
        camera.setMode(cameraMode) { error ->
            if (error == null) {
                showToast("Switch Camera Mode Succeeded")
            } else {
                showToast("Switch Camera Error: ${error.description}")
            }
        }
    }

    private fun checkAndRequestPermissions() {
        for (eachPermission in REQUIRED_PERMISSION_LIST) {
            if (ContextCompat.checkSelfPermission(this, eachPermission) != PackageManager.PERMISSION_GRANTED) {
                missingPermission.add(eachPermission)
                Log.d(TAG, "Missing permission: $eachPermission")
            }
        }
        if (missingPermission.isNotEmpty()) {
            showToast("Missing permissions: ${missingPermission.joinToString(", ")}")
            ActivityCompat.requestPermissions(this, missingPermission.toTypedArray(), REQUEST_PERMISSION_CODE)
        } else {
            showToast("All permissions granted")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_CODE) {
            val stillMissingPermissions = mutableListOf<String>()
            grantResults.indices.forEach { i ->
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    stillMissingPermissions.add(permissions[i])
                }
            }
            if (stillMissingPermissions.isEmpty()) {
                showToast("All permissions now granted")
            } else {
                showToast("Permissions still missing: ${stillMissingPermissions.joinToString(", ")}")
                guideUserToSettings()
            }
        }
    }

    private fun guideUserToSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
    }

    private fun showToast(text: String) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleDetectionResponse(response: String, responseTime: Long) {
        val detections = parseDetections(response)

        if (frameQueue.isNotEmpty()) {
            frameQueue.lastOrNull()?.detections = detections.map { detection ->
                val originalWidth = videoTextureView.width
                val originalHeight = videoTextureView.height
                Detection(
                    (detection.x * originalWidth / 10000),
                    (detection.y * originalHeight / 10000),
                    (detection.width * originalWidth / 10000),
                    (detection.height * originalHeight / 10000),
                    detection.label,
                    detection.id,
                    detection.confidence
                )
            }
        }
        updateTrackedObjects(frameQueue.lastOrNull()?.detections ?: emptyList())
        interpolateFrames()
        adjustDelay(responseTime)
    }

    private fun parseDetections(response: String): List<Detection> {
        return response.lines().mapNotNull { line ->
            val parts = line.split(" ")
            if (parts.size == 6) {
                val id = parts[0].toInt()
                val x = parts[1].toFloat()
                val y = parts[2].toFloat()
                val width = parts[3].toFloat()
                val height = parts[4].toFloat()
                val confidence = parts[5].toFloat()
                Detection((x * 10000).toInt(), (y * 10000).toInt(), (width * 10000).toInt(), (height * 10000).toInt(), id.toString(), id, confidence)
            } else {
                null
            }
        }
    }

    private fun drawBoxes(detections: List<Detection>) {
        val canvas = videoTextureView.lockCanvas()
        if (canvas != null) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR) // Clear the canvas
            detections.forEach { detection ->
                val paint = Paint().apply {
                    color = colors[detection.id % colors.size]
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                val rect = Rect(
                    detection.x,
                    detection.y,
                    detection.x + detection.width,
                    detection.y + detection.height
                )
                canvas.drawRect(rect, paint)
                // Draw the label
                val labelPaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 20f
                    style = Paint.Style.FILL
                }
                canvas.drawText(detection.label, detection.x.toFloat(), detection.y.toFloat() - 10, labelPaint)
            }
            videoTextureView.unlockCanvasAndPost(canvas)
        }
    }

    private fun getProductInstance(): BaseProduct? {
        return DJISDKManager.getInstance().product
    }

    private fun getCameraInstance(): Camera? {
        val product = getProductInstance() ?: return null
        return when (product) {
            is Aircraft -> product.camera
            is HandHeld -> product.camera
            else -> null
        }
    }

    override fun onResume() {
        super.onResume()
        initPreviewer()
    }

    override fun onPause() {
        uninitPreviewer()
        super.onPause()
    }

    override fun onDestroy() {
        uninitPreviewer()
        super.onDestroy()
    }

    private fun initPreviewer() {
        val product = getProductInstance() ?: return
        if (!product.isConnected) {
            showToast(getString(R.string.disconnected))
        } else {
            videoTextureView.surfaceTextureListener = this
            if (product.model != Model.UNKNOWN_AIRCRAFT) {
                receivedVideoDataListener?.let {
                    VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(it)
                }
            }
        }
    }

    private fun uninitPreviewer() {
        VideoFeeder.getInstance().primaryVideoFeed.removeVideoDataListener(receivedVideoDataListener)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (codecManager == null) {
            codecManager = DJICodecManager(this, surface, width, height)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        codecManager?.cleanSurface()
        codecManager = null
        VideoFeeder.getInstance().primaryVideoFeed.removeVideoDataListener(receivedVideoDataListener)
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (isProcessingFrame) {
            return // Skip sending the frame if one is already being processed
        }

        val bitmap = videoTextureView.bitmap ?: return
        val frame = Mat()
        Utils.bitmapToMat(bitmap, frame)
        val resizedFrame = resizeFrame(frame)

        // Store the frame with the current timestamp
        frameQueue.add(FrameData(System.currentTimeMillis(), resizedFrame, null))

        // Send the frame to the server for detection
        sendFrameToServer(resizedFrame)
    }

    private fun resizeFrame(frame: Mat): Mat {
        val resizedFrame = Mat()
        Imgproc.resize(frame, resizedFrame, Size(640.0, 640.0))
        return resizedFrame
    }
    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.btn_capture -> captureAction()
            R.id.btn_shoot_photo_mode -> switchCameraMode(CameraMode.SHOOT_PHOTO)
            R.id.btn_record_video_mode -> switchCameraMode(CameraMode.RECORD_VIDEO)
            else -> {}
        }
    }

    private fun sendFrameToServer(frame: Mat) {
        isProcessingFrame = true // Set the flag to true when a frame is being processed
        val startTime = System.currentTimeMillis()
        val byteArrayOutputStream = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(frame.cols(), frame.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(frame, bitmap)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "frame.jpg",
                RequestBody.create("image/jpeg".toMediaTypeOrNull(), byteArray)
            )
            .build()

        val request = Request.Builder()
            .url("http://10.42.0.1:5000/detect")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Failed to send frame: ${e.message}")
                runOnUiThread {
                    detectionStatus.text = "Detection Status: Failed"
                    showToast("Failed to send frame to server: ${e.message}")
                    isProcessingFrame = false // Clear the flag on failure
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val responseTime = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    responseBody?.let {
                        handleDetectionResponse(it, responseTime)
                        runOnUiThread {
                            detectionStatus.text = "Detection Status: Success"
                            showToast("Frame sent to server successfully")
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to get response: ${response.message}")
                    runOnUiThread {
                        detectionStatus.text = "Detection Status: Failed"
                        showToast("Failed to get response from server: ${response.message}")
                    }
                }
                isProcessingFrame = false // Clear the flag on success or error
            }
        })
    }
    private fun updateTrackedObjects(detections: List<Detection>) {
        val startTime = System.currentTimeMillis() // Start time for tracking

        val updatedTrackedObjects = mutableListOf<TrackedObject>()

        for (detection in detections) {
            var matched = false

            for (trackedObject in trackedObjects) {
                val iou = TrackingUtils.calculateIoU(trackedObject.bbox, detection)
                if (iou > iouThreshold) {
                    val tracker = trackers[trackedObject.id]
                    val meas = Mat(2, 1, CvType.CV_32F)
                    meas.put(0, 0, floatArrayOf(detection.x.toFloat()))
                    meas.put(1, 0, floatArrayOf(detection.y.toFloat()))
                    tracker.correct(meas)

                    trackedObject.bbox = detection
                    trackedObject.lost = false
                    updatedTrackedObjects.add(trackedObject)
                    matched = true
                    break
                }
            }

            if (!matched) {
                val tracker = KalmanTracker()
                tracker.setInitialState(detection.x.toFloat(), detection.y.toFloat())
                trackers.add(tracker)
                updatedTrackedObjects.add(TrackedObject(TrackingUtils.getNextId(), detection))
            }
        }

        for (trackedObject in trackedObjects) {
            if (!updatedTrackedObjects.contains(trackedObject)) {
                trackedObject.lost = true
                updatedTrackedObjects.add(trackedObject)
            }
        }

        trackedObjects.clear()
        trackedObjects.addAll(updatedTrackedObjects)

        val trackingTime = System.currentTimeMillis() - startTime // Time taken for tracking

        // Adjust delay based on tracking time
        adjustDelay(trackingTime)
    }

    private fun interpolateDetections(firstDetections: List<Detection>, secondDetections: List<Detection>, factor: Double): List<Detection> {
        val interpolatedDetections = mutableListOf<Detection>()
        for (i in firstDetections.indices) {
            val detection1 = firstDetections[i]
            val detection2 = secondDetections.find { it.label == detection1.label } ?: continue

            val interpolatedDetection = Detection(
                x = interpolate(detection1.x, detection2.x, factor),
                y = interpolate(detection1.y, detection2.y, factor),
                width = interpolate(detection1.width, detection2.width, factor),
                height = interpolate(detection1.height, detection2.height, factor),
                label = detection1.label,
                id = detection1.id, // Ensure id is passed
                confidence = detection1.confidence // Ensure confidence is passed
            )
            interpolatedDetections.add(interpolatedDetection)
        }
        return interpolatedDetections
    }

    private fun interpolate(start: Int, end: Int, factor: Double): Int {
        return (start + factor * (end - start)).toInt()
    }
    private fun interpolateFrames() {
        val framesWithDetections = frameQueue.filter { it.detections != null }

        if (framesWithDetections.size < 2) return

        val firstFrame = framesWithDetections[framesWithDetections.size - 2]
        val secondFrame = framesWithDetections[framesWithDetections.size - 1]

        val startTime = System.currentTimeMillis() // Start time for interpolation
        val frameCount = frameQueue.size - 2

        for (i in 1 until frameCount) {
            val interpolatedFrame = frameQueue.poll()
            val interpolationFactor = i.toDouble() / frameCount

            val interpolatedDetections = interpolateDetections(firstFrame.detections!!, secondFrame.detections!!, interpolationFactor)
            interpolatedFrame.detections = interpolatedDetections
            drawBoxes(interpolatedFrame.detections!!)
        }

        val interpolationTime = System.currentTimeMillis() - startTime // Time taken for interpolation

        // Remove processed frames from the queue
        frameQueue.poll()
        frameQueue.poll()

        // Adjust delay based on interpolation time
        adjustDelay(interpolationTime)
    }
    private fun adjustDelay(processingTime: Long) {
        frameProcessingDelay += processingTime // Adjust delay based on processing time
    }
    private fun checkServerConnection() {
        val serverUrl = "http://10.42.0.1:5000/ping" // Replace with your server IP and port
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Failed to connect to server: ${e.message}")
                runOnUiThread {
                    connectionStatus.text = "Raspberry Pi: Disconnected"
                    showToast("Failed to connect to server")
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    runOnUiThread {
                        connectionStatus.text = "Raspberry Pi: Connected"
                        showToast("Connected to server")
                    }
                } else {
                    runOnUiThread {
                        connectionStatus.text = "Raspberry Pi: Disconnected"
                        showToast("Server response failed")
                    }
                }
            }
        })
    }
}
