package com.example.eattogether_neep.feature.activity

import android.Manifest
import android.Manifest.permission.CAMERA
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.example.eattogether_neep.R
import com.example.eattogether_neep.socket.SocketService
import com.example.eattogether_neep.feature.User
import com.example.eattogether_neep.emotion.coredetection.DrawFace
import com.example.eattogether_neep.emotion.facedetection.FaceDetector
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.firebase.ml.vision.face.FirebaseVisionFace
import com.otaliastudios.cameraview.Facing
import com.otaliastudios.cameraview.Frame
import io.socket.client.IO
import kotlinx.android.synthetic.main.activity_emotion_analysis2.*
import kotlinx.android.synthetic.main.activity_emotion_analysis2.cam_emotion
import kotlinx.android.synthetic.main.activity_emotion_analysis2.imageViewOverlay
import java.io.*
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread


typealias LumaListener2 = (luma: Double) -> Unit

private const val REQUEST_CODE_PERMISSIONS = 10
private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
private val SOCKET_URL="[your server url]"
private var hasConnection: Boolean = false
private var mHandler: Handler? = null
private var mSocket: io.socket.client.Socket? = null
private lateinit var socketReceiver: EmotionAnalysisActivity2.EmotionReciver2
private lateinit var intentFilter: IntentFilter
private var resultFromServer = -1

private var f_name: Array<String> = arrayOf()
private var f_img: Array<String> = arrayOf()
private var savedUri: Uri? =null
private  var smileProb=0.0F
private  var smileSum=0.0F
private var photoPath:String?= null

// Camera Capture로 이미지 REST 통신 (UI 상  Rectangle 안그려짐)
class EmotionAnalysisActivity2 : AppCompatActivity() {
    private lateinit var viewFinder: TextureView
    private lateinit var uuid: String
    var i=0
    private var imageCapture: ImageCapture? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private var faceDetector: FaceDetector? = null
    private var detectionViewer: DrawFace? = null
    private var cameraWidth: Int = 0
    private var cameraHeight: Int = 0
    private var isLoadingDetection = false
    private var roomName = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emotion_analysis2)

        f_name = intent.getStringArrayExtra("food_name")!!
        f_img = intent.getStringArrayExtra("food_img")!!
        roomName=intent.getStringExtra("roomName")!!
        Log.e("Food Name: ", f_name[0].toString())
        Log.e("Food Image: ", f_img[0].toString())

        checkPermission()
        startCameraThread()

        try {

            //IO.socket 메소드는 URL를 토대로 클라이언트 객체를 Return 합니다.
            mSocket = IO.socket(SOCKET_URL)
        } catch (e: URISyntaxException) {
            Log.e("EmotionAnalysisActivity", e.reason)
        }

        uuid = User.getUUID(this)
        socketReceiver = EmotionReciver2()
        intentFilter = IntentFilter()
        with(intentFilter){
            addAction("com.example.eattogether_neep.RESULT_SAVE_IMAGE")
        }
        registerReceiver(socketReceiver, intentFilter)

        with(cam_emotion){
            facing = Facing.FRONT
            addFrameProcessor { if (!isLoadingDetection) detect(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(socketReceiver)
    }

    private fun startCameraThread(){
        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        //camera_capture_button.setOnClickListener { takePhoto() }

        mHandler = object : Handler() {
            override fun handleMessage(msg: Message) {
                if (this@EmotionAnalysisActivity2.isFinishing)
                    return
                else{
                    Glide.with(this@EmotionAnalysisActivity2).load(f_img[i / 3]).into(img_food)
                    tv_food_num.text="후보 "+(i/3+1)
                    txt_food_name.text = f_name[i / 3]

                    // 1초마다 표정, 기기번호, 음식번호 전송
                    takePhoto()
                    saveImage(i, getBase64Data(photoPath))
                    i++

                    smileSum+= smileProb

                    // 3초마다 기기번호, 음식번호
                    if(i%3==0){
                       smileSum=0.0F
                    }

                    if((i/3)>= f_name.size) {
                        val intent = Intent(
                            this@EmotionAnalysisActivity2,
                            RankingActivity::class.java
                        )
                        intent.putExtra("roomName", roomName)
                        startActivity(intent)
                        finish()
                    }
                }
            }
        }

        thread(start = true) {
            while (true) {
                Thread.sleep(1000)
                mHandler?.sendEmptyMessage(0)
            }
        }

        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {

        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(
                FILENAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg"
        )

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val msg = "Photo capture succeeded: $savedUri"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                }
            })
    }

    // Convert Failed to Image
    private fun encoder1(filePath: File): String{
        val bytes = filePath.readBytes()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return base64
    }

    // Saved Broken Image
    private fun encoder2(imageUri: Uri?): String {
        val input = imageUri?.let { this.contentResolver.openInputStream(it) }
        //val bm = BitmapFactory.decodeResource(resources, R.drawable.test)
        val image = BitmapFactory.decodeStream(input, null, null)
        //encode image to base64 string
        val baos = ByteArrayOutputStream()
        //bm.compress(Bitmap.CompressFormat.JPEG, 100, baos);
        image!!.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        var imageBytes = baos.toByteArray()

        return android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
        //return Base64.getEncoder().encodeToString(imageBytes) // Not Worked, too.
    }

    // Saved Broken Image
    private fun encoder3(path: String?): String {
        val imagefile = File(path)
        var fis: FileInputStream? = null
        try {
            fis = FileInputStream(imagefile)
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }
        val bm = BitmapFactory.decodeStream(fis)
        val baos = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        val b = baos.toByteArray()

        return Base64.getEncoder().encodeToString(b)
    }

    // Convert Failed to Image
    private fun getBase64Data(filePath: String?): String {
        try {
            val inputStream: InputStream =
                FileInputStream(filePath) //You can get an inputStream using any IO API
            val bytes: ByteArray
            val buffer = ByteArray(8192)
            var bytesRead: Int
            val output = ByteArrayOutputStream()
            try {
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
            bytes = output.toByteArray()
            Log.d(
                "getBase64Data", android.util.Base64.encodeToString(
                    bytes,
                    android.util.Base64.NO_WRAP
                )
            )
            return /*"data:image/jpeg;base64," + */android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.NO_WRAP
            )
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            Log.d("getBase64Data", "error")
        }
        return ""
    }

    // 이미지 Base64코드 전송
    private fun saveImage(imageOrder: Int, base64Str: String) {
        val work = Intent()

        Log.d("SaveImage Called:", base64Str)
        work.putExtra("serviceFlag", "saveImage")
        work.putExtra("image", base64Str)
        work.putExtra("uuid", uuid)
        work.putExtra("imageOrder", imageOrder)
        SocketService.enqueueWork(this, work)
    }

    private fun avgPredict(imageOrder: Int) {
        Log.d("Average Predict Called", "Emotion Analysis enqueue every 1seconds")
        val work = Intent()
        work.putExtra("serviceFlag", "avgPredict")
        work.putExtra("uuid", uuid)
        work.putExtra("imageOrder", imageOrder)
        SocketService.enqueueWork(this, work)
    }

    private fun savePredict(avgPredict: Float) {
        Log.d("Average Predict Called", "Emotion Analysis enqueue every 1seconds")
        val work = Intent()
        work.putExtra("serviceFlag", "savePredict")
        work.putExtra("avgPredict", avgPredict)
        work.putExtra("uuid", uuid)
        SocketService.enqueueWork(this, work)
    }

    private fun detect(frame: Frame) {
        if (cameraWidth > 0 && cameraHeight > 0) {
            faceDetector?.detectFromByteArray(frame.data)
            isLoadingDetection = true
        } else {
            cameraWidth = frame.size.width
            cameraHeight = frame.size.height
            setupFaceDetector()
        }
    }

    private fun setupFaceDetector() {
        faceDetector = FaceDetector(
            cameraWidth = cameraWidth,
            cameraHeight = cameraHeight,
            successListener = OnSuccessListener() {
                val bmp = detectionViewer?.showVisionDetection(it)
                imageViewOverlay.setImageBitmap(bmp)
                isLoadingDetection = false
                processHappiness(it)
                processFace(it)
            },
            failureListener = OnFailureListener {
                Toast.makeText(this, getString(R.string.detection_error), Toast.LENGTH_SHORT).show()
            })

        detectionViewer = DrawFace(cameraWidth, cameraHeight)
    }


    private fun processHappiness(faces: List<FirebaseVisionFace>) {

        for (face in faces) {
            if (face.smilingProbability != FirebaseVisionFace.UNCOMPUTED_PROBABILITY) {
                smileProb = face.smilingProbability
                if (smileProb > 0) {
                    /*Toast.makeText(
                        this,
                        "The degree of your happiness is:$smileProb",
                        Toast.LENGTH_SHORT
                    ).show()*/
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun processFace(faces: List<FirebaseVisionFace>) {

        for (face in faces) {

            //textViewMood.text = getEmojiUnicode(0x1F60A) + getEmojiUnicode(0x1F60A) + getEmojiUnicode(0x1F60A)

            if (face.leftEyeOpenProbability != FirebaseVisionFace.UNCOMPUTED_PROBABILITY) {
                val leftEyeOpenProb = face.leftEyeOpenProbability
                val rightEyeOpenProb = face.rightEyeOpenProbability
                if (leftEyeOpenProb < 0.2 || rightEyeOpenProb < 0.2) {
                    //textViewMood.text = getEmojiUnicode(0X1F609) + getEmojiUnicode(0X1F609) + getEmojiUnicode(0X1F609)
                }
            }
            if (face.smilingProbability != FirebaseVisionFace.UNCOMPUTED_PROBABILITY) {
                val smileProb = face.smilingProbability
                if (smileProb > 0.4) {
                    //textViewMood.text = getEmojiUnicode(0x1F601) + getEmojiUnicode(0x1F601) + getEmojiUnicode(0x1F601)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                viewFinder.post { startCamera() }
            } else {
                Toast.makeText(
                    this,
                    "권한이 허용되지 않았습니다.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
            return
            if (grantResults.isNotEmpty() && grantResults[0] == PERMISSION_GRANTED
                && grantResults[1] == PERMISSION_GRANTED) {
                cam_emotion.start()
            }
        }
        if (allPermissionsGranted()) {
            startCamera()
            //viewModel.startCamera(this, cam_emotion)
        } else {
            Toast.makeText(this, "Missing camera permission.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(cam_emotion2.createSurfaceProvider())
                }
            imageCapture = ImageCapture.Builder()
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                        Log.d(TAG, "Average luminosity: $luma")
                    })
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(this, cameraSelector, preview,  imageCapture, imageAnalyzer)

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private class LuminosityAnalyzer(private val listener: LumaListener2) : ImageAnalysis.Analyzer {
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()
            val data = ByteArray(remaining())
            get(data)
            return data
        }

        override fun analyze(image: ImageProxy) {

            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listener(luma)

            image.close()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name2)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(
                this, CAMERA) != PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(CAMERA, WRITE_EXTERNAL_STORAGE), REQUEST_CAMERA_PERMISSION
            )
        } else {
            cam_emotion.start()
        }
    }

    inner class EmotionReciver2() : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.eattogether_neep.RESULT_SAVE_IMAGE" -> {
                    resultFromServer = intent.getIntExtra("error", -1)
                }
                else -> return
            }
        }
    }

    companion object {
        private const val TAG = "EmotionAnalysis"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(CAMERA)
        private const val REQUEST_CAMERA_PERMISSION = 123
    }
}
