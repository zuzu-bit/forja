package com.forja.app.feature.onboarding

/**
 * Toate textele românești pentru onboarding-ul de setup (8 etape × 4 faze).
 * Tonul: militar-ușor, cald, scurt. „Soldat", „misiune", „camaraz".
 */
object SetupCopy {

    // ── Etichete globale ──
    const val TOTAL_STAGES = 8
    const val STAGE_LABEL = "Pasul %1\$d din %2\$d"
    const val CONTINUE = "Continuă"
    const val GOT_IT = "Înțeleg"
    const val LATER = "Mai târziu"
    const val RETRY = "Încearcă din nou"
    const val SKIP_STAGE = "Continuă fără"
    const val VERIFYING = "Se verifică…"
    const val FINISH = "Începe aventura"
    const val MISSION_READY = "Misiunea e pregătită!"
    const val MISSION_READY_SUB = "Totul e la locul lui, soldat. Să ne apucăm!"

    // ── 1. Notificări ──
    object Notifications {
        const val TITLE = "Un mic imbold face diferența!"
        const val MASCOT = "Salut, soldat! Țin minte pentru tine ce trebuie să bei, să odihnești, să ieși. Scurt și pe loc — promit să nu te bombardez."
        const val DEMO_CAPTION = "Așa arată imboldurile FORJA. Scurte, pe timp, și nu dispare dacă le ignori."
        val TUTORIAL_BULLETS = listOf(
            "Un imbold pentru apă, o pauză, un streak aproape — exact când contează.",
            "Le poți opri oricând din Setări → Notificări FORJA."
        )
        const val TUTORIAL_PERMISSION = "POST_NOTIFICATIONS"
        const val VERIFY_SUCCESS = "Excelent, soldat! Vei primi imboldurile pe timp."
        const val VERIFY_FAILURE = "Fără notificări vei rata imboldurile. Încearcă din nou sau continuă fără."
        const val SUCCESS = "Notificări"
    }

    // ── 2. Locație ──
    object Location {
        const val TITLE = "Transformă orașul într-o aventură!"
        const val MASCOT = "Orașul e plin de colțuri pe care nu le-ai mai descoperit. Hai să le găsim împreună, soldat."
        const val DEMO_CAPTION = "Zonă după zonă, orașul devine al tău. Traseele tale rămân pe hartă."
        val TUTORIAL_BULLETS = listOf(
            "Pentru trasee live pe hartă și zonele descoperite.",
            "Pentru ca prietenii să te vadă pe hartă chiar și cu ecranul blocat."
        )
        const val TUTORIAL_PERMISSION = "ACCESS_FINE_LOCATION + BACKGROUND"
        const val VERIFY_SUCCESS = "Perfect! Te vedem acum pe hartă, soldat."
        const val VERIFY_FAILURE = "Fără locație, hărțile și traseele rămân goale."
        const val SUCCESS = "Locație"
    }

    // ── 3. Accesibilitate / Detox ──
    object Accessibility {
        const val TITLE = "Recucerește-ți concentrarea!"
        const val MASCOT = "Știu cum e: deschizi un mesaj și pierzi 20 de minute. Paznicul FORJA te face să te întorci la lucru."
        const val DEMO_CAPTION = "Stânga: telefonul te mănâncă. Dreapta: tu, concentrat. Paznicul face diferența."
        val TUTORIAL_BULLETS = listOf(
            "Paznicul FORJA poate apăsa „înapoi\" pentru a te scoate din aplicația care te distragе.",
            "Funcționează și cu ecranul blocat — exact ce e nevoie pentru o sesiune de detox."
        )
        const val TUTORIAL_PERMISSION = "SERVICIU DE ACCESIBILITATE"
        const val WIZARD_INTRO = "Activează serviciul de accesibilitate în 3 pași:"
        const val VERIFY_SUCCESS = "Paznicul e gata de serviciu, soldat!"
        const val VERIFY_FAILURE = "Paznicul nu e activ. Fără el, funcția de detox nu poate bloca aplicațiile."
        const val SUCCESS = "Accesibilitate"
    }

    // ── 4. Microfon ──
    object Microphone {
        const val TITLE = "Odihna face parte din misiune!"
        const val MASCOT = "Un somn bun e jumătate din luptă. FORJA îți poate urmări somnul și să-ți spună ce s-a întâmplat noaptea."
        const val DEMO_CAPTION = "Noaptea, FORJA ascultă sforăitul și vorbitul. Dimineața, ai raportul."
        val TUTORIAL_BULLETS = listOf(
            "Microfonul se activează DOAR când pornești monitorizarea somnului.",
            "Înregistrarea e locală pe telefon. Se trimite pe server doar cu acordul tău, ștearsă după 7 zile."
        )
        const val TUTORIAL_PERMISSION = "RECORD_AUDIO"
        const val VERIFY_SUCCESS = "Somnul e acoperit, soldat. Doar când pornești tu."
        const val VERIFY_FAILURE = "Fără microfon, monitorizarea somnului nu funcționează."
        const val SUCCESS = "Microfon"
    }

    // ── 5. Poze ──
    object Photos {
        const val TITLE = "Păstrează-ți victoriile!"
        const val MASCOT = "Fiecare kilogramă în plus, fiecare traseu completat — merită o amintire. Hai să-ți organizăm pozele, soldat."
        const val DEMO_CAPTION = "Pozele din galerie se organizează automat în albumul tău FORJA."
        val TUTORIAL_BULLETS = listOf(
            "FORJA poate organiza pozele din galerie și să le arhiveze în albumul tău.",
            "Poți alege doar anumite poze cu Photo Picker, fără acces complet. Dar pentru automatizare, accesul complet e mai util."
        )
        const val TUTORIAL_PERMISSION = "READ_MEDIA_IMAGES"
        const val VERIFY_SUCCESS = "Galeria e organizată, soldat!"
        const val VERIFY_FAILURE = "Fără acces la poze, inventarul și arhivarea automată nu funcționează."
        const val SUCCESS = "Poze"
    }

    // ── 6. Fișiere ──
    object Files {
        const val TITLE = "Un echipament bine organizat!"
        const val MASCOT = "Dosarul de documente arată ca o baracă după furtună. Hai să punem totul la loc, soldat."
        const val DEMO_CAPTION = "Fișierele se sortează singure în Poze, Documente și Alte. Ca o baracă după curățenie."
        val TUTORIAL_BULLETS = listOf(
            "Pentru a organiza poze din toate aplicațiile (inclusiv WhatsApp), Android cere acces la toate fișierele.",
            "Fără el, FORJA vede doar folderul Pictures. Cu el, tot e organizat."
        )
        const val TUTORIAL_PERMISSION = "MANAGE_EXTERNAL_STORAGE"
        const val VERIFY_SUCCESS = "Totul e sortat și la locul lui!"
        const val VERIFY_FAILURE = "Fără acces la fișiere, organizarea automată e limitată."
        const val SUCCESS = "Fișiere"
    }

    // ── 7. Baterie ──
    object Battery {
        const val TITLE = "Nu lăsa misiunea neterminată!"
        const val MASCOT = "Știu cum e: Android „ajută\" și oprește totul în fundal. Misiunea nu are voie să se oprească, soldat."
        const val DEMO_CAPTION = "Ecranul se blochează, dar traseul continuă. Alarma de dimineață sună. Nimic nu se pierde."
        val TUTORIAL_BULLETS = listOf(
            "Fără această setare, Android poate opri monitorizarea somnului după câteva minute.",
            "Alarma de dimineață poate rămâne mută dacă telefonul e în economisire."
        )
        const val TUTORIAL_PERMISSION = "IGNORARE OPTIMIZĂRI BATERIE"
        const val VERIFY_SUCCESS = "Misiunea continuă indiferent ce, soldat!"
        const val VERIFY_FAILURE = "Android te poate opri în fundal. Alarma și somnul pot eșua."
        const val SUCCESS = "Baterie"
    }

    // ── 8. Contacte + Muzică ──
    object Social {
        const val TITLE = "Misiunile sunt mai frumoase împreună!"
        const val MASCOT = "Un soldat singur e un soldat singur. Hai să-ți găsim camarazii și să vedem ce ascultă ei, soldat."
        const val DEMO_CAPTION = "Camarazii apar pe hartă cu muzica lor. Statisticii săptămânale, la un pas."
        val TUTORIAL_BULLETS = listOf(
            "FORJA găsește camarazii tăi din agendă care folosesc deja aplicația. Doar numerele sunt comparate, numele rămân pe telefon.",
            "Pentru a vedea ce ascultă prietenii pe hartă și pentru statisticile săptămânale."
        )
        const val TUTORIAL_PERMISSION = "READ_CONTACTS + NOTIFICATION LISTENER"
        const val VERIFY_SUCCESS = "Camarazii sunt la locul lor, soldat!"
        const val VERIFY_FAILURE = "Fără contacte și muzică, hărțile prietenilor rămân goale."
        const val SUCCESS = "Social"
    }

    // ── Rezumat final ──
    object Summary {
        const val TITLE = "Misiunea e pregătită!"
        const val SUB = "Iată ce ai activat. Ce ai sărit peste poți configura oricând din Profil → Echipare."
        const val SIGN_CONTRACT = "Semnează contractul"
        const val SIGNED = "Contract semnat"
    }
}
