# Analiza conținutului — implementare și alegerea modelului

Stare la 27 septembrie 2026: adaptorul și testele locale sunt implementate. Alegerea unui candidat nou necesită rezultatul evaluării Cloudflare; apariția unui model în documentație nu demonstrează că este activ în cont.

## Flux și dovezi

`server/organizer-job-ai.mjs` cere copiile prin serviciul autentificat al contului. `server/organizer-analysis.mjs` verifică dimensiunea, semnătura formatului și SHA-256 al fiecărei copii înaintea oricărei inferențe. Numele fișierului nu intră în prompt ca dovadă de conținut.

- Text UTF-8: citit direct din bytes pe server, maximum 32.000 de caractere. Depășirea limitei produce acoperire parțială.
- Fotografii PNG/JPEG/WebP: modelul vizual primește bytes reali până la 4 MiB. Pentru originale mai mari poate primi miniatura JPEG deja încărcată și legată de original. Rezultatul marchează rezoluția redusă și acoperirea parțială.
- PDF: telefonul verifică hash-ul copiei locale, extrage text și aplică OCR paginilor cu puțin text. Trimite numărul real al paginilor, paginile procesate, intervalele textului fiecărei pagini și metoda. Maximum 40 de pagini distribuite în document, 12 pagini OCR și 32.000 de caractere. Serverul atribuie explicit extracția telefonului; hash-ul leagă versiunile, fără să certifice independent corectitudinea OCR.
- Office: telefonul citește părțile XML din DOCX/PPTX/XLSX și ODF, cu limite pentru arhive decomprimate și interdicția declarațiilor externe. Imaginile, formulele, aspectul paginii și ordinea unor celule nu sunt integral analizate; rezultatul rămâne parțial.
- Un format fără conținut citibil rămâne „De verificat”, fără o inferență bazată doar pe nume sau metadate.

PDF-urile nu sunt declarate integral înțelese doar pentru că a fost extras text din toate paginile: diagramele și aspectul vizual pot rămâne neanalizate. Observațiile vizuale sunt interpretări AI neconfirmate, nu fapte autentificate. Citatele text sunt acceptate numai dacă există exact în sursa furnizată, iar pagina este stabilită de adaptor.

## Acord, izolare și rezultate

Apelantul verifică proprietarul, telefonul, jobul, identitatea originalului, versiunea, copia și acordul de analiză. Înainte și după fiecare apel AI, adaptorul cere același token de acord și revizie. Retragerea acordului, anularea sau oprirea urmată de repornire invalidează un rezultat vechi.

Fișierele, OCR-ul și preferințele sunt date neîncrezute. Promptul nu oferă unelte și nu execută instrucțiuni găsite în conținut. Schema rezultatului respinge proprietăți necunoscute, surse inventate, citate absente, căi absolute și traversarea directoarelor. Schema strictă și validarea reduc riscul; nu garantează că o interpretare semantică este corectă.

Rezultatul este o propunere cu `applied:false`. Adaptorul nu mută și nu șterge originale. Ștergerea duplicatelor necesită separat verificarea deterministă a bytes/hash-ului, păstrarea unui original și confirmarea utilizatorului. Modelul nu declară un fișier inutil pe baza titlului, vechimii ori aspectului și nu deduce trăsături sensibile despre persoane.

Copiile și extracțiile folosesc politica temporară existentă a contului; noua stare persistentă a jobului nu prelungește automat retenția OCR sau a dovezilor. Text propus la activare: **„Analiză online a copiilor selectate”**. Etichete scurte: **„Analizat parțial”**, **„De verificat”**, **„Propunere”**.

## Modele și configurare

`ORGANIZER_ANALYSIS_MODEL` acceptă exclusiv:

| Model | Contractul folosit | Situație |
| --- | --- | --- |
| `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | Text, JSON Schema Cloudflare; fotografiile trec mai întâi prin Llama 3.2 Vision | Valoare implicită existentă; descrierea vizuală intermediară este marcată parțială |
| `@cf/moonshotai/kimi-k2.6` | Mesaje text/imagine, JSON Schema, `reasoning_effort:none`, `max_completion_tokens` | Candidat; documentația cere plan plătit, fără upgrade automat |
| `@cf/meta/llama-4-scout-17b-16e-instruct` | Mesaje text/imagine, `guided_json` | Candidat; activare numai după evaluare în cont |

Nu există schimbare automată de furnizor, cont sau abonament. Un model indisponibil returnează o eroare reală; copia rămâne disponibilă. Nu susținem „cel mai bun model” universal.

Surse oficiale pentru contracte:

- https://developers.cloudflare.com/workers-ai/models/kimi-k2.6/
- https://developers.cloudflare.com/workers-ai/models/llama-4-scout-17b-16e-instruct/
- https://developers.cloudflare.com/workers-ai/features/json-mode/
- https://developers.cloudflare.com/workers-ai/configuration/bindings/

Pagina Kimi K2.5 din planul inițial nu mai este disponibilă la verificare; adaptorul folosește identificatorul documentat K2.6.

## Evaluare reproductibilă

`node server/organizer-model-eval.mjs` rulează numai fixture-uri sintetice, maximum 12 apeluri. Folosește secretele Cloudflare existente din mediu, fără să le scrie în raport. `ORGANIZER_EVAL_DIAGNOSTIC=1` limitează diagnosticul la un singur caz text. Erorile furnizorului includ numai status HTTP și coduri numerice, fără mesaje brute, corpuri sau secrete.

Fixture-urile inițiale verifică document românesc cu nume înșelător, factură fotografiată și conținut mixt cu instrucțiuni malițioase. Raportul păstrează dosarul, citatul/observația, acoperirea, numărul apelurilor și durata. Ele constituie un test de integrare și un gate limitat, nu un benchmark de acuratețe generală. Documente scanate dificil, tabele complexe și fotografii apropiate semantic necesită evaluare separată înaintea unor afirmații de performanță extinse.

Test local: `node --test server/organizer-analysis.test.mjs`. Acoperă bytes/hash, metadate înșelătoare, limite, proveniența paginilor, citate inventate, protecție la traversare, retragerea acordului, reprezentarea imaginilor, contractele modelelor și erorile furnizorului.
