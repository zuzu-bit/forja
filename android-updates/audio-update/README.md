# FORJA audio source

Current v24 packaging uses [../visual-update/README.md](../visual-update/README.md).
`src/` contains the current web-pairing UI. The following v19 instructions and
the historical build script are retained as provenance, not a current release
procedure. v24 preserves the delivered v23 recording implementation.

# Historical FORJA 3.7-online.19 — Audio din web cu ecranul blocat

## Instalare și utilizare

1. Publică serverul din `FORJA_Publicare_Windows.zip`: extrage arhiva complet,
   deschide `PUBLICA_FORJA.cmd`, autorizează Wrangler în contul Cloudflare care
   deține site-ul și așteaptă mesajul **GATA**. Serverul trebuie să raporteze
   `/health`: `version: 7`, `background_audio: 1`.
2. Instalează `FORJA_Research.apk` peste versiunea existentă, fără dezinstalare.
3. În FORJA: **Profil → Permisiuni și sincronizare → Control din contul web**.
   Ecranul se numește **Audio și conexiune**.
4. Dacă nu ai asociat contul, activează-l în secțiunea **Contul tău online**.
5. În **Audio din web**, bifează acordul explicit pentru controlul din propriul
   cont și încărcare. Apasă **Activează Audio din web** și permite microfonul și
   notificările. Așteaptă confirmarea „Pregătit pentru Start din web”.
6. Poți bloca ecranul. În același cont pe site, așteaptă „Audio din web activ”,
   alege durata și apasă **Pornește acum**. Comenzile se verifică aproximativ la
   10 secunde, când conexiunea permite; panoul confirmă separat pornirea reală.
7. Înregistrarea are notificare **FORJA înregistrează audio**. **Oprește și
   trimite** finalizează fișierul. **Dezactivează audio web** închide întregul mod.
8. La oprire sau la expirarea intervalului, fișierul AAC/M4A complet se trimite
   prin coada existentă. În site: **Date primite → Înregistrări audio**.
   Primirea datelor trebuie să fie activă. Copiile online expiră după 24 h.

Modul care așteaptă comenzi nu creează un recorder și nu salvează sunet.
Notificarea sa rămâne vizibilă, cu buton de dezactivare. Acordul pentru Somn
nu este reutilizat implicit pentru control web sau încărcare.

Oprirea unei înregistrări păstrează modul web activ pentru următoarea comandă.
Dezactivarea modului invalidează intervalele în așteptare. O nouă activare
creează un identificator nou; comenzile unei activări vechi sunt respinse atât
pe telefon, cât și pe server. Pentru programarea unui interval, modul trebuie
să fie activ și telefonul conectat la momentul programării.

După repornire, force-stop sau închiderea serviciului de către Android, modul
necesită reactivare pe telefon. Nu se pornește automat din boot și nu folosește
excepții ascunse, suprapuneri sau servicii de accesibilitate. Economisirea
bateriei/Doze, lipsa internetului și restricțiile producătorului pot întârzia
comenzile; nu este o alarmă exactă. Modul activ consumă baterie. Butonul pentru
setările bateriei deschide setările FORJA, unde utilizatorul poate verifica
restricțiile. Nu modifică automat setările sistemului.

Modul Somn păstrează recorderul și fluxul său existente. Dacă Somn folosește
microfonul, o înregistrare web separată este refuzată. Dacă pornești Somn în
 timpul unei înregistrări web, sesiunea web este finalizată. Audio din Somn nu
este transferat în alt flux prin această modificare.

## Verificări și limite

- 68 de teste ale serverului: inclusiv comenzi în fundal, autentificare,
  izolare între conturi, revocare, sesiuni expirate, reluări și fișiere audio.
- 7 teste ale programului de publicare.
- 24 verificări JVM ale diagnosticului și 26 ale regulilor pentru autorizare,
  identificator de activare și durată.
- Compilare Java/DEX, semnătură APK identică versiunii existente, unicitatea
  claselor și verificarea referințelor ABI către codul păstrat.
- Compilare Worker prin `wrangler deploy --dry-run`; aceasta NU publică site-ul.

Nu există un telefon sau emulator Android conectat în mediul de construire.
Captura reală cu ecranul blocat, efectul Doze pe dispozitiv și încărcarea în
contul live nu au fost verificate aici. Publicarea live nu a fost efectuată.

### Probă necesară pe telefon

1. Activează modul, blochează ecranul, pornește o sesiune de 5 minute din web.
2. Confirmă schimbarea notificării, apoi oprește după minimum 10 secunde.
3. Ascultă fișierul din site; verifică ora și durata, apoi pornește încă o sesiune
   fără să redeschizi aplicația.
4. Dezactivează modul din notificare: Start trebuie refuzat. Reactivează-l:
   un interval programat înainte de dezactivare nu trebuie executat.
5. Repetă după o perioadă de ecran blocat pentru a evalua restricțiile de baterie.

## Construire reproductibilă

Baza este APK-ul v17 exact, SHA-256:
`2e95b91c19cbb07f2854f6ba1945975fd554bcf453c84ea89bb327ceb579b0fa`.
Nu folosi drept bază APK-ul v18/v19 și nu reconstrui aplicația veche de pe main.
Modelele locale, curățenia și programarea fișierelor din v17 sunt păstrate.

Necesită Java 17 cu modulul javac, Python, platforma Android 35, build-tools 35,
R8 8.7.18, smali/baksmali 2.5.2 și cheia de semnare existentă, furnizată separat.
Instrumentele sunt distribuite în depozitele oficiale Google/Maven.

Structura `tools`: `sdk/android-35/android.jar`, `sdk/android-15/`,
`smali/*.jar`, `r8-8.7.18.jar`, `debug.keystore`.

```sh
python build.py --base /path/FORJA-v17.apk --tools /path/tools --output /path/FORJA_Research.apk
python verify.py /path/FORJA_Research.apk --work-dir /tmp/path-printed-by-build --android-jar /path/tools/sdk/android-35/android.jar
```

Declarațiile ABI generate sunt numai pentru compilare și nu sunt incluse în
APK. Serviciul nou păstrează componenta Android și ABI-ul `Companion.getActiveId`
folosit de coada existentă. Manifestul schimbă numai versiunea 49/3.7-online.19.
Se modifică DEX 4/11/12/16 și se adaugă DEX 23; celelalte 18 DEX și resursele
originale rămân identice. Coada WorkManager și confirmarea SHA-256/bytes la
încărcare sunt păstrate.

Pornirea inițială a serviciului de microfon se face explicit cât timp activitatea
este vizibilă. Ulterior, comenzile folosesc aceeași instanță deja promovată,
cu o notificare permanentă. `foreground` continuă să descrie vizibilitatea
reală; `audio_ready` și `audio_session` descriu separat modul activ.
Starea de pregătire nu se restaurează din preferințe după moartea procesului.

Referințe Android:
- https://developer.android.com/develop/background-work/services/fgs/service-types#microphone
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
