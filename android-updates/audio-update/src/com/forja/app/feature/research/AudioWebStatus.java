package com.forja.app.feature.research;
import org.json.JSONObject;
import com.forja.app.core.data.CollectionSettings;
/** Capability negotiation keeps the existing server usable until its update is published. */
public final class AudioWebStatus {
    private static volatile boolean capable;
    private static volatile boolean sleepCapable;
    private AudioWebStatus(){}
    public static void server(JSONObject response){capable=response.optInt("background_audio",0)==1;sleepCapable=response.optInt("sleep_audio",0)==1;}
    public static JSONObject decorate(JSONObject payload){
        try{if(capable){String session=TimedRecordingService.readySession();payload.put("audio_ready",session!=null).put("audio_session",session==null?JSONObject.NULL:session);}}catch(Exception ignored){}
        if(sleepCapable && AudioDiagnostics.context()!=null)SleepAudioState.decorate(AudioDiagnostics.context(),payload);
        return payload;
    }
    public static JSONObject filterCommand(JSONObject command){
        if(sleepCapable && AudioDiagnostics.context()!=null && SleepAudioState.receiveCommand(AudioDiagnostics.context(),command))return null;
        if(command==null || !"start".equals(command.optString("action")))return command;
        if(!TimedRecordingService.isReady())return null;
        if(capable)return AudioReadyPolicy.sameSession(TimedRecordingService.readySession(),command.optString("audio_session",null))?command:null;
        return CollectionSettings.INSTANCE.getVisible()?command:null;
    }
    public static boolean supported(){return capable;}
}
