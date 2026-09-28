# Regulile Firestore (FORJA 4.4)

Regulile noi închid profilul tău. Până acum, oricine avea un cont FORJA îți putea citi documentul `users/{uid}`:
numele, poziția, ce asculți și emailul. De acum îl citesc doar trei categorii de oameni:

- tu;
- prietenii tăi (cei cu o prietenie în FORJA);
- familia ta (oamenii pe care i-ai pus în familie).

Emailul nu mai stă deloc acolo. Aplicația 4.4 îl mută în `users/{uid}/settings/account`, pe care îl vezi doar tu.

## Cum le publici (o singură dată)

1. Deschide [console.firebase.google.com](https://console.firebase.google.com) → proiectul FORJA → **Firestore Database** → fila **Rules**.
2. Șterge tot textul de acolo și lipește conținutul fișierului [`firestore.rules`](firestore.rules).
3. Apasă **Publish**.

Regulile intră în vigoare în cel mult un minut.

Le poți publica oricând, înainte sau după ce instalezi 4.4: aplicația merge cu ambele seturi de reguli.
Emailul se mută din profil la prima deschidere a versiunii 4.4.

## Ce verifici după (2 minute)

- **Harta:** prietenii apar ca înainte, cu poziția și cu „♪” când ascultă muzică. Familia apare și în modul fantomă.
- **Prieten nou prin cod** (`FORJA-XXXXXX`): prietenia se face și numele lui apare imediat.
- **Energie** (fulgerul trimis unui prieten): pleacă acum. Cu regulile vechi era refuzată mereu, iar aplicația spunea
  „I-ai trimis deja energie azi”.
- **Site-ul** (`/insights`): după ce intri în cont, secțiunile se încarcă ca înainte.

Dacă ceva nu merge, poți reveni la regulile de dinainte. În consola Firebase, fila **Rules**, alegi versiunea
anterioară din istoricul din stânga și apeși **Publish**.

## Ce s-a schimbat, pe scurt

| Document | Înainte | Acum |
|---|---|---|
| `users/{uid}` (profil) | oricine conectat | tu, prietenii, familia; nimeni nu poate lista toți utilizatorii |
| `users/{uid}/…` (jurnale, antrenamente, rație, cont) | doar tu | doar tu (neschimbat) |
| `energy/{id}` | doar destinatarul; trimiterea eșua | destinatarul și expeditorul; trimiterea merge |
| `places/{id}` la actualizare | proprietarul, orice câmpuri | proprietarul, aceleași verificări ca la creare; locul nu poate fi dat altcuiva |
| `inviteCodes`, `friendships`, `familyLoc` | — | neschimbate |

Costul este mic. Când un prieten îți citește profilul, regula mai face o citire: verifică dacă prietenia există.
Pentru familie, regula nu face nicio citire în plus.

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

- 50 de verificări trecute cu regulile noi, dintre care 3 pentru citirile site-ului;
- 47 de verificări trecute cu regulile vechi.

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
