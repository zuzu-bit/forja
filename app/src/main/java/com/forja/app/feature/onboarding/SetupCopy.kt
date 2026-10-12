package com.forja.app.feature.onboarding

/**
 * Toate textele românești pentru onboarding-ul de setup (8 etape × 4 faze).
 * Tonul: militar-cald, persuasiv. Mascota e camarazul care are nevoie de permisiuni
 * ca să-ți fie alături. Fiecare permisiune e prezentată ca o NEVOIE a funcției,
 * nu ca un simplu acord Android.
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
    const val VERIFYING = "Verific, soldat…"
    const val FINISH = "Începe aventura"
    const val MISSION_READY = "Misiunea e pregătită!"
    const val MISSION_READY_SUB = "Totul e la locul lui, soldat. Să ne apucăm!"
    const val TUTORIAL_ACTION = "Un singur pas, soldat: apasă butonul de mai jos."
    const val SKIP_ALL = "Mai târziu"
    const val WIZARD_DONE = "Am terminat"
    const val WIZARD_TRIED = "Am încercat"
    const val WIZARD_DONE_SHORT = "Am făcut"

    // ── 1. Notificări ──
    object Notifications {
        const val TITLE = "Un mic imbold face diferența!"
        const val MASCOT =
            "Uneori ai nevoie doar de puțină încurajare pentru a face primul pas! Eu sunt aici " +
            "să-ți amintesc de obiective, pauze, apă, mișcare și micile tale victorii. " +
            "Promit să nu te bombardez cu notificări!"
        const val DEMO_CAPTION = "Așa arată imboldurile FORJA. Scurte, pe timp, exact când ai nevoie."
        const val DEMO_INTRO = "Uite cum te imbolduiesc eu:"
        const val TUTORIAL_BTN = "Activează imboldurile"
        val TUTORIAL_BULLETS = listOf(
            "Un imbold pentru apă, o pauză, un streak aproape — exact când contează.",
            "Fără notificări, FORJA nu te mai imbolduiește. Seria ta se oprește fără să observi."
        )
        const val TUTORIAL_PERMISSION = "NOTIFICĂRI"
        const val VERIFY_SUCCESS = "Excelent, soldat! Vei primi imboldurile exact când contează."
        const val VERIFY_FAILURE = "Fără notificări, imboldurile se pierd. Încearcă din nou sau continuă — dar vei rata micile victorii."
        const val SUCCESS = "Notificări"
    }

    // ── 2. Locație ──
    object Location {
        const val TITLE = "Transformă orașul într-o aventură!"
        const val MASCOT =
            "De ce să mergi mereu pe aceleași drumuri? Cu FORJA, fiecare plimbare poate deveni " +
            "o aventură! Explorează cartiere, descoperă locuri noi, deblochează zone pe hartă " +
            "și transformă mișcarea în progres. Hai să vedem cât din oraș poți cuceri!"
        const val DEMO_CAPTION = "Zonă după zonă, orașul devine al tău. Traseele tale rămân pe hartă."
        const val DEMO_INTRO = "Vezi cum se cucerește orașul:"
        const val TUTORIAL_BTN_FG = "Cere locația"
        const val TUTORIAL_BTN_BG = "Cere „tot timpul”"
        val TUTORIAL_BULLETS_FG = listOf(
            "Traseul live pe hartă, zonele descoperite, kilometrii săptămânii — totul pornește de aici.",
            "Fără această permisiune, traseele se opresc la prima intersecție."
        )
        val TUTORIAL_BULLETS_BG = listOf(
            "Când blochezi telefonul și continui plimbarea, traseul nu trebuie să se oprească.",
            "Camarazii tăi văd poziția pe hartă chiar și cu ecranul blocat.",
            "„Se permite tot timpul” e setarea Android care face asta. Doar FORJA o folosește — nimic altceva."
        )
        const val TUTORIAL_PERMISSION_FG = "LOCAȚIE — ÎN TIMP CE FOLOSEȘTI"
        const val TUTORIAL_PERMISSION_BG = "LOCAȚIE — TOT TIMPUL"
        const val VERIFY_SUCCESS = "Perfect! Te vedem pe hartă, soldat. Orașul e al nostru acum."
        const val VERIFY_PARTIAL = "Bine! Traseele funcționează în prim-plan. Pentru traseul continuu cu ecranul blocat, setează „Se permite tot timpul”."
        const val VERIFY_FAILURE = "Fără locație, hărțile și traseele rămân goale. Orașul te așteaptă — încearcă din nou."
        const val SUCCESS = "Locație"
    }

    // ── 3. Accesibilitate / Detox ──
    object Accessibility {
        const val TITLE = "Recâștigă-ți concentrarea!"
        const val MASCOT =
            "Ți s-a întâmplat să deschizi telefonul pentru cinci minute și să pierzi o oră întreagă? " +
            "Atenția ta merită mai mult! Paznicul te ajută să reduci distragerile, " +
            "să stabilești limite și să construiești obiceiuri mai sănătoase. " +
            "E timpul să recâștigi controlul asupra timpului tău!"
        const val DEMO_CAPTION = "Stânga: telefonul te mănâncă. Dreapta: tu, concentrat. Paznicul face diferența."
        const val DEMO_INTRO = "Diferența pe care o face paznicul:"
        val TUTORIAL_BULLETS = listOf(
            "Paznicul FORJA poate apăsa „înapoi” pentru a te scoate din aplicația care te distrage.",
            "Funcționează și cu ecranul blocat — exact ce e nevoie pentru o sesiune de detox."
        )
        const val TUTORIAL_PERMISSION = "SERVICIU DE ACCESIBILITATE"
        // 3 pași — reutilizați din FocusScreen
        const val WIZARD_STEP1_TITLE = "Încearcă să pornești paznicul"
        const val WIZARD_STEP1_TEXT =
            "În Accesibilitate, atinge „FORJA · Accesibilitate” și pornește-l. " +
            "Android îl blochează: „Setare restricționată”. E normal — blocajul deschide pasul 2."
        const val WIZARD_STEP1_BTN = "Deschide Accesibilitatea"
        const val WIZARD_STEP2_TITLE = "Deblochează setările"
        const val WIZARD_STEP2_TEXT = "Setări → Aplicații → FORJA."
        const val WIZARD_STEP2_H1 = "1.  Sus-dreapta: ⋮"
        const val WIZARD_STEP2_H2 = "2.  „Permite setările restricționate”. Dacă cere codul, introdu-l."
        const val WIZARD_STEP2_BTN = "Deschide Aplicații"
        const val WIZARD_STEP3_TITLE = "Pornește paznicul"
        const val WIZARD_STEP3_TEXT =
            "Înapoi în Accesibilitate: pornește „FORJA · Accesibilitate”. Acum merge. " +
            "Paznicul stă în post pentru tine, nu împotriva ta."
        const val WIZARD_STEP3_BTN = "Deschide Accesibilitatea"
        const val VERIFY_SUCCESS = "Paznicul e gata de serviciu, soldat! Concentrarea e în mâinile tale."
        const val VERIFY_FAILURE = "Paznicul nu e activ. Fără el, funcția de detox nu poate bloca aplicațiile. Încearcă din nou."
        const val SUCCESS = "Accesibilitate"
    }

    // ── 4. Microfon ──
    object Microphone {
        const val TITLE = "Odihna face parte din misiune!"
        const val MASCOT =
            "Nu poți câștiga fiecare misiune fără odihnă! Somnul este momentul în care corpul " +
            "și mintea își refac energia. Dacă alegi monitorizarea audio, FORJA te poate ajuta " +
            "să înțelegi mai bine anumite aspecte ale odihnei tale."
        const val DEMO_CAPTION = "Noaptea, FORJA ascultă sforăitul și vorbitul. Dimineața, ai raportul."
        const val DEMO_INTRO = "Astfel înțeleg eu somnul tău:"
        const val TUTORIAL_BTN = "Activează microfonul"
        val TUTORIAL_BULLETS = listOf(
            "Microfonul se activează DOAR când pornești tu monitorizarea somnului. Nu e ascuns.",
            "Înregistrarea e locală pe telefon. Analiza se face doar pe telefonul tău — nimic nu pleacă în afară.",
            "Fără microfon, somnul rămâne o enigmă. Cu el, știi exact cum doarmești."
        )
        const val TUTORIAL_PERMISSION = "RECORD_AUDIO"
        const val VERIFY_SUCCESS = "Somnul e acoperit, soldat. Doar când pornești tu, doar pe telefonul tău."
        const val VERIFY_FAILURE = "Fără microfon, monitorizarea somnului nu funcționează. Noaptea rămâne o cutie neagră."
        const val SUCCESS = "Microfon"
    }

    // ── 5. Poze ──
    object Photos {
        const val TITLE = "Păstrează-ți victoriile!"
        const val MASCOT =
            "Fiecare progres merită ținut minte! Păstrează fotografiile momentelor importante " +
            "din aventura ta și privește cât de departe ai ajuns!"
        const val DEMO_CAPTION = "Pozele din galerie se organizează automat în albumul tău FORJA."
        const val DEMO_INTRO = "Așa arată albumul tău FORJA:"
        const val TUTORIAL_BTN = "Dă acces la poze"
        val TUTORIAL_BULLETS = listOf(
            "FORJA organizează pozele din galerie și le arhivează în albumul tău.",
            "Accesul complet la poze include și pozele din WhatsApp, Telegram sau capturile de ecran — totul rămâne organizat.",
            "Fără acces complet, doar folderul Pictures e vizibil. Cu el, tot e la locul lui."
        )
        const val TUTORIAL_PERMISSION = "ACCES LA POZE"
        const val VERIFY_SUCCESS = "Galeria e organizată, soldat! Fiecare victorie e păstrată."
        const val VERIFY_FAILURE = "Fără acces la poze, inventarul și arhivarea automată nu funcționează. Misiunile tale vor rămâne neatinse în galerie."
        const val SUCCESS = "Poze"
    }

    // ── 6. Fișiere ──
    object Files {
        const val TITLE = "Un echipament bine organizat!"
        const val MASCOT =
            "Fiecare misiune merge mai bine când ai totul pregătit. Alege documentele și resursele " +
            "pe care vrei să le păstrezi organizate în FORJA!"
        const val DEMO_CAPTION = "Fișierele se sortează singure în Poze, Documente și Altele. Ca o baracă după curățenie."
        const val DEMO_INTRO = "Așa organizez eu echipamentul:"
        const val TUTORIAL_BTN = "Activează accesul"
        val TUTORIAL_BULLETS = listOf(
            "Pentru a organiza poze din toate aplicațiile (inclusiv WhatsApp), Android cere acces la toate fișierele.",
            "Fără el, FORJA vede doar folderul Pictures. Cu el, tot e organizat — poze, documente, capturi de ecran.",
            "Inventarul folosește acest acces doar ca să mută fișierele unde le pui tu — nimic nu părăsește telefonul."
        )
        const val TUTORIAL_PERMISSION = "ACCES LA TOATE FIȘIERELE"
        const val VERIFY_SUCCESS = "Totul e sortat și la locul lui, soldat! Baraca e curată."
        const val VERIFY_FAILURE = "Fără acces la fișiere, organizarea automată e limitată. Inventarul nu poate muta pozele între dosare."
        const val SUCCESS = "Fișiere"
    }

    // ── 7. Baterie ──
    object Battery {
        const val TITLE = "Nu lăsa misiunea neterminată!"
        const val MASCOT =
            "Unele misiuni durează mai mult decât altele! Pentru trasee și sesiuni de somn, " +
            "îți voi arăta setările care pot ajuta aplicația să continue atunci când telefonul este blocat."
        const val DEMO_CAPTION = "Ecranul se blochează, dar traseul continuă. Alarma de dimineață sună. Nimic nu se pierde."
        const val DEMO_INTRO = "Misiunea nu se oprește, niciodată:"
        const val TUTORIAL_BTN = "Deschide setările"
        const val FSI_NOTE =
            "Pe Android 14+, alarma are nevoie și de permisiunea de afișare pe tot ecranul " +
            "ca să te trezească chiar și cu ecranul blocat."
        val TUTORIAL_BULLETS = listOf(
            "Fără această setare, Android poate opri monitorizarea somnului după câteva minute.",
            "Alarma de dimineață poate rămâne mută dacă telefonul e în economisire de baterie.",
            "Doar tu decizi: un singur comutator din Setări, activat o singură dată."
        )
        const val TUTORIAL_PERMISSION = "IGNORARE OPTIMIZĂRI BATERIE"
        const val VERIFY_SUCCESS = "Misiunea continuă indiferent ce, soldat! Niciun somn, niciun traseu nu se pierde."
        const val VERIFY_FAILURE = "Android te poate opri în fundal. Alarma și somnul pot eșua. Într-o noapte decisivă, va conta."
        const val SUCCESS = "Baterie"
    }

    // ── 8. Contacte + Muzică ──
    object Social {
        const val TITLE = "Misiunile sunt mai frumoase împreună!"
        const val MASCOT =
            "Misiunile se câștigă în echipă. Hai să-ți găsim camarazii care folosesc deja FORJA " +
            "și să vedem ce ascultă ei. Statisticile săptămânale, la un pas."
        const val DEMO_CAPTION = "Camarazii apar pe hartă cu muzica lor. Statisticile săptămânale, la un pas."
        const val DEMO_INTRO = "Așa văd eu echipa:"
        const val TUTORIAL_BTN = "Găsește camarazii"
        val TUTORIAL_BULLETS = listOf(
            "FORJA găsește camarazii tăi din agendă care folosesc deja aplicația. " +
            "Doar numerele sunt comparate — numele rămân pe telefon.",
            "Pentru a vedea ce ascultă prietenii pe hartă și pentru statisticile săptămânale.",
            "Fără agendă, hărțile camarazilor rămân goale. Singur, misiunea e mai grea."
        )
        const val TUTORIAL_PERMISSION = "ACCES LA AGENDĂ"
        const val VERIFY_SUCCESS = "Camarazii sunt la locul lor, soldat! Hărțile sunt pline."
        const val VERIFY_FAILURE = "Fără agendă, camarazii nu pot fi găsiți. Hărțile prietenilor rămân goale."
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
