package com.forja.app.feature.cleanup

/** Keeps stale cached fixes and invalid coordinates out of the durable upload queue. */
internal object JourneySamplePolicy {
 fun accept(at:Long,startedAt:Long,lastAt:Long,now:Long,ageNanos:Long,accuracy:Double,hasAccuracy:Boolean,lat:Double,lon:Double):Boolean =
  startedAt>0&&at>=startedAt&&at<=now+10000&&at>=now-7L*24*60*60*1000&&at>lastAt&&(lastAt==0L||at-lastAt>=25000)&&
  ageNanos in 0..90000000000L&&hasAccuracy&&accuracy.isFinite()&&accuracy in 0.0..50.0&&lat.isFinite()&&lat in -85.0..85.0&&lon.isFinite()&&lon in -180.0..180.0
}
