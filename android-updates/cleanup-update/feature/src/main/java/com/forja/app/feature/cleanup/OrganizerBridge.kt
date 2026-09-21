package com.forja.app.feature.cleanup

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.*

/** One setup screen reuses Android grants. It never broadens a previous source grant. */
object OrganizerBridge {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var installed=false
    @JvmStatic fun enabled(c:Context)=CleanupAuto.enabled(c)
    @JvmStatic fun status(c:Context)=if(enabled(c))CleanupAuto.prefs(c).getString("status","").orEmpty()else "Organizarea din laptop este oprită."
    @JvmStatic fun tree(c:Context)=if(enabled(c))CleanupAuto.prefs(c).getString("tree","").orEmpty()else c.getSharedPreferences("cleanup_scope_v14",Context.MODE_PRIVATE).getString("phone_tree","").orEmpty()
    @JvmStatic fun activate(c:Context,photos:Boolean,files:Boolean,tree:String,moves:Boolean,callback:java.util.function.Consumer<String>){
        scope.launch {val result=try{CleanupAuto.activate(c.applicationContext,photos,files,tree,moves);"Sursele au fost autorizate. Acum poți cere selecții și propuneri din laptop."}catch(e:Exception){e.message?:"Activarea nu a reușit."};withContext(Dispatchers.Main){callback.accept(result)}}
    }
    @JvmStatic fun stop(c:Context,callback:java.util.function.Consumer<String>){scope.launch{CleanupAuto.stop(c.applicationContext);withContext(Dispatchers.Main){callback.accept(status(c))}}}
    @JvmStatic fun check(c:Context){CleanupAuto.schedule(c,true)}
    @JvmStatic fun install(app:Application){
        if(installed)return;installed=true;SocialRecovery.install(app);LostPhoneRecovery.install(app)
        var visible=0;var polling:Job?=null
        app.registerActivityLifecycleCallbacks(object:Application.ActivityLifecycleCallbacks{
            override fun onActivityStarted(a:Activity){visible++;if(visible==1){polling=scope.launch{while(isActive){if(CleanupAuto.enabled(app))CleanupAuto.poll(app);delay(15000)}}}}
            override fun onActivityStopped(a:Activity){visible=(visible-1).coerceAtLeast(0);if(visible==0){polling?.cancel();polling=null;CleanupAuto.schedule(app)}}
            override fun onActivityCreated(a:Activity,b:Bundle?){};override fun onActivityResumed(a:Activity){};override fun onActivityPaused(a:Activity){};override fun onActivitySaveInstanceState(a:Activity,b:Bundle){};override fun onActivityDestroyed(a:Activity){}
        })
        CleanupAuto.schedule(app)
    }
}
