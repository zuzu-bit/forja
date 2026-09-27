# FORJA v23 — găsirea propriului telefon

Stadiu la reverificarea din 21.09.2026: APK-ul v23 este acum semnat cu aceeași
cheie ca v22, recuperată din proiectul FORJA. Semnătura, integritatea și manifestul
au fost reverificate. Worker v11 a trecut din nou cele 126 teste și compilarea.
Testarea pe un telefon real rămâne necesară; starea publicării este documentată
separat în raportul de livrare.

## Folosire după instalarea actualizării

1. Pe telefon: Hartă → Telefon pierdut → Activează găsirea din site.
   Proprietarul confirmă acest scop separat. Permisiunile Android deja acordate
   sunt refolosite; sunt necesare locația precisă și notificările.
2. Găsirea rămâne pregătită, cu o notificare permanentă. Serviciul pentru găsire
   nu solicită poziții în această stare; verifică aproximativ la 30 secunde
   dacă proprietarul a trimis o cerere din site.
3. În site, cu ACELAȘI cont FORJA: Hartă → Telefon pierdut → Localizează telefonul.
   Alegi 5, 15 sau 30 minute. Notificarea telefonului arată când începe căutarea.
4. Site-ul separă cererea în așteptare, confirmarea telefonului și poziția GPS
   efectiv primită. Coordonatele vin cu ora măsurării, precizia estimată în metri
   și bateria. Harta arată un cerc de precizie; nu promite o poziție perfect exactă.
5. Oprirea este disponibilă în site și în notificare. Dezactivarea găsirii
   retrage activarea telefonului; va fi necesară o nouă activare locală.

Partajarea de cuplu și găsirea propriului telefon au scopuri și comenzi separate.
Partenerul nu poate porni această căutare și nu primește pozițiile ei. Pozițiile
pentru găsire sunt disponibile exclusiv în contul proprietarului, inclusiv dacă
telefonul nu are un partener asociat. Funcția folosește autentificarea FORJA
existentă și nu necesită configurarea unui canal FCM sau verificarea prin SMS.

## Condiții reale de funcționare

Activarea trebuie făcută înaintea pierderii telefonului. Serviciul vizibil,
pornit din aplicația aflată în prim-plan, poate primi cererea cu ecranul blocat
sau după ieșirea din interfață. Android poate întârzia execuția și accesul la
rețea; intervalul de 30 secunde nu este o garanție de livrare. Modul pregătit
consumă energie și trafic pentru verificările periodice, chiar fără GPS activ.

Telefonul trebuie să fie pornit și conectat. Site-ul nu poate acorda permisiuni,
porni telefonul sau activa comutatorul GPS/Locație dacă este dezactivat în Android.
O aplicație oprită forțat trebuie redeschisă. Pentru reluare după repornire sunt
necesare activarea locală păstrată, notificările și locația „Tot timpul” pe
Android 10+. Restricțiile producătorului/bateriei pot opri reluarea. Dacă
permisiunile sunt retrase, localizarea nu continuă.

Cererea trebuie primită/confirmată în cel mult cinci minute; altfel expiră.
Termenul de 5/15/30 minute se calculează de la trimiterea din site. Chiar dacă
rețeaua cade, telefonul oprește căutarea la termenul local; site-ul refuză imediat
pozițiile aferente unei cereri oprite sau expirate. Oprirea din site ajunge fizic
la telefon la următorul contact reușit, nu instantaneu dacă telefonul este offline.

Doar ultima poziție este păstrată, cel mult 24 ore de la măsurare. După două
minute este etichetată „ultima poziție cunoscută”, cu ora, fără a fi prezentată
ca localizare actuală. Nu se construiește un traseu. Oprirea sau dezactivarea
șterge și poziția salvată. Activările inactive expiră după 30 de zile; contactul
normal al unui telefon încă activ prelungește acest termen.

## Implementare și acces

Workerul verifică tokenul Firebase RS256, emitentul, audiența și expirarea. Pentru
`/v2/recovery/*`, UID-ul intern este suprascris din token, iar cererea este rutată
la Durable Object-ul contului (`account:<UID>`). Nicio rută de recuperare nu
folosește graful social sau ID-ul partenerului.

Activarea locală creează un UUID de dispozitiv și un secret aleatoriu de 256 biți.
Serverul păstrează numai SHA-256 al secretului. Citirea comenzilor, confirmarea și
raportarea pozițiilor cer atât contul corect, cât și secretul activării. Listele
web nu expun secretul sau hash-ul. O activare cu alt secret nu poate prelua ID-ul
unui telefon deja activat. Secretul rămâne în spațiul privat al aplicației.

Rutele, relative la `/v2/recovery`:
- GET devices — telefoanele și ultima poziție din propriul cont.
- POST devices/:id/grant — {name,secret,consent:true}, activare locală.
- DELETE devices/:id/grant — dezactivare din cont, ștergere date.
- POST devices/:id/revoke — {secret}, revocare de pe telefon.
- POST devices/:id/command — {id,minutes}, comandă web; răspunde 202 pentru cerere.
- DELETE devices/:id/command?id=:command — oprire exact a cererii afișate.
- POST devices/:id/stop — {secret,command}, oprire de pe telefon.
- POST devices/:id/poll — {secret,status}, preluarea comenzii curente.
- POST devices/:id/status — {secret,command,status}, confirmare telefon.
- POST devices/:id/position — {secret,command,lat,lon,accuracy,at,battery}.

Limite: 5 activări/cont; 100 cereri în 24 h și minimum 30 secunde între cereri noi.
Un ID de cerere închis nu poate fi reluat în fereastra de idempotență de 24 h.
O reluare veche a comenzii de oprire nu poate opri o cerere mai nouă.
Raportările necesită o cerere confirmată și activă, coordonate/precizie valide
și o măsurare recentă făcută după cerere. Datele sunt transmise prin HTTPS și
izolate prin autentificare; nu sunt criptate end-to-end.

Native: LostPhoneRecovery păstrează activarea legată de UID; LostPhoneService
este un serviciu location privat, cu notificare, START_STICKY și oprire locală.
SocialBootReceiver poate relua doar activarea locală existentă, sub restricțiile
Android. Localizarea folosește GPS/network și filtrează fixurile vechi sau
neordonate. Revocările întrerup imediat colectarea locală și folosesc WorkManager
pentru confirmarea serverului la reconectare, cu UID/secret/comandă fixe.
Nicio permisiune nouă față de v22 și nicio modificare a regulilor audio.

## Verificări

126 teste server, inclusiv acces între conturi, activare, secret, stările cererii,
oprire, revocare, întârzieri, expirare 24 h/30 zile și validarea pozițiilor.
39 teste Android existente, compilare Kotlin, manifest, 23 DEX fără clase duplicate
și verificarea apelurilor reale FORJA/Firebase/osmdroid. 9 verificări DOM pentru
recuperare, 12 pentru cuplu/social, 9 verificări JWT cu chei RS256 de test și
7 teste publisher. Build-ul Workerului a trecut dry-run. Acestea nu înlocuiesc
testul pe telefon cu ecran blocat, GPS oprit, lipsă rețea, reboot și force-stop.

Referințe oficiale verificate la 19.09.2026:
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/sensors-and-location/location/permissions
