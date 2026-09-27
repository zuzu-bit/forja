package com.forja.app.feature.research;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import com.forja.app.core.data.CollectionSettings;
import com.forja.app.core.data.WebAccountControl;
import com.forja.app.feature.cleanup.SleepBridge;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;
import java.io.File;

/** Owner-bound sleep consent and durable metadata. Never records from a read method. */
public final class SleepAudioState {
    private SleepAudioState() {}
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("sleep_audio_v25",Context.MODE_PRIVATE); }
    private static SharedPreferences web(Context c) { return c.getSharedPreferences("web_account_control_v1",Context.MODE_PRIVATE); }
    public static boolean authorized(Context c) {
        String uid=AudioDiagnostics.owner(); SharedPreferences p=prefs(c),w=web(c);
        return uid!=null && uid.equals(p.getString("owner",null)) && uid.equals(w.getString("owner",null))
            && p.getBoolean("enabled",false) && p.getString("grant","").equals(w.getString("grant",null))
            && WebAccountControl.INSTANCE.enabled(c);
    }
    public static boolean analysisAllowed(Context c) { return authorized(c) && prefs(c).getBoolean("analysis",false); }
    public static boolean authorizeRemoteSleep(Context c) { return authorizeSleep(c,true); }
    public static boolean authorizeSleep(Context c,boolean analysis) {
        String uid=AudioDiagnostics.owner();
        if(uid==null || !CollectionSettings.INSTANCE.getVisible())return false;
        if(!WebAccountControl.INSTANCE.enabled(c))WebAccountControl.INSTANCE.allow(c);
        if(!uid.equals(web(c).getString("owner",null)))return false;
        String grant=web(c).getString("grant",null);if(grant==null || grant.isEmpty())return false;
        if(!prefs(c).edit().putString("owner",uid).putString("grant",grant).putBoolean("enabled",true).putBoolean("analysis",analysis).commit())return false;
        return armAuthorized(c);
    }
    public static boolean armAuthorized(Context c) {
        if(!authorized(c) || !CollectionSettings.INSTANCE.getVisible() || c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED || !c.getSystemService(NotificationManager.class).areNotificationsEnabled())return false;
        if(TimedRecordingService.isReady())return true;
        try { c.startForegroundService(new Intent(c,TimedRecordingService.class).setAction(TimedRecordingService.ARM).putExtra("explicit_web_audio_consent",true).putExtra("owner",AudioDiagnostics.owner()));return true; }
        catch(RuntimeException e){return false;}
    }
    public static boolean startNow(Context c,int minutes) {
        if(!authorized(c) || !CollectionSettings.INSTANCE.getVisible() || !SleepAudioPolicy.isDurationAllowed(minutes*60000L))return false;
        Intent intent=new Intent(c,TimedRecordingService.class).setAction(TimedRecordingService.START_SLEEP).putExtra("owner",AudioDiagnostics.owner())
            .putExtra("sleep_id",UUID.randomUUID().toString()).putExtra("until",System.currentTimeMillis()+minutes*60000L).putExtra("explicit_sleep_start",true);
        try { c.startForegroundService(intent);return true; }catch(RuntimeException e){return false;}
    }
    public static void stop(Context c) { c.startService(new Intent(c,TimedRecordingService.class).setAction(TimedRecordingService.STOP)); }
    public static void legacyStart(Context c) {
        if(!startNow(c,480))c.startActivity(new Intent().setClassName(c.getPackageName(),"com.forja.app.feature.research.ResearchExportActivity").putExtra("forja_sync_setup",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    public static void disable(Context c) { prefs(c).edit().putBoolean("enabled",false).apply();c.startService(new Intent(c,TimedRecordingService.class).setAction(TimedRecordingService.DISARM)); }
    public static synchronized JSONObject session(Context c,String id) {
        try { JSONObject row=new JSONObject(prefs(c).getString("session:"+id,"{}"));
            if(!AudioDiagnostics.owner().equals(row.optString("owner")))return null; return row.has("id")?row:null;
        } catch(Exception e){return null;}
    }
    public static synchronized void begin(Context c,String id,long until) {
        if(!authorized(c))throw new IllegalStateException("Confirmă sincronizarea somnului pe telefon.");
        if(prefs(c).contains("session:"+id))throw new IllegalStateException("Sesiunea a fost deja pornită.");
        try { JSONObject row=new JSONObject().put("id",id).put("owner",AudioDiagnostics.owner()).put("grant",web(c).getString("grant",null))
            .put("epoch",RecordingStore.INSTANCE.epoch(c)).put("device_id",web(c).getString("device",null)).put("started_at",System.currentTimeMillis())
            .put("planned_stop_at",until).put("analysis_consent",analysisAllowed(c)).put("state","recording").put("chunks",new JSONArray());
            if(!prefs(c).edit().putString("session:"+id,row.toString()).putString("active",id).commit())throw new IllegalStateException("Nu pot salva sesiunea.");
            SleepBridge.sync(c);
        }catch(org.json.JSONException e){throw new IllegalStateException(e);}
    }
    public static synchronized void addChunk(Context c,String sleepId,TimedRecording recording) {
        JSONObject row=session(c,sleepId);if(row==null)throw new IllegalStateException("Sesiune de somn lipsă.");
        try { row.getJSONArray("chunks").put(recording.getId());
            if(!prefs(c).edit().putString("session:"+sleepId,row.toString()).putString("chunk:"+recording.getId(),sleepId).commit())throw new IllegalStateException("Nu pot salva segmentul.");
        }catch(org.json.JSONException e){throw new IllegalStateException(e);}
    }
    public static synchronized void ended(Context c,String id,boolean interrupted) {
        JSONObject row=session(c,id);if(row==null)return;
        try { row.put("ended_at",System.currentTimeMillis()).put("state",interrupted?"interrupted":"uploading");
            SharedPreferences.Editor edit=prefs(c).edit().putString("session:"+id,row.toString());
            if(id.equals(prefs(c).getString("active",null)))edit.remove("active");edit.commit();
            com.forja.app.feature.cleanup.SleepJournalBridge.persist(c,id,row.optLong("started_at"),row.optLong("ended_at"));
            SleepBridge.sync(c);SleepBridge.checkAlarm(c);
        }catch(org.json.JSONException ignored){}
    }
    public static synchronized JSONArray sessions(Context c) {
        JSONArray rows=new JSONArray();String uid=AudioDiagnostics.owner();if(uid==null)return rows;
        for(String key:prefs(c).getAll().keySet())if(key.startsWith("session:")) {
            JSONObject row=session(c,key.substring(8));if(row!=null && System.currentTimeMillis()-row.optLong("started_at")<=7*86400000L)rows.put(row);
        }
        return rows;
    }
    public static JSONObject sessionForChunk(Context c,String id) { String sleep=prefs(c).getString("chunk:"+id,null);return sleep==null?null:session(c,sleep); }
    /** Called on a worker thread before upload; retains only derived results, bound to this recording. */
    public static JSONObject acoustic(Context c,String id) {
        JSONObject row=sessionForChunk(c,id);
        if(!transferAuthorized(c,row) || !analysisAllowed(c) || !row.optBoolean("analysis_consent"))return null;
        try {
            String cached=prefs(c).getString("acoustic:"+id,null);
            if(cached!=null)return new JSONObject(cached);
            File file=RecordingStore.INSTANCE.file(c,id);
            if(!file.isFile())return null;
            JSONObject result=SleepSoundClassifier.analyze(c,file,()->!transferAuthorized(c,row) || !analysisAllowed(c));
            if(!transferAuthorized(c,row) || !analysisAllowed(c))return null;
            if("complete".equals(result.optString("status")))prefs(c).edit().putString("acoustic:"+id,result.toString()).commit();
            return result;
        }catch(Exception e){return null;}
    }
    public static boolean transferAuthorized(Context c,JSONObject row) {
        return row!=null && authorized(c) && row.optString("owner").equals(AudioDiagnostics.owner()) && row.optString("grant").equals(web(c).getString("grant",null)) && row.optLong("epoch",-1)==RecordingStore.INSTANCE.epoch(c);
    }
    public static synchronized JSONArray recordings(Context c) {
        JSONArray rows=new JSONArray();String uid=AudioDiagnostics.owner();if(uid==null)return rows;
        for(TimedRecording r:RecordingStore.INSTANCE.all(c))if(uid.equals(r.getOwner()))try {
            rows.put(new JSONObject().put("id",r.getId()).put("state",r.getState()).put("duration_ms",r.getDuration()).put("from",r.getFrom()));
        }catch(org.json.JSONException ignored){} return rows;
    }
    public static void recover(Context c) { RecordingStore.INSTANCE.recover(c); }
    public static String status(Context c) { return CollectionSettings.INSTANCE.prefs(c).getString("recording_status",""); }
    public static JSONObject active(Context c) { String id=TimedRecordingService.sleepId();return id==null?null:session(c,id); }
    public static void interruptedAfterRestart(Context c) {
        String id=prefs(c).getString("active",null);if(id!=null && TimedRecordingService.sleepId()==null)ended(c,id,true);
    }
    public static synchronized boolean receiveCommand(Context c,JSONObject command) {
        if(command==null || !"sleep".equals(command.optString("purpose")))return false;
        String action=command.optString("action"),id=command.optString("id"),ready=TimedRecordingService.readySession();
        if("stop".equals(action)) {
            if(TimedRecordingService.isReady() && AudioReadyPolicy.sameSession(ready,command.optString("audio_session",null))) {
                stop(c);ack(c,id,ready);
            }return true;
        }
        if(!"start".equals(action))return true;
        boolean handled=String.valueOf(ready).equals(prefs(c).getString("handled_session",null)) && prefs(c).getStringSet("handled_ids",java.util.Collections.emptySet()).contains(id);
        if(!SleepAudioPolicy.remoteStartAllowed(id,command.optString("sleep_id"),command.optString("audio_session",null),ready,System.currentTimeMillis(),command.optLong("start_at"),command.optLong("start_before"),command.optLong("stop_at"),authorized(c),handled))return true;
        if(TimedRecordingService.Companion.getActiveId()!=null)return true;
        Intent intent=new Intent(c,TimedRecordingService.class).setAction(TimedRecordingService.START_SLEEP).putExtra("owner",AudioDiagnostics.owner()).putExtra("sleep_id",command.optString("sleep_id")).putExtra("until",command.optLong("stop_at")).putExtra("web_audio",true).putExtra("ready_session",ready);
        if(!ack(c,id,ready))return true;
        try { c.startService(intent); }catch(RuntimeException e){CollectionSettings.INSTANCE.prefs(c).edit().putString("recording_status","Sesiunea din site nu a putut porni. Reîncearcă din telefon.").apply();} return true;
    }
    private static boolean ack(Context c,String id,String session) {
        java.util.Set<String> ids=new java.util.HashSet<>();
        if(String.valueOf(session).equals(prefs(c).getString("handled_session",null)))ids.addAll(prefs(c).getStringSet("handled_ids",java.util.Collections.emptySet()));
        ids.add(id);return prefs(c).edit().putString("handled",id).putString("handled_session",session).putStringSet("handled_ids",ids).commit();
    }
    public static void decorate(Context c,JSONObject payload) {
        try { payload.put("sleep_capable",true).put("sleep_analysis_allowed",analysisAllowed(c));
            String ready=TimedRecordingService.readySession();if(ready!=null && ready.equals(prefs(c).getString("handled_session",null)))payload.put("handled_command",prefs(c).getString("handled",null));
            JSONObject row=active(c);if(row!=null)payload.put("sleep_session",new JSONObject().put("id",row.getString("id")).put("state","recording").put("planned_stop_at",row.getLong("planned_stop_at")));
        }catch(org.json.JSONException ignored){}
    }
}
