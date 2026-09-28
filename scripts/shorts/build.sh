#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────────
# FORJA Shorts: clipurile verticale ale feed-ului (FRONT + RECRUȚI) și manifestul lor, în bucketul
# R2 „forja-media”, servite de forja-api la /media/<cheie> (aceeași formă ca Media.mediaUrl din app).
#
# Cu PEXELS_API_KEY: caută clipuri portret de 5–25 s pe Pexels (licență gratuită, autorul e creditat
#   în aplicație), apoi: 720×1280, ≤ 12 s, gradare caldă discretă, ștampila FORJA, fade 0,4 s, H.264 CRF 28,
#   fără sunet, +faststart; poster JPG la 1 s. Legenda (captions.json) ajunge în manifest și o desenează
#   aplicația, ca în prototip (Barlow 30/32); cu BURN_CAPTIONS=1 e arsă și în video (drawtext, cu halou),
#   iar manifestul o marchează (burned/capTop) ca aplicația să nu o mai deseneze a doua oară.
# Fără cheie: FLUX_TARGET (12) shorts FRONT din pozele FLUX deja existente în R2 (Ken Burns + legendă).
#
# Idempotent: ce e deja în manifest (sau a fost scos prin rotație) nu se reconstruiește, manifestul
# existent se păstrează și se completează, iar o rulare fără nimic de făcut nu scrie nimic în R2.
# Timp limitat: MAX_MINUTES (implicit 22); ce s-a terminat până atunci ajunge în manifest.
#
# Variabile (toate opționale, în afara secretelor):
#   CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID   aceleași secrete ca în build-apk.yml (wrangler)
#   PEXELS_API_KEY        cheia gratuită de pe pexels.com/api
#   FORJA_API_URL         baza publică a serverului (implicit: forja-api.<subdomeniul contului>.workers.dev)
#   TARGET_FRONT=36 TARGET_RECRUTI=18 FLUX_TARGET=12 PER_QUERY=15 MAX_PAGES=3
#   ROTATE_PCT=0          % din clipurile Pexels, cele mai vechi, scoase din feed înainte de completare
#   BURN_CAPTIONS=0       1 = legenda arsă și în video (drawtext); 0 = doar aplicația o desenează
#   DRY_RUN=0             1 = nu citește și nu scrie R2; totul rămâne în OUT_DIR
#   OUT_DIR=./shorts-out  MAX_MINUTES=22  WRANGLER="npx --yes wrangler@3"
# Local:  DRY_RUN=1 bash scripts/shorts/build.sh
# ─────────────────────────────────────────────────────────────────────────────────────────────
# SC2016: programele jq stau intenționat între apostrofuri ($id, $t… sunt variabile jq).
# SC1111: ghilimelele românești „…” din mesajele de jurnal sunt text, nu citare shell.
# shellcheck disable=SC2016,SC1111
set -Eeuo pipefail
trap 'printf "EROARE neașteptată (linia %s): %s\n" "$LINENO" "$BASH_COMMAND" >&2' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CAPTIONS="${CAPTIONS:-$SCRIPT_DIR/captions.json}"
FONT_DIR="${FONT_DIR:-$REPO_ROOT/app/src/main/res/font}"
BUCKET="${R2_BUCKET:-forja-media}"
MANIFEST_KEY="shorts_manifest.json"
DEFAULT_API="https://forja-api.forja-22e7ea2d.workers.dev"   # același implicit ca app/build.gradle.kts
PEXELS_API="${PEXELS_API:-https://api.pexels.com}"

TARGET_FRONT="${TARGET_FRONT:-36}"
TARGET_RECRUTI="${TARGET_RECRUTI:-18}"
FLUX_TARGET="${FLUX_TARGET:-12}"
PER_QUERY="${PER_QUERY:-15}"
MAX_PAGES="${MAX_PAGES:-3}"
ROTATE_PCT="${ROTATE_PCT:-0}"
BURN_CAPTIONS="${BURN_CAPTIONS:-0}"
DRY_RUN="${DRY_RUN:-0}"
MAX_MINUTES="${MAX_MINUTES:-22}"
OUT_DIR="${OUT_DIR:-$PWD/shorts-out}"
WRANGLER="${WRANGLER:-npx --yes wrangler@3}"

# Geometria cadrului, în px ai unui cadru 720×1280. Aplicația afișează clipul crop-to-fill, deci pe
# ecranele 20:9–21:9 laterale se taie ~10 %: textul stă între x = 96 și 624. Când legenda e arsă, capTop/capLeft
# ajung în manifest, ca aplicația să-și așeze ștampila FRONT/RECRUȚI exact deasupra ei.
VW=720; VH=1280; FPS=30; CRF=28; FADE=0.4
SAFE_X=96
CAP_TOP=980; CAP_SIZE=42; CAP_MAXC=24; CAP_SIZE_SMALL=36; CAP_MAXC_SMALL=30; CAP_LS=6
STAMP_X=96; STAMP_Y=168; STAMP_W=100; STAMP_H=36
CLIP_MAX=12; DUR_MIN=5; DUR_MAX=25; KB_DUR=8
GRADE="eq=contrast=1.04:saturation=0.92:brightness=-0.012,colorbalance=rs=0.035:gs=0.008:bs=-0.045:rm=0.03:gm=0:bm=-0.035:rh=0.02:gh=0:bh=-0.03"
ENC=(-c:v libx264 -preset veryfast -crf "$CRF" -profile:v high -level:v 4.0 -pix_fmt yuv420p
     -g $((FPS * 2)) -r "$FPS" -movflags +faststart)

# Căutări Pexels: „interogare|teme”. Temele aleg legendele potrivite din captions.json (+ „general”).
FRONT_QUERIES=(
  "push ups|flotari forta"
  "morning run|alergare dimineata"
  "boxing training|box"
  "military training|instructie forta"
  "cold water swim|frig"
  "healthy breakfast|hrana dimineata"
  "stretching|mobilitate"
  "mountain hike|munte"
  "pull ups|tractiuni forta"
  "jump rope|cardio"
)
RECRUTI_QUERIES=(
  "funny cat|amuzant"
  "kitten playing|pui joaca"
  "cat jumping|salt"
  "cat surprised|surpriza"
)
# Fără cheie Pexels: pozele FLUX din R2, în ordinea asta; se folosesc primele FLUX_TARGET care există.
FLUX_STILLS=(
  "430398119.jpg|alergare dimineata"
  "840977260.jpg|forta"
  "204232438.jpg|flotari forta"
  "1016024842.jpg|mobilitate"
  "622385753.jpg|forta"
  "471644726.jpg|hrana dimineata"
  "333728678.jpg|forta"
  "470987805.jpg|dimineata"
  "427022252.jpg|forta"
  "330198627.jpg|hrana"
  "392709785.jpg|forta"
  "503881704.jpg|hrana"
  "ex_shadow_boxing.jpg|box"
  "ex_plank.jpg|forta instructie"
  "ex_burpees.jpg|instructie"
  "ex_jumping_jacks.jpg|cardio"
  "ex_high_knees.jpg|cardio alergare"
  "ex_breathing.jpg|mobilitate"
  "snd_forest.jpg|munte"
  "snd_stream.jpg|munte"
)

# ── utilitare ────────────────────────────────────────────────────────────────────────────────
T0=$(date +%s)
MODE="?"; ADDED=0; FAILED=0; RETIRED=0; CHANGED=0; TIMED_OUT=0; SINCE_FLUSH=0; PEXELS_REJECTED=0
MAN=""; API=""; SCRIM=""; STAMP=""; CAP_FONT_SIZE=$CAP_SIZE

log()  { printf '%s\n' "$*"; }
warn() { if [ -n "${GITHUB_ACTIONS:-}" ]; then printf '::warning::%s\n' "$*"; else printf 'ATENȚIE: %s\n' "$*"; fi; }
die()  {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then printf '::error::%s\n' "$*" >&2; else printf 'EROARE: %s\n' "$*" >&2; fi
  if [ -n "${GITHUB_OUTPUT:-}" ]; then printf 'error=%s\n' "$(printf '%s' "$*" | tr '\n' ' ')" >> "$GITHUB_OUTPUT"; fi
  exit 1
}
now() { date +%s; }
time_left() { [ "$(now)" -lt "$DEADLINE" ]; }
int_or() { case "${1:-}" in ''|*[!0-9]*) printf '%s' "$2";; *) printf '%s' "$1";; esac; }
gt() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a > b) }'; }   # comparații cu zecimale

TARGET_FRONT=$(int_or "$TARGET_FRONT" 36); TARGET_RECRUTI=$(int_or "$TARGET_RECRUTI" 18)
FLUX_TARGET=$(int_or "$FLUX_TARGET" 12); PER_QUERY=$(int_or "$PER_QUERY" 15); MAX_PAGES=$(int_or "$MAX_PAGES" 3)
ROTATE_PCT=$(int_or "$ROTATE_PCT" 0); MAX_MINUTES=$(int_or "$MAX_MINUTES" 22)
[ "$ROTATE_PCT" -le 90 ] || ROTATE_PCT=90
if [ "$PER_QUERY" -lt 1 ] || [ "$PER_QUERY" -gt 80 ]; then PER_QUERY=15; fi
DEADLINE=$((T0 + MAX_MINUTES * 60))

WORK="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/forja-shorts.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
# Căile intră în graful de filtre ffmpeg: doar caractere care nu cer escapare (fără „:”, „'”, „\”, spații).
case "$WORK" in *[!A-Za-z0-9/._-]*) die "Dosarul temporar are caractere nepotrivite pentru ffmpeg: $WORK";; esac
CAP_FILE="$WORK/caption.txt"
PX_HDR="$WORK/pexels.h"; CF_HDR="$WORK/cloudflare.h"
(umask 077; : > "$PX_HDR"; : > "$CF_HDR")
# Secretele ajung la curl prin fișiere de antet (nu în linia de comandă, nu în jurnal).
if [ -n "${PEXELS_API_KEY:-}" ]; then (umask 077; printf 'Authorization: %s\n' "$PEXELS_API_KEY" > "$PX_HDR"); fi
if [ -n "${CLOUDFLARE_API_TOKEN:-}" ]; then (umask 077; printf 'Authorization: Bearer %s\n' "$CLOUDFLARE_API_TOKEN" > "$CF_HDR"); fi
if [ -z "${CLOUDFLARE_ACCOUNT_ID:-}" ]; then unset CLOUDFLARE_ACCOUNT_ID; fi   # wrangler îl deduce singur

read -r -a WR <<< "$WRANGLER"
R2_EXTRA=()
wr() { "${WR[@]}" "$@"; }

media_duration() {   # secunde (zecimal) sau gol
  local f=$1 d=""
  if command -v ffprobe >/dev/null 2>&1; then
    d=$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$f" 2>/dev/null | head -n 1 || true)
  fi
  case "$d" in ''|N/A|*[!0-9.]*)
    d=$(ffmpeg -hide_banner -nostdin -i "$f" 2>&1 | sed -n 's/.*Duration: \([0-9]*\):\([0-9]*\):\([0-9.]*\).*/\1 \2 \3/p' \
        | head -n 1 | awk '{ printf "%.2f", $1 * 3600 + $2 * 60 + $3 }' || true);;
  esac
  printf '%s' "$d"
}

# ── pregătire ────────────────────────────────────────────────────────────────────────────────
preflight() {
  local c filters
  for c in ffmpeg jq curl awk timeout; do command -v "$c" >/dev/null 2>&1 || die "Lipsește comanda $c."; done
  filters=$(ffmpeg -hide_banner -filters 2>/dev/null || true)
  for c in drawtext zoompan colorbalance geq gblur alphamerge rotate overlay fade split; do
    grep -q " $c " <<< "$filters" || die "ffmpeg nu are filtrul $c (drawtext cere libfreetype + libharfbuzz din 6.1)."
  done
  mkdir -p "$WORK/fonts" "$OUT_DIR"
  cp "$FONT_DIR/barlowc_600.ttf" "$WORK/fonts/cap.ttf" 2>/dev/null || die "Lipsește fontul $FONT_DIR/barlowc_600.ttf."
  cp "$FONT_DIR/barlowc_700.ttf" "$WORK/fonts/stamp.ttf" 2>/dev/null || die "Lipsește fontul $FONT_DIR/barlowc_700.ttf."
  jq -e '(.front | type == "array" and length > 0) and (.recruti | type == "array" and length > 0)' "$CAPTIONS" >/dev/null 2>&1 \
    || die "captions.json lipsește sau nu are listele front/recruti."
  local bad
  bad=$(jq -r '[.front[], .recruti[]] | map(.text // "" | select(test("!") or length == 0 or length > 60)) | .[]' "$CAPTIONS")
  if [ -n "$bad" ]; then warn "Legende ignorate (goale, cu semn de exclamare sau > 60 de caractere): $(tr '\n' '|' <<< "$bad")"; fi
  if [ "$DRY_RUN" != 1 ] && [ -z "${CLOUDFLARE_API_TOKEN:-}" ]; then
    die "Lipsește secretul CLOUDFLARE_API_TOKEN (același ca pentru build-apk.yml). Pentru o probă fără R2: DRY_RUN=1."
  fi
  if [ "$DRY_RUN" != 1 ]; then
    local v
    v=$(wr --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n 1 || true)
    [ -n "$v" ] || die "wrangler nu pornește ($WRANGLER)."
    # wrangler 3 lucrează implicit pe R2-ul de la distanță și respinge --remote; din v4 e obligatoriu.
    if [ "${v%%.*}" -ge 4 ]; then R2_EXTRA=(--remote); fi
    log "wrangler $v"
  fi
}

resolve_api() {
  if [ -n "${FORJA_API_URL:-}" ]; then API="${FORJA_API_URL%/}"; return 0; fi
  API="$DEFAULT_API"
  [ "$DRY_RUN" != 1 ] || return 0
  local acc="${CLOUDFLARE_ACCOUNT_ID:-}" sub=""
  if [ -z "$acc" ]; then acc=$(wr whoami 2>/dev/null | grep -oE '[0-9a-f]{32}' | head -n 1 || true); fi
  if [ -n "$acc" ]; then
    sub=$(curl -sS --max-time 20 -H @"$CF_HDR" "https://api.cloudflare.com/client/v4/accounts/$acc/workers/subdomain" 2>/dev/null \
          | jq -r '.result.subdomain // empty' 2>/dev/null || true)
  fi
  if [ -n "$sub" ]; then API="https://forja-api.$sub.workers.dev"; fi
}

make_assets() {
  SCRIM="$WORK/scrim.png"; STAMP="$WORK/stamp.png"
  if [ "$BURN_CAPTIONS" = 1 ]; then
    # Umbră discretă sus și jos, sub legenda arsă (fără ea, umbrele le desenează aplicația peste clip).
    ffmpeg -nostdin -hide_banner -loglevel error -y -f lavfi -i "color=c=black:s=${VW}x${VH}:d=1" \
      -vf "format=rgba,geq=r='0':g='0':b='0':a='255*min(1,0.66*pow(max(0,(Y/H-0.52)/0.48),1.4)+0.42*pow(max(0,1-Y/(0.22*H)),1.5))'" \
      -frames:v 1 -update 1 "$SCRIM" || die "Nu am putut genera umbra (scrim)."
  fi
  # Ștampila FORJA ca StampLabel din aplicație: chenar dublu, „tuș tocit”, majuscule rărite, rotită -6°,
  # cu un halou întunecat moale, ca să se vadă și pe cadre luminoase.
  local p=12 x y w=$STAMP_W h=$STAMP_H border
  x="(X-$p)"; y="(Y-$p)"
  border="between($x,0,$((w - 1)))*between($y,0,$((h - 1)))"
  border="$border*if(gt(lt($x,2)+gte($x,$((w - 2)))+lt($y,2)+gte($y,$((h - 2))),0),235,if(gt((eq($x,5)+eq($x,$((w - 6))))*between($y,5,$((h - 6)))+(eq($y,5)+eq($y,$((h - 6))))*between($x,5,$((w - 6))),0),140,0))"
  border="$border*if(lt(abs($x-$w*(0.18+0.06*$y/$h)),0.9)+lt(abs($x-$w*(0.61+0.05*$y/$h)),0.9)+lt(abs($x-$w*(0.86+0.04*$y/$h)),0.9),0.35,1)"
  local cs="$((w + 2 * p))x$((h + 2 * p))"
  ffmpeg -nostdin -hide_banner -loglevel error -y \
    -f lavfi -i "color=c=black:s=${cs}:d=1" -f lavfi -i "color=c=0xF4F2EE:s=${cs}:d=1" -f lavfi -i "color=c=black:s=${cs}:d=1" \
    -filter_complex "[0:v]format=gray,geq=lum='${border}',drawtext=fontfile=$WORK/fonts/stamp.ttf:text='F O R J A':fontsize=22:fontcolor=white:x=(w-tw)/2:y=(h-th)/2,split[m1][m2];[m2]gblur=sigma=3[mb];[2:v]format=rgba[k];[k][mb]alphamerge,colorchannelmixer=aa=0.5[halo];[1:v]format=rgba[c];[c][m1]alphamerge[ink];[halo][ink]overlay=0:0:format=rgb,format=rgba,rotate=-6*PI/180:ow=rotw(-6*PI/180):oh=roth(-6*PI/180):c=none,colorchannelmixer=aa=0.92[o]" \
    -map "[o]" -frames:v 1 -update 1 "$STAMP" || die "Nu am putut genera ștampila FORJA."
}

# ── manifest ─────────────────────────────────────────────────────────────────────────────────
load_manifest() {
  MAN="$WORK/manifest.json"
  local raw="$WORK/manifest.remote.json"
  rm -f "$raw"
  if [ "$DRY_RUN" = 1 ]; then
    if [ -f "$OUT_DIR/$MANIFEST_KEY" ]; then cp "$OUT_DIR/$MANIFEST_KEY" "$raw"; fi
  elif wr r2 object get "$BUCKET/$MANIFEST_KEY" --file "$raw" ${R2_EXTRA[@]+"${R2_EXTRA[@]}"} > "$WORK/get.log" 2>&1; then
    :
  elif grep -qiE '10007|does not exist|NoSuchKey|not found' "$WORK/get.log"; then
    # Confirmăm și public (serverul dă 404 pentru ce lipsește) înainte să pornim un manifest nou.
    local code
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$API/media/$MANIFEST_KEY" 2>/dev/null) || code=000
    if [ "$code" = 200 ]; then die "R2 spune că $MANIFEST_KEY lipsește, dar serverul îl servește; nu scriu peste el."; fi
    log "Manifest nou (nu exista în R2)."
    rm -f "$raw"
  else
    # Nu scriem niciodată peste un manifest pe care nu l-am putut citi.
    die "Nu pot citi $MANIFEST_KEY din R2: $(tail -c 400 "$WORK/get.log" | tr '\n' ' ')"
  fi
  if [ -s "$raw" ]; then
    jq -e '(.items // []) | type == "array"' "$raw" >/dev/null 2>&1 || die "$MANIFEST_KEY din R2 nu e JSON valid; nu scriu peste el."
    jq '{version: 1, items: [(.items // [])[] | select(type == "object" and (.id | type) == "string")], retired: (.retired // [])}' "$raw" > "$MAN"
  else
    printf '{"version":1,"items":[],"retired":[]}\n' > "$MAN"
  fi
}

man_edit() {   # man_edit <filtru jq> [argumente jq…]: modifică manifestul pe loc
  local filter=$1; shift
  jq "$@" "$filter" "$MAN" > "$WORK/manifest.tmp" && mv "$WORK/manifest.tmp" "$MAN"
}

count_items() {   # count_items <filtru select>
  jq "[.items[] | select($1)] | length" "$MAN"
}

R2_WORKS=0
r2_put() {   # r2_put <cheie> <fișier> <content-type>
  local key=$1 file=$2 type=$3 try out=""
  [ "$DRY_RUN" != 1 ] || return 0
  for try in 1 2 3; do
    if out=$(wr r2 object put "$BUCKET/$key" --file "$file" --content-type "$type" ${R2_EXTRA[@]+"${R2_EXTRA[@]}"} 2>&1); then
      R2_WORKS=1
      return 0
    fi
    sleep $((try * 3))
  done
  out=$(printf '%s' "$out" | tail -c 300 | tr '\n' ' ')
  # Prima urcare a rulării a eșuat: bucketul sau permisiunile lipsesc; nu randăm degeaba restul clipurilor.
  [ "$R2_WORKS" = 1 ] || die "R2 nu acceptă fișiere în $BUCKET (token fără Workers R2 Storage: Edit?): $out"
  warn "R2: nu am putut urca $key: $out"
  return 1
}

flush_manifest() {
  [ "$CHANGED" = 1 ] || return 0
  man_edit '{version: 1, updated: $t, count: (.items | length), items: .items, retired: ((.retired // []) | .[-1000:])}' \
    --arg t "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  jq -e '.items | type == "array"' "$MAN" >/dev/null || die "Manifestul generat e invalid."
  cp "$MAN" "$OUT_DIR/$MANIFEST_KEY"
  if [ "$DRY_RUN" != 1 ]; then
    r2_put "$MANIFEST_KEY" "$MAN" "application/json" || die "Nu am putut urca $MANIFEST_KEY în R2."
  fi
  SINCE_FLUSH=0
}

add_item() {   # add_item <id> <kind> <legendă> <credit json|null> <durată> <src> <interogare>
  local burned=false captop=null capleft=null
  if [ "$BURN_CAPTIONS" = 1 ]; then burned=true; captop=$CAP_TOP; capleft=$SAFE_X; fi
  man_edit '.items = ([.items[] | select(.id != $id)] + [{
      id: $id, kind: $kind, url: ($api + "/media/short_" + $id + ".mp4"), poster: ($api + "/media/short_" + $id + ".jpg"),
      caption: $caption, credit: $credit, dur: $dur, w: $w, h: $h, burned: $burned, capTop: $captop, capLeft: $capleft,
      src: $src, q: $q, added: $added }])' \
    --arg id "$1" --arg kind "$2" --arg caption "$3" --argjson credit "$4" --argjson dur "$5" --arg src "$6" --arg q "$7" \
    --arg api "$API" --argjson w "$VW" --argjson h "$VH" --argjson burned "$burned" --argjson captop "$captop" --argjson capleft "$capleft" \
    --argjson added "$(now)"
  CHANGED=1; ADDED=$((ADDED + 1)); SINCE_FLUSH=$((SINCE_FLUSH + 1))
  if [ "$SINCE_FLUSH" -ge 8 ]; then flush_manifest; fi   # dacă jobul e oprit, ce e urcat nu se pierde
}

rotate_manifest() {
  [ "$ROTATE_PCT" -gt 0 ] || return 0
  local before after
  before=$(jq '.items | length' "$MAN")
  man_edit 'def oldest($k): [.items[] | select(.kind == $k and (.src // "pexels") == "pexels")] | sort_by(.added // 0)
              | .[0:((length * $pct / 100) | floor)] | map(.id);
            (oldest("front") + oldest("recruti")) as $gone
            | .items |= map(select(.id | IN($gone[]) | not))
            | .retired = ((((.retired // []) - $gone) + $gone) | .[-1000:])' --argjson pct "$ROTATE_PCT"
  after=$(jq '.items | length' "$MAN")
  RETIRED=$((before - after))
  if [ "$RETIRED" -gt 0 ]; then CHANGED=1; log "Rotație: $RETIRED clipuri vechi scoase din feed ($ROTATE_PCT %)."; fi
}

# ── legende ──────────────────────────────────────────────────────────────────────────────────
pick_caption() {   # pick_caption <kind> <teme, prima = principala> <id>: legenda potrivită, cea mai puțin folosită
  # Potrivire: tema principală (0) < o temă secundară (1) < „general” (2); fiecare folosire anterioară +4.
  jq -r --arg kind "$1" --arg tags "$2" --arg id "$3" --slurpfile man "$MAN" '
    ($tags | split(" ") | map(select(length > 0))) as $q
    | [$man[0].items[].caption] as $used
    | [ .[$kind] | to_entries[]
        | .key as $i | (.value.text // "") as $t | (.value.tags // []) as $tg
        | select(($t | length) > 0 and ($t | length) <= 60 and ($t | test("!") | not))
        | (if ($q | length) == 0 then 0
           elif IN($tg[]; $q[0]) then 0
           elif ([$tg[] | select(IN($q[1:][]))] | length) > 0 then 1
           elif IN($tg[]; "general") then 2
           else null end) as $fit
        | select($fit != null)
        | {t: $t, cost: (([$used[] | select(. == $t)] | length) * 4 + $fit),
           tie: (($i * 7919 + ($id | explode | add)) % 997)} ]
    | if length == 0 then "FORJA" else (sort_by(.cost, .tie) | .[0].t) end' "$CAPTIONS"
}

wrap_text() {   # wrap_text <text> <max caractere pe rând>: 1–2 rânduri echilibrate, ruptura preferă punctuația
  jq -rn --arg t "$1" --argjson max "$2" '
    ($t | gsub("[\\r\\n\\t]"; " ") | gsub(" +"; " ") | ltrimstr(" ") | rtrimstr(" ")) as $s
    | ($s | split(" ")) as $w
    | if ($s | length) <= $max or ($w | length) < 2 then $s
      else [ range(1; $w | length) as $k
             | ($w[0:$k] | join(" ")) as $a | ($w[$k:] | join(" ")) as $b
             | {a: $a, b: $b, cost: (([($a | length), ($b | length)] | max)
                 - (if ($a | test("[.,:;]$")) then 5 else 0 end)
                 + (if ($w[$k - 1] | length) <= 2 then 4 else 0 end))} ]
           | min_by(.cost) | "\(.a)\n\(.b)"
      end'
}

write_capfile() {   # textul legendei, rupt pe rânduri, în CAP_FILE (drawtext îl citește cu expansion=none)
  local wrapped longest
  wrapped=$(wrap_text "$1" "$CAP_MAXC")
  longest=$(jq -rn --arg w "$wrapped" '$w | split("\n") | map(length) | max')
  CAP_FONT_SIZE=$CAP_SIZE
  if [ "$longest" -gt "$CAP_MAXC" ]; then
    wrapped=$(wrap_text "$1" "$CAP_MAXC_SMALL"); CAP_FONT_SIZE=$CAP_SIZE_SMALL
  fi
  printf '%s' "$wrapped" > "$CAP_FILE"
}

# ── randare ──────────────────────────────────────────────────────────────────────────────────
finish_chain() {   # finish_chain <durată>: [g] → (umbră) → ștampilă → (halou + legendă) → fade → [v]
  local d=$1 fo fades
  fo=$(awk -v d="$d" -v f="$FADE" 'BEGIN { printf "%.2f", d - f }')
  fades="fade=t=in:st=0:d=${FADE},fade=t=out:st=${fo}:d=${FADE},format=yuv420p[v]"
  if [ "$BURN_CAPTIONS" != 1 ]; then
    printf '%s' "[g][1:v]overlay=${STAMP_X}:${STAMP_Y},${fades}"
    return 0
  fi
  # textfile + expansion=none: fără escapări pentru „:”, ghilimele, „%” sau „\” din legendă.
  local dt="drawtext=fontfile=$WORK/fonts/cap.ttf:textfile=${CAP_FILE}:expansion=none:fontsize=${CAP_FONT_SIZE}:line_spacing=${CAP_LS}:x=${SAFE_X}:y=${CAP_TOP}"
  # Halou: aceeași legendă, albă pe negru, estompată și folosită ca alfa pentru un strat negru.
  printf '%s' "[g][2:v]overlay=0:0[s1];[s1][1:v]overlay=${STAMP_X}:${STAMP_Y}[s2];"
  printf '%s' "color=c=black:s=${VW}x${VH}:r=1:d=1,format=gray,${dt}:fontcolor=white,gblur=sigma=5[cm];"
  printf '%s' "color=c=black:s=${VW}x${VH}:r=1:d=1,format=rgba[ck];[ck][cm]alphamerge,colorchannelmixer=aa=0.85[glow];"
  printf '%s' "[s2][glow]overlay=0:0,${dt}:fontcolor=0xF4F2EE:shadowcolor=0x000000@0.35:shadowx=0:shadowy=2,${fades}"
}

overlay_inputs() {   # intrările 1 (ștampila) și 2 (umbra, doar cu legenda arsă) ale grafului
  printf '%s\n' -i "$STAMP"
  if [ "$BURN_CAPTIONS" = 1 ]; then printf '%s\n' -i "$SCRIM"; fi
}

render_video() {   # render_video <sursă> <start> <durată> <ieșire>
  local fg
  fg="[0:v]fps=${FPS},scale=${VW}:${VH}:force_original_aspect_ratio=increase:flags=bicubic,crop=${VW}:${VH},setsar=1,${GRADE}[g];$(finish_chain "$3")"
  local -a extra; mapfile -t extra < <(overlay_inputs)
  timeout 300 ffmpeg -nostdin -hide_banner -loglevel error -y -ss "$2" -t "$3" -i "$1" "${extra[@]}" \
    -filter_complex "$fg" -map "[v]" -an -sn -dn -map_metadata -1 "${ENC[@]}" "$4"
}

render_still() {   # render_still <imagine> <variantă 0–3> <ieșire>: Ken Burns lent, 8 s
  local n=$((KB_DUR * FPS)) z x y fg
  local t="on/$((n - 1))"
  case $(($2 % 4)) in
    0) z="1+0.12*$t";    x="(iw-iw/zoom)*(0.5-0.10*$t)"; y="(ih-ih/zoom)*0.5";;
    1) z="1.12-0.12*$t"; x="(iw-iw/zoom)*(0.4+0.2*$t)";  y="(ih-ih/zoom)*0.5";;
    2) z="1+0.10*$t";    x="(iw-iw/zoom)*0.5";           y="(ih-ih/zoom)*(0.6-0.2*$t)";;
    *) z="1.04+0.10*$t"; x="(iw-iw/zoom)*(0.5+0.12*$t)"; y="(ih-ih/zoom)*0.45";;
  esac
  # Mărim întâi (1620×2880, tot 9:16), ca pașii întregi ai zoompan să nu tremure în 720×1280.
  fg="[0:v]scale=1620:2880:force_original_aspect_ratio=increase:flags=lanczos,crop=1620:2880,setsar=1,zoompan=z='${z}':x='${x}':y='${y}':d=${n}:s=${VW}x${VH}:fps=${FPS},${GRADE}[g];$(finish_chain "$KB_DUR")"
  local -a extra; mapfile -t extra < <(overlay_inputs)
  timeout 300 ffmpeg -nostdin -hide_banner -loglevel error -y -i "$1" "${extra[@]}" \
    -filter_complex "$fg" -map "[v]" -frames:v "$n" -an -sn -dn -map_metadata -1 "${ENC[@]}" "$3"
}

make_poster() { ffmpeg -nostdin -hide_banner -loglevel error -y -ss 1 -i "$1" -frames:v 1 -q:v 4 -update 1 "$2"; }

publish_pair() {   # publish_pair <id> <mp4> <jpg>: posterul întâi; clipul intră în manifest doar dacă ambele au urcat
  r2_put "short_$1.jpg" "$3" "image/jpeg" && r2_put "short_$1.mp4" "$2" "video/mp4"
}

size_mb() { awk -v b="$(wc -c < "$1")" 'BEGIN { printf "%.1f MB", b / 1048576 }'; }

# ── Pexels ───────────────────────────────────────────────────────────────────────────────────
pexels_candidates() {   # pexels_candidates <kind> <câte trebuie> <interogări…> → $WORK/cands_<kind>.json
  local kind=$1 need=$2; shift 2
  local all="$WORK/cands_${kind}.jsonl" page qi entry q tags f code n fresh
  local want=$((need * 2 + 4))
  : > "$all"
  for ((page = 1; page <= MAX_PAGES; page++)); do
    if [ "$page" -gt 1 ]; then
      fresh=$(jq -s --slurpfile man "$MAN" '([$man[0].items[].id] + ($man[0].retired // [])) as $seen
               | map(select(.id | IN($seen[]) | not)) | unique_by(.id) | length' "$all")
      [ "$fresh" -lt "$want" ] || break
    fi
    qi=0
    for entry in "$@"; do
      q=${entry%%|*}; tags=${entry#*|}
      if [ -f "$WORK/px_${kind}_${qi}.done" ]; then qi=$((qi + 1)); continue; fi
      f="$WORK/px_${kind}_${qi}_${page}.json"
      code=$(curl -sS --max-time 30 -G "$PEXELS_API/videos/search" -H @"$PX_HDR" \
               --data-urlencode "query=$q" -d orientation=portrait -d per_page="$PER_QUERY" -d page="$page" \
               -o "$f" -w '%{http_code}' 2>/dev/null) || code=000
      case "$code" in
        200) ;;
        401|403) PEXELS_REJECTED=1; warn "Pexels a respins cheia (HTTP $code)."; return 2;;
        429) warn "Pexels: limita de cereri atinsă; continui cu ce am găsit."; break 2;;
        *) warn "Pexels «$q» pagina $page: HTTP $code"; : > "$WORK/px_${kind}_${qi}.done"; qi=$((qi + 1)); continue;;
      esac
      jq -c --arg kind "$kind" --arg q "$q" --arg tags "$tags" --argjson qi "$qi" \
         --argjson base $(((page - 1) * PER_QUERY)) --argjson dmin "$DUR_MIN" --argjson dmax "$DUR_MAX" '
        def absv: if . < 0 then -. else . end;
        (.videos // []) | to_entries[] | .key as $r | .value
        | select((.duration // 0) >= $dmin and (.duration // 0) <= $dmax and (.height // 0) > (.width // 0))
        | ([.video_files[]? | select((.file_type // "") == "video/mp4" and (.link // "") != ""
                                     and (.width // 0) >= 540 and (.height // 0) > (.width // 0))]
           | sort_by(((.width - 720) | absv) + ((.height - 1280) | absv)) | first) as $f
        | select($f != null and .id != null)
        | {id: ("px_" + (.id | tostring)), dur: .duration, link: $f.link, fw: $f.width, fh: $f.height,
           credit: {name: (.user.name // "" | if . == "" then "Pexels" else . end), url: (.url // "https://www.pexels.com")},
           q: $q, tags: $tags, qi: $qi, rank: ($base + $r), kind: $kind}' "$f" >> "$all" 2>/dev/null \
        || warn "Pexels «$q»: răspuns ilizibil."
      n=$(jq '(.videos // []) | length' "$f" 2>/dev/null || echo 0)
      if [ "$n" -lt "$PER_QUERY" ]; then : > "$WORK/px_${kind}_${qi}.done"; fi
      qi=$((qi + 1))
    done
  done
  # Fără duplicate, fără ce e deja în feed sau a fost scos; ordine „round-robin”: primul rezultat din fiecare
  # căutare, apoi al doilea… ca toate temele să fie reprezentate.
  jq -s --slurpfile man "$MAN" '([$man[0].items[].id] + ($man[0].retired // [])) as $seen
    | map(select(.id | IN($seen[]) | not)) | group_by(.id) | map(min_by(.rank)) | sort_by(.rank, .qi)' \
    "$all" > "$WORK/cands_${kind}.json"
}

process_pexels() {   # process_pexels <candidat json> <etichetă>
  local id="" link="" adur=0 q="" tags="" credit kind="" label=$2 src sdur ss=0 d cap out poster
  eval "$(jq -r '@sh "id=\(.id) link=\(.link) adur=\(.dur) q=\(.q) tags=\(.tags) kind=\(.kind)"' <<< "$1")"
  credit=$(jq -c '.credit' <<< "$1")
  src="$WORK/src.mp4"; rm -f "$src"
  if ! curl -fsSL --max-time 150 --retry 2 --max-filesize 150000000 -A "FORJA-Shorts/1.0" -o "$src" "$link" 2> "$WORK/dl.err"; then
    log "  ✗ $id: descărcare eșuată ($(tail -c 160 "$WORK/dl.err" | tr '\n' ' '))"; return 1
  fi
  sdur=$(media_duration "$src"); [ -n "$sdur" ] || sdur=$adur
  if gt "$sdur" 13; then ss=1; fi
  d=$(awk -v s="$sdur" -v ss="$ss" -v m="$CLIP_MAX" 'BEGIN { d = s - ss - 0.05; if (d > m) d = m; printf "%.1f", int(d * 10) / 10 }')
  if ! gt "$d" 3.9; then log "  ✗ $id: prea scurt ($sdur s)"; return 1; fi
  cap=$(pick_caption "$kind" "$tags" "$id")
  write_capfile "$cap"
  out="$OUT_DIR/short_$id.mp4"; poster="$OUT_DIR/short_$id.jpg"
  if ! render_video "$src" "$ss" "$d" "$out" 2> "$WORK/ff.err"; then
    log "  ✗ $id: ffmpeg a eșuat ($(tail -c 300 "$WORK/ff.err" | tr '\n' ' '))"; return 1
  fi
  make_poster "$out" "$poster" 2> "$WORK/ff.err" || { log "  ✗ $id: posterul a eșuat"; return 1; }
  publish_pair "$id" "$out" "$poster" || return 1
  add_item "$id" "$kind" "$cap" "$credit" "$d" pexels "$q"
  log "  ✓ $label $id «$q» ${d} s · $(size_mb "$out") · „$cap”"
  rm -f "$src"
}

build_pexels_kind() {   # build_pexels_kind <kind> <țintă> <nume> <interogări…>
  local kind=$1 target=$2 name=$3; shift 3
  local have need total i=0 made=0 cand rc=0
  have=$(count_items ".kind == \"$kind\" and (.src // \"pexels\") == \"pexels\"")
  need=$((target - have))
  if [ "$need" -le 0 ]; then log "$name: complet ($have/$target)."; return 0; fi
  if ! time_left; then TIMED_OUT=1; log "$name: $have/$target, fără timp rămas în rularea asta."; return 0; fi
  log "$name: $have/$target, caut încă $need pe Pexels…"
  pexels_candidates "$kind" "$need" "$@" || rc=$?
  if [ "$rc" -ne 0 ]; then return "$rc"; fi
  total=$(jq 'length' "$WORK/cands_${kind}.json")
  log "$name: $total candidați noi."
  while [ "$made" -lt "$need" ] && [ "$i" -lt "$total" ]; do
    if ! time_left; then TIMED_OUT=1; warn "Timpul alocat ($MAX_MINUTES min) s-a terminat; restul la rularea următoare."; break; fi
    cand=$(jq -c ".[$i]" "$WORK/cands_${kind}.json"); i=$((i + 1))
    if process_pexels "$cand" "[$name $((have + made + 1))/$target]"; then made=$((made + 1)); else FAILED=$((FAILED + 1)); fi
  done
  if [ "$made" -lt "$need" ] && [ "$TIMED_OUT" = 0 ]; then warn "$name: doar $made din $need clipuri noi (candidați epuizați)."; fi
  return 0
}

# ── FLUX (fără cheie Pexels) ─────────────────────────────────────────────────────────────────
build_flux() {
  local have need made=0 variant=0 entry key tags id img code out poster cap
  have=$(count_items '.src == "flux"')
  need=$((FLUX_TARGET - have))
  if [ "$need" -le 0 ]; then log "FLUX: complet ($have/$FLUX_TARGET)."; return 0; fi
  log "FLUX: $have/$FLUX_TARGET, construiesc încă $need din pozele din R2 ($API/media/…)."
  for entry in "${FLUX_STILLS[@]}"; do
    [ "$made" -lt "$need" ] || break
    if ! time_left; then TIMED_OUT=1; warn "Timpul alocat s-a terminat."; break; fi
    key=${entry%%|*}; tags=${entry#*|}
    id="fx_$(printf '%s' "${key%.*}" | tr -c 'A-Za-z0-9_-' '_')"
    variant=$((variant + 1))
    if jq -e --arg id "$id" 'any(.items[]; .id == $id)' "$MAN" >/dev/null; then continue; fi
    img="$WORK/still.jpg"; rm -f "$img"
    code=$(curl -sS --max-time 90 -A "FORJA-Shorts/1.0" -o "$img" -w '%{http_code}' "$API/media/$key" 2>/dev/null) || code=000
    if [ "$code" != 200 ] || [ ! -s "$img" ] || [ "$(wc -c < "$img")" -lt 20000 ]; then
      log "  · $key indisponibil (HTTP $code)"; continue
    fi
    cap=$(pick_caption front "$tags" "$id")
    write_capfile "$cap"
    out="$OUT_DIR/short_$id.mp4"; poster="$OUT_DIR/short_$id.jpg"
    if ! render_still "$img" "$variant" "$out" 2> "$WORK/ff.err"; then
      log "  ✗ $id: ffmpeg a eșuat ($(tail -c 300 "$WORK/ff.err" | tr '\n' ' '))"; FAILED=$((FAILED + 1)); continue
    fi
    if ! make_poster "$out" "$poster" 2> "$WORK/ff.err"; then log "  ✗ $id: posterul a eșuat"; FAILED=$((FAILED + 1)); continue; fi
    if ! publish_pair "$id" "$out" "$poster"; then FAILED=$((FAILED + 1)); continue; fi
    add_item "$id" front "$cap" null "$KB_DUR" flux "$key"
    made=$((made + 1))
    log "  ✓ [FLUX $((have + made))/$FLUX_TARGET] $id · $(size_mb "$out") · „$cap”"
  done
  if [ "$made" -lt "$need" ] && [ "$TIMED_OUT" = 0 ]; then warn "FLUX: doar $made din $need (poze lipsă în R2)."; fi
}

# ── rezumat ──────────────────────────────────────────────────────────────────────────────────
emit_summary() {
  local front recruti total mins
  front=$(count_items '.kind == "front"'); recruti=$(count_items '.kind == "recruti"')
  total=$(jq '.items | length' "$MAN"); mins=$((($(now) - T0 + 59) / 60))
  log "────────────────────────────────────────────────────────"
  log "Mod: $MODE · FRONT: $front · RECRUȚI: $recruti · noi: $ADDED · eșuate: $FAILED · scoase: $RETIRED · $mins min"
  if [ "$DRY_RUN" = 1 ]; then log "Probă (DRY_RUN): nimic urcat; rezultatul e în $OUT_DIR"; else log "Manifest: $API/media/$MANIFEST_KEY"; fi
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
      printf 'mode=%s\nfront=%s\nrecruti=%s\ntotal=%s\nadded=%s\nfailed=%s\nretired=%s\n' \
        "$MODE" "$front" "$recruti" "$total" "$ADDED" "$FAILED" "$RETIRED"
      printf 'minutes=%s\ntimed_out=%s\nmanifest=%s\ndry_run=%s\n' "$mins" "$TIMED_OUT" "$API/media/$MANIFEST_KEY" "$DRY_RUN"
    } >> "$GITHUB_OUTPUT"
  fi
  [ "$total" -gt 0 ] || die "Feed-ul e gol: niciun clip construit (vezi mesajele de mai sus)."
}

main() {
  preflight
  resolve_api
  log "FORJA Shorts · server $API · bucket $BUCKET · legendă arsă: $BURN_CAPTIONS · DRY_RUN: $DRY_RUN · limită $MAX_MINUTES min"
  load_manifest
  log "Manifest: $(count_items '.kind == "front"') FRONT, $(count_items '.kind == "recruti"') RECRUȚI."
  make_assets
  if [ -n "${PEXELS_API_KEY:-}" ]; then
    MODE=pexels
    rotate_manifest
    local rc=0
    build_pexels_kind front "$TARGET_FRONT" FRONT "${FRONT_QUERIES[@]}" || rc=$?
    if [ "$rc" -eq 0 ]; then build_pexels_kind recruti "$TARGET_RECRUTI" RECRUȚI "${RECRUTI_QUERIES[@]}" || rc=$?; fi
    if [ "$PEXELS_REJECTED" = 1 ]; then
      MODE="flux (cheia Pexels respinsă)"
      build_flux
    elif [ "$(count_items '.kind == "front" and (.src // "pexels") == "pexels"')" -ge "$FLUX_TARGET" ] \
         && [ "$(count_items '.src == "flux"')" -gt 0 ]; then
      # Clipurile reale au ajuns: pozele animate FLUX ies din feed (fișierele rămân în R2).
      man_edit '.items |= map(select(.src != "flux"))'; CHANGED=1
      log "FLUX: clipurile Ken Burns au ieșit din feed (Pexels FRONT ≥ $FLUX_TARGET)."
    fi
  else
    MODE=flux
    log "Fără PEXELS_API_KEY: shorts FRONT din pozele FLUX (cheia gratuită: pexels.com/api → secretul PEXELS_API_KEY)."
    build_flux
  fi
  flush_manifest
  emit_summary
}

main "$@"
