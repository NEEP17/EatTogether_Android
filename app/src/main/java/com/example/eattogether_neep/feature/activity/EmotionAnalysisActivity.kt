package com.example.eattogether_neep.feature.activity

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.TextureView
import android.Manifest.permission.CAMERA
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import java.nio.ByteBuffer
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
import kotlinx.android.synthetic.main.activity_emotion_analysis.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.net.URISyntaxException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread


typealias LumaListener = (luma: Double) -> Unit

private val SOCKET_URL="[your server url]"
private var mHandler: Handler? = null
private var mSocket: io.socket.client.Socket? = null
private lateinit var socketReceiver: EmotionAnalysisActivity.EmotionReciver
private lateinit var intentFilter: IntentFilter
private var resultFromServer = -1
private var f_name: Array<String> = arrayOf()
private var f_img: Array<String> = arrayOf()
private var savedUri: Uri? =null
private var smileProb=0.0F
private var smileSum=0.0F
private var neutralProb=0.0F
private var neutralSum=0.0F

// Firebase를 이용한 Face Detection (단, 이때는 이미지 캡쳐 안됨)
class EmotionAnalysisActivity : AppCompatActivity() {
    private lateinit var viewFinder: TextureView
    private lateinit var uuid: String
    private var i=0
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
        setContentView(R.layout.activity_emotion_analysis)

        f_name = intent.getStringArrayExtra("food_name")!!
        f_img = intent.getStringArrayExtra("food_img")!!
        roomName=intent.getStringExtra("roomName")!!

        checkPermission()
        startCameraThread()

        try {

            //IO.socket 메소드는 URL를 토대로 클라이언트 객체를 Return
            mSocket = IO.socket(SOCKET_URL)
        } catch (e: URISyntaxException) {
            Log.e("EmotionAnalysisActivity", e.reason)
        }

        uuid = User.getUUID(this)
        socketReceiver = EmotionReciver()
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
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        @SuppressLint("HandlerLeak")
        mHandler = object : Handler() {
            override fun handleMessage(msg: Message) {
                if (this@EmotionAnalysisActivity.isFinishing)
                    return
                if(i < f_img.size*3){
                    Glide.with(this@EmotionAnalysisActivity).load(f_img[i/3]).thumbnail(0.05f).into(img_food1)
                    tv_food_num1.text="후보 "+(i/3+1)
                    txt_food_name1.text = f_name[i/3]
                    i++

                    // 1초마다 표정, 기기번호, 음식번호 전송
                    takePhoto()
                    Log.d("1초마다 표정, 기기번호, 음식번호 전송", "Emotion Analysis enqueue every 1seconds")
                    //saveImage(i/3, smileProb.toString())
                    smileSum+= smileProb
                                // 0~0.5+(-0.1~0.1)
                    var random=Random().nextInt(21) // 0~20
                    var randomP=Random().nextInt(6) // 0~5
                    neutralProb=(1-smileProb)+(random-10)/100
                    if(neutralProb<0)
                        neutralProb+=(randomP+10)/100
                    neutralSum+=neutralProb

                    // 3초마다 기기번호, 음식번호
                    if(i%3==0){
                        Log.d("3초마다 기기번호, 음식번호","Emotion Analysis enqueue every 3seconds")
                        //savePredict(smileSum/3, i/3)
                        Log.d("Random Values",random.toString()+" "+randomP.toString())
                        Log.d("Happy Neutral value", (smileSum/3).toString()+"   "+(neutralSum/30).toString())
                        savePredict(smileSum/3, neutralSum/30, i/3)
                        smileSum=0.0F
                    }
                }
                else {
                    val intent = Intent(this@EmotionAnalysisActivity,RankingActivity::class.java)
                    intent.putExtra("roomName", roomName)
                    this@EmotionAnalysisActivity.startActivity(intent)
                    this@EmotionAnalysisActivity.finish()
                    return
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
        val imageCapture = imageCapture ?: return

        // Create time-stamped output file to hold the image
       val photoFile = File(
            outputDirectory,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile!!).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    var photoPath = photoFile.canonicalPath
                    savedUri = Uri.fromFile(photoFile)
                    Log.d("Before Base64 encoder",savedUri.toString())
                    Log.d("Base64 encoder111", encoder1(photoFile))
                    Log.d("Base64 encoder222", encoder2(savedUri))
                    Log.d("Base64 encoder333", encoder3(photoPath))
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
        val image = BitmapFactory.decodeStream(input, null, null)
        //encode image to base64 string
        val baos = ByteArrayOutputStream()
        image!!.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        var imageBytes = baos.toByteArray()

        return android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
    }

    // Saved Broken Image
    private fun encoder3(path: String): String {
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

    private fun savePredict(avgHappy:Float,avgNeutral:Float, imageOrder: Int ) {
        Log.d("Average Predict Called", "Emotion Analysis enqueue every 1seconds")
        val work = Intent()
        work.putExtra("serviceFlag", "savePredict")
        work.putExtra("avgHappy", avgHappy)
        work.putExtra("avgNeutral", avgNeutral)
        work.putExtra("uuid", uuid)
        work.putExtra("imageOrder", imageOrder)
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
                //Toast.makeText(this, getString(R.string.detection_error), Toast.LENGTH_SHORT).show()
            })
        detectionViewer = DrawFace(cameraWidth, cameraHeight)
    }


    private fun processHappiness(faces: List<FirebaseVisionFace>) {

        for (face in faces) {
            if (face.smilingProbability != FirebaseVisionFace.UNCOMPUTED_PROBABILITY) {
                smileProb = face.smilingProbability
                if (smileProb > 0) {
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun processFace(faces: List<FirebaseVisionFace>) {

        for (face in faces) {
            if (face.leftEyeOpenProbability != FirebaseVisionFace.UNCOMPUTED_PROBABILITY) {
                val leftEyeOpenProb = face.leftEyeOpenProbability
                val rightEyeOpenProb = face.rightEyeOpenProbability
                if (leftEyeOpenProb < 0.2 || rightEyeOpenProb < 0.2) {
                }
            }
            if (face.smilingProbability != FirebaseVisionFace.UNCOMPUTED_PROBABILITY) {
                val smileProb = face.smilingProbability
                if (smileProb > 0.4) {
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
        } else {
            finish()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            imageCapture = ImageCapture.Builder()
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                        Log.d(TAG, "Average luminosity: $luma")
                    })
                }

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
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
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this,
               CAMERA
            ) != PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this,
                arrayOf(CAMERA, WRITE_EXTERNAL_STORAGE), REQUEST_CAMERA_PERMISSION)
        } else {
            cam_emotion.start()
        }
    }
    inner class EmotionReciver() : BroadcastReceiver() {
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
        const val REQUEST_CODE_PERMISSIONS = 10
        val REQUIRED_PERMISSIONS = arrayOf(CAMERA)
        private const val REQUEST_CAMERA_PERMISSION = 123
    }
}
