"""Constrained audio update on the exact v17 APK. Foreground start and explicit readiness remain distinct; permissions and ownership are enforced."""
import argparse, hashlib, json, os, pathlib, re, shutil, struct, subprocess, tempfile, zipfile
p=argparse.ArgumentParser();p.add_argument('--base',type=pathlib.Path,required=True);p.add_argument('--tools',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);args=p.parse_args()
root=pathlib.Path(__file__).resolve().parent
assert hashlib.sha256(args.base.read_bytes()).hexdigest()=='2e95b91c19cbb07f2854f6ba1945975fd554bcf453c84ea89bb327ceb579b0fa'
w=pathlib.Path(tempfile.mkdtemp(prefix='forja-audio-v19-'));print('WORK_DIR='+str(w),flush=True)
sdk=args.tools/'sdk';android=sdk/'android-35/android.jar';bt=sdk/'android-15';cp=str(args.tools/'smali/*')
def run(*a): subprocess.run([str(v) for v in a],check=True)
def write(path,s):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(s)
stubs={
'com/google/firebase/auth/FirebaseAuth.java':'''package com.google.firebase.auth; public class FirebaseAuth {public static FirebaseAuth getInstance(){return null;} public FirebaseUser getCurrentUser(){return null;} public void addAuthStateListener(AuthStateListener l){} public void removeAuthStateListener(AuthStateListener l){} public interface AuthStateListener{void onAuthStateChanged(FirebaseAuth a);}}''',
'com/google/firebase/auth/FirebaseUser.java':'''package com.google.firebase.auth; public class FirebaseUser {public String getUid(){return null;}}''',
'com/forja/app/core/data/CollectionSettings.java':'''package com.forja.app.core.data; public class CollectionSettings {public static CollectionSettings INSTANCE; public boolean getVisible(){return false;} public android.content.SharedPreferences prefs(android.content.Context c){return null;}}''',
'com/forja/app/core/data/WebAccountControl.java':'''package com.forja.app.core.data; public class WebAccountControl {public static WebAccountControl INSTANCE; public boolean enabled(android.content.Context c){return false;} public void allow(android.content.Context c){} public void revoke(android.content.Context c){} public void recordingChanged(android.content.Context c){}}''',
'com/forja/app/feature/research/TimedRecording.java':'''package com.forja.app.feature.research; public class TimedRecording {public TimedRecording(String id,String owner,long epoch,long from,long until,long duration,String state,boolean sync,String note){} public long getEpoch(){return 0;}public long getUntil(){return 0;}public String getOwner(){return null;} public String getId(){return null;} public String getState(){return null;} public String getNote(){return null;} public long getFrom(){return 0;}}''',
'com/forja/app/feature/research/RecordingStore.java':'''package com.forja.app.feature.research; public class RecordingStore {public static RecordingStore INSTANCE;public long epoch(android.content.Context c){return 0;}public void save(android.content.Context c,TimedRecording r){}public void enqueue(android.content.Context c,TimedRecording r){}public java.util.List<TimedRecording> all(android.content.Context c){return null;}public java.io.File file(android.content.Context c,String id){return null;}public void recover(android.content.Context c){}public boolean authorized(android.content.Context c,TimedRecording r){return false;}public void retry(android.content.Context c,String id){}public void deleteLocal(android.content.Context c,String id){}}'''
}
for n,s in stubs.items():write(w/'stubs'/n,s)
classes=w/'classes';classes.mkdir()
run('java','com.sun.tools.javac.Main','-source','8','-target','8','-encoding','UTF-8','-classpath',android,'-d',classes,*sorted((w/'stubs').rglob('*.java')),*sorted((root/'src').rglob('*.java')))
run('java','com.sun.tools.javac.Main','-source','8','-target','8','-d',w/'tests',root/'src/com/forja/app/feature/research/ErrorText.java',root/'tests/ErrorTextTest.java',root/'src/com/forja/app/feature/research/AudioReadyPolicy.java',root/'tests/AudioReadyPolicyTest.java')
run('java','-cp',w/'tests','ErrorTextTest')
run('java','-cp',w/'tests','AudioReadyPolicyTest')
allowed={'AudioDiagnostics','ErrorText','WebPairActivity','TimedRecordingService','AudioReadyPolicy','AudioWebStatus'}
with zipfile.ZipFile(w/'feature.jar','w',zipfile.ZIP_DEFLATED) as z:
 for f in classes.rglob('*.class'):
  if f.stem.split('$')[0] in allowed:z.write(f,f.relative_to(classes))
(w/'new-dex').mkdir()
run('java','-Xmx2g','-cp',args.tools/'r8-8.7.18.jar','com.android.tools.r8.D8','--min-api','26','--lib',android,'--output',w/'new-dex',w/'feature.jar')
def disasm(dex,out):run('java','-Xmx2g','-cp',cp,'org.jf.baksmali.Main','d',dex,'-o',out)
with zipfile.ZipFile(args.base) as z:
 for n in [4,11,12,16]:
  (w/f'classes{n}.dex').write_bytes(z.read(f'classes{n}.dex'));disasm(w/f'classes{n}.dex',w/f'smali{n}')
disasm(w/'new-dex/classes.dex',w/'new-smali')
def edit(path,old,new,count=1):
 s=path.read_text();assert s.count(old)==count,(path,old,s.count(old));path.write_text(s.replace(old,new))
diag='Lcom/forja/app/feature/research/AudioDiagnostics;'
app=w/'smali4/com/forja/app/ForjaApp.smali'
old='    invoke-virtual {v0, v1}, Lcom/forja/app/core/data/WebAccountControl;->install(Landroid/app/Application;)V'
edit(app,old,old+'\n\n    invoke-static {v1}, '+diag+'->install(Landroid/content/Context;)V')
poll=w/'smali12/com/forja/app/core/data/WebAccountControl$poll$1.smali'
s=poll.read_text();labels=set(re.findall(r'\.catch Ljava/lang/Exception; \{[^}]+\} (:\w+)',s));assert labels
for label in labels:
 pattern=re.escape(label)+r'\n    move-exception v0'
 s,n=re.subn(pattern,lambda m:m.group(0)+'\n\n    invoke-static {v0}, '+diag+'->linkError(Ljava/lang/Throwable;)V',s);assert n==1,(label,n)
poll.write_text(s)
edit(poll,'    .local v5, "data":Lorg/json/JSONObject;','    .local v5, "data":Lorg/json/JSONObject;\n\n    invoke-static {v5}, '+diag+'->linkOkay(Lorg/json/JSONObject;)V')
callback=w/'smali12/com/forja/app/core/data/AccountWebApi$request$result$1$2.smali'
old='    new-instance v3, Lorg/json/JSONObject;'
edit(callback,old,'    invoke-virtual {v1}, Lokhttp3/Response;->code()I\n    move-result v3\n    invoke-static {v3}, Lcom/forja/app/feature/research/ErrorText;->requireHttpSuccess(I)V\n\n'+old)
sleep=w/'smali11/com/forja/app/core/sleep/SleepTrackService.smali'
old='    invoke-virtual {v2}, Landroid/media/AudioRecord;->startRecording()V'
edit(sleep,old,old+'\n\n    invoke-static {}, '+diag+'->sleepStarted()V')
for signature,registers in [('private final finishSession()V',12),('public onDestroy()V',3)]:
 s=sleep.read_text();pattern=r'(\.method '+re.escape(signature)+r'\n    \.registers \d+\n)'
 s,n=re.subn(pattern,lambda m:m.group(0)+'\n    invoke-static {}, '+diag+'->sleepStopped()V\n',s);assert n==1;sleep.write_text(s)
# Replace the recorder implementation, preserving its public Companion ABI.
for f in (w/'smali16/com/forja/app/feature/research').glob('TimedRecordingService*.smali'):f.unlink()
service_type='Lcom/forja/app/feature/research/TimedRecordingService;'
# Keep foreground truthful; readiness is a separate capability of a live promoted service.
s=poll.read_text()
old='    invoke-virtual {v0}, Lcom/forja/app/core/data/CollectionSettings;->getVisible()Z'
assert s.count(old)==1
s=s.replace(old,old+'\n    move-result v0\n    if-nez v0, :cond_156\n    invoke-static {}, '+service_type+'->isReady()Z')
old='    invoke-virtual {v2}, Lcom/forja/app/core/data/CollectionSettings;->getVisible()Z\n\n    move-result v34'
assert s.count(old)==1
s=s.replace(old,'    invoke-static {}, '+service_type+'->isReady()Z\n\n    move-result v34')
old='    .line 92\n    const-string v3, "state"'
assert s.count(old)==1
s=s.replace(old,'    invoke-static {v0}, Lcom/forja/app/feature/research/AudioWebStatus;->decorate(Lorg/json/JSONObject;)Lorg/json/JSONObject;\n    move-result-object v0\n\n'+old)
old='    invoke-static {v0, v4}, Landroidx/core/content/ContextCompat;->startForegroundService(Landroid/content/Context;Landroid/content/Intent;)V'
assert s.count(old)==1;s=s.replace(old,'    invoke-static {v0, v4}, '+service_type+'->dispatchWeb(Landroid/content/Context;Landroid/content/Intent;)V')
# Filter the returned command by the current explicitly armed session. The existing
# poll runs on Main.immediate; the start branch has no suspension before dispatchWeb.
old='    .local v2, "command":Lorg/json/JSONObject;\n    if-eqz v2, :cond_47d'
assert s.count(old)==1
s=s.replace(old,'    invoke-static {v2}, Lcom/forja/app/feature/research/AudioWebStatus;->filterCommand(Lorg/json/JSONObject;)Lorg/json/JSONObject;\n    move-result-object v2\n\n'+old)
poll.write_text(s)
for f in (w/'smali16/com/forja/app/feature/research').glob('WebPairActivity*.smali'):f.unlink()
for n,relative in [(4,'com/forja/app/BuildConfig.smali'),(16,'com/forja/app/feature/research/HealthExportActivity.smali')]:
 f=w/f'smali{n}'/relative;edit(f,'"3.7-online.17"','"3.7-online.19"')
 if n==4:edit(f,'.field public static final VERSION_CODE:I = 0x2f','.field public static final VERSION_CODE:I = 0x31')
for n in [4,11,12,16]:run('java','-Xmx2g','-cp',cp,'org.jf.smali.Main','a',w/f'smali{n}','-o',w/f'classes{n}.dex','--api','26')
# Exact, same-length metadata changes in binary XML; service declarations and permissions stay intact.
def manifest_version(data):
 b=bytearray(data);offset=8;strings=[];code=False;name=False
 while offset<len(b):
  typ,head,size=struct.unpack_from('<HHI',b,offset)
  if typ==1:
   count,styles,flags,start,style_start=struct.unpack_from('<IIIII',b,offset+8);utf8=bool(flags&0x100)
   def length(pos):
    if utf8:
     n=b[pos];return (((n&127)<<8)|b[pos+1],pos+2) if n&128 else (n,pos+1)
    n=struct.unpack_from('<H',b,pos)[0];return (((n&32767)<<16)|struct.unpack_from('<H',b,pos+2)[0],pos+4) if n&32768 else (n,pos+2)
   for i in range(count):
    pos=offset+start+struct.unpack_from('<I',b,offset+head+4*i)[0];n,pos=length(pos)
    if utf8:n,pos=length(pos)
    raw=bytes(b[pos:pos+n*(1 if utf8 else 2)]);text=raw.decode('utf-8' if utf8 else 'utf-16le');strings.append(text)
    if text=='3.7-online.17':
     replacement='3.7-online.19'.encode('utf-8' if utf8 else 'utf-16le');assert len(replacement)==len(raw);b[pos:pos+len(raw)]=replacement;name=True
  elif typ==0x102:
   ns,key,astart,asize,acount=struct.unpack_from('<IIHHH',b,offset+16)
   if strings[key]=='manifest':
    for i in range(acount):
     pos=offset+16+astart+i*asize;_,key,_=struct.unpack_from('<III',b,pos)
     if strings[key]=='versionCode':
      assert b[pos+15]==0x10 and struct.unpack_from('<I',b,pos+16)[0]==47
      struct.pack_into('<I',b,pos+16,49);code=True
  offset+=size
 assert code and name;return bytes(b)
changed={'classes4.dex','classes11.dex','classes12.dex','classes16.dex','AndroidManifest.xml'}
def signature(n):return n.startswith('META-INF/') and n.endswith(('.RSA','.DSA','.EC','.SF','MANIFEST.MF'))
with zipfile.ZipFile(args.base) as before,zipfile.ZipFile(w/'unsigned.apk','w') as after:
 for entry in before.infolist():
  name=entry.filename
  if signature(name):continue
  data=(w/name).read_bytes() if name in changed and name.endswith('.dex') else before.read(name)
  if name=='AndroidManifest.xml':data=manifest_version(data)
  after.writestr(entry,data)
 after.writestr('classes23.dex',(w/'new-dex/classes.dex').read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
(bt/'zipalign').chmod(0o755)
run(bt/'zipalign','-f','-P','16','4',w/'unsigned.apk',w/'aligned.apk')
args.output.parent.mkdir(parents=True,exist_ok=True)
run('java','-jar',bt/'lib/apksigner.jar','sign','--ks',args.tools/'debug.keystore','--ks-pass','pass:android','--key-pass','pass:android','--out',args.output,w/'aligned.apk')
verified=subprocess.check_output(['java','-jar',str(bt/'lib/apksigner.jar'),'verify','--print-certs',str(args.output)],text=True)
assert '6fc56c1ff164d46b4994fdbfa3e38c786e804c191897ccad581d278aa8feed74' in verified,'Signer must match the installed APK'
with zipfile.ZipFile(args.base) as before,zipfile.ZipFile(args.output) as after:
 for name in before.namelist():
  if name not in changed and not signature(name):assert before.read(name)==after.read(name),name
result={'version':'3.7-online.19','version_code':49,'sha256':hashlib.sha256(args.output.read_bytes()).hexdigest(),'bytes':args.output.stat().st_size,'work_dir':str(w),'signer_matches':True,'unchanged_dex':18,'original_assets_preserved':True,'android_device_tested':False,'live_server_tested':False,'diagnostic_checks':24,'readiness_policy_checks':26}
(root/'verification.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
