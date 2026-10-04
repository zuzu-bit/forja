package com.forja.app.feature.cleanup

/** UI choices are validated before creating an immutable organizer job. */
internal object OrganizerUiPolicy {
    const val MAX_BATCH=1000
    fun customCount(raw:String):Int? {
        val value=raw.trim()
        if(value.isEmpty()||value.any{it !in '0'..'9'})return null
        return value.toIntOrNull()?.takeIf{it in 1..MAX_BATCH}
    }
    fun canContinue(phase:String)=phase in setOf("ready","needs_permission","needs_access","failed_retryable","paused")
    fun canNext(phase:String)=phase in setOf("complete","needs_review","cancelled")
    fun working(phase:String)=phase in setOf("queued","inventory","analyzing","uploading","applying")
    fun label(phase:String)=when(phase){
        "queued"->"Pregătim lotul"
        "inventory"->"Căutăm fișierele rămase"
        "analyzing"->"Găsim dosarul potrivit"
        "uploading"->"Încărcăm copiile în cont"
        "ready"->"Destinațiile sunt pregătite"
        "needs_permission"->"Confirmă mutările în Android"
        "needs_access"->"Accesul trebuie confirmat"
        "applying"->"Punem fișierele la loc"
        "complete"->"Lot terminat"
        "needs_review"->"Câteva fișiere au nevoie de tine"
        "failed_retryable"->"Poți relua de aici"
        "paused"->"Lot în pauză"
        "cancelled"->"Lot oprit"
        else->"Organizarea ta"
    }
    fun itemLabel(state:String)=when(state){
        "moved"->"Mutat"
        "copied_pending_removal"->"Copiat · original păstrat"
        "needs_review"->"De verificat"
        "failed_retryable"->"De reîncercat"
        "skipped"->"Omis"
        "applying"->"Se mută"
        "uploaded"->"Copie primită pe site"
        "upload_pending"->"Așteaptă încărcarea"
        "analyzed","ready"->"Destinație pregătită"
        else->"În așteptare"
    }
}
