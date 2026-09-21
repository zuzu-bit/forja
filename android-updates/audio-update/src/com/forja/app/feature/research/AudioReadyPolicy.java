package com.forja.app.feature.research;
/** Pure rules shared by readiness, command dispatch and final upload authorization. */
public final class AudioReadyPolicy {
    private AudioReadyPolicy(){}
    public static boolean authorized(String owner,String current,String grant,String currentGrant,long epoch,long currentEpoch,boolean linked,boolean permissions){
        return owner!=null && owner.equals(current) && grant!=null && !grant.isEmpty() && grant.equals(currentGrant) && epoch==currentEpoch && linked && permissions;
    }
    public static boolean sameSession(String current,String expected){return current!=null && !current.isEmpty() && current.equals(expected);}
    public static boolean durationAllowed(long remaining){return remaining>=5000 && remaining<=3600000;}
}
