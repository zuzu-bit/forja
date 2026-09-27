package com.forja.app.feature.cleanup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.*
import org.json.JSONObject

/** The same pinned renderer is served to the site and this view. It never receives a token. */
internal class JourneyMapView(c:Context,val onEvent:(JSONObject)->Unit):WebView(c){
 companion object{const val ORIGIN="https://forja-insights.forja-22e7ea2d.workers.dev";const val PAGE="$ORIGIN/insights/map-frame"}
 private var ready=false
 private var latest=JSONObject()
 private var pendingFocus:Triple<Double,Double,Double>?=null
 init{
  settings.javaScriptEnabled=true;settings.allowFileAccess=false;settings.allowContentAccess=false;settings.domStorageEnabled=false;settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
  setBackgroundColor(0xffd9e1d0.toInt())
  addJavascriptInterface(object{@JavascriptInterface fun event(raw:String){if(raw.length>8192)return;runCatching{JSONObject(raw)}.getOrNull()?.let{event->post{if(event.optString("type")=="ready"){ready=true;data(latest);pendingFocus?.let{focus(it.first,it.second,it.third)}};onEvent(event)}}}},"ForjaMapHost")
  webViewClient=object:WebViewClient(){
   override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{if(request.url.toString()==PAGE)return false;if(request.isForMainFrame&&request.hasGesture()&&request.url.scheme=="https")runCatching{c.startActivity(Intent(Intent.ACTION_VIEW,request.url))};return true}
   override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){if(request.isForMainFrame)onEvent(JSONObject().put("type","error").put("message","Harta vectorială nu s-a încărcat."))}
   override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){if(request.isForMainFrame)onEvent(JSONObject().put("type","error").put("message","Harta 2D este disponibilă până revine conexiunea."))}
  }
  loadUrl(PAGE)
 }
 fun data(value:JSONObject){latest=value;if(ready)evaluateJavascript("window.ForjaMap && window.ForjaMap.setData(${value});",null)}
 fun mode(three:Boolean){if(ready)evaluateJavascript("window.ForjaMap && window.ForjaMap.set3D($three);",null)}
 fun focus(lat:Double,lon:Double,zoom:Double=16.0){if(!lat.isFinite()||!lon.isFinite())return;pendingFocus=Triple(lat,lon,zoom);if(ready)evaluateJavascript("window.ForjaMap && window.ForjaMap.focus({lat:$lat,lon:$lon},$zoom);",null)}
 fun close(){ready=false;removeJavascriptInterface("ForjaMapHost");stopLoading();loadUrl("about:blank");destroy()}
}
