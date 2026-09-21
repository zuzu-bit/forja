package com.forja.app.feature.cleanup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** IDs belong to a frozen analysis selection. Retries never inventory the phone again. */
internal data class TransferItem(val id:String,val uri:String,val name:String,val mime:String,val folder:String,val size:Long,val sha:String="")
internal data class TransferReceipt(val id:String,val sha:String,val bytes:Long,val received:Long,val expires:Long)
internal object CleanupSelection {
    fun freeze(files:List<CleanFile>)=files.distinctBy{it.uri}.map{TransferItem(java.util.UUID.randomUUID().toString(),it.uri,it.name,it.mime,it.path,it.bytes)}
}
internal class TransferProblem(val retryable:Boolean,val stopRound:Boolean,message:String):Exception(message)
internal interface TransferPort {
    suspend fun pending():List<TransferItem>
    suspend fun checkActive()
    suspend fun upload(item:TransferItem):TransferReceipt
    suspend fun confirm(item:TransferItem,receipt:TransferReceipt)
    suspend fun failed(item:TransferItem,problem:TransferProblem)
}
internal object CleanupTransferPump {
    suspend fun run(port:TransferPort):Boolean {
        var retry=false
        for(item in port.pending()) {
            currentCoroutineContext().ensureActive();port.checkActive()
            try {
                val receipt=port.upload(item)
                require(receipt.id==item.id&&receipt.sha.matches(Regex("[a-f0-9]{64}"))&&receipt.bytes>0&&receipt.expires>receipt.received&&receipt.expires-receipt.received<=24*60*60*1000L){"Confirmare invalidă de la server."}
                port.checkActive();port.confirm(item,receipt)
            }catch(e:CancellationException){throw e}
            catch(e:Exception){
                val p=e as? TransferProblem?:TransferProblem(true,true,e.message?:"Conexiune întreruptă.")
                port.failed(item,p);retry=retry||p.retryable
                if(p.stopRound)break
            }
        }
        return retry
    }
}
