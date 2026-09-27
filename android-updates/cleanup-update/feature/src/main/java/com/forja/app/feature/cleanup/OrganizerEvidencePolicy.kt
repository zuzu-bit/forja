package com.forja.app.feature.cleanup

import org.json.JSONObject

/** A review suggestion is never executable and must point to actual content evidence. */
internal object OrganizerEvidencePolicy {
    fun evidence(finding:JSONObject):List<JSONObject> {
        val rows=finding.optJSONArray("evidence")?:return emptyList()
        return (0 until minOf(rows.length(),6)).mapNotNull{rows.optJSONObject(it)}.filter{
            it.optString("id").isNotBlank()&&when(it.optString("kind")){
                "text"->it.optString("quote").isNotBlank()
                "visual"->it.optString("observation").isNotBlank()
                else->false
            }
        }
    }
    fun deletionReview(finding:JSONObject):JSONObject? {
        val suggestion=finding.optJSONObject("deletion")?:return null
        if(suggestion.opt("suggested")!=true||suggestion.opt("requires_confirmation")!=true||suggestion.opt("review_only")!=true||suggestion.optString("basis")!="low_information"||suggestion.optString("reason").isBlank())return null
        val refs=suggestion.optJSONArray("evidence_ids")?:return null
        val ids=(0 until refs.length()).map{refs.optString(it)}
        val known=evidence(finding).map{it.optString("id")}.toSet()
        return suggestion.takeIf{ids.isNotEmpty()&&ids.size<=6&&ids.distinct().size==ids.size&&ids.all{it in known}}
    }
    fun duplicateCount(value:JSONObject?):Int {
        if(value==null||value.optString("kind")!="exact_bytes"||value.opt("verified_received_bytes")!=true||value.opt("requires_confirmation")!=true||value.opt("review_only")!=true)return 0
        return value.optInt("original_count").takeIf{it>1}?:0
    }
}
