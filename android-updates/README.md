# Delivered Android source and visual update

The root `app/` project predates the delivered v23 app. Building it as a new
release would remove later features. This directory restores the latest source
from `FORJA_v23_sources.zip`; the historical update directories record the
successive changes and their pinned inputs.

`cleanup-update/` is the current Android feature module. It includes cleanup,
files, the social map, partner sharing, contacts and lost-phone recovery.
`audio-update/src/` contains the current web-pairing UI and recording support.
The delivered APK retains the rest of the application's compiled classes.

Use `visual-update/build.py` for the v24 visual update. It requires the exact
signed v23 APK, compiles the changed UI, retains the other features, and refuses
a signing certificate different from the installed app. Historical `build.py`
files are provenance, not the current release command.

See [visual-update/README.md](visual-update/README.md) for build and verification.
