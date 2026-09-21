package com.forja.app.feature.research;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import com.forja.app.core.data.CollectionSettings;
import com.forja.app.core.data.WebAccountControl;
import com.google.firebase.auth.FirebaseAuth;
import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.UUID;

/** User-activated microphone foreground service. Never restarted after process death. */
public final class TimedRecordingService extends Service {
    public static final String ARM="com.forja.app.ARM_WEB_AUDIO", DISARM="com.forja.app.DISARM_WEB_AUDIO";
    public static final String START="com.forja.app.START_TIMED_RECORDING", STOP="com.forja.app.STOP_TIMED_RECORDING";
    public static final String CHANNEL="timed_recording";
    private static final int NOTICE=7310;
    private static volatile TimedRecordingService live;
    private static volatile String activeId;
    public static final Companion Companion=new Companion();
    public static final class Companion { public String getActiveId(){return activeId;} }
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final FirebaseAuth.AuthStateListener auth=a->checkAuthorization();
    private final SharedPreferences.OnSharedPreferenceChangeListener changed=(p,k)->checkAuthorization();
    private volatile boolean ready, promoted, destroying;
    private String owner, grant;
    private volatile String readySession;
    private long epoch, deadline, wakeRenewed;
    private MediaRecorder recorder;
    private TimedRecording item;
    private PowerManager.WakeLock wake;
    private SharedPreferences web, settings;
    private final Runnable tick=new Runnable(){public void run(){
        if(destroying)return;
        if(!authorized()){shutdown(false,"Controlul audio s-a oprit: verifică autorizarea și notificările.");return;}
        if(item!=null && SystemClock.elapsedRealtime()>=deadline)finish(true);
        if(destroying || (!ready && item==null))return;
        maintainWake();handler.postDelayed(this,1000);
    }};
    public static boolean isReady(){TimedRecordingService s=live;return s!=null && s.ready && s.promoted && !s.destroying && s.authorized();}
    public static void sleepTakingMicrophone(){
        TimedRecordingService s=live;
        if(s!=null)s.handler.post(()->{if(s.item!=null){s.finish(true);s.status("Înregistrarea web s-a oprit: ai pornit modul Somn pe telefon.");}});
    }
    public static String readySession(){TimedRecordingService s=live;return isReady() && s!=null?s.readySession:null;}
    public static boolean canRecord(){return CollectionSettings.INSTANCE.getVisible() || isReady();}
    public static void dispatchWeb(Context c,Intent i){
        // Only send to the already-promoted service. A cold background start never arms it.
        if(!isReady())throw new IllegalStateException("Activează Audio din web pe telefon.");
        i.putExtra("web_audio",true).putExtra("ready_session",readySession());c.startService(i);
    }
    @Override public void onCreate(){
        super.onCreate();AudioDiagnostics.install(this);
        web=getSharedPreferences("web_account_control_v1",MODE_PRIVATE);settings=CollectionSettings.INSTANCE.prefs(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Audio FORJA · control și înregistrare",NotificationManager.IMPORTANCE_LOW));
        live=this; settings.edit().putBoolean("recording_active",false).apply();
        web.registerOnSharedPreferenceChangeListener(changed);settings.registerOnSharedPreferenceChangeListener(changed);
        FirebaseAuth.getInstance().addAuthStateListener(auth);
    }
    private boolean permissions(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return false;
        NotificationManager nm=getSystemService(NotificationManager.class);
        NotificationChannel channel=nm.getNotificationChannel(CHANNEL);
        return nm.areNotificationsEnabled() && channel!=null && channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }
    private boolean authorized(){
        return AudioReadyPolicy.authorized(owner,AudioDiagnostics.owner(),grant,web.getString("grant",null),epoch,RecordingStore.INSTANCE.epoch(this),WebAccountControl.INSTANCE.enabled(this),permissions());
    }
    private void checkAuthorization(){
        // Preference callbacks may be triggered while metadata is being committed.
        handler.post(()->{if(owner!=null && !destroying && !authorized())shutdown(false,"Autorizarea audio a fost retrasă.");});
    }
    private void bindOwner(){owner=AudioDiagnostics.owner();grant=web.getString("grant",null);epoch=RecordingStore.INSTANCE.epoch(this);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?null:intent.getAction();
        if(DISARM.equals(action)){shutdown(true,"Audio din web este dezactivat pe acest telefon.");return START_NOT_STICKY;}
        if(STOP.equals(action)){finish(true);if(!ready)shutdown(false,null);return START_NOT_STICKY;}
        if(ARM.equals(action)){
            if(!CollectionSettings.INSTANCE.getVisible() || !intent.getBooleanExtra("explicit_web_audio_consent",false) || !WebAccountControl.INSTANCE.enabled(this) || !permissions() || !sameOwner(intent)){
                status("Activarea Audio din web necesită confirmarea pe telefon și notificări active.");if(!promoted)stopSelf();return START_NOT_STICKY;
            }
            if(ready)return START_NOT_STICKY;
            bindOwner();
            try{promote(notification(item!=null));readySession=UUID.randomUUID().toString();ready=true;status(item==null?"Audio din web activ. Aștept Start din contul tău; nu înregistrez sunet.":"Se înregistrează. Audio din web este activ.");startTick();}
            catch(RuntimeException e){shutdown(false,AudioDiagnostics.captureFailure(e));}
            return START_NOT_STICKY;
        }
        if(!START.equals(action)){if(!promoted)stopSelf();return START_NOT_STICKY;}
        if(item!=null)return START_NOT_STICKY;
        long until=intent.getLongExtra("until",0),remaining=until-System.currentTimeMillis();
        if(!sameOwner(intent) || !WebAccountControl.INSTANCE.enabled(this) || !permissions() || !canRecord() || (intent.getBooleanExtra("web_audio",false) && (!isReady() || !AudioReadyPolicy.sameSession(readySession,intent.getStringExtra("ready_session")))) || !AudioReadyPolicy.durationAllowed(remaining)){
            status("Pornirea audio a fost refuzată. Verifică modul Audio din web, contul și intervalul.");if(!promoted)stopSelf();return START_NOT_STICKY;
        }
        String conflict=AudioDiagnostics.microphoneConflict();
        if(conflict!=null){status(conflict);if(!promoted)stopSelf();return START_NOT_STICKY;}
        if(!ready)bindOwner();
        if(!authorized()){shutdown(false,"Autorizarea audio s-a schimbat.");return START_NOT_STICKY;}
        RecordingStore.INSTANCE.recover(this);
        int pending=0;for(TimedRecording r:RecordingStore.INSTANCE.all(this))if(RecordingStore.INSTANCE.file(this,r.getId()).exists())pending++;
        if(pending>=5){status("Cinci înregistrări așteaptă trimiterea. Verifică primirea datelor și conexiunea.");if(!promoted)stopSelf();return START_NOT_STICKY;}
        File file=null;
        try{
            long from=System.currentTimeMillis();
            // RecordingStore.authorized requires sync=true, bound to this owner and epoch.
            item=new TimedRecording(UUID.randomUUID().toString(),owner,epoch,from,until,0,"recording",true,"Microfon pornit; fișierul se trimite la final.");
            file=RecordingStore.INSTANCE.file(this,item.getId());file.getParentFile().mkdirs();
            AudioDiagnostics.beforeForeground();promote(notification(true));
            recorder=Build.VERSION.SDK_INT>=31?new MediaRecorder(this):new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setAudioChannels(1);recorder.setAudioSamplingRate(44100);recorder.setAudioEncodingBitRate(64000);
            recorder.setOutputFile(file.getAbsolutePath());recorder.setMaxDuration((int)remaining);recorder.setMaxFileSize(30L*1024*1024);
            recorder.setOnInfoListener((r,what,extra)->{if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)handler.post(()->{if(recorder==r)finish(true);});});
            recorder.setOnErrorListener((r,what,extra)->handler.post(()->{if(recorder!=r)return;finish(false);status("Recorderul a raportat o eroare. Verifică fișierul local.");}));
            AudioDiagnostics.beforePrepare();recorder.prepare();AudioDiagnostics.beforeMicrophone();recorder.start();
            activeId=item.getId();deadline=SystemClock.elapsedRealtime()+Math.max(0,until-System.currentTimeMillis());
            RecordingStore.INSTANCE.save(this,item);settings.edit().putBoolean("recording_active",true).putLong("recording_until",until).apply();
            status("Microfon pornit. Fișierul complet se trimite automat după oprire.");startTick();
        }catch(Exception e){String message=AudioDiagnostics.captureFailure(e);finish(false);status(message);}
        return START_NOT_STICKY;
    }
    private boolean sameOwner(Intent i){String uid=AudioDiagnostics.owner();return uid!=null && uid.equals(i.getStringExtra("owner"));}
    private void promote(Notification n){
        if(!promoted){if(Build.VERSION.SDK_INT>=29)startForeground(NOTICE,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);else startForeground(NOTICE,n);promoted=true;}
        else getSystemService(NotificationManager.class).notify(NOTICE,n);
    }
    private Notification notification(boolean recording){
        PendingIntent open=PendingIntent.getActivity(this,NOTICE,new Intent(this,WebPairActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent disable=PendingIntent.getService(this,NOTICE+1,new Intent(this,TimedRecordingService.class).setAction(DISARM),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String title=recording?"FORJA înregistrează audio":"FORJA · Audio din web activ";
        String text=recording?"Până la "+DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(item.getUntil()))+" · se trimite în contul tău":"Așteaptă Start din contul tău. Nu înregistrează acum.";
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(title).setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE);
        if(Build.VERSION.SDK_INT>=31)b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if(recording){PendingIntent stop=PendingIntent.getService(this,NOTICE+2,new Intent(this,TimedRecordingService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);b.addAction(new Notification.Action.Builder(null,"Oprește și trimite",stop).build());}
        b.addAction(new Notification.Action.Builder(null,"Dezactivează audio web",disable).build());return b.build();
    }
    private void maintainWake(){
        long now=SystemClock.elapsedRealtime();
        if(wake==null){wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,getPackageName()+":web-audio");wake.setReferenceCounted(false);}
        if(!wake.isHeld() || now-wakeRenewed>240000){wake.acquire(300000);wakeRenewed=now;}
    }
    private void startTick(){maintainWake();handler.removeCallbacks(tick);handler.post(tick);WebAccountControl.INSTANCE.recordingChanged(this);}
    private void finish(boolean upload){
        TimedRecording current=item;if(current==null)return;
        item=null;activeId=null;boolean stopped=false;
        if(recorder!=null){try{recorder.stop();stopped=true;}catch(RuntimeException ignored){}try{recorder.release();}catch(RuntimeException ignored){}recorder=null;}
        settings.edit().putBoolean("recording_active",false).remove("recording_until").apply();
        File file=RecordingStore.INSTANCE.file(this,current.getId());long duration=0;
        if(stopped && file.exists()){
            MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(file.getAbsolutePath());duration=Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));}catch(Exception ignored){}finally{try{r.release();}catch(Exception ignored){}}
        }
        boolean valid=duration>=1000 && duration<=3602000 && file.length()>0 && file.length()<=30L*1024*1024;
        boolean send=valid && upload && authorized() && RecordingStore.INSTANCE.authorized(this,current);
        TimedRecording done=new TimedRecording(current.getId(),current.getOwner(),current.getEpoch(),current.getFrom(),current.getUntil(),valid?duration:0,send?"pending":valid?"cancelled":"interrupted",send,
            send?"Se trimite automat când există internet.":"Sesiune încheiată fără trimitere.");
        try{RecordingStore.INSTANCE.save(this,done);if(send)RecordingStore.INSTANCE.enqueue(this,done);}catch(RuntimeException e){status("Fișierul este păstrat local; salvarea cozii nu a reușit.");}
        if(!valid)file.delete();
        status(send?"Înregistrare încheiată. Urmează trimiterea automată.":"Înregistrare încheiată fără trimitere.");
        if(ready && authorized() && !destroying)promote(notification(false));else shutdown(false,null);
    }
    private void status(String message){settings.edit().putString("recording_status",message).apply();WebAccountControl.INSTANCE.recordingChanged(this);}
    private void shutdown(boolean upload,String message){
        ready=false;readySession=null;if(destroying)return;destroying=true;finish(upload);handler.removeCallbacksAndMessages(null);
        if(wake!=null && wake.isHeld())wake.release();wake=null;
        if(live==this)live=null;promoted=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        if(message!=null)status(message);else WebAccountControl.INSTANCE.recordingChanged(this);
    }
    @Override public void onDestroy(){shutdown(false,null);web.unregisterOnSharedPreferenceChangeListener(changed);settings.unregisterOnSharedPreferenceChangeListener(changed);FirebaseAuth.getInstance().removeAuthStateListener(auth);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
