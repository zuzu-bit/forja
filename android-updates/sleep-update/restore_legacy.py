"""Patch the exact v26 legacy sleep card to observe the current recorder state."""
from pathlib import Path


def patch_sleep(work):
    screen = Path(work) / 'smali6/com/forja/app/feature/sleep/SleepScreenKt.smali'
    source = screen.read_text()
    original = 'invoke-interface {v2}, Lcom/forja/app/core/data/db/SleepDao;->activeSession()Lkotlinx/coroutines/flow/Flow;'
    replacement = 'invoke-static {v14}, Lcom/forja/app/feature/cleanup/SleepLegacyBridge;->activeSession(Landroid/content/Context;)Lkotlinx/coroutines/flow/Flow;'
    assert source.count(original) == 1, 'Expected the unchanged v26 active-session observer'
    start = source.index('.method public static final SleepScreenLegacy(')
    end = source.index('.end method', start)
    method = source[start:end]
    assert original in method and '.local v14, "context":Landroid/content/Context;' in method
    assert method.index('.local v14, "context":Landroid/content/Context;') < method.index(original)
    screen.write_text(source.replace(original, replacement))


patch = patch_sleep

if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('work', type=Path)
    patch_sleep(parser.parse_args().work)
