package com.forja.app.feature.cleanup

import org.junit.Assert.*
import org.junit.Test

class JourneySamplePolicyTest {
 private val now=1900000000000L
 private fun accepts(at:Long=now,start:Long=now-60000,last:Long=now-30000,age:Long=1000000000,accuracy:Double=12.0,hasAccuracy:Boolean=true,lat:Double=44.4,lon:Double=26.1)=JourneySamplePolicy.accept(at,start,last,now,age,accuracy,hasAccuracy,lat,lon)
 @Test fun acknowledgedFreshFixIsAccepted(){assertTrue(accepts());assertTrue(accepts(at=now-60000,start=now-60000,last=0))}
 @Test fun cachedFixBeforeAcknowledgedStartIsRejected(){assertFalse(accepts(at=now-1000,start=now));assertFalse(accepts(start=0))}
 @Test fun wallClockAndMonotonicFreshnessAreBothRequired(){assertFalse(accepts(at=now+10001));assertFalse(accepts(age=90000000001));assertFalse(accepts(age=-1));assertFalse(accepts(at=now-8L*86400000,start=now-9L*86400000,last=0))}
 @Test fun invalidOrInaccurateCoordinatesNeverPoisonQueue(){assertFalse(accepts(accuracy=51.0));assertFalse(accepts(accuracy=Double.NaN));assertFalse(accepts(hasAccuracy=false));assertFalse(accepts(lat=86.0));assertFalse(accepts(lon=181.0))}
 @Test fun duplicateAndOverFrequentFixesAreDropped(){assertFalse(accepts(last=now));assertFalse(accepts(last=now-24999));assertTrue(accepts(last=now-25000))}
}
