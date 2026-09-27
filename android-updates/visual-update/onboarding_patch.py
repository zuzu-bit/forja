"""Resume explicitly requested sync setup after the existing account sign-in.

Only the pinned MainActivity lifecycle method receives one static observer call.
The preserved controller still owns permissions, saving and its seen marker.
"""
from pathlib import Path

CALL = "    invoke-static {p0}, Lcom/forja/app/feature/cleanup/PendingSyncReturn;->onHostResume(Landroid/app/Activity;)V"


def patch(smali4, smali9=None, smali12=None, smali16=None):
    path = Path(smali4) / "com/forja/app/MainActivity.smali"
    source = path.read_text()
    anchor = "    invoke-super {p0}, Landroidx/activity/ComponentActivity;->onPostResume()V"
    assert source.count(anchor) == 1, "Unexpected MainActivity lifecycle"
    assert CALL not in source, "Onboarding patch already applied"
    signature = ".method protected onPostResume()V"
    assert source.count(signature) == 1
    before, after = source.split(signature, 1)
    assert anchor in after.split(".end method", 1)[0]
    path.write_text(before + signature + after.replace(anchor, anchor + "\n\n" + CALL, 1))
