package com.forja.app.feature.research;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import com.forja.app.core.data.CollectionSettings;
import com.forja.app.core.data.WebAccountControl;
import com.google.firebase.auth.FirebaseAuth;
import org.json.JSONObject;

/** Observes existing consented recording; never opens the microphone or sends data. */
public final class AudioDiagnostics {
    private static volatile Context app;
    private static volatile boolean sleepMicrophone;
    private static volatile String captureStage = "pornire";
    private AudioDiagnostics() {}
    public static void install(Context c) { app = c.getApplicationContext(); }
    public static Context context() { return app; }
    public static String owner() {
        com.google.firebase.auth.FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? null : user.getUid();
    }
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("audio_diagnostics_v18", Context.MODE_PRIVATE); }
    public static void sleepStarted() { sleepMicrophone = true; TimedRecordingService.sleepTakingMicrophone(); }
    public static void sleepStopped() { sleepMicrophone = false; }
    public static String microphoneConflict() {
        if (sleepMicrophone) return "Modul Somn folosește deja microfonul. Oprește sesiunea de somn înainte de o înregistrare separată.";
        try { if (app != null) {
            AudioManager manager = app.getSystemService(AudioManager.class);
            if (manager != null && manager.isMicrophoneMute())
                return "Accesul general la microfon este oprit în Android. Activează Microfon din setările rapide.";
        } } catch (RuntimeException unavailable) { /* OS permission enforcement remains authoritative. */ }
        return null;
    }
    public static String preflight(Context c) {
        if (owner() == null) return "Conectează-te în contul FORJA.";
        if (!WebAccountControl.INSTANCE.enabled(c)) return "Autorizează mai jos înregistrarea și trimiterea în contul tău.";
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return "Permisiunea Microfon lipsește. Apasă Permisiuni Android.";
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) return "Notificările FORJA sunt oprite. Activează-le pentru a avea permanent acces la Oprește.";
        NotificationChannel channel = nm.getNotificationChannel("timed_recording");
        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
            return "Notificarea Înregistrare în curs este oprită. Activează canalul din Permisiuni Android.";
        String conflict = microphoneConflict();
        if (conflict != null) return conflict;
        if (!TimedRecordingService.canRecord()) return "Revino în FORJA pentru pornire. După confirmare poți bloca ecranul.";
        return null;
    }
    public static String startFailure(Context c) {
        String problem = preflight(c);
        return problem != null ? problem : "Pornirea nu mai este valabilă: contul sau intervalul s-a schimbat. Pornește o sesiune nouă.";
    }
    public static void beforeForeground() { captureStage = "activarea serviciului audio"; }
    public static void beforePrepare() { captureStage = "pregătirea fișierului audio"; }
    public static void beforeMicrophone() { captureStage = "pornirea microfonului"; }
    public static String captureFailure(Throwable e) {
        String issue = app == null ? null : preflight(app);
        return issue != null ? issue : "Eroare la " + captureStage + " (" + e.getClass().getSimpleName() + "). Înregistrarea nu a pornit.";
    }
    public static void linkError(Throwable e) {
        if (app == null || owner() == null) return;
        // Store a bounded classification, never HTTP bodies, tokens, URLs or account IDs in the displayed text.
        int code = ErrorText.status(e.getMessage());
        prefs(app).edit().putString("owner", owner()).putString("link", ErrorText.connection(code, e.getClass().getSimpleName()))
            .putLong("checked", System.currentTimeMillis()).apply();
    }
    public static void linkOkay(JSONObject response) {
        if (app == null || owner() == null) return;
        SharedPreferences grant = app.getSharedPreferences("web_account_control_v1", Context.MODE_PRIVATE);
        if (!owner().equals(grant.getString("owner", null)) || !response.optString("grant").equals(grant.getString("grant", null))) return;
        AudioWebStatus.server(response);
        prefs(app).edit().putString("owner", owner()).putString("link", "Telefonul comunică cu site-ul.")
            .putLong("checked", System.currentTimeMillis()).putLong("success", System.currentTimeMillis()).apply();
    }
    public static String linkSummary(Context c) {
        SharedPreferences p = prefs(c);
        if (owner() == null || !owner().equals(p.getString("owner", null))) return "Legătura cu site-ul nu este încă verificată.";
        long ago = Math.max(0, (System.currentTimeMillis() - p.getLong("checked", 0)) / 1000);
        return p.getString("link", "Aștept verificarea.") + (ago > 30 ? " Ultima verificare: acum " + ago + " s." : "");
    }
    public static boolean connected(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        NetworkCapabilities n = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return n != null && n.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
