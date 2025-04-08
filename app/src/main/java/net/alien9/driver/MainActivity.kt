package net.alien9.driver

import android.Manifest
import android.R.id.input
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.media.ThumbnailUtils.createImageThumbnail
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.*
import android.webkit.*
import android.webkit.WebView.*
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.Response.Listener
import com.fasterxml.jackson.databind.JsonNode
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mil.nga.geopackage.BoundingBox
import mil.nga.geopackage.GeoPackageFactory
import mil.nga.geopackage.features.index.FeatureIndexManager
import mil.nga.geopackage.features.index.FeatureIndexType
import mil.nga.sf.MultiLineString
import mil.nga.sf.MultiPolygon
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.zip.ZipFile
import kotlin.collections.ArrayList


private const val FCR = 1
class MainActivity : AppCompatActivity() {
    private var locator: String?="Fused"
    private var POSITION: Int=333
    private lateinit var dataset: JSONArray
    private var currentRecordIndex: Int = 0
    private var token: String=null.toString()
    private lateinit var photoURI: Uri
    private var media_uri: Uri?=null
    private var basepath: File? = null
    private var frontend: String? = null
    private val REQUEST_IMAGE_CAPTURE=1
    private var backend: String? = null
    private var state: String? = ""
    private var mCM: String? = null
    private var mUM: ValueCallback<Uri>? = null
    private var mUMA: ValueCallback<Array<Uri>>? = null
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onCreate(savedInstanceState: Bundle?) {
        WebView.setWebContentsDebuggingEnabled(true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        basepath=this.getExternalFilesDir(null)
        val PERMISSION_ALL = 1
        val PERMISSIONS = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS

        )

        if (!hasPermissions(this, *PERMISSIONS)) {
            requestPermissions(PERMISSIONS, PERMISSION_ALL)
            Log.d("DRIVER", "PERMISSION WAS REQUESTED")
        }


        var mUploadMessage: ValueCallback<Uri>
        Companion.FILECHOOSER_RESULTCODE = 1

        var loadingFinished = true
        var redirect = false
        val toolbar: Toolbar = findViewById(R.id.my_toolbar)
        toolbar.setTitle(R.string.app_name)
        setSupportActionBar(toolbar)

        val mWebview: WebView = findViewById(R.id.webview)

        val webSettings: WebSettings = mWebview.settings
        webSettings.setSupportMultipleWindows(true)
        webSettings.domStorageEnabled=true
        webSettings.setGeolocationEnabled(true)
        webSettings.javaScriptEnabled=true
        webSettings.userAgentString="random"
        webSettings.setSupportMultipleWindows(true)
        webSettings.javaScriptCanOpenWindowsAutomatically=false
        webSettings.allowFileAccess=true
        webSettings.loadWithOverviewMode=true
        //webSettings.cacheMode=WebSettings.LOAD_CACHE_ELSE_NETWORK
        webSettings.setGeolocationEnabled(true)
        mWebview.addJavascriptInterface(JsWebInterface(this, mWebview), "androidApp")
        val context=this
        val assetLoader=WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/driver-angular/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(this))
            .build()
        var sharedPref: SharedPreferences = this.getPreferences(Context.MODE_PRIVATE)
        val b = sharedPref.getString("backend", getString(R.string.backend) ).toString()
        mWebview.addJavascriptInterface(MyJavascriptInterface(context), "android")
        mWebview.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView:WebView, filePathCallback:ValueCallback<Array<Uri>>, fileChooserParams:FileChooserParams):Boolean {
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
                    // Ensure that there's a camera activity to handle the intent
                    takePictureIntent.resolveActivity(packageManager)?.also {
                    }        // Create the File where the photo should go
                    val photoFile: File? = try {
                        createImageFile()
                    } catch (ex: IOException) {
                        // Error occurred while creating the File
                        null
                    }

                    // Continue only if the File was successfully created
                    photoFile?.also {
                        this@MainActivity.photoURI = FileProvider.getUriForFile(
                            webView.context,
                            "net.alien9.driver.fileprovider",
                            it
                        )
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                        startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
                    }
                }
                return super.onShowFileChooser(webView, filePathCallback, fileChooserParams)

            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback){
                callback.invoke(origin, true, false)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(
                    "DRIVER-CONSOLE", consoleMessage.message() + " -- From line "
                            + consoleMessage.lineNumber() + " of "
                            + consoleMessage.sourceId()
                )
                return super.onConsoleMessage(consoleMessage)
            }

            fun onPageFinished(view: WebView?, url: String?) {
                Log.d("DRIVER", "page was loaded")
            }
            fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                handler.proceed() // Ignore SSL certificate errors
            }
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean,
                isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                val newWebView = WebView(this@MainActivity)
                view.addView(newWebView)
                val transport = resultMsg.obj as WebViewTransport
                transport.webView=newWebView
                resultMsg.sendToTarget()
                newWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        url: String
                    ): Boolean {
                        val browserIntent = Intent(Intent.ACTION_VIEW)
                        browserIntent.data = Uri.parse(url)
                        startActivity(browserIntent)
                        return true
                    }
                }
                return true
            }

            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            override fun onPermissionRequest(request: PermissionRequest) {
                Log.d("DRIVER", "onPermissionRequest")
                runOnUiThread {
                    Log.d("DRIVER", request.origin.toString())
                    if (request.origin.toString() == "file:///") {
                        Log.d("DRIVER", "GRANTED")
                        request.grant(request.resources)
                    } else {
                        Log.d("DRIVER", "DENIED")
                        request.deny()
                    }
                }
            }

        }
        mWebview.webViewClient = LocalContentWebClient(assetLoader, b)
        mWebview.settings.allowUniversalAccessFromFileURLs=true
        val activity=this@MainActivity
        sharedPref = activity.getPreferences(Context.MODE_PRIVATE)
        backend = sharedPref.getString("backend", getString(R.string.backend) )
        frontend = sharedPref.getString("frontend", getString(R.string.frontend) )
        if(hasPermissions(this, *PERMISSIONS)) {
            startUp()
        }
    }
    private fun startUp(){
        val activity=this@MainActivity
        var sharedPref: SharedPreferences = this.getPreferences(Context.MODE_PRIVATE)
        sharedPref = activity.getPreferences(Context.MODE_PRIVATE)
        backend = sharedPref.getString("backend", getString(R.string.backend) )
        if (backend == "") backend=getString(R.string.backend)
        if (backend == "") {
            getQRCode()
        } else {
            val sharedPref = this@MainActivity.getPreferences(Context.MODE_PRIVATE)
            val b= backend?.split("?")
            var url="${b?.get(0)?.replace(Regex("\\/$"), "")}/"
            if (b != null) {
                if(b.count()>1) url="${url}?${b[1]}"
            }
            (findViewById<WebView>(R.id.webview)).loadUrl(url)
        }
    }
    lateinit var currentPhotoPath: String
    @Throws(IOException::class)
    private fun createImageFile(): File {
        // Create an image file name
        val filename=java.lang.Long.toHexString(System.currentTimeMillis())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "JPEG_${filename}_", /* prefix */
            ".jpg", /* suffix */
            storageDir /* directory */
        ).apply {
            // Save a file: path for use with ACTION_VIEW intents
            currentPhotoPath = absolutePath
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions:Array<out String>,
        grantResults:IntArray
    )
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startUp()
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode==2 && data!=null){
            val e=data.getExtras()
            if(!(e == null || e?.containsKey("CODE") != true)){
                saveBackend(e.getString("CODE", "").replace("/static/driver.apk", ""))
                finish()
                startActivity(intent)
            }
        }
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            val thumbImage: Bitmap = createImageThumbnail(
                File(currentPhotoPath),
                Size(280,280), null
            )
            var bas=ByteArrayOutputStream()
            thumbImage.compress(Bitmap.CompressFormat.PNG, 100, bas)
            var byteArray = bas.toByteArray()
            var ext=Base64.encodeToString(byteArray, Base64.NO_WRAP)
            var j= JSONObject()

            j.put("src", ext)
            var u=
                "javascript:var k=document.getElementById(localStorage.getItem('image-field'));if(k)k.value='data:image/png;base64,$ext';changeEvent = new Event('change'); k.dispatchEvent(changeEvent)"
            findViewById<WebView>(R.id.webview).loadUrl(u)
        }

    }
    private fun getToken():String{
        val w= findViewById<WebView>(R.id.webview)
        w.evaluateJavascript("(function(){const c=document.cookie.match(/AuthService.token=([^;]+);/); if(c) return c.pop();})();"){ s->
            token=s.replace("\"","")
        }
        return token
    }
    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val token=getToken()
        val w=findViewById<WebView>(R.id.webview)
        w.evaluateJavascript("(function(){return $('#state').val()})();") { s ->
            val state=s.replace("\"", "")
            menu?.findItem(R.id.take_photo)?.isVisible = false //state == "input"
            menu?.findItem(R.id.action_refresh)?.isVisible = true
            menu?.findItem(R.id.reload)?.isVisible = false
            menu?.findItem(R.id.action_map)?.isVisible = state == "input" || state == "locate"
            menu?.findItem(R.id.action_logout)?.isVisible = true
            menu?.findItem(R.id.upload)?.isVisible = state == "list"
            menu?.findItem(R.id.action_logout)?.isVisible = state != "login" && token != null
            menu?.findItem(R.id.action_update_map)?.isVisible = token != null
        }
        return super.onPrepareOptionsMenu(menu)
    }

    fun place(it: Location?){
        findViewById<WebView>(R.id.webview).evaluateJavascript(
            """   
(function(){
$('input[name=position]').val('${it?.latitude},${it?.longitude}');
$('input[name=position]').click();
})();
"""
        ) {
        }
    }



    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater: MenuInflater = menuInflater
        inflater.inflate(R.menu.main_context_menu, menu)
        return true
    }
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle item selection
        return when (item.itemId) {
            R.id.reload -> {
                val w= findViewById<WebView>(R.id.webview)
                w.clearCache(true)
                w.loadUrl("javascript:localStorage.setItem('backend', '%s')".format(backend))
                val a=this@MainActivity
                val sharedPref = a.getPreferences(Context.MODE_PRIVATE)
                backend = sharedPref.getString("backend", getString(R.string.backend))
                frontend = sharedPref.getString("frontend",  getString(R.string.frontend))
                w.loadUrl("${frontend}/index.html")
                true
            }
            R.id.action_refresh->{
                val w= findViewById<WebView>(R.id.webview)
                w.clearCache(true)
                val sharedPref = this@MainActivity.getPreferences(Context.MODE_PRIVATE)
                val b= sharedPref.getString("backend", getString(R.string.backend))?.replace(":8009",":4201")?.split("?")
                var url="${b?.get(0)?.replace(Regex("\\/$"),"")}/"
                if (b != null) {
                    if(b.count()>1) url="${url}?${b[1]}"
                }
                w.loadUrl(url)
                return true
            }
            R.id.take_photo->{
                val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)

                takePictureIntent.resolveActivity(packageManager)?.also {
                }        // Create the File where the photo should go
                val photoFile: File? = try {
                    createImageFile()
                } catch (ex: IOException) {
                    // Error occurred while creating the File
                    null
                }
                // Continue only if the File was successfully created
                photoFile?.also {
                    this@MainActivity.photoURI = FileProvider.getUriForFile(
                        this,
                        "org.alien9.driver.fileprovider",
                        it
                    )
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
                }
                true
            }
            R.id.action_settings -> {
                val builder = AlertDialog.Builder(this)
                val inflater = layoutInflater
                builder.setTitle("DRIVER Settings")
                builder.setMessage("host")
                val dialogLayout = inflater.inflate(R.layout.alert_edit, null)
                val editText = dialogLayout.findViewById<EditText>(R.id.editText)
                val frontendText = dialogLayout.findViewById<EditText>(R.id.frontendText)
                val sharedPref = this@MainActivity.getPreferences(Context.MODE_PRIVATE)
                backend = sharedPref.getString("backend", getString(R.string.backend))
                frontend = sharedPref.getString("frontend", getString(R.string.frontend))
                locator=sharedPref.getString("locator", "Fused")
                editText.setText(backend)
                frontendText.setText(frontend)
                builder.setView(dialogLayout)

                var spinner = dialogLayout.findViewById<Spinner>(R.id.locationBackend)
                ArrayAdapter.createFromResource(
                    this,
                    R.array.location_datasets,
                    android.R.layout.simple_spinner_item
                ).also {
                        adapter ->
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spinner.adapter = adapter
                    var vs=resources.getStringArray(R.array.location_datasets)
                    spinner.setSelection(vs.indexOf(locator))
                }
                builder.setPositiveButton(android.R.string.ok) { dialog, which ->
                    backend=editText.text.toString()
                    val sharedPref = this@MainActivity.getPreferences(Context.MODE_PRIVATE)
                    with (sharedPref.edit()) {
                        putString("backend", backend)
                        putString("frontend", frontendText.text.toString())
                        putString("locator", spinner.getItemAtPosition(spinner.selectedItemPosition).toString())
                        apply()
                    }
                    (findViewById<WebView>(R.id.webview)).clearCache(true)
                    (findViewById<WebView>(R.id.webview)).evaluateJavascript("""
                        (function(){
                        localStorage.setItem('backend', '${backend}');
                        })();
                    """) {

                    }
                }

                builder.setNegativeButton(android.R.string.cancel) { dialog, which ->

                }
                builder.show()
                true
            }
            R.id.action_set_backend->{
                getQRCode()
                true
            }
            R.id.action_logout -> {
                (findViewById<WebView>(R.id.webview)).loadUrl("javascript:document.cookie.split(';').forEach(cookie => {" +
                        "        const eqPos = cookie.indexOf('=');" +
                        "        const name = eqPos > -1 ? cookie.substring(0, eqPos) : cookie;" +
                        "        document.cookie = name + '=;expires=Thu, 01 Jan 1970 00:00:00 GMT';" +
                        "    });")
                startUp()
                true
            }
            /*R.id.cleanup->{
                (findViewById<WebView>(R.id.webview)).evaluateJavascript("(function(){$('#cleanup-button').click()})();") { }
                true
            }*/
            R.id.upload->{
                (findViewById<WebView>(R.id.webview)).evaluateJavascript("(function(){return localStorage.getItem('token');})();") { s ->
                    token=s.replace("\"", "")
                }
                (findViewById<WebView>(R.id.webview)).evaluateJavascript("(function(){return JSON.parse($('#dataset').val())})();") { s ->
                    dataset = JSONArray(s)
                    currentRecordIndex=0
                    if(currentRecordIndex<dataset.length()){
                        this.upload()
                    }
                }
                true
            }
            R.id.action_map->{
                val sharedPref = this@MainActivity.getPreferences(Context.MODE_PRIVATE)
                locator=sharedPref.getString("locator", "Fused")
                when(locator){
                    "Fused" -> {
                        val fusedLocationClient = (applicationContext as Driver).fusedLocationClient
                        fusedLocationClient.lastLocation.addOnSuccessListener {
                            if (it != null) {
                                place(it)
                            } else {
                                Toast.makeText(
                                    this,
                                    "Location returned was null",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                        }
                        true
                    }
                    "GPS" -> {
                        val loca = (applicationContext as Driver).loca as LocationManager
                        loca.getLastKnownLocation(LocationManager.NETWORK_PROVIDER).also {
                            if (it != null) {
                                place(it)
                            }
                        }
                    }
                    "Network" -> {
                        val loca = (applicationContext as Driver).loca as LocationManager
                        loca.getLastKnownLocation(LocationManager.NETWORK_PROVIDER).also {
                            if (it != null) {
                                place(it)
                            }
                        }
                    }

                }
                true
            }

            R.id.action_update_bounds->{
                (findViewById<View>(R.id.webview)).visibility = GONE
                (findViewById<View>(R.id.progressBar)).visibility = VISIBLE
                val queue = Bowser.getInstance(this.applicationContext).requestQueue
                val sharedPref = this.getPreferences(Context.MODE_PRIVATE)
                backend = getBackend()
                val token=getToken()
                val url = backend.toString().replace("\\?.*$".toRegex(),"")+"api/boundaries/"
                val context=this
                val listener=Response.Listener<JSONObject> { j->
                    if(j.getJSONArray("results").length()>0){
                        File(context.filesDir,"boundaries.json").printWriter().use { out ->
                            out.println(j.toString())
                        }
                        for(i in 0 until j.getJSONArray("results").length()){
                            val bound=j.getJSONArray("results").getJSONObject(i)
                            val uuid=j.getJSONArray("results").getJSONObject(i).getString("uuid")
                            val ru=backend.toString().replace("\\?.*$".toRegex(),"")+"api/boundaries/${uuid}/?format=gpkg"
                            val isvr = InputStreamVolleyRequest(Request.Method.GET, ru, Listener<ByteArray> { b->
                                val f=File(context.filesDir, "boundaries_${uuid}.gpkg")
                                val output = BufferedOutputStream(FileOutputStream(f))
                                output.write(b)
                                output.flush()
                                output.close()
                                if(i>=j.getJSONArray("results").length()-1) {
                                    (findViewById<View>(R.id.webview)).visibility = VISIBLE
                                    (findViewById<View>(R.id.progressBar)).visibility = GONE
                                    Toast.makeText(context, "Download completed", Toast.LENGTH_LONG)
                                        .show()
                                }
                            }, errorListener, HashMap())
                            isvr.setToken(token)
                            isvr.setRetryPolicy(
                                DefaultRetryPolicy(
                                    400000,
                                    DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                                    DefaultRetryPolicy.DEFAULT_BACKOFF_MULT)
                            );
                            queue.add(isvr)


                        }
                    }
                }
                val jsonObjectRequest = DriverRequest<JSONArray>(
                    Request.Method.GET, url, null.toString(),
                    errorListener, listener
                )
                jsonObjectRequest.setToken(token)
                queue.add(jsonObjectRequest)
                true

            }
            R.id.action_update_map->{
                (findViewById<View>(R.id.webview)).visibility = GONE
                (findViewById<View>(R.id.progressBar)).visibility = VISIBLE
                val queue = Bowser.getInstance(this.applicationContext).requestQueue
                val sharedPref = this.getPreferences(Context.MODE_PRIVATE)
                backend = getBackend()
                val token=getToken()
                val url = backend.toString().replace("\\?.*$".toRegex(),"")+"api/roadmaps/"
                val context=this

                val listener=Response.Listener<JSONObject> { j->
                    Log.d("********************************", j.toString())
                    if(j.getJSONArray("result").length()>0){
                        val ru=backend.toString().replace("\\?.*$".toRegex(),"")+"api/roadmaps/${j.getJSONArray("result").getJSONObject(0).getString("uuid")}/?format=gpkg"

                        val isvr = InputStreamVolleyRequest(Request.Method.GET, ru, Listener<ByteArray> { b->
                            val f=File(context.filesDir, "roads.gpkg")
                            val output = BufferedOutputStream(FileOutputStream(f))
                            output.write(b)
                            output.flush()

                            output.close()
                            (findViewById<View>(R.id.webview)).visibility = VISIBLE
                            (findViewById<View>(R.id.progressBar)).visibility = GONE
                            Toast.makeText(context, "Download completed", Toast.LENGTH_LONG).show()


                        }, errorListener, HashMap())
                        isvr.setToken(token)
                        isvr.setRetryPolicy(
                            DefaultRetryPolicy(
                                400000,
                                DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT)
                        );
                        queue.add(isvr)
                    }
                }

                val jsonObjectRequest = DriverRequest<JSONArray>(
                    Request.Method.GET, url, null.toString(),
                    errorListener, listener
                )
                jsonObjectRequest.setToken(token)
                queue.add(jsonObjectRequest)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }


    private fun saveToFile(inputStream: InputStream, outputFilePath: String) {
        try {
            FileOutputStream(File(outputFilePath)).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
    private suspend fun <T> withZipFromUri(
        context: Context,
        uri: Uri, block: suspend (ZipFile) -> T
    ) : T {
        val file = File(context.filesDir, "driver.zip")
        try {
            return withContext(Dispatchers.IO) {
                kotlin.runCatching {
                    context.contentResolver.openInputStream(uri).use { input ->
                        if (input == null) throw FileNotFoundException("openInputStream failed")
                        file.outputStream().use { input.copyTo(it) }
                    }
                    ZipFile(file, ZipFile.OPEN_READ).use { block.invoke(it) }
                }.getOrThrow()
            }
        } finally {
            file.delete()
        }
    }
    private fun saveBackend(backend: String) {
        val sharedPref = this@MainActivity.getPreferences(Context.MODE_PRIVATE)
        with (sharedPref.edit()) {
            putString("backend", backend)
            apply()
        }
    }

    private fun getQRCode() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_AZTEC)
            .build()

        val scanner = GmsBarcodeScanning.getClient(this,options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val b=barcode.rawValue.toString()
                val matcher=Regex("https?:\\/\\/[^\\/|\\?]+")
                backend=matcher.find(b)?.value?.replace("/static/driver.apk", "")
                saveBackend(backend!!)
            }
            .addOnCanceledListener {
                Log.w("DRIVER","Cancelled")
                // Task canceled
            }
            .addOnFailureListener { e ->
                Log.w("DRIVER","Failure")
            }
    }
    val errorListener=Response.ErrorListener {
        if(it.networkResponse==null){
            Toast.makeText(this, it.toString(), Toast.LENGTH_LONG).show()
        }else {
            val mess = JSONObject(String(it.networkResponse.data))
            if (mess.has("data")) {
                Toast.makeText(this, mess.getString("data"), Toast.LENGTH_LONG).show()
            } else {
                if (mess.has("schema")) {
                    Toast.makeText(this, mess.optJSONArray("schema").join(""), Toast.LENGTH_LONG)
                        .show()
                } else {
                    if (mess.has("detail")) {
                        Toast.makeText(this, mess.optString("detail"), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, it.toString(), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        (findViewById<View>(R.id.webview)).visibility = VISIBLE
        (findViewById<View>(R.id.progressBar)).visibility = GONE
    }
    private fun upload() {
        if(currentRecordIndex>=dataset.length()) {
            (findViewById<View>(R.id.webview)).visibility = VISIBLE
            (findViewById<View>(R.id.progressBar)).visibility = GONE
            return
        }
        val jo: JSONObject=dataset.getJSONObject(currentRecordIndex)
        (findViewById<View>(R.id.webview)).visibility = GONE
        (findViewById<View>(R.id.progressBar)).visibility = VISIBLE
        val queue = Bowser.getInstance(this.applicationContext).requestQueue
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE)
        backend = getBackend()
        val url = backend.toString().replace("/\\?.*$".toRegex(),"")+"api/records/"
        val listener= Listener<JSONObject> { it ->
            dataset.getJSONObject(currentRecordIndex).put("uploaded",true)
            currentRecordIndex++
            if(currentRecordIndex<dataset.length()) {
                upload()
            }else {
                (findViewById<View>(R.id.webview)).visibility = VISIBLE
                (findViewById<View>(R.id.progressBar)).visibility = GONE
            }
            (findViewById<WebView>(R.id.webview)).evaluateJavascript("""
                    (function(){
                    console.log('$dataset');
                    localStorage.setItem('dataset','$dataset');
                    $("#dataset").click();
                    })();
                """){ }


        }

        if(!jo.optBoolean("uploaded", false)) {
            val jsonObjectRequest = DriverRequest<JSONArray>(
                Request.Method.POST, url, jo.getJSONObject("record").toString(),
                errorListener, listener
            )
            jsonObjectRequest.setToken(token)
            queue.add(jsonObjectRequest)
        }else{
            currentRecordIndex++
            upload()
        }
    }

    fun hasPermissions(context: Context, vararg permissions: String): Boolean = permissions.all {
        ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        var FILECHOOSER_RESULTCODE = 1
    }

    inner class JsWebInterface(context: Context, w: WebView) {
        val context=context
        val webview=w
        @JavascriptInterface
        fun makeToast(message: String?, lengthLong: Boolean) {
            Toast.makeText(
                context,
                message,
                if (lengthLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        }
        @JavascriptInterface
        fun changeLocation(){
            var locationManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val s = object: Runnable{
                override fun run() {
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return
                    }
                    val location: Location? = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    val u="javascript:localStorage.setItem('latitude', %.7f);localStorage.setItem('longitude', %.7f);".format(location?.latitude,location?.longitude)
                    webview.loadUrl(u)
//                    webview.loadUrl("javascript:window.document.getElementById('tab').setAttribute('value', 'location');")
                }
            }
            webview.post(s)
        }
        @JavascriptInterface
        fun setState(s: String?){
            this@MainActivity.state=s
        }

    }
    class MyJavascriptInterface(private val context: Context) {
        fun geoformat(t:String):String{
            return "{\n" +
                    "    \"type\": \"Feature\", \n" +
                    "\"style\": {"+
                    "\"__comment\": \"all SVG styles allowed\","+
                    "\"fill\":\"red\"," +
                    "\"stroke-color\":\"red\","+
                    "\"width\":\"30\","+
                    "\"fill-opacity\":0.4"+
                    "},"+
                    "    \"geometry\": {\n" +
                    "        \"type\": \"MultiLineString\", \n" +
                    "        \"coordinates\": ${t}" +
                    "}}"
        }
        @JavascriptInterface
        fun showToast(message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        @JavascriptInterface
        fun getBoundaries(): String {
            val fu=File(context.filesDir,"boundaries.json")
            if(!fu.exists()){
                return "{}"
            }
            val inputStream: InputStream = fu.inputStream()
            val inputString = inputStream.bufferedReader().use { it.readText() }
            return  inputString
        }
        @JavascriptInterface
        fun getBoundaryPolygon(polygon:String, boundary:String):String{
            val fu=File(context.filesDir, "boundaries_${boundary}.gpkg")
            if(!fu.exists()){
                return "{}"
            }
            val manager= GeoPackageFactory.getManager(context)
            try {
                manager.importGeoPackage(fu, true)
            }catch(e:Exception){
                return "{}"
            }
            var res=JSONObject()
            res.put("count",0)
            res.put("results", JSONArray())
            val geopackage=manager.open("boundaries_${boundary}")
            val features=geopackage.featureTables
            val ft= features[0]
            val featuredao=geopackage.getFeatureDao(ft)
            val cursor=featuredao.query("uuid='${polygon}'")

            val geo=JSONArray()
            for (row in cursor){
                val d=JSONObject()
                d.put("id", polygon)
                d.put("type", "Feature")
                res=d
                for (g in (row.geometry.geometry as MultiPolygon).geometries) {
                    for (p in g.rings) {
                        val ring=JSONArray()
                        for(p in p.points){
                            val c=JSONArray()
                            c.put(p.x)
                            c.put(p.y)
                            ring.put(c)
                        }
                        geo.put(ring)
                    }
                }
            }
            cursor.close()
            val g=JSONObject()
            g.put("coordinates", geo)
            g.put("type", "Polygon")
            res.put("geometry",g)
            return res.toString()
        }
        @JavascriptInterface
        fun getBoundaryPolygons(boundary:String):String{
            val fu=File(context.filesDir, "boundaries_${boundary}.gpkg")
            if(!fu.exists()){
                return "{}"
            }
            val manager= GeoPackageFactory.getManager(context)
            try {
                manager.importGeoPackage(fu, true)
            }catch(e:Exception){
                return "{}"
            }
            var res=JSONObject()
            res.put("count",0)
            res.put("results", JSONArray())
            val geopackage=manager.open("boundaries_${boundary}")
            val features=geopackage.featureTables
            val ft= features[0]
            val featuredao=geopackage.getFeatureDao(ft)
            val cursor=featuredao.query(ArrayList<String>(featuredao.columnNames.clone().filter { g->g!="geom" }).toTypedArray())
            for (row in cursor){
                val d=JSONObject()
                for(t in row.columnNames) {
                    d.put(t, row.getValue(t))
                }
                val r=JSONObject()
                r.put("data", d)
                r.put("uuid", d.getString("uuid"))
                val other_cursor=featuredao.query("uuid='${d.getString("uuid")}'")
                for(other_row in other_cursor){
                    val b=other_row.geometry.boundingBox
                    val min=JSONObject()
                    val max=JSONObject()
                    min.put("lat", b.minLatitude)
                    min.put("lat", b.minLatitude)
                    min.put("lon", b.minLongitude)
                    max.put("lat", b.maxLatitude)
                    max.put("lon", b.maxLongitude)
                    r.put("bbox", JSONArray(listOf(min, max)))
                }
                other_cursor.close()
                res.getJSONArray("results").put(r)
                res.put("count",res.getInt("count")+1)
            }
            cursor.close()
            return res.toString()
        }
        @JavascriptInterface
        fun getLocalRoads(parameters:String): String {
            val params=JSONObject(parameters)
            val manager= GeoPackageFactory.getManager(context)
            try {
                manager.importGeoPackage(File(context.filesDir, "roads.gpkg"), true)
            }catch(e:Exception){
                return "[]"
            }
            val geopackage=manager.open("roads")
            val features=geopackage.featureTables
            val ft= features[0]
            val featuredao=geopackage.getFeatureDao(ft)
            val indexer= FeatureIndexManager(context, geopackage,ft)
            indexer.indexLocation=FeatureIndexType.GEOPACKAGE
            val p=params.getJSONArray("bounds")
            val results=indexer.query(BoundingBox(p.getDouble(1),p.getDouble(0),p.getDouble(3), p.getDouble(2)))
            val roadmap= ArrayList<Any>()
            var n=0
            for (r in results){
                for (g in (r.geometry.geometry as MultiLineString).geometries) {
                    val roadsegment = ArrayList<Any>()
                    for (p in g.points) {
                        roadsegment.add(listOf(p.x, p.y))
                    }
                    roadmap.add(roadsegment)
                    n++
                    if (n>5000){
                        return geoformat(roadmap.toString())
                    }
                }
            }
            return geoformat(roadmap.toString())
        }
    }
    fun getBackend():String{
        var sharedPref: SharedPreferences = this.getPreferences(Context.MODE_PRIVATE)
        val b= sharedPref.getString("backend", getString(R.string.backend))?.replace(":8009",":4201")?.split("?")
        var url="${b?.get(0)?.replace(Regex("\\/$"),"")}/"
        if (b != null) {
            if(b.count()>1) url="${url}?${b[1]}"
        }
        return url
    }

}


sealed class Result<out R> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Error(val exception: Exception) : Result<Nothing>()
}