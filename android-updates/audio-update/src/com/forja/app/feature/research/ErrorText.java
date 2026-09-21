package com.forja.app.feature.research;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ErrorText {
    private ErrorText() {}
    public static void requireHttpSuccess(int code) {
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + connection(code,""));
    }
    public static int status(String message) {
        if (message == null) return 0;
        Matcher m = Pattern.compile("(?i)(?:HTTP|server|status|code)[ :]*(4\\d\\d|5\\d\\d)\\b").matcher(message);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
    public static String connection(int code, String type) {
        switch (code) {
            case 401: return "Site-ul nu acceptă autentificarea. Conectează-te din nou în FORJA.";
            case 403: return "Site-ul a refuzat accesul. Verifică asocierea telefonului cu acest cont.";
            case 404: return "Funcția audio nu este disponibilă la server. Verifică actualizarea site-ului.";
            case 409: return "Starea telefonului s-a schimbat. Reîmprospătează panoul web.";
            case 423: return "Primirea datelor este oprită din panoul web.";
            case 429: return "Serverul cere o pauză. Conexiunea va fi reîncercată.";
        }
        if (code >= 500) return "Serverul nu răspunde corect. Conexiunea va fi reîncercată.";
        if ("UnknownHostException".equals(type)) return "Adresa site-ului nu poate fi găsită. Verifică internetul și DNS-ul telefonului.";
        if (type != null && (type.contains("Timeout") || type.contains("ConnectException"))) return "Conectarea la site a expirat. Verifică internetul telefonului.";
        if (type != null && type.contains("SSL")) return "Conexiunea securizată cu site-ul a eșuat. Verifică data și ora telefonului.";
        return "Telefonul nu a putut comunica cu site-ul. Verifică internetul și contul FORJA.";
    }
}
