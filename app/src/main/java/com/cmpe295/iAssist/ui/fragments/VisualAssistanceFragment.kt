package com.cmpe295.iAssist.ui.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.view.TextureView.SurfaceTextureListener
import android.widget.Button
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.fragment.app.Fragment
import com.cmpe295.iAssist.Constants
import com.cmpe295.iAssist.databinding.FragmentVisualBinding
import com.google.gson.Gson
import edmt.dev.edmtdevcognitivevision.Contract.AnalysisResult
import edmt.dev.edmtdevcognitivevision.Rest.VisionServiceException
import edmt.dev.edmtdevcognitivevision.VisionServiceClient
import edmt.dev.edmtdevcognitivevision.VisionServiceRestClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.util.*

class VisualAssistanceFragment : Fragment() {

    var bitmap: Bitmap? = null
    var string: String = "{}"
    lateinit var res : AnalysisResult
    lateinit var visionServiceClient : VisionServiceClient
    companion object {
        val API_KEY = "*********"
        val API_LINK = "************"
        val ORIENTATIONS = SparseIntArray()
        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }
    }
    private lateinit var binding: FragmentVisualBinding
    private var imageCapture: ImageCapture?=null
    lateinit var mTextToSpeech: TextToSpeech

    //camera2 stuff
    private val TAG: String? = "AndroidCameraApi"
    private val takePictureButton: Button? = null
    private val textureView: TextureView? = null
    private val cameraId: String? = null
    protected var cameraDevice: CameraDevice? = null
    protected var cameraCaptureSessions: CameraCaptureSession? = null
    protected var captureRequest: CaptureRequest? = null
    protected var captureRequestBuilder: CaptureRequest.Builder? = null
    private val imageDimension: Size? = null
    private val imageReader: ImageReader? = null
    private val file: File? = null
    private val REQUEST_CAMERA_PERMISSION = 200
    private val mFlashSupported = false
    private var mBackgroundHandler: Handler? = null
    private var mBackgroundThread: HandlerThread? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentVisualBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

//        if(allPermissionGranted()){
//            startCamera()
//            Toast.makeText(requireActivity(), "We have permission", Toast.LENGTH_SHORT).show()
//        }else{
//            requestPermissions(Constants.REQUIRED_PERMISSIONS, Constants.REQUEST_CODE_PERMISSIONS)
//        }

        binding.btnTakepicture.setOnClickListener {
            takePicture()
        }

        binding.texture.surfaceTextureListener = textureListener

        mTextToSpeech = TextToSpeech(requireContext(), TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.ERROR) {
                //if there is no error then set language
                mTextToSpeech.language = Locale.US
            }
        })
    }

    var textureListener: SurfaceTextureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            //open your camera here
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            // Transform you image captured size according to the surface width and height
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private val stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            //This is called when the camera is open
            Log.e(TAG, "onOpened")
            cameraDevice = camera
            createCameraPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice!!.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice!!.close()
            cameraDevice = null
        }
    }

    val captureCallbackListener: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            Log.e(
                TAG,
                String.format(
                    "captureCallbackListener %s-%f",
                    result.get(CaptureResult.LENS_STATE).toString(),
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                )
            )
            Log.e(
                TAG,
                String.format(
                    "AF mode %s-%s",
                    result.get(CaptureResult.CONTROL_AF_MODE).toString(),
                    result.get(CaptureResult.CONTROL_AF_STATE).toString()
                )
            )
            val calculated_distance = 1 / result.get(CaptureResult.LENS_FOCUS_DISTANCE)!!
            Toast.makeText(
                requireContext(),
                "Distance:" + calculated_distance + "meters",
                Toast.LENGTH_SHORT
            ).show()
            createCameraPreview()
        }
    }

    protected fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("Camera Background")
        mBackgroundThread!!.start()
        mBackgroundHandler = Handler(mBackgroundThread!!.getLooper())
    }

    protected fun stopBackgroundThread() {
        mBackgroundThread!!.quitSafely()
        try {
            mBackgroundThread!!.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    protected fun takePicture() {
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null")
            return
        }
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = manager.getCameraCharacteristics(
                cameraDevice!!.id
            )
            var jpegSizes: Array<Size>? = null
            if (characteristics != null) {
                jpegSizes =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                        .getOutputSizes(ImageFormat.JPEG)
            }
            var width = 640
            var height = 480
            if (jpegSizes != null && 0 < jpegSizes.size) {
                width = jpegSizes[0].width
                height = jpegSizes[0].height
            }
            val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
            val outputSurfaces: MutableList<Surface> = ArrayList(2)
            outputSurfaces.add(reader.surface)
            outputSurfaces.add(Surface(textureView!!.surfaceTexture))
            val captureBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(reader.surface)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            // Orientation
            val rotation: Int = getWindowManager().getDefaultDisplay().getRotation()
            captureBuilder.set(
                CaptureRequest.JPEG_ORIENTATION,
                MainActivity.ORIENTATIONS.get(rotation)
            )
            val file = File(Environment.getExternalStorageDirectory().toString() + "/pic.jpg")
            val readerListener: ImageReader.OnImageAvailableListener =
                object : ImageReader.OnImageAvailableListener {
                    override fun onImageAvailable(reader: ImageReader) {
                        var image: Image? = null
                        try {
                            image = reader.acquireLatestImage()
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.capacity())
                            buffer[bytes]
                            save(bytes)
                        } catch (e: FileNotFoundException) {
                            e.printStackTrace()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        } finally {
                            image?.close()
                        }
                    }

                    @Throws(IOException::class)
                    private fun save(bytes: ByteArray) {
                        var output: OutputStream? = null
                        try {
                            output = FileOutputStream(file)
                            output.write(bytes)
                        } finally {
                            output?.close()
                        }
                    }
                }
            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler)
            val captureListener: CaptureCallback = object : CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    Log.e(
                        MainActivity.TAG,
                        String.format(
                            "captureCallbackListener %s-%f",
                            result.get(CaptureResult.LENS_STATE).toString(),
                            result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                        )
                    )
                    Log.e(
                        MainActivity.TAG,
                        String.format(
                            "AF mode %s-%s",
                            result.get(CaptureResult.CONTROL_AF_MODE).toString(),
                            result.get(CaptureResult.CONTROL_AF_STATE).toString()
                        )
                    )
                    val calculated_distance = 1 / result.get(CaptureResult.LENS_FOCUS_DISTANCE)!!
                    Toast.makeText(
                        this@MainActivity,
                        "Distance: $calculated_distance meters",
                        Toast.LENGTH_SHORT
                    ).show()
                    createCameraPreview()
                }
            }
            /*final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(
                        CameraCaptureSession session,
                        CaptureRequest request,
                        CaptureResult partialResult) {
                    Toast.makeText(MainActivity.this, "okk1:" + partialResult.LENS_FOCUS_DISTANCE, Toast.LENGTH_SHORT).show();
                    Log.i("progress", String.valueOf(partialResult.LENS_FOCUS_DISTANCE));
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    //Toast.makeText(MainActivity.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
                    float distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                    Log.i("COMPLETE", String.valueOf(distance));
                    Toast.makeText(MainActivity.this, "okk:" + distance, Toast.LENGTH_SHORT).show();
                    createCameraPreview();
                }
            };*/cameraDevice!!.createCaptureSession(
                outputSurfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            session.capture(
                                captureBuilder.build(),
                                captureListener,
                                mBackgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                },
                mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun allPermissionGranted() =
        Constants.REQUIRED_PERMISSIONS.all {
            context?.let { it1 -> ContextCompat.checkSelfPermission(it1, it) } == PackageManager.PERMISSION_GRANTED
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(requestCode == Constants.REQUEST_CODE_PERMISSIONS){
            if(allPermissionGranted()){
                //code
                startCamera()
            }else{
                Toast.makeText(requireActivity(), "Permission not granted by the user", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun takePhoto(){

        val imageCapture = imageCapture?: return
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()), object :ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    super.onCaptureSuccess(image)
                    Log.i("assist","captured photo")
                    // code to call azure api
                    /**
                     * Convert Image Proxy to Bitmap
                     */
                    Log.i("ImageFormat", "${image.format}")
                    fun toBitmap(image: ImageProxy): Bitmap? {
                        val byteBuffer = image.planes[0].buffer
                        byteBuffer.rewind()
                        val bytes = ByteArray(byteBuffer.capacity())
                        byteBuffer[bytes]
                        val clonedBytes = bytes.clone()
                        return BitmapFactory.decodeByteArray(clonedBytes, 0, clonedBytes.size)
                    }

                    bitmap = toBitmap(image)
                    visionServiceClient = VisionServiceRestClient(VisualAssistanceFragment.API_KEY, VisualAssistanceFragment.API_LINK)
                    val uiScope = CoroutineScope(Dispatchers.Main)
                    //TODO
                    uiScope.launch {
                        processimage()
                    }
                    image.close()
                }

                override fun onError(exception: ImageCaptureException) {
                    super.onError(exception)
                    Log.e(Constants.TAG, "onError: ${exception.message}", exception)
                }
            }
        )
    }

    private fun startCamera(){
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener(Runnable {
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also { mPreview->
                    mPreview.setSurfaceProvider(
                        binding.viewFinder.surfaceProvider
                    )
                }
            imageCapture = ImageCapture.Builder()
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            }catch (e: Exception){
                Log.d(Constants.TAG, "StartCamera Fail:", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private suspend fun processimage() {
        withContext(Dispatchers.Default) {
            val outputStream = ByteArrayOutputStream()
            bitmap?.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            val inputStream = ByteArrayInputStream(outputStream.toByteArray())
            try {
                val features : Array<String> = arrayOf("Description")
                val details = arrayOf<String>()
                res = visionServiceClient.analyzeImage(inputStream, features,details)
                string = Gson().toJson(res)
                Log.d("result", string);

            } catch (e: VisionServiceException){
                Log.e("visionexception",e.message.toString())
            }

            withContext(Dispatchers.Main) {
                //val txt_result : TextView = findViewById(R.id.textresult)
                val result : AnalysisResult = Gson().fromJson<AnalysisResult>(string,AnalysisResult::class.java)

                val result_text = StringBuilder()
                for(caption in result.description.captions!!){
                    result_text.append(caption.text)
                    Log.i("Description",result_text.toString())
                    val toSpeak = result_text.toString()
                    if (toSpeak != null) {
                        Log.i("Speech",toSpeak.toString())
                        if(!::mTextToSpeech.isInitialized){
                            mTextToSpeech = TextToSpeech(requireContext(), TextToSpeech.OnInitListener {

                            })
                        }
                        mTextToSpeech.speak(toSpeak, TextToSpeech.QUEUE_FLUSH,null)
                    }
                }

            }
//            //get text
//            val toSpeak = textresult.text.toString()
//            if (toSpeak != null) {
//                mTextToSpeech.speak(toSpeak, TextToSpeech.QUEUE_FLUSH,null)
//            }
        }
    }

}