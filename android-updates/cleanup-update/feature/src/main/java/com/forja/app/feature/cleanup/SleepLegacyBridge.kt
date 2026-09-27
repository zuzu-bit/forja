package com.forja.app.feature.cleanup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.util.UUID

internal data class LegacyActiveSleep(val owner:String,val id:String,val started:Long)

internal object SleepLegacyPolicy {
    fun active(owner:String?,session:JSONObject?,now:Long):LegacyActiveSleep? {
        if(owner.isNullOrBlank()||session==null||session.optString("owner")!=owner||session.has("ended_at"))return null
        val id=session.optString("id");val started=session.optLong("started_at")
        if(started<=0||started>now+60000||runCatching{UUID.fromString(id).toString()==id.lowercase()}.getOrDefault(false).not())return null
        return LegacyActiveSleep(owner,id,started)
    }
}

/** Supplies the existing Start/Stop card with the current recorder state, without writing Room. */
object SleepLegacyBridge {
    private val entityConstructor by lazy {
        Class.forName("com.forja.app.core.data.db.SleepSessionEntity").getConstructor(
            Long::class.javaPrimitiveType,Long::class.javaPrimitiveType,Long::class.javaObjectType,
            Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,String::class.java,String::class.java,Long::class.javaPrimitiveType
        )
    }
    @JvmStatic fun activeSession(context:Context):Flow<Any?> {
        val app=context.applicationContext
        return flow {
            while(currentCoroutineContext().isActive) {
                val owner=FileSync.owner()
                val session=SleepRuntime.active(app)
                val active=SleepLegacyPolicy.active(owner,session,System.currentTimeMillis())
                // Recheck after reading the owner-bound recorder, including sign-out/account changes.
                val value=active?.takeIf{FileSync.owner()==it.owner}?.let{
                    entityConstructor.newInstance(SleepJournalPolicy.localId(it.owner,it.id),it.started,null,0,-1,-1,-1,-1,"","",0L)
                }
                emit(value)
                delay(1000)
            }
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }
}
