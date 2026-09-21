"""FORJA v20: compile Java + Kotlin feature updates against checksum-pinned v19.
Replaces cleanup DEX22 and audio/setup DEX23. Version metadata and lifecycle
installation are the only changes in DEX4/16. All other code/assets are retained.
"""
import argparse,ast,hashlib,io,json,pathlib,re,shutil,struct,subprocess,tempfile,zipfile
P=argparse.ArgumentParser();P.add_argument('--base',type=pathlib.Path,required=True);P.add_argument('--tools',type=pathlib.Path,required=True);P.add_argument('--compiled',type=pathlib.Path);P.add_argument('--output',type=pathlib.Path,required=True);a=P.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];cleanup=root/'audio-diagnosis/forja-v17/cleanup-update';audio=root/'audio-update';compiled=a.compiled or cleanup
assert hashlib.sha256(a.base.read_bytes()).hexdigest()=='48da9bf3459901972194e21678b8d9171b4280a32786e0b0171252ab29463196','Expected v19 base APK'
w=pathlib.Path(tempfile.mkdtemp(prefix='forja-v20-'));print('WORK_DIR='+str(w),flush=True)
android=a.tools/'sdk/android-35/android.jar';bt=a.tools/'sdk/android-15';cp=str(a.tools/'smali/*')
def run(*args):subprocess.run([str(v) for v in args],check=True)
def disasm(dex,out):run('java','-Xmx2g','-cp',cp,'org.jf.baksmali.Main','d',dex,'-o',out)
def edit(path,old,new,count=1):s=path.read_text();assert s.count(old)==count,(path,old,s.count(old));path.write_text(s.replace(old,new))
# Known compile-only signatures, verified against all actual classes in the final APK.
stubs=next(ast.literal_eval(n.value) for n in ast.parse((audio/'build.py').read_text()).body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='stubs' for t in n.targets))
stubs['com/forja/app/feature/cleanup/OrganizerBridge.java']='''package com.forja.app.feature.cleanup;public final class OrganizerBridge {public static boolean enabled(android.content.Context c){return false;}public static String status(android.content.Context c){return null;}public static String tree(android.content.Context c){return null;}public static void check(android.content.Context c){}public static void activate(android.content.Context c,boolean photos,boolean files,String tree,boolean moves,java.util.function.Consumer<String> callback){}public static void stop(android.content.Context c,java.util.function.Consumer<String> callback){}}'''
for name,s in stubs.items():p=w/'stubs'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
classes=w/'classes';classes.mkdir();run('java','com.sun.tools.javac.Main','-source','8','-target','8','-encoding','UTF-8','-classpath',android,'-d',classes,*sorted((w/'stubs').rglob('*.java')),*sorted((audio/'src').rglob('*.java')))
allowed={'AudioDiagnostics','ErrorText','WebPairActivity','TimedRecordingService','AudioReadyPolicy','AudioWebStatus'}
with zipfile.ZipFile(w/'audio.jar','w',zipfile.ZIP_DEFLATED) as dst:
 for p in classes.rglob('*.class'):
  if p.stem.split('$')[0] in allowed:dst.write(p,p.relative_to(classes))
STUBS={'CleanFile','ContentFinding','CleanReport','CleanupViewModel','VisualPrint','CleanupScreenLegacyKt','Placement','CleanupOutcome','CleanupScan','CleanupOperations','DuplicateGroup','SimilarGroup'}
with zipfile.ZipFile(compiled/'feature/build/outputs/aar/feature-debug.aar') as z:jar=z.read('classes.jar')
with zipfile.ZipFile(io.BytesIO(jar)) as src,zipfile.ZipFile(w/'cleanup.jar','w',zipfile.ZIP_DEFLATED) as dst:
 for n in src.namelist():
  if not n.endswith('.class') or n.rsplit('/',1)[-1].split('$')[0].removesuffix('.class') in STUBS:continue
  assert n.startswith('com/forja/app/feature/cleanup/'),n;dst.writestr(n,src.read(n))
classpath=[]
for i,line in enumerate((compiled/'feature/build/compile-classpath.txt').read_text().splitlines()):
 dep=pathlib.Path(line)
 if dep.suffix=='.aar':
  with zipfile.ZipFile(dep) as z:
   if 'classes.jar' not in z.namelist():continue
   dest=w/f'dep-{i}.jar';dest.write_bytes(z.read('classes.jar'));classpath+=['--classpath',dest]
 elif dep.suffix=='.jar':classpath+=['--classpath',dep]
for name,number in [('cleanup',22),('audio',23)]:
 out=w/f'dex{number}';out.mkdir();run('java','-Xmx2g','-cp',a.tools/'r8-8.7.18.jar','com.android.tools.r8.D8','--min-api','26','--lib',android,*classpath,'--output',out,w/f'{name}.jar');assert len(list(out.glob('*.dex')))==1
 shutil.copyfile(out/'classes.dex',w/f'classes{number}.dex');disasm(out/'classes.dex',w/f'new{number}')
shutil.copytree(w/'new22',w/'new-smali');shutil.copytree(w/'new23',w/'new-smali',dirs_exist_ok=True)
with zipfile.ZipFile(a.base) as z:
 for n in [4,12,16]:
  (w/f'classes{n}.dex').write_bytes(z.read(f'classes{n}.dex'));disasm(w/f'classes{n}.dex',w/f'smali{n}')
app=w/'smali4/com/forja/app/ForjaApp.smali';hook='    invoke-static {v1}, Lcom/forja/app/feature/research/AudioDiagnostics;->install(Landroid/content/Context;)V'
edit(app,hook,hook+'\n\n    invoke-static {v1}, Lcom/forja/app/feature/cleanup/OrganizerBridge;->install(Landroid/app/Application;)V')
for n,relative in [(4,'com/forja/app/BuildConfig.smali'),(16,'com/forja/app/feature/research/HealthExportActivity.smali')]:
 p=w/f'smali{n}'/relative;edit(p,'"3.7-online.19"','"3.7-online.20"')
 if n==4:edit(p,'.field public static final VERSION_CODE:I = 0x31','.field public static final VERSION_CODE:I = 0x32')
 run('java','-Xmx2g','-cp',cp,'org.jf.smali.Main','a',w/f'smali{n}','-o',w/f'classes{n}.dex','--api','26')
# Reuse the checked binary XML parser, changing only the asserted version pair.
source=(audio/'build.py').read_text();start=source.index('def manifest_version(');end=source.index('\nchanged=',start);method=source[start:end].replace("text=='3.7-online.17'","text=='3.7-online.19'").replace("replacement='3.7-online.19'","replacement='3.7-online.20'").replace('==47','==49').replace('pos+16,49','pos+16,50');exec(compile(method,'manifest_version','exec'))
changed={'classes4.dex','classes16.dex','classes22.dex','classes23.dex','AndroidManifest.xml'}
def signature(n):return n.startswith('META-INF/') and n.endswith(('.RSA','.DSA','.EC','.SF','MANIFEST.MF'))
with zipfile.ZipFile(a.base) as before,zipfile.ZipFile(w/'unsigned.apk','w') as after:
 for entry in before.infolist():
  n=entry.filename
  if signature(n):continue
  data=(w/n).read_bytes() if n in changed and n.endswith('.dex') else before.read(n)
  if n=='AndroidManifest.xml':data=manifest_version(data)
  after.writestr(entry,data)
run(bt/'zipalign','-f','-P','16','4',w/'unsigned.apk',w/'aligned.apk');a.output.parent.mkdir(parents=True,exist_ok=True)
run('java','-jar',bt/'lib/apksigner.jar','sign','--ks',a.tools/'debug.keystore','--ks-pass','pass:android','--key-pass','pass:android','--out',w/'signed.apk',w/'aligned.apk')
verified=subprocess.check_output(['java','-jar',str(bt/'lib/apksigner.jar'),'verify','--print-certs',str(w/'signed.apk')],text=True);assert '6fc56c1ff164d46b4994fdbfa3e38c786e804c191897ccad581d278aa8feed74' in verified
with zipfile.ZipFile(a.base) as before,zipfile.ZipFile(w/'signed.apk') as after:
 for n in before.namelist():
  if n not in changed and not signature(n):assert before.read(n)==after.read(n),n
shutil.copyfile(w/'signed.apk',a.output)
assert a.output.read_bytes()==(w/'signed.apk').read_bytes()
result={'version':'3.7-online.20','version_code':50,'sha256':hashlib.sha256(a.output.read_bytes()).hexdigest(),'bytes':a.output.stat().st_size,'work_dir':str(w),'signer_matches':True,'unchanged_dex':19,'original_assets_preserved':True,'android_device_tested':False,'live_server_tested':False}
(root/'organizer-update/verification.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
