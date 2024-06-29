package com.example.hhawk4

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import dji.common.product.Model
import org.opencv.android.Utils
import org.opencv.core.Mat
import okhttp3.OkHttpClient
import dji.common.flightcontroller.FlightControllerState
import dji.sdk.flightcontroller.FlightController
import dji.sdk.battery.Battery
import dji.common.battery.BatteryState
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.core.view.GestureDetectorCompat
import com.google.android.gms.maps.MapView

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
    private lateinit var connectionStatus: TextView
    private lateinit var batteryLevelTextView: TextView
    private lateinit var altitudeTextView: TextView
    private lateinit var mapView: MapView
    private lateinit var gestureDetector: GestureDetectorCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        videoTextureView = findViewById(R.id.video_previewer_surface)
        mapView = findViewById(R.id.map_view)

        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleViewSizes()
                return true
            }
        })

        videoTextureView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
        mapView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
        setupBatteryStateListener()
        setupFlightControllerStateListener()
    }
    private fun initUi() {
        videoTextureView = findViewById(R.id.video_previewer_surface)
        recordingTime = findViewById(R.id.timer)
        captureBtn = findViewById(R.id.btn_capture)
        recordBtn = findViewById(R.id.btn_record)
        shootPhotoModeBtn = findViewById(R.id.btn_shoot_photo_mode)
        recordVideoModeBtn = findViewById(R.id.btn_record_video_mode)
        connectionStatus = findViewById(R.id.connection_status)
        videoTextureView.surfaceTextureListener = this

        captureBtn.setOnClickListener(this)
        shootPhotoModeBtn.setOnClickListener(this)
        recordVideoModeBtn.setOnClickListener(this)

        recordingTime.visibility = View.INVISIBLE



        // Set OnClickListener for the "Open" button
        val btnOpen = findViewById<Button>(R.id.btn_open)
        if (btnOpen != null) {
            btnOpen.setOnClickListener {

            }
        } else {
            Log.e(TAG, "Button 'btn_open' not found in layout")
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


        val bitmap = videoTextureView.bitmap ?: return
        val frame = Mat()
        Utils.bitmapToMat(bitmap, frame)
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.btn_capture -> captureAction()
            R.id.btn_shoot_photo_mode -> switchCameraMode(CameraMode.SHOOT_PHOTO)
            R.id.btn_record_video_mode -> switchCameraMode(CameraMode.RECORD_VIDEO)
            else -> {}
        }
    }
    private fun setupBatteryStateListener() {
        val aircraft = getProductInstance() as? Aircraft
        aircraft?.battery?.setStateCallback { batteryState ->
            runOnUiThread {
                batteryLevelTextView.text = "Battery: ${batteryState.chargeRemainingInPercent}%"
            }
        }
    }

    private fun setupFlightControllerStateListener() {
        val aircraft = getProductInstance() as? Aircraft
        aircraft?.flightController?.setStateCallback { flightControllerState ->
            runOnUiThread {
                altitudeTextView.text = "Altitude: ${flightControllerState.aircraftLocation.altitude}m"
            }
        }
    }
    private fun toggleViewSizes() {
        // Toggle layout parameters for full screen and minimized view
        val paramsFullScreen = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        val paramsMinimized = FrameLayout.LayoutParams(300, 300) // Example size, adjust as needed
        paramsMinimized.setMargins(50, 50, 0, 0) // Example margins, adjust as needed

        if (videoTextureView.layoutParams.height == FrameLayout.LayoutParams.MATCH_PARENT) {
            // Video is currently full screen, make it small
            videoTextureView.layoutParams = paramsMinimized
            mapView.layoutParams = paramsFullScreen
        } else {
            // Map is currently full screen, make it small
            videoTextureView.layoutParams = paramsFullScreen
            mapView.layoutParams = paramsMinimized
        }
    }

}
