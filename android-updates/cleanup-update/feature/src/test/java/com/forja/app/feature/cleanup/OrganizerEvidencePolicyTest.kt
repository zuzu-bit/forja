package com.forja.app.feature.cleanup

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrganizerEvidencePolicyTest {
    private fun finding()=JSONObject("""{"evidence":[{"id":"e1","kind":"visual","observation":"Scanare aparent goală."}],"deletion":{"suggested":true,"basis":"low_information","reason":"Verifică scanarea.","evidence_ids":["e1"],"requires_confirmation":true,"review_only":true}}""")
    @Test fun reviewRequiresRealEvidenceAndExplicitManualOnlyFlags(){
        assertNotNull(OrganizerEvidencePolicy.deletionReview(finding()))
        for(field in listOf("requires_confirmation","review_only")){
            val value=finding();value.getJSONObject("deletion").put(field,false)
            assertNull(OrganizerEvidencePolicy.deletionReview(value))
        }
        val unknown=finding();unknown.getJSONObject("deletion").put("evidence_ids",org.json.JSONArray().put("invented"))
        assertNull(OrganizerEvidencePolicy.deletionReview(unknown))
    }
    @Test fun missingContentCannotSupportADeletionBadge(){
        val value=finding();value.getJSONArray("evidence").getJSONObject(0).put("observation","")
        assertNull(OrganizerEvidencePolicy.deletionReview(value))
    }
    @Test fun filenameOrUnverifiedMatchCannotShowExactDuplicateBadge(){
        assertEquals(0,OrganizerEvidencePolicy.duplicateCount(JSONObject("""{"kind":"same_name","original_count":2}""")))
        val exact=JSONObject("""{"kind":"exact_bytes","original_count":2,"verified_received_bytes":true,"requires_confirmation":true,"review_only":true}""")
        assertEquals(2,OrganizerEvidencePolicy.duplicateCount(exact))
        exact.put("verified_received_bytes",false);assertEquals(0,OrganizerEvidencePolicy.duplicateCount(exact))
    }
}
