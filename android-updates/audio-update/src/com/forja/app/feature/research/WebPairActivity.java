package com.forja.app.feature.research;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import com.forja.app.feature.cleanup.OrganizerBridge;
import com.forja.app.core.data.CollectionSettings;
import com.forja.app.core.data.WebAccountControl;
import com.google.firebase.auth.FirebaseAuth;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Local, explicit session start. Uses the existing owner/epoch-bound recorder and upload queue. */
public final class WebPairActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int ink = Color.rgb(237,242,232), dim = Color.rgb(170,185,174), green = Color.rgb(201,232,156);
    private TextView state, connection, notice, sleepState, filesState, folderState;
    private CheckBox photosSource, documentsSource, moveConsent, filesConsent;
    private Button activateFiles;
    private String documentTree="";
    private boolean filesRequest;
    private Button start, stop, authorize, background;
    private TextView backgroundState;
    private CheckBox backgroundConsent;
    private CheckBox consent;
    private Spinner duration;
    private LinearLayout recordings, audioPage, filesPage;
    private boolean resumed, pairingRequest, armRequest;
    private long startRequested;
    private String listKey = "", pageOwner;
    private MediaPlayer player;
    private final int[] minutes = {1,5,10,15,30,60};
    private final Runnable refresh = new Runnable() { public void run() { if (resumed) { update(); handler.postDelayed(this,1000); } } };
    private final FirebaseAuth.AuthStateListener auth = value -> runOnUiThread(() -> {
        if (state == null || isFinishing()) return;
        String current = AudioDiagnostics.owner();
        if (pageOwner == null ? current != null : !pageOwner.equals(current)) {
            pageOwner = current; filesRequest=false;if(filesConsent!=null){filesConsent.setChecked(false);moveConsent.setChecked(false);photosSource.setChecked(false);documentsSource.setChecked(false);documentTree="";folderState.setText("Alege dosarul pentru acest cont.");}consent.setChecked(false); backgroundConsent.setChecked(false); armRequest=false; startRequested = 0; listKey = ""; stopPlayback(); notice.setText("");
        }
        update();
    });
    @Override public void onCreate(Bundle b) {
        super.onCreate(b); AudioDiagnostics.install(this); pageOwner = AudioDiagnostics.owner();
        getWindow().setStatusBarColor(Color.rgb(16,21,19)); getWindow().setNavigationBarColor(Color.rgb(16,21,19));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(16,21,19));
        LinearLayout root = column(); root.setPadding(dp(22),dp(28),dp(22),dp(32)); scroll.addView(root); setContentView(scroll);
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(22),insets.getSystemWindowInsetTop()+dp(16),dp(22),insets.getSystemWindowInsetBottom()+dp(24));return insets;});root.requestApplyInsets();
        Button back = button("Înapoi", () -> finish()); root.addView(back);
        root.addView(text("Telefonul meu",28,true));
        LinearLayout tabs=new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL); root.addView(tabs);
        audioPage=column();filesPage=column();root.addView(audioPage);root.addView(filesPage);filesPage.setVisibility(View.GONE);
        Button audioTab=button("Audio",()->{audioPage.setVisibility(View.VISIBLE);filesPage.setVisibility(View.GONE);});
        Button filesTab=button("Fișiere",()->{audioPage.setVisibility(View.GONE);filesPage.setVisibility(View.VISIBLE);});
        tabs.addView(audioTab,new LinearLayout.LayoutParams(0,dp(52),1));tabs.addView(filesTab,new LinearLayout.LayoutParams(0,dp(52),1));
        View.OnClickListener selectTab=v->{boolean audio=v==audioTab;audioPage.setVisibility(audio?View.VISIBLE:View.GONE);filesPage.setVisibility(audio?View.GONE:View.VISIBLE);audioTab.setSelected(audio);filesTab.setSelected(!audio);audioTab.setBackground(background(audio?green:Color.rgb(40,53,44)));audioTab.setTextColor(audio?Color.rgb(23,32,17):ink);filesTab.setBackground(background(audio?Color.rgb(40,53,44):green));filesTab.setTextColor(audio?ink:Color.rgb(23,32,17));};
        audioTab.setOnClickListener(selectTab);filesTab.setOnClickListener(selectTab);audioTab.performClick();
        root.addView(button("Confidențialitate și ajutor",this::showPrivacy));
        LinearLayout sleep=details(audioPage,"Microfon pentru Somn",false);
        sleepState=text("",14,false);sleep.addView(sleepState);

        sleep.addView(button("Permite microfonul",()->{armRequest=false;pairingRequest=false;permissions();}));
        addFileSetup(filesPage);
        LinearLayout webAudio=card(audioPage);webAudio.addView(text("Audio din web",20,true));
        backgroundState=text("",16,true);webAudio.addView(backgroundState);

        backgroundConsent=new CheckBox(this);backgroundConsent.setText("Permit pornirea microfonului din contul meu web, inclusiv cu ecranul blocat. Înregistrările se trimit în contul meu pentru 24 h.");backgroundConsent.setTextColor(ink);webAudio.addView(backgroundConsent);
        background=button("Activează Audio din web",()->{
            if(TimedRecordingService.isReady()){startService(new Intent(this,TimedRecordingService.class).setAction(TimedRecordingService.DISARM));return;}
            if(!backgroundConsent.isChecked()){notice.setText("Bifează acordul pentru controlul microfonului din web.");return;}
            armRequest=true;pairingRequest=false;permissions();
        });webAudio.addView(background);
        LinearLayout audioHelp=details(webAudio,"Setări și ajutor",false);
        audioHelp.addView(button("Setările bateriei",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))));
        audioHelp.addView(button("Cum funcționează",this::showPrivacy));
        LinearLayout session = card(audioPage); session.addView(text("O înregistrare nouă",20,true));
        state = text("Verific starea…",16,true); session.addView(state);
        session.addView(text("Durata sesiunii",13,false));
        duration = new Spinner(this); String[] labels = {"1 minut · probă","5 minute","10 minute","15 minute","30 minute","60 minute"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View convert, android.view.ViewGroup parent) {
                TextView v = (TextView)super.getView(position,convert,parent);v.setTextColor(ink);v.setTextSize(17);v.setPadding(dp(8),dp(12),dp(8),dp(12));return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); duration.setAdapter(adapter); duration.setSelection(2); session.addView(duration);
        start = button("Pornește înregistrarea",this::begin);start.setTextColor(Color.rgb(23,32,17));start.setBackground(background(green));session.addView(start);
        stop = button("Oprește și trimite",() -> {
            if (TimedRecordingService.Companion.getActiveId()!=null) startService(new Intent(this,TimedRecordingService.class).setAction("com.forja.app.STOP_TIMED_RECORDING"));
        });session.addView(stop);
        notice = text("",14,false);notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(notice,2);
        LinearLayout account = details(audioPage,"Conexiune și cont",false);
        connection = text("",14,false);account.addView(connection);

        consent = backgroundConsent;
        authorize = button("Activează pe acest telefon",() -> {
            if (WebAccountControl.INSTANCE.enabled(this)) {
                new AlertDialog.Builder(this).setTitle("Dezactivezi controlul audio?").setMessage("Înregistrarea activă și transferurile autorizate prin această asociere vor fi oprite.")
                    .setNegativeButton("Renunță",null).setPositiveButton("Dezactivează",(d,w)->{WebAccountControl.INSTANCE.revoke(this);consent.setChecked(false);update();}).show();
            } else {
                if (!consent.isChecked()) { notice.setText("Bifează autorizarea pentru contul tău înainte de activare.");return; }
                armRequest=false;pairingRequest=true; permissions();
            }
        });account.addView(authorize);
        account.addView(button("Verifică legătura cu site-ul",() -> {WebAccountControl.INSTANCE.recordingChanged(this);notice.setText("Cer verificarea legăturii. Starea se actualizează aici.");}));
        account.addView(button("Deschide panoul online",() -> startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://forja-insights.forja-22e7ea2d.workers.dev")))));
        LinearLayout queue = card(audioPage);queue.addView(text("Înregistrările tale",20,true));

        recordings=column();queue.addView(recordings);
        FirebaseAuth.getInstance().addAuthStateListener(auth);
    }
    private CheckBox choice(String label,boolean value){CheckBox b=new CheckBox(this);b.setText(label);b.setTextColor(ink);b.setChecked(value);return b;}
    private void addFileSetup(LinearLayout root){
        LinearLayout card=card(root);card.addView(text("Organizare din laptop",20,true));
        card.addView(text("Alege ce organizezi din contul tău online.",14,false));
        boolean enabled=OrganizerBridge.enabled(this);android.content.SharedPreferences p=getSharedPreferences("cleanup_auto_v17",MODE_PRIVATE);
        photosSource=choice("Fotografii",enabled&&p.getBoolean("photos",false));documentsSource=choice("Dosar și subdosare",enabled&&p.getBoolean("files",false));card.addView(photosSource);card.addView(documentsSource);
        documentTree=OrganizerBridge.tree(this);folderState=text(documentTree.isEmpty()?"Niciun dosar ales.":"Dosar autorizat: "+Uri.parse(documentTree).getLastPathSegment(),13,false);card.addView(folderState);
        card.addView(button("Alege dosarul de pe telefon",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),182)));

        filesConsent=choice("Permit analiza locală și încărcarea în contul meu a selecțiilor pe care le cer sau programez (24 h).",enabled);card.addView(filesConsent);
        moveConsent=choice("Permit mutarea originalelor doar după aprobarea mea din site. Pentru Galerie confirm și în Android.",enabled&&p.getBoolean("organize",false));card.addView(moveConsent);
        filesState=text(OrganizerBridge.status(this),14,false);card.addView(filesState);
        activateFiles=button("Activează organizarea",()->{
            if(AudioDiagnostics.owner()==null){notice.setText("Conectează-te în FORJA cu contul folosit pe site.");return;}
            if(!filesConsent.isChecked()||!photosSource.isChecked()&&!documentsSource.isChecked()){notice.setText("Alege sursele și confirmă analiza cu încărcare în propriul cont.");return;}
            if(documentsSource.isChecked()&&documentTree.isEmpty()){notice.setText("Alege mai întâi dosarul cu fișiere.");return;}
            if(photosSource.isChecked()&&!galleryGranted()){filesRequest=true;requestPermissions(Build.VERSION.SDK_INT>=34?new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}:new String[]{Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE},181);return;}
            enableFiles();
        });card.addView(activateFiles);
        card.addView(button("Verifică cererile din site",()->{OrganizerBridge.check(this);notice.setText("Cer verificarea selecțiilor și planurilor din site.");}));
        card.addView(button("Oprește organizarea din laptop",()->OrganizerBridge.stop(this,result->{if(!isFinishing())notice.setText(result);})));
    }
    private boolean galleryGranted(){return Build.VERSION.SDK_INT>=33?checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED||(Build.VERSION.SDK_INT>=34&&checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED):checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;}
    private void enableFiles(){if(!filesConsent.isChecked()||AudioDiagnostics.owner()==null)return;activateFiles.setEnabled(false);OrganizerBridge.activate(this,photosSource.isChecked(),documentsSource.isChecked(),documentTree,moveConsent.isChecked(),result->{if(!isFinishing()){activateFiles.setEnabled(true);notice.setText(result);filesState.setText(OrganizerBridge.status(this));}});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==182&&result==RESULT_OK&&data!=null&&data.getData()!=null){try{Uri uri=data.getData();getContentResolver().takePersistableUriPermission(uri,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));boolean writable=false;for(android.content.UriPermission permission:getContentResolver().getPersistedUriPermissions())if(permission.getUri().equals(uri)&&permission.isReadPermission()&&permission.isWritePermission())writable=true;if(!writable)throw new SecurityException();documentTree=uri.toString();documentsSource.setChecked(true);folderState.setText("Dosar ales: "+uri.getLastPathSegment());}catch(Exception e){notice.setText("Dosarul trebuie să permită citirea și organizarea. Alege un dosar accesibil.");}}}
    private void permissions() {
        if (AudioDiagnostics.owner()==null) {notice.setText("Conectează-te în FORJA înainte de activare.");pairingRequest=false;return;}
        List<String> needed=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.RECORD_AUDIO);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!needed.isEmpty()){requestPermissions(needed.toArray(new String[0]),180);return;}
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(!nm.areNotificationsEnabled()) {startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));return;}
        if(armRequest && nm.getNotificationChannel("timed_recording")!=null && nm.getNotificationChannel("timed_recording").getImportance()==0) {
            startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,"timed_recording"));return;
        }
        if(armRequest){
            armRequest=false;
            if(!backgroundConsent.isChecked())return;
            try{if(!WebAccountControl.INSTANCE.enabled(this))WebAccountControl.INSTANCE.allow(this);startForegroundService(new Intent(this,TimedRecordingService.class).setAction(TimedRecordingService.ARM)
                .putExtra("explicit_web_audio_consent",true).putExtra("owner",AudioDiagnostics.owner()));notice.setText("Activez Audio din web. Așteaptă confirmarea notificării.");}
            catch(Exception e){notice.setText(AudioDiagnostics.captureFailure(e));}
        } else if(pairingRequest) {
            pairingRequest=false;
            if(consent.isChecked())try {WebAccountControl.INSTANCE.allow(this);notice.setText("Activată. Poți porni o sesiune din acest ecran sau din site.");}catch(Exception e){notice.setText("Activarea nu a reușit. Verifică autentificarea FORJA.");}
        } else notice.setText("Microfonul și notificările sunt autorizate. Dacă Android blochează microfonul global, activează-l din setările rapide.");
        update();
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if(r==181){if(!filesRequest)return;filesRequest=false;if(galleryGranted())enableFiles();else notice.setText("Alege accesul la fotografiile pe care vrei să le organizezi. Poți autoriza toată galeria sau doar o selecție.");return;}
        if(r!=180)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)) {
            pairingRequest=false;armRequest=false;
            new AlertDialog.Builder(this).setTitle("Permisiune necesară").setMessage("Înregistrarea folosește microfonul și o notificare vizibilă cu Oprește. Le poți activa în setările FORJA.")
                .setNegativeButton("Mai târziu",null).setPositiveButton("Setări",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).show();
        } else permissions();
    }
    private void begin() {
        String problem=AudioDiagnostics.preflight(this);
        if(problem!=null){notice.setText(problem);return;}
        if(TimedRecordingService.Companion.getActiveId()!=null || System.currentTimeMillis()-startRequested<10000)return;
        stopPlayback();startRequested=System.currentTimeMillis();notice.setText("Pornesc sesiunea. Așteaptă confirmarea înainte de blocarea ecranului.");
        try {startForegroundService(new Intent(this,TimedRecordingService.class).setAction("com.forja.app.START_TIMED_RECORDING")
            .putExtra("owner",AudioDiagnostics.owner()).putExtra("until",System.currentTimeMillis()+minutes[duration.getSelectedItemPosition()]*60000L));}
        catch(Exception e){startRequested=0;notice.setText(AudioDiagnostics.captureFailure(e));}
        update();
    }
    private void update() {
        if(state==null || isFinishing())return;
        sleepState.setText(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED?"Microfon permis":"Microfon oprit");
        if(filesState!=null)filesState.setText(OrganizerBridge.status(this));
        String uid=AudioDiagnostics.owner();boolean linked=WebAccountControl.INSTANCE.enabled(this), active=TimedRecordingService.Companion.getActiveId()!=null;
        boolean ready=TimedRecordingService.isReady();
        background.setText(ready?"Dezactivează Audio din web":"Activează Audio din web");
        backgroundConsent.setVisibility(ready?View.GONE:View.VISIBLE);
        backgroundState.setText(ready?(active?"Microfonul înregistrează · control web activ":"Pregătit · microfon oprit"):"Control web oprit");
        authorize.setText("Dezactivează controlul audio");authorize.setVisibility(linked?View.VISIBLE:View.GONE);
        String status=CollectionSettings.INSTANCE.prefs(this).getString("recording_status","");
        if(active){startRequested=0;state.setText("Se înregistrează. Poți bloca ecranul.");notice.setText(status);}
        else if(startRequested>0 && System.currentTimeMillis()-startRequested<10000){state.setText("Aștept confirmarea microfonului…");}
        else {state.setText(linked?"Pregătit pentru o sesiune":"Activează audio pentru contul tău");if(startRequested>0){notice.setText(status.isEmpty()?"Pornirea nu a fost confirmată. Verifică permisiunile de mai jos.":status);startRequested=0;}}
        start.setEnabled(linked && !active && startRequested==0);stop.setEnabled(active);duration.setEnabled(!active && startRequested==0);
        connection.setText((AudioDiagnostics.connected(this)?"Internet disponibil. ":"Fără internet validat. Înregistrările rămân pe telefon până la reconectare. ")+AudioDiagnostics.linkSummary(this)+(ready && !AudioWebStatus.supported()?" Publică și actualizarea site-ului pentru pornire în fundal.":""));
        List<TimedRecording> own=new ArrayList<>();StringBuilder key=new StringBuilder(String.valueOf(uid));
        for(TimedRecording item:RecordingStore.INSTANCE.all(this))if(uid!=null && uid.equals(item.getOwner())) {own.add(item);key.append(item.getId()).append(item.getState()).append(item.getNote());}
        key.append(active);
        if(key.toString().equals(listKey))return;listKey=key.toString();recordings.removeAllViews();
        if(own.isEmpty())recordings.addView(text("Prima ta înregistrare va apărea aici.",14,false));
        for(TimedRecording item:own) {
            LinearLayout row=column();row.setPadding(0,dp(12),0,dp(12));recordings.addView(row);
            row.addView(text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(item.getFrom()))+" · "+stateName(item.getState()),15,true));
            if(!item.getNote().isEmpty())row.addView(text(item.getNote(),13,false));
            File file=RecordingStore.INSTANCE.file(this,item.getId());
            if(file.exists() && !"recording".equals(item.getState()))row.addView(button("Ascultă / oprește copia locală",()->play(item)));
            if("failed".equals(item.getState()))row.addView(button("Reîncearcă trimiterea",()->{if(RecordingStore.INSTANCE.authorized(this,item)){RecordingStore.INSTANCE.retry(this,item.getId());listKey="";update();}}));
            if(file.exists() && !"recording".equals(item.getState()))row.addView(button("Șterge copia locală",()->new AlertDialog.Builder(this).setTitle("Ștergi această copie din telefon?")
                .setMessage("Copiile deja primite pe site expiră separat după 24 de ore.").setNegativeButton("Renunță",null).setPositiveButton("Șterge",(d,w)->{if(item.getOwner().equals(AudioDiagnostics.owner())){stopPlayback();RecordingStore.INSTANCE.deleteLocal(this,item.getId());listKey="";update();}}).show()));
        }
    }
    private void play(TimedRecording item) {
        if(player!=null){stopPlayback();return;}
        if(!item.getOwner().equals(AudioDiagnostics.owner()))return;
        try {player=new MediaPlayer();player.setDataSource(RecordingStore.INSTANCE.file(this,item.getId()).getAbsolutePath());player.setOnCompletionListener(p->stopPlayback());player.prepare();player.start();}
        catch(Exception e){stopPlayback();notice.setText("Copia locală nu poate fi redată. Înregistrarea poate fi incompletă.");}
    }
    private void stopPlayback(){if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}}
    private String stateName(String s) {
        switch(s){case "recording":return "în curs";case "pending":return "așteaptă trimiterea";case "uploading":return "se încarcă";case "uploaded":return "primită pe server";case "failed":return "trimitere nereușită";case "cancelled":return "trimitere anulată";case "interrupted":return "înregistrare întreruptă";default:return s;}
    }
    @Override public void onResume(){super.onResume();resumed=true;if(state!=null){RecordingStore.INSTANCE.recover(this);WebAccountControl.INSTANCE.recordingChanged(this);handler.removeCallbacks(refresh);handler.post(refresh);}}
    @Override public void onPause(){resumed=false;handler.removeCallbacks(refresh);stopPlayback();super.onPause();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);FirebaseAuth.getInstance().removeAuthStateListener(auth);stopPlayback();super.onDestroy();}
    private void showPrivacy() {
        ScrollView scroll=new ScrollView(this);TextView copy=text(
            "AUDIO\nÎnregistrările pornesc doar prin comenzile autorizate. Controlul web poate porni microfonul și cu ecranul blocat, pentru 1–60 de minute. Fișierul se trimite automat în propriul cont după oprire și rămâne online 24 h. Notificarea arată starea și permite oprirea. Somnul folosește aceeași permisiune, dar nu poate folosi microfonul simultan cu audio din web.\n\n"+
            "FIȘIERE\nSunt accesate numai sursele alese. Cererile și programările tale pot trimite copii, miniaturi și text extras în contul tău pentru 24 h. AI online se pornește separat din site. Mutările originalelor cer aprobarea planului; pentru Galerie se confirmă și în Android. Nu se șterg automat originale. Limite: 25 MB pe fișier, 500 de copii și 512 MB online.\n\n"+
            "CONTROL\nPoți opri fiecare opțiune de aici. Dezactivarea audio oprește sesiunea și transferurile autorizate prin asociere. Copiile deja primite se șterg din site sau expiră separat. După restart sau oprire forțată, reactivează audio din web. Internetul, GPS-ul, bateria și Android pot întârzia comenzile. Pot exista costuri de internet mobil.",15,false);
        scroll.setBackgroundColor(Color.rgb(25,33,29));copy.setTextColor(ink);copy.setPadding(dp(22),dp(16),dp(22),dp(16));scroll.addView(copy);
        new AlertDialog.Builder(this).setTitle("Confidențialitate și ajutor").setView(scroll).setPositiveButton("Am înțeles",null).show();
    }
    private LinearLayout details(LinearLayout parent,String title,boolean expanded) {
        LinearLayout wrapper=card(parent),body=column();Button toggle=button(title+(expanded?" −":" +"),()->{});
        wrapper.addView(toggle);wrapper.addView(body);body.setVisibility(expanded?View.VISIBLE:View.GONE);
        toggle.setOnClickListener(v->{boolean open=body.getVisibility()!=View.VISIBLE;body.setVisibility(open?View.VISIBLE:View.GONE);toggle.setText(title+(open?" −":" +"));toggle.setContentDescription(title+(open?", extins":", restrâns"));});
        return body;
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));return l;}
    private LinearLayout card(LinearLayout parent){LinearLayout l=column();l.setPadding(dp(18),dp(16),dp(18),dp(18));l.setBackground(background(Color.rgb(25,33,29)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(20);parent.addView(l,p);return l;}
    private TextView text(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(bold?ink:dim);if(bold)t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(8),0,dp(10));return t;}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(ink);b.setMinHeight(dp(48));b.setBackground(background(Color.rgb(40,53,44)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);b.setLayoutParams(p);b.setOnClickListener(v->action.run());return b;}
    private GradientDrawable background(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
