# AI pe serverul FORJA (forja-api) — chei, ce aduce fiecare, costuri

Tot ce cere un model pe `forja-api` trece printr-un singur loc: `server/ai-router.mjs`. Routerul încearcă furnizorii
în ordine și trece la următorul la orice eroare (rețea, 4xx/5xx, limită zilnică, JSON invalid). Utilizatorii nu au chei:
cheile stau pe worker, ca secrete, și ajung acolo doar din GitHub → Settings → Secrets (jobul `deploy-api`).

## Gratuit, fără card (decizia finală)

Ordinea reală a furnizorilor: **Gemini → Groq → Claude → OpenAI → Workers AI**. Primele două au chei gratuite; Claude și
OpenAI se folosesc DOAR dacă cineva le pune cheia (probabil nu); Workers AI e mereu acolo, fără cheie.

| Secret (GitHub) | De unde | Ce aduce | Limite / cost |
|---|---|---|---|
| `GEMINI_API_KEY` | [aistudio.google.com/apikey](https://aistudio.google.com/apikey) → „Create API key” (cont Google, fără card) | Poze (mese, curățenie), **PDF nativ** și **singura cale pentru AUDIO integral**: clipurile de 5 s și chunk-urile de 30 min sunt ascultate întregi (vorbit cu transcriere exactă, sforăit cu intensitate, tuse). Modele: `gemini-2.5-flash`, apoi `gemini-2.5-flash-lite`; `2.5-pro` doar dacă răspunde. | Gratuit: ~250 cereri/zi la Flash (orientativ). O noapte de 8 h în chunk-uri de 30 min = 16 cereri. Dacă ar fi plătit: ≈ 0,3 $/noapte la Flash, ≈ 1,2 $ la Pro. |
| `GROQ_API_KEY` | [console.groq.com/keys](https://console.groq.com/keys) (cont, fără card) | A doua opinie gratuită: viziune cu Llama 4 Scout/Maverick (poze), text cu Llama 3.3 70B (rezumate, curățenie fără PDF), **Whisper large v3 cu timpi pe segmente** (transcriere pentru clipuri și chunk-uri când Gemini lipsește sau pică). Nu citește PDF (primește doar textul extras). | Gratuit: ~14 400 cereri/zi (orientativ). |
| `ANTHROPIC_API_KEY` | [console.anthropic.com](https://console.anthropic.com) (plătit) | Cea mai bună analiză foto/PDF/text (`claude-fable-5-1`, apoi `claude-opus-5-5`, `claude-sonnet-5`). Fără audio. | ≈ 0,01–0,03 $ per masă (două treceri). Se folosește doar dacă cheia există. |
| `OPENAI_API_KEY` | [platform.openai.com](https://platform.openai.com) (plătit) | Opțional: poze + text (`gpt-5`, apoi `gpt-4.1`). Fără PDF nativ, fără audio. | După plată. |
| *(fără nicio cheie)* | — | **Workers AI** (Cloudflare, inclus): Llama 3.2 Vision descrie poza, Llama 3.3 70B pune cifrele/JSON-ul și face a doua trecere de verificare; Whisper large v3 turbo transcrie; clasificarea sforăit/zgomot/liniște se face în Worker, pe energie și periodicitatea respirației (0,5–2 Hz). **Mai slab** decât Gemini/Groq: `/v1/diag` o spune clar („fără chei — doar modelele Cloudflare”). | 10 000 neuroni/zi pe planul gratuit. |

Recomandare: pune măcar `GEMINI_API_KEY` (audio) și `GROQ_API_KEY` (a doua opinie + Whisper cu timpi). Amândouă sunt gratuite.

## Cum adaugi o cheie (2 minute)

1. Ia cheia de la furnizor (linkurile din tabel).
2. GitHub → repo `forja` → **Settings → Secrets and variables → Actions → New repository secret**. Numele EXACT din tabel
   (`GEMINI_API_KEY`, `GROQ_API_KEY`, …), valoarea = cheia.
3. Rulează workflow-ul **build-apk** (sau dă un push). Jobul `deploy-api` publică worker-ul și pune fiecare cheie găsită ca
   secret pe `forja-api` (`wrangler secret put`), doar dacă există. Rezumatul rulării spune „Chei AI puse pe worker: …”.
4. Verifică: `curl https://<worker>.workers.dev/v1/diag?models=0` → `providers: { gemini: "configured", … }`, `order`, `budget`.
   Cheile nu apar niciodată în diag, în jurnal sau în răspunsuri.

Ca să scoți o cheie: șterge secretul din GitHub și rulează `npx wrangler secret delete GEMINI_API_KEY` din `server/`
(sau din dash.cloudflare.com → Workers → forja-api → Settings → Variables).

## Ce face fiecare rută cu routerul

- `POST /v1/meal` — **mese v2**: poza → `visionJson` cu două treceri (a doua verifică porțiile și kcal ≈ 4P + 4C + 9G ±15 %
  și coboară încrederea unde nu se vede clar). Răspuns compatibil cu v1 (`fel`, `incredere`, `componente[nume, grame, kcal,
  proteine, carbo, grasimi]`) + `fibre`, `incredere` per componentă, `total`, `scor{valoare 1–10, motiv}`, `sfat`, `observatii[]`,
  `portie`, `model`, `provider`, `verificat`, `versiune: 2`. Timp ≤ 45 s per furnizor, apoi următorul.
- `POST /v1/organize` — **curățenie v2**: iteme cu miniaturi și/sau `pdfB64` (≤ 4 MB, cel mult 6 per cerere; 30 iteme / 8 MB).
  PDF-ul ajunge nativ la Gemini/Claude; Groq/OpenAI/Workers primesc doar `text` + metadate. Per item, pe lângă v1
  (`suggestion`, `folder`, `reason`, `confidence`): `rezumat`, `categorie`, `dosar` (≤ 24 caractere), `sterge{recomandat, motiv,
  incredere}`, `duplicatDe`. Nimic nu se șterge automat.
- `POST /v1/sleep-audio` — clip WAV 5 s: cu Gemini → ascultare integrală (`type: talk|snore|cough|noise|silence`, `transcript`,
  `words`, `speech`, `confidence` 0..1, `intensity` 0..1); fără → Whisper + filtrul de halucinații + clasificarea acustică.
- `PUT /v1/sleep-chunk?session&index&from&dur` (audio/mp4 ≤ 25 MB, ≤ 35 min) → R2 `uid/sesiune/chunk_i.m4a`, șters după 7 zile.
  `GET /v1/sleep-chunk?session&index` cu `Range` pentru redare.
- `POST /v1/sleep-analyze {session, chunks:[{index, from, dur}], sessionMs?, clips?}` → fiecare chunk la Gemini („ascultă
  integral, nu inventa”), unite în `sleep-timeline.mjs` (decalaj, sforăit unit sub 20 s, statistici, acoperire). Peste 25 s
  răspunde `{status:"processing"}` și continuă în fundal (`ctx.waitUntil`); clientul face polling la `GET /v1/sleep-analysis?session`.
  Fără Gemini: Whisper cu timpi (doar vorbit; limitarea e spusă în `limitari`); fără nimic: `{status:"clips_only"}`.
- `POST /v1/sleep-summary` — rezumatul de dimineață, 2–4 propoziții calde și oneste din cifre și citate reale (`timeline`
  cu statistici și până la 6 citate); `POST /v1/sleep-talk-summary` — două propoziții din frazele auzite. Fără chei → Llama.
- `GET /v1/diag` — `providers` (configured/absent), `order`, `mode`, `audio`, `lastUsed` per sarcină, `budget` (consum azi +
  limita cunoscută), modelele Cloudflare care răspund (`?models=0` sare testul lor, ~30 s).

## Limite zilnice și siguranță

- Contor per furnizor și zi (`ai-budget:{furnizor}:{zi}`) în KV (`AI_BUDGET`, dacă e legat) sau R2 (`RECORDS`); la limită
  sau la 429 routerul trece automat la următorul furnizor.
- JSON strict: extragere fără ghicit, validare cu schema (`ai-schemas.mjs`), o singură reîncercare „repară JSON-ul” per furnizor.
- Textul venit de la client (note, fraze, nume de fișiere, text din documente) intră în prompt mărginit și marcat ca DATE,
  între « »; cifrele sunt limitate la intervale reale (`bounded`).
- Timeouts: Gemini 90 s (audio 180 s), Groq/Claude/OpenAI 60 s, Workers 60 s; mese ≤ 45 s per furnizor.

## Cum adaugi un furnizor nou

1. `server/ai-<nume>.mjs` cu obiectul `{ name, timeoutMs, supports:{images, documents, audio, verifyWithImages, transcribe?},
   available(env), models(env, {images}), dailyLimit, generate(env, {model, system, prompt, images, documents, audio, schema,
   maxTokens, timeoutMs}) → text, transcribe?(env, {bytes, mime, language}) }`. Erorile se aruncă ca `AiError` din
   `ai-common.mjs` (fără chei în mesaj; `fatal: true` la 429/401/403 sare peste restul modelelor furnizorului).
2. Îl pui în `ALL_PROVIDERS` din `ai-router.mjs`, la locul lui în ordine, și în `KNOWN_LIMITS`.
3. Secretul: în tabelul de mai sus, în lista `for K in …` din `.github/workflows/build-apk.yml` (jobul `deploy-api`) și în `env:`.
4. Teste: `server/ai-router.test.mjs` (ordine, fallback, reparare JSON) — `cd server && node --check worker.js && npm test`.
