"""Replace only the v23 permission presentation; retain its grants and collection controller."""
from pathlib import Path
import re


def patch(smali16_dir):
    path = Path(smali16_dir) / 'com/forja/app/feature/research/ResearchExportActivity.smali'
    source = path.read_text()
    required = [
        '.method private final getSelected()Ljava/util/Set;',
        '.method private final getBackground()Z',
        '.method private final getNotice()Ljava/lang/String;',
        '.method private final getStatus()Ljava/lang/String;',
        '.method private final toggle(Ljava/lang/String;Z)V',
        '.method private final setBackground(Z)V',
        '.method private final applyChoices()V',
    ]
    for signature in required:
        assert source.count(signature) == 1, f'Unexpected v23 controller: {signature}'
    signature = '.method private final Screen(Landroidx/compose/runtime/Composer;I)V'
    pattern = re.escape(signature) + r'\n.*?\.end method'
    replacement = signature + '''
    .registers 3
    invoke-static {p0, p1, p2}, Lcom/forja/app/feature/cleanup/PermissionHubKt;->PermissionHub(Landroid/app/Activity;Landroidx/compose/runtime/Composer;I)V
    return-void
.end method'''
    changed, count = re.subn(pattern, lambda _: replacement, source, flags=re.S)
    assert count == 1, 'Expected exactly one permission Screen entrypoint'
    path.write_text(changed)
