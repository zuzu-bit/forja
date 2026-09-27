# Livrare FORJA v26

APK semnat `FORJA_v26_Continuitate.apk`, versiune `3.7-online.26` / cod 56, 304.766.657 bytes. SHA-256: `917d24c066a7811e81ef5e85525303da46c8ed0e79920bea384ae5c77490a103`.

Site public: https://forja-insights.forja-22e7ea2d.workers.dev/insights

Publicare reușită prin [GitHub Actions 36310249814](https://github.com/zuzu-bit/forja/actions/runs/36310249814), commit producție `10f75fb2522f968ce55c0892eee8cee98b3ca3b3`, versiune Cloudflare `e25da7c6-7c65-4f3a-95d5-4e5b25a68f7a`. Serviciul declară versiunea 14 și protocolul organizer 4. Hash-urile paginii, scripturilor și tuturor resurselor hărții au fost comparate cu sursele; ruta privată fără autentificare răspunde 401.

## Verificări executate

- 85 teste Android trecute, inclusiv SQLite real, galerie de 15.037 de fișiere, reluare, lease-uri și recuperarea mutărilor.
- 228 teste server și 49 verificări DOM trecute în publicarea CI.
- 7 teste ale scriptului de publicare trecute.
- Compilare și verificare ABI: 24.592 referințe către dependențe, 17.995 apeluri statice, zero clase duplicate. Semnătură păstrată, zero permisiuni noi; activele și bibliotecile native originale păstrate.
- Instalare `adb install -r` reușită peste v25 în emulator AOSP API 30. Versiunea 56 confirmată; activitatea principală afișează autentificarea existentă, verificată prin UI tree și captură. Nu au fost observate excepții fatale ale aplicației.
- Evaluare AI live pe trei exemple sintetice: 3/3 rezultate acceptate pentru Scout; nicio informație privată a utilizatorului folosită.
- APK-ul și previzualizarea interactivă au fost salvate ca versiunile noi ale livrabilelor existente. Previzualizarea folosește date demonstrative și nu dovedește autentificare sau sincronizare reală.

## Limite ale verificării

Emulatorul folosește execuție software, fără Google Play Services; primul apel de așteptare a pornirii a expirat, iar activitatea s-a afișat ulterior. Acesta nu este un benchmark de performanță pe telefon.

Nu a fost disponibilă o sesiune autentificată a utilizatorului. Fluxul complet cont → telefon → fișiere reale → upload → analiză → mutare, înregistrarea GPS pe un telefon fizic și comportamentul restricțiilor de baterie nu au fost verificate cap-coadă. Randarea vectorială 3D live nu a putut fi confirmată în browserul de verificare; suportul dispozitivului și accesul la furnizorul de hărți rămân condiții de funcționare. Implementarea revine la harta 2D când rendererul vectorial nu este disponibil.

Dovezi structurate: [Android](../../delivery/v26/android-validation.json), [publicare site](../../delivery/v26/site-deployment.json), [evaluare AI](model-evaluation-quality.json).
