# FORJA v26 — organizare continuă și explorare personală

Implementare din 27 septembrie 2026, construită peste APK-ul v25 livrat și serviciul Insights existent. Acest director descrie codul implementat; dovezile publicării și verificării finale sunt în `delivery.md`.

## Organizare

Telefonul și site-ul folosesc același job persistent, protocol v4. Poți alege galeria, un album sau un dosar de documente, apoi toate elementele rămase, următoarele 50/100 sau un număr propriu. Continuă reia progresul; următorul lot exclude versiunile deja organizate. Inventarul galeriei este paginat, fără plafonul vechi de 15.000 de fișiere.

Identitatea originalului, versiunea conținutului, mutările și confirmările sunt persistate separat de copiile temporare online. Fișiere distincte cu același conținut rămân originale distincte. Telefonul execută mutările în sursele autorizate; site-ul trimite comenzi idempotente și afișează rezultatele reale. Accesul la sursă și modificările galeriei respectă dialogurile Android.

Mutările persistă intenția înainte de scriere și verifică destinația înainte de eliminarea originalului. Pauza păstrează operația reluabilă; anularea/revocarea permit numai reconcilierea faptelor deja produse. Rezultatele ambigue păstrează originalele pentru verificare. Nu există ștergere automată bazată pe presupunerea AI că un document ar fi inutil.

Copiile și dovezile analizate au retenție temporară de 24 de ore; progresul durabil rămâne. În modul online, fotografiile/documentele alese sunt încărcate efectiv, verificate prin hash și analizate. Modul manual păstrează aprobarea explicită a destinației pe site. Expirarea unei copii nu autorizează o mutare nouă.

Contractul și limitele sunt documentate în [organizer-protocol.md](organizer-protocol.md).

## Analiză foto și documente

Analiza folosește conținut verificat, extragere de text, OCR și imagini reale. Acoperirea incompletă este etichetată. HEIF/AVIF folosesc o miniatură JPEG generată din original și legată de hash-ul acestuia. Rezultatele prezintă motive și dovezi în detalii, fără a încărca ecranul principal.

Llama 4 Scout este modelul configurat pe infrastructura Cloudflare existentă după o probă live de 3/3 cazuri sintetice trecute. Proba verifică o factură cu nume înșelător, o factură imagine și rezistența la instrucțiuni din document. Nu reprezintă o evaluare universală a calității AI. Kimi nu a fost activat, fiind indisponibil în configurația testată.

Duplicatele exacte au verificare deterministă și un exemplar păstrat. Sugestiile semantice de ștergere sunt doar propuneri de verificat, cu dovezi; nu selectează și nu șterg automat fișiere. Detaliile evaluării sunt în [organizer-models.md](organizer-models.md).

## Harta personală

Istoricul personal este separat de partajarea socială. Traseele sunt albastre, zonele explorate verzi, locurile vizitate aurii. O vizită necesită strict peste cinci ore susținute de observații valide; cinci ore fără semnal nu se transformă într-o vizită. Precizia slabă, golurile și salturile GPS sunt filtrate.

Ambele interfețe folosesc aceeași hartă MapLibre GL JS 5.10.0, date geografice și clădiri vectoriale reale, cu selector 2D/3D. Există revenire la harta 2D pentru dispozitive fără randare compatibilă. Locurile au nume editabil, rating, recomandare explicită către un prieten și deschidere pentru navigare. Înregistrările offline au coadă persistentă, legată de cont și sesiune.

Fiecare prieten poate primi separat locația curentă, excepția în modul fantomă și istoricul. Eticheta „familie” nu acordă acces automat. Oprirea tuturor partajărilor revocă excepțiile; blocarea și eliminarea prietenului revocă accesul. Acordurile sunt verificate pe server la fiecare citire și protejate împotriva salvărilor întârziate.

Implementarea și limitele sunt descrise în [maps.md](maps.md).

## Compatibilitate și verificare

APK-ul v26 păstrează pachetul `com.forja.app.research`, semnătura existentă și funcțiile din v25, inclusiv introducerea vizuală și somnul. Versiunea este `3.7-online.26`, cod 56. Builder-ul păstrează resursele și bibliotecile native originale și verifică compatibilitatea bytecode-ului nou cu aplicația livrată. Nu adaugă permisiuni Android; serviciul nou de locație nu este exportat.

Testele acoperă persistența SQLite reală, reluarea unei galerii de 15.037 de fișiere, lease-uri, operații întrerupte, comenzi concurente, copii expirate, dovezi AI, pragul vizitelor, paginarea istoricului și revocarea partajării. Verificarea de publicare compară hash-urile paginii, scripturilor și hărții cu sursele și verifică refuzul accesului neautentificat la datele private.

Testele automate și emulatorul nu înlocuiesc o verificare completă pe telefonul utilizatorului, cu propriul cont, furnizor de documente, GPS și restricții de baterie. Aceste limite trebuie citite împreună cu raportul final de livrare.
