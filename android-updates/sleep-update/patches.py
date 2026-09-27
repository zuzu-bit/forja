"""Version-pinned routing and upload hooks; original UI, recorder queue and alarms retained."""
from pathlib import Path
import re


def patch_sleep(work):
    work=Path(work)
    folder=work/'smali6/com/forja/app/feature/sleep'
    screen=folder/'SleepScreenKt.smali'
    body=screen.read_text()
    signature='.method public static final SleepScreen(Landroidx/compose/runtime/Composer;I)V'
    assert body.count(signature)==1
    body=body.replace(signature,signature.replace('SleepScreen(', 'SleepScreenLegacy('))
    screen.write_text(body)
    for path in folder.glob('*.smali'):
        source=path.read_text()
        path.write_text(source.replace('Lcom/forja/app/feature/sleep/SleepScreenKt;->SleepScreen(', 'Lcom/forja/app/feature/sleep/SleepScreenKt;->SleepScreenLegacy('))
    screen.write_text(screen.read_text()+'''\n
.method public static final SleepScreen(Landroidx/compose/runtime/Composer;I)V
    .registers 2
    invoke-static {p0, p1}, Lcom/forja/app/feature/cleanup/SleepHubKt;->SleepHub(Landroidx/compose/runtime/Composer;I)V
    return-void
.end method
''')
    body=screen.read_text()
    marker=':goto_9d3\n    int-to-float v2, v1'
    assert body.count(marker)==1
    screen.write_text(body.replace(marker,':goto_9d3\n    const/4 v2, 0x0\n    invoke-static {v1, v2}, Ljava/lang/Math;->max(II)I\n    move-result v2\n    int-to-float v2, v2'))
    score=folder/'SleepScreenKt$SleepScreen$1$1$2$1.smali'
    body=score.read_text()
    marker='invoke-static {v5}, Ljava/lang/String;->valueOf(I)Ljava/lang/String;'
    assert body.count(marker)==1
    score.write_text(body.replace(marker,'invoke-static {v5}, Lcom/forja/app/feature/cleanup/SleepBridge;->scoreText(I)Ljava/lang/String;'))
    phases=folder/'SleepScreenKt$SleepScreen$1$10.smali'
    body=phases.read_text()
    for name in ['Deep','Light','Rem']:
        marker=f'invoke-virtual/range {{v25 .. v25}}, Lcom/forja/app/core/data/db/SleepSessionEntity;->get{name}Min()I\n\n    move-result v1\n\n    invoke-virtual {{v0, v1}}, Lcom/forja/app/core/util/Fmt;->durationHm(I)Ljava/lang/String;\n\n    move-result-object v29'
        assert body.count(marker)==1
        known=f'forja_{name}_known';done=f'forja_{name}_done'
        replacement=marker.replace('    invoke-virtual {v0, v1}',f'    if-gez v1, :{known}\n    const-string v29, "—"\n    goto :{done}\n    :{known}\n    invoke-virtual {{v0, v1}}')+f'\n    :{done}'
        body=body.replace(marker,replacement)
    phases.write_text(body)
    companion=work/'smali11/com/forja/app/core/sleep/SleepTrackService$Companion.smali'
    body=companion.read_text()
    for old,new in [('start','legacyStart'),('stop','stop')]:
        signature=f'.method public final {old}(Landroid/content/Context;)V'
        replacement=signature+f'''\n    .registers 2
    invoke-static {{p1}}, Lcom/forja/app/feature/research/SleepAudioState;->{new}(Landroid/content/Context;)V
    return-void
.end method'''
        body,count=re.subn(re.escape(signature)+r'\n.*?\.end method',lambda _:replacement,body,flags=re.S)
        assert count==1
    companion.write_text(body)
    worker=work/'smali16/com/forja/app/feature/research/RecordingUploadWorker.smali'
    body=worker.read_text()
    marker='    invoke-virtual {v11, v13}, Lcom/forja/app/feature/research/ResearchTransport;->openRecording(Ljava/lang/String;)V'
    assert body.count(marker)==1
    body=body.replace(marker,'    invoke-static {v4, v13}, Lcom/forja/app/feature/cleanup/SleepBridge;->reserveBeforeUpload(Landroid/content/Context;Ljava/lang/String;)V\n\n'+marker)
    worker.write_text(body)
