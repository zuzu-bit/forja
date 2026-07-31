# FORJA — Android · „REAL & VIU"

Aplicația nativă FORJA (Jetpack Compose, dark-only, în română), construită după prototipul
Claude Design din `design_handoff_forja`. Fitness & lifestyle: antrenamente cu video,
nutriție cu cod de bare + baza de date OpenFoodFacts, somn, hartă socială live cu prieteni
reali (Firebase) și Focus (blocare de aplicații, onestă, fără AccessibilityService).

## Cum obții aplicația (APK)

La fiecare push pe `main`, GitHub Actions construiește APK-ul și îl publică la
**Releases → „FORJA — ultimul APK"**:

1. Deschide pagina de Releases a acestui repo pe telefon.
2. Descarcă `FORJA.apk`.
3. Deschide fișierul și acceptă instalarea din surse necunoscute.

## Configurare Firebase (o singură dată, ~2 minute)

Proiectul Firebase există deja: **forja-65093** (fișierul `app/google-services.json` e inclus).
În [consola Firebase](https://console.firebase.google.com/project/forja-65093):

1. **Authentication → Get started → Sign-in method → Email/Password → Enable.**
   Fără asta, crearea de conturi afișează o eroare clară în aplicație.
2. **Firestore Database → Create database** (alege locația `eur3` sau `europe-west`).
   Pornește în *production mode*, apoi:
3. **Firestore → Rules** → lipește conținutul din [`firestore.rules`](firestore.rules) → Publish.

Notă despre release: pagina de release afișează starea acestor servicii la momentul build-ului.

## Arhitectură

- **UI**: Jetpack Compose, design tokens exacți din handoff (culori, Archivo Expanded /
  Hanken Grotesk / JetBrains Mono ca fonturi variabile, cele 3 arcuri spring: snappy/natural/gentle).
- **Local (pe telefon)**: Room — antrenamente, serii, mese, somn, activități, reguli Focus.
  DataStore — preferințe. Mesele și somnul NU pleacă de pe telefon.
- **Cloud (între prieteni)**: Firebase Auth (email+parolă) + Firestore — profil, cod de
  invitație, prietenii (reciproc, prin cod), poziția live (doar când nu ești fantomă), energie (kudos).
- **Hartă**: osmdroid + tiles CARTO dark cu tentă caldă (paleta din prototip), markeri cu
  interpolare (fără teleport), mod fantomă, înregistrare GO cu serviciu foreground.
- **Nutriție**: ML Kit (cod de bare, on-device) → OpenFoodFacts (valori verificate).
  Principiu din handoff: AI-ul identifică, baza de date dă valorile. Analiza pozelor cu AI
  e pregătită ca pas următor.
- **Video**: Media3/ExoPlayer — loop-uri mute, poster fallback, pauză off-screen.
  Asset-urile foto/video sunt preview-uri Adobe Stock (watermark), conform manifestului
  din handoff — de licențiat sau înlocuit cu generări AI înainte de lansarea publică.

## Structură

```
app/src/main/java/com/forja/app/
  core/designsystem/   tokens + componente (butoane, carduri, video, tab bar)
  core/data/           Room, DataStore, repos Firebase (auth, prieteni, prezență)
  core/network/        OpenFoodFacts (OkHttp)
  core/location|sleep|focus/  servicii foreground (GO, somn, blocare)
  feature/             splash, onboarding, auth, dashboard, workout, nutrition,
                       sleep, map, focus, profile
```

## Dezvoltare locală

Android Studio (Ladybug+): deschide folderul, sync, Run. Sau în terminal:
`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
