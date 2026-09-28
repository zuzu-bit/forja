# Regulile Firestore (FORJA 4.4)

Regulile noi închid profilul tău. Până acum, oricine avea un cont FORJA îți putea citi documentul `users/{uid}`:
numele, poziția, ce asculți și emailul. De acum îl citesc doar trei categorii de oameni:

- tu;
- prietenii tăi (cei cu o prietenie în FORJA);
- familia ta (oamenii pe care i-ai pus în familie).

Emailul nu mai stă deloc acolo. Aplicația 4.4 îl mută în `users/{uid}/settings/account`, pe care îl vezi doar tu.

Prietenia deschide profilul, așa că nu se mai poate face doar știind contul cuiva. Cine o creează aduce dovada că
celălalt vrea:

- **prin cod:** codul lui de invitație, cel din profilul lui de acum (el ți l-a dat);
- **din agendă:** cererea telefonului lui, lăsată când serverul v-a găsit reciproc în agende (valabilă 30 de zile).

Codul tău de invitație se schimbă o singură dată, la prima deschidere a versiunii 4.4. Codul vechi se putea calcula
din contul tău; cel nou e aleatoriu. Cine are codul vechi și nu ți-e încă prieten îți cere codul nou.

## Cum le publici (o singură dată)

1. Deschide [console.firebase.google.com](https://console.firebase.google.com) → proiectul FORJA → **Firestore Database** → fila **Rules**.
2. Șterge tot textul de acolo și lipește conținutul fișierului [`firestore.rules`](firestore.rules).
3. Apasă **Publish**.

Regulile intră în vigoare în cel mult un minut.

**Publică-le după ce tu și prietenii tăi aveți 4.4.** Aplicația 4.4 merge cu ambele seturi de reguli. Versiunea 4.3
citește și trimite ca înainte, dar cu regulile noi nu mai poate adăuga prieteni noi (nu aduce dovada).
Emailul se mută din profil și codul se schimbă la prima deschidere a versiunii 4.4.

## Ce verifici după (2 minute)

- **Harta:** prietenii apar ca înainte, cu poziția și cu „♪” când ascultă muzică. Familia apare și în modul fantomă.
- **Prieten nou prin cod** (`FORJA-XXXXXX`, codul nou din Profil): prietenia se face și numele lui apare imediat.
  Cu un cod vechi, aplicația spune „Codul s-a schimbat. Cere-i codul nou.”
- **Prieten din agendă:** prietenia apare după ce și telefonul celuilalt compară agenda. Asta se întâmplă când el
  deschide FORJA sau, fără asta, în cel mult o zi.
- **Energie** (fulgerul trimis unui prieten): pleacă. Până la 4.4 era refuzată mereu, iar aplicația spunea
  „I-ai trimis deja energie azi”. Aplicația 4.4 o trimite cu ambele seturi de reguli.
- **Site-ul** (`/insights`): după ce intri în cont, secțiunile se încarcă ca înainte.

Dacă ceva nu merge, poți reveni la regulile de dinainte. În consola Firebase, fila **Rules**, alegi versiunea
anterioară din istoricul din stânga și apeși **Publish**.

## Ce s-a schimbat, pe scurt

| Document | Înainte | Acum |
|---|---|---|
| `users/{uid}` (profil) | oricine conectat | tu, prietenii, familia; nimeni nu poate lista toți utilizatorii |
| `friendships/{a_b}` la creare | oricine, cu orice cont alături de al lui | unul dintre cei doi, cu id-ul sortat și cu dovada: codul lui din profil sau cererea lui din agendă |
| `friendRequests/{de-la_către}` (nou) | — | o scrie doar expeditorul, cu ora serverului; o citesc și o șterg cei doi |
| `users/{uid}/…` (jurnale, antrenamente, rație, cont) | doar tu | doar tu (neschimbat) |
| `energy/{id}` | doar destinatarul; trimiterea eșua | destinatarul și expeditorul; trimiterea merge |
| `places/{id}` la actualizare | proprietarul, orice câmpuri | proprietarul, aceleași verificări ca la creare; locul nu poate fi dat altcuiva |
| `inviteCodes/{cod}` | oricine le putea lista pe toate (cu uid-ul fiecărui cont) și putea rescrie un cod existent | se citește doar cine știe codul; codul rămâne al celui care l-a creat; proprietarul își poate șterge codul vechi |
| `familyLoc` | — | neschimbat |

Costul este mic. Când un prieten îți citește profilul, regula mai face o citire: verifică dacă prietenia există.
Pentru familie, regula nu face nicio citire în plus. O prietenie nouă costă o citire în plus (codul sau cererea).

## Ce rămâne deschis

- **Conturile rămase pe 4.3** au încă codul vechi în profil, iar acela se poate calcula din uid. Cine le află uid-ul
  își poate face prietenie cu ele prin cod, până când își instalează 4.4 și li se schimbă codul.
- **Uid-urile se văd între prieteni.** Un prieten vede uid-urile celorlalți prieteni ai tăi (în `places.visibleTo`)
  și ale familiei tale (`familyUids`). Singur, un uid nu mai deschide nimic.
- **Serverul de agendă** (`/v2/social/contacts/match`) întoarce uid-ul unui cont FORJA oricui are numărul lui de
  telefon în agendă. Cu regulile noi, uid-ul nu mai ajunge pentru o prietenie.

## Pentru dezvoltatori: verificarea pe emulator

`firestore.rules.test.mjs` verifică fiecare drum de citire și scriere al aplicației, pe emulatorul Firestore.
Rulează același set de verificări de două ori:

- cu regulile noi;
- cu cele vechi, ca să dovedească compatibilitatea.

Ai nevoie de Java 17+ și Node 20+.

```sh
mkdir -p /tmp/forja-rules && cd /tmp/forja-rules
npm init -y >/dev/null && npm i firebase-tools@15 @firebase/rules-unit-testing@5 firebase@12
cp <repo>/firestore.rules <repo>/firestore.rules.test.mjs .
git -C <repo> show a864a44:firestore.rules > firestore.rules.old
echo '{"emulators":{"firestore":{"port":8080,"host":"127.0.0.1"}}}' > firebase.json
RULES=$PWD/firestore.rules OLD_RULES=$PWD/firestore.rules.old \
  npx firebase emulators:exec --only firestore --project demo-forja "node firestore.rules.test.mjs"
```

Rezultatul la 4.4:

- 68 de verificări trecute cu regulile noi, dintre care 3 pentru citirile site-ului;
- 65 de verificări trecute cu regulile vechi.

### Ce trebuie să respecte serverul site-ului

Site-ul citește profilurile prietenilor prin REST, cu tokenul utilizatorului (`documents:batchGet`). Două reguli:

- **Loturi de cel mult 20 de profiluri.** Fiecare profil de prieten costă o verificare `exists()`. O cerere
  multi-document are voie la 20 de verificări, iar la 21 de prieteni tot lotul primește 403. Loturile de 10
  păstrează o marjă pentru prieteniile cu id în ordinea inversă, care cer 2 verificări.
- **Doar prieteni de acum.** Un singur profil refuzat în lot (un fost prieten, un străin) face tot lotul 403.
  Uid-urile se iau din `friendships` (members array-contains eu) chiar înainte de batchGet. La 403 se reîncearcă
  document cu document și se sar cele refuzate.

Emailul contului nu se citește din `users/{uid}`. Se ia din tokenul verificat (câmpul `email`) sau din
`users/{uid}/settings/account`.
