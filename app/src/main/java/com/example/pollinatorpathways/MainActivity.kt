package com.example.pollinatorpathways

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

private const val FRAME_INTERVAL_MS = 5_000L

data class GpsSample(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double
)

data class FrameRecord(
    val frameId: Int,
    val timestampUtc: String,
    val videoOffsetMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val gpsTimeDifferenceMs: Long?,
    val imageFilename: String
)

class MainActivity : ComponentActivity() {
    private val samples = mutableStateListOf<GpsSample>()
    private val frames = mutableStateListOf<FrameRecord>()
    private var isRecording by mutableStateOf(false)
    private var isProcessing by mutableStateOf(false)
    private var status by mutableStateOf("Ready — grant camera and location permissions to begin")
    private var cameraReady by mutableStateOf(false)
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: androidx.camera.video.Recording? = null
    private var previewView: PreviewView? = null
    private var locationCallback: LocationCallback? = null
    private var surveyStartMs: Long = 0L
    private var currentVideoFile: File? = null
    private var currentSurveyDir: File? = null
    private var pendingStart = false
    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val utcFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private fun utc(ms: Long): String = synchronized(utcFormatter) { utcFormatter.format(Date(ms)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { CollectorScreen() }
            }
        }
    }

    @Composable
    private fun CollectorScreen() {
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val locationAllowed = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true || hasLocationPermission()
            val cameraAllowed = result[Manifest.permission.CAMERA] == true || hasCameraPermission()
            if (locationAllowed && cameraAllowed) {
                pendingStart = true
                previewView?.let { bindCamera(it) }
                status = "Permissions granted — preparing camera"
            } else {
                status = "Camera and location permissions are required for a survey"
            }
        }

        val gpsExportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv")
        ) { uri -> if (uri != null) exportGpsCsv(uri) else status = "GPS CSV export cancelled" }
        val frameExportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv")
        ) { uri -> if (uri != null) exportFramesCsv(uri) else status = "Frame CSV export cancelled" }
        val bundleExportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri -> if (uri != null) exportSurveyZip(uri) else status = "Survey ZIP export cancelled" }

        val context = LocalContext.current
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Pollen Pathways", style = MaterialTheme.typography.headlineMedium)
            Text("Park survey · GPS + video + automatic frames", style = MaterialTheme.typography.titleMedium)
            Text("Status: $status")
            Text("GPS fixes: ${samples.size}    Extracted frames: ${frames.size}")

            AndroidView(
                modifier = Modifier.fillMaxWidth().height(230.dp),
                factory = { ctx ->
                    PreviewView(ctx).also { view ->
                        view.scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewView = view
                        if (hasCameraPermission()) bindCamera(view)
                    }
                },
                update = { view -> previewView = view }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !isRecording && !isProcessing,
                    onClick = {
                        if (hasLocationPermission() && hasCameraPermission()) {
                            if (cameraReady) startSurvey() else {
                                pendingStart = true
                                previewView?.let { bindCamera(it) }
                                status = "Preparing camera…"
                            }
                        } else {
                            permissionLauncher.launch(arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.CAMERA
                            ))
                        }
                    }
                ) { Text("Start survey") }
                Button(enabled = isRecording, onClick = { stopSurvey() }) { Text("Stop survey") }
            }

            Text("Frame interval: 5 seconds. Frames are extracted after recording stops.", style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = samples.isNotEmpty() && !isRecording && !isProcessing,
                    onClick = { gpsExportLauncher.launch("pollen_pathways_gps.csv") }
                ) { Text("Export GPS CSV") }
                OutlinedButton(
                    enabled = frames.isNotEmpty() && !isRecording && !isProcessing,
                    onClick = { frameExportLauncher.launch("pollen_pathways_frames.csv") }
                ) { Text("Export frame CSV") }
            }
            OutlinedButton(
                enabled = currentSurveyDir != null && frames.isNotEmpty() && !isRecording && !isProcessing,
                onClick = { bundleExportLauncher.launch("pollen_pathways_survey.zip") }
            ) { Text("Export survey ZIP (video + frames + CSVs)") }
            Text(
                "Survey files are stored in the app's PollenPathways folder. Keep the app open, " +
                    "walk safely on public paths, and avoid filming identifiable people. GPS readings " +
                    "are matched to each frame by timestamp; the CSV records the GPS time difference.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun bindCamera(view: PreviewView) {
        if (!hasCameraPermission()) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                provider.bindToLifecycle(this as LifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                videoCapture = capture
                cameraReady = true
                status = "Camera ready — start a survey when you are at your sampling route"
                if (pendingStart && hasLocationPermission()) {
                    pendingStart = false
                    startSurvey()
                }
            } catch (e: Exception) {
                cameraReady = false
                pendingStart = false
                status = "Could not initialise camera: ${e.message ?: "unknown error"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @Suppress("MissingPermission")
    private fun startSurvey() {
        if (isRecording || isProcessing) return
        if (!hasLocationPermission() || !hasCameraPermission()) {
            status = "Grant camera and location permissions first"
            return
        }
        val capture = videoCapture
        if (capture == null) {
            pendingStart = true
            previewView?.let { bindCamera(it) }
            status = "Preparing camera…"
            return
        }

        samples.clear()
        frames.clear()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val surveyDir = File(base, "PollenPathways/survey_$stamp")
        if (!surveyDir.mkdirs() && !surveyDir.isDirectory) {
            status = "Could not create survey folder"
            return
        }
        currentSurveyDir = surveyDir
        val videoFile = File(surveyDir, "survey_$stamp.mp4")
        currentVideoFile = videoFile
        surveyStartMs = System.currentTimeMillis()

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMinUpdateDistanceMeters(0f)
            .build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location: Location ->
                    samples.add(GpsSample(
                        timestampMs = location.time,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy,
                        altitudeMeters = if (location.hasAltitude()) location.altitude else Double.NaN
                    ))
                }
                if (isRecording) status = "Survey recording — ${samples.size} GPS fixes"
            }
        }
        try {
            fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
                .addOnFailureListener { error -> status = "GPS start failed: ${error.message}" }
            val outputOptions = FileOutputOptions.Builder(videoFile).build()
            activeRecording = capture.output.prepareRecording(this, outputOptions).start(
                ContextCompat.getMainExecutor(this)
            ) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        surveyStartMs = System.currentTimeMillis()
                        isRecording = true
                        status = "Survey recording — walk steadily and keep the camera pointed at vegetation"
                    }
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        if (event.hasError()) {
                            status = "Video recording failed (code ${event.error}); GPS fixes: ${samples.size}"
                            isRecording = false
                            stopGpsUpdates()
                        } else {
                            isRecording = false
                            stopGpsUpdates()
                            status = "Video saved — extracting frames every 5 seconds…"
                            extractFrames(videoFile, surveyStartMs, surveyDir, samples.toList())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            stopGpsUpdates()
            isRecording = false
            status = "Could not start survey: ${e.message ?: "unknown error"}"
        }
    }

    private fun stopSurvey() {
        if (!isRecording) return
        isRecording = false
        status = "Stopping video…"
        activeRecording?.stop()
    }

    private fun stopGpsUpdates() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun extractFrames(videoFile: File, recordingStartMs: Long, surveyDir: File, gpsSnapshot: List<GpsSample>) {
        isProcessing = true
        processingExecutor.execute {
            val newFrames = mutableListOf<FrameRecord>()
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(videoFile.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (durationMs <= 0L) error("Could not read video duration")
                val frameDir = File(surveyDir, "frames")
                if (!frameDir.mkdirs() && !frameDir.isDirectory) error("Could not create frames folder")
                var offset = 500L
                var id = 1
                while (offset < durationMs) {
                    val bitmap: Bitmap? = retriever.getFrameAtTime(offset * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap != null) {
                        val filename = "frame_%04d_%06dms.jpg".format(Locale.US, id, offset)
                        val imageFile = File(frameDir, filename)
                        FileOutputStream(imageFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        bitmap.recycle()
                        val frameTimeMs = recordingStartMs + offset
                        val nearest = gpsSnapshot.minByOrNull { abs(it.timestampMs - frameTimeMs) }
                        newFrames.add(FrameRecord(
                            frameId = id,
                            timestampUtc = utc(frameTimeMs),
                            videoOffsetMs = offset,
                            latitude = nearest?.latitude,
                            longitude = nearest?.longitude,
                            accuracyMeters = nearest?.accuracyMeters,
                            gpsTimeDifferenceMs = nearest?.let { abs(it.timestampMs - frameTimeMs) },
                            imageFilename = filename
                        ))
                        id++
                    }
                    offset += FRAME_INTERVAL_MS
                }
                runOnUiThread {
                    frames.clear()
                    frames.addAll(newFrames)
                    isProcessing = false
                    status = "Done — extracted ${newFrames.size} frames. Video and images saved in the survey folder."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isProcessing = false
                    status = "Frame extraction failed: ${e.message ?: "unknown error"}. Video is retained."
                }
            } finally {
                try { retriever?.release() } catch (_: Exception) { }
            }
        }
    }

    private fun exportGpsCsv(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write("sample_id,timestamp_utc,latitude,longitude,accuracy_m,altitude_m\n")
                samples.forEachIndexed { index, sample ->
                    writer.write("${index + 1},${utc(sample.timestampMs)},${sample.latitude},${sample.longitude},${sample.accuracyMeters},${sample.altitudeMeters}\n")
                }
            } ?: error("Could not open selected file")
            status = "Exported ${samples.size} GPS readings"
        } catch (e: Exception) { status = "GPS CSV export failed: ${e.message}" }
    }

    private fun exportFramesCsv(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write("frame_id,timestamp_utc,video_offset_ms,latitude,longitude,accuracy_m,gps_time_difference_ms,image_filename\n")
                frames.forEach { frame ->
                    writer.write("${frame.frameId},${frame.timestampUtc},${frame.videoOffsetMs},${frame.latitude ?: ""},${frame.longitude ?: ""},${frame.accuracyMeters ?: ""},${frame.gpsTimeDifferenceMs ?: ""},${frame.imageFilename}\n")
                }
            } ?: error("Could not open selected file")
            status = "Exported ${frames.size} frame records"
        } catch (e: Exception) { status = "Frame CSV export failed: ${e.message}" }
    }

    private fun exportSurveyZip(uri: Uri) {
        val dir = currentSurveyDir
        if (dir == null || !dir.exists()) {
            status = "Survey files were not found"
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { raw ->
                ZipOutputStream(raw.buffered()).use { zip ->
                    dir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relative = file.relativeTo(dir).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry("${dir.name}/$relative"))
                        file.inputStream().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("${dir.name}/gps_readings.csv"))
                    zip.write(buildString {
                        append("sample_id,timestamp_utc,latitude,longitude,accuracy_m,altitude_m\n")
                        samples.forEachIndexed { index, sample ->
                            append("${index + 1},${utc(sample.timestampMs)},${sample.latitude},${sample.longitude},${sample.accuracyMeters},${sample.altitudeMeters}\n")
                        }
                    }.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("${dir.name}/frame_metadata.csv"))
                    zip.write(buildString {
                        append("frame_id,timestamp_utc,video_offset_ms,latitude,longitude,accuracy_m,gps_time_difference_ms,image_filename\n")
                        frames.forEach { frame ->
                            append("${frame.frameId},${frame.timestampUtc},${frame.videoOffsetMs},${frame.latitude ?: ""},${frame.longitude ?: ""},${frame.accuracyMeters ?: ""},${frame.gpsTimeDifferenceMs ?: ""},${frame.imageFilename}\n")
                        }
                    }.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            } ?: error("Could not open selected file")
            status = "Survey ZIP exported — includes video, frames and metadata CSVs"
        } catch (e: Exception) {
            status = "ZIP export failed: ${e.message ?: "unknown error"}"
        }
    }

    override fun onDestroy() {
        stopGpsUpdates()
        activeRecording?.close()
        processingExecutor.shutdown()
        super.onDestroy()
    }
}
