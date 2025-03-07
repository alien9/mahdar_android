package net.alien9.driver


import com.android.volley.NetworkResponse
import com.android.volley.ParseError
import com.android.volley.Response
import com.android.volley.toolbox.HttpHeaderParser
import org.json.JSONException
import org.json.JSONObject
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import com.android.volley.toolbox.JsonRequest as JsonRequest

class DriverRequest<T>(
    method: Int,
    url: String,
    postData: String,
    errorListener: Response.ErrorListener,
    responseListener: Response.Listener<JSONObject>
): JsonRequest<JSONObject>(
    method, url, postData, responseListener, errorListener
) {
    private lateinit var token: String

    @Override
    override fun getHeaders(): Map<String,String> {
        val h = HashMap<String, String>()
        h.put("Authorization", "Token ${token}")
        return h
    }
    override fun parseNetworkResponse(response: NetworkResponse?): Response<JSONObject> {
        try{
            val jsonString = String(response!!.data, Charset.forName(HttpHeaderParser.parseCharset(response.headers)))
            return Response.success(JSONObject(jsonString), HttpHeaderParser.parseCacheHeaders(response))
        }catch (e: UnsupportedEncodingException){
            return Response.error(ParseError(e))
        }catch (je: JSONException){
            return Response.error(ParseError(je))
        }
    }

    fun setToken(t:String){
        token=t
    }
}