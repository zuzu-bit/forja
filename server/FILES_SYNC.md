# FORJA — Poze și documente în cont, 24 de ore

Actualizare pregătită pentru APK 3.7-online.15 (versionCode 45).
Serverul trebuie publicat în contul Cloudflare care găzduiește deja
`forja-insights.forja-22e7ea2d.workers.dev`. Pachetul nu a fost publicat din
mediul de dezvoltare: nu există autentificare Cloudflare în această sesiune.

## Publicare

Folosește Node.js 22 sau mai nou, din directorul acestui pachet:

```sh
npm ci
npm test
npx wrangler login
npx wrangler deploy --config wrangler.insights.toml
```

Verifică faptul că autentificarea este în contul site-ului existent. Configurația
folosește același Worker, bucket R2 `forja-insights-data`, Durable Object
`InsightsAccount` și Firebase `forja-65093`. Nu este necesară o migrare nouă;
metadatele copiilor au prefixe noi. Nu publica în alt cont și nu crea un bucket
public. Endpoint-ul `/health` trebuie să afișeze `version: 5, files_sync: 1`.
Acest pachet păstrează codul serverului `ff256ee` pentru audio de durată mai mare,
controlul web și campaniile existente. Nu înlocuiește celălalt Worker FORJA pentru
analiza meselor și somnului.

## Activare și folosire

1. Instalează APK-ul v15 peste versiunea anterioară; păstrează datele aplicației.
2. Conectează-te cu același cont FORJA pe telefon și pe site.
3. În Curățenia de astăzi, apasă „Poze și documente pe site · 24 h”.
4. Alege pozele autorizate și/sau un dosar, selecția 50/100/200/toate și conexiunea.
5. Apasă „Activează trimiterea automată”. Fișierele noi se trimit fără selecție
   repetată; poți opri trimiterea din aplicație sau din pagina web.
6. În site, deschide „Poze și documente”: previzualizare, descărcare, căutare,
   dosare online și ștergerea copiilor. PDF-urile au navigare pe pagini; DOCX se
   previzualizează ca text. Alte formate se pot descărca.

Prima rundă este solicitată imediat. Android decide când rulează transferul în
funcție de rețea, baterie și restricțiile telefonului; verificarea periodică are
interval minim de 15 minute. Nu este un transfer garantat în timp real și
oprirea forțată a aplicației din setările Android oprește și lucrările până la
redeschidere. Pe conexiuni cu cost de date, dezactivează opțiunea Wi-Fi dacă dorești
transferul prin date mobile.

## Păstrarea copiilor și limite

- 24 h de la prima încărcare acceptată, separat pentru fiecare fișier. Retrimiterea
  unei cereri sau mutarea într-un dosar online nu prelungește termenul.
- După termen, API-ul refuză accesul și o alarmă elimină fișierul și miniatura din
  R2. O eroare temporară de stocare declanșează reîncercări de ștergere; accesul
  rămâne refuzat. Nu există promisiunea că o copie deja descărcată pe alt dispozitiv
  poate fi retrasă de la acel dispozitiv.
- Fișierele neschimbate nu sunt reîncărcate după expirare. Registrul local păstrează
  amprentele până la ștergerea datelor aplicației/dezinstalare; resetarea acestora
  poate duce la o nouă încărcare după o nouă activare.
- Originalele rămân în telefon. Dosarele online nu mută fișierele din galerie sau
  din aplicația Fișiere. Organizarea fizică existentă rămâne în fluxul de curățenie.
- Maximum 25 MiB/fișier, 500 copii / 512 MiB temporar per cont, 5 dispozitive,
  1.500 încărcări acceptate pe zi. Fișierele care depășesc limita sunt omise.
- Maximum 25 încărcări într-o rundă, cu un buget de opt minute verificat între
  fișiere. Transferul în curs se poate prelungi; Android poate opri lucrarea. Restul sunt verificate
  la rundele următoare. Inventarul are limita existentă de 15.000 intrări/sursă și
  opt niveluri de subdosare; registrul păstrează cel mult 15.000 URI-uri/cont.
- Autentificare Firebase verificată de Worker și stocare separată pentru fiecare
  utilizator. Nicio adresă publică R2 și nicio cheie de server în APK.
- HTML/SVG nu sunt executate în previzualizare. Textul și DOCX sunt afișate ca text;
  extracția DOCX are limite de decomprimare. PDF.js 5.6.205, distribuția legacy,
  este livrat din același site; documentele nu sunt trimise unui vizualizator extern.

## Verificări

46 teste Node trecute (inclusiv traseul audio anterior), 15 teste JVM trecute,
verificare DOM pentru autentificare, previzualizare Word/text, dosare, pauză,
filtre, ștergere, expirare și deconectare în timpul transferului; un PDF de test
randat în canvas. Worker compilat prin `wrangler deploy --dry-run`.
APK: semnătură păstrată, 22 DEX, referințe ABI și integritatea DEX verificate.
Nu există verificare pe telefon/emulator Android; mediul a blocat pornirea
browserului pentru verificare vizuală. Nu s-a făcut test cu un cont real sau
publicare Cloudflare. Testează un fișier propriu și o fotografie după publicare.
