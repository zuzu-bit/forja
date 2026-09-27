package com.forja.app.feature.cleanup

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.*
import org.json.JSONObject

/** Raster first; the isolated page receives only geographic data, never account credentials. */
internal class JourneyMapView(c:Context,val onEvent:(JSONObject)->Unit):WebView(c){
 companion object{const val ORIGIN="https://forja-insights.forja-22e7ea2d.workers.dev";const val PAGE="$ORIGIN/insights/map-frame?v=27"}
 private var ready=false
 private var closed=false
 private var latest=JSONObject()
 private var pendingFocus:Triple<Double,Double,Double>?=null
 private val handler=Handler(Looper.getMainLooper())
 private val watchdog=Runnable{if(!ready&&!closed)fail("Harta online nu a răspuns. Harta 2D a telefonului rămâne disponibilă.")}
 private fun fail(message:String){if(closed)return;ready=false;handler.removeCallbacks(watchdog);onEvent(JSONObject().put("type","error").put("recoverable",false).put("message",message))}
 init{
  settings.javaScriptEnabled=true;settings.allowFileAccess=false;settings.allowContentAccess=false;settings.domStorageEnabled=false;settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
  setBackgroundColor(0x00000000)
  addJavascriptInterface(object{@JavascriptInterface fun event(raw:String){if(raw.length>8192)return;runCatching{JSONObject(raw)}.getOrNull()?.let{event->post{if(closed)return@post;if(event.optString("type")=="ready"){ready=true;handler.removeCallbacks(watchdog);data(latest);pendingFocus?.let{focus(it.first,it.second,it.third)}};onEvent(event)}}}},"ForjaMapHost")
  webViewClient=object:WebViewClient(){
   override fun onPageStarted(view:WebView,url:String?,favicon:Bitmap?){if(closed)return;ready=false;handler.removeCallbacks(watchdog);handler.postDelayed(watchdog,18000);onEvent(JSONObject().put("type","loading"))}
   override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{if(request.url.toString()==PAGE)return false;if(request.isForMainFrame&&request.hasGesture()&&request.url.scheme=="https")runCatching{c.startActivity(Intent(Intent.ACTION_VIEW,request.url))};return true}
   override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){if(request.isForMainFrame)fail("Harta online nu s-a încărcat. Harta 2D a telefonului rămâne disponibilă.")}
   override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){if(request.isForMainFrame)fail("Harta 2D a telefonului rămâne disponibilă până revine conexiunea.")}
   override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean{fail("Randarea hărții online s-a oprit. Harta 2D a telefonului rămâne disponibilă.");return true}
  }
  handler.postDelayed(watchdog,18000);loadUrl(PAGE)
 }
 fun data(value:JSONObject){latest=value;if(ready&&!closed)evaluateJavascript("window.ForjaMap && window.ForjaMap.setData(${value});",null)}
 fun mode(three:Boolean){if(ready&&!closed)evaluateJavascript("window.ForjaMap && window.ForjaMap.set3D($three);",null)}
 fun focus(lat:Double,lon:Double,zoom:Double=16.0){if(!lat.isFinite()||!lon.isFinite())return;pendingFocus=Triple(lat,lon,zoom);if(ready&&!closed)evaluateJavascript("window.ForjaMap && window.ForjaMap.focus({lat:$lat,lon:$lon},$zoom);",null)}
 fun close(){if(closed)return;closed=true;ready=false;handler.removeCallbacks(watchdog);removeJavascriptInterface("ForjaMapHost");stopLoading();destroy()}
}
