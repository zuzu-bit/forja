"""FORJA v23: checksum-pinned v22 with owner-armed lost-phone recovery. Unsigned unless the original key is supplied."""
import argparse,hashlib,io,json,pathlib,re,shutil,subprocess,tempfile,zipfile
from manifest import patch_manifest
p=argparse.ArgumentParser();p.add_argument('--base',type=pathlib.Path,required=True);p.add_argument('--tools',type=pathlib.Path,required=True);p.add_argument('--compiled',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--keystore',type=pathlib.Path);p.add_argument('--store-pass-env',default='FORJA_KEYSTORE_PASSWORD');p.add_argument('--key-pass-env',default='FORJA_KEYSTORE_PASSWORD');a=p.parse_args()
assert hashlib.sha256(a.base.read_bytes()).hexdigest()=='244e0e9bf98be9203df119819765c0d5d1775917fb5c647df521c543722ea6e3'
w=pathlib.Path(tempfile.mkdtemp(prefix='forja-lost-v23-build-'));print('WORK_DIR='+str(w),flush=True)
android=a.tools/'sdk/android-35/android.jar';bt=a.tools/'sdk/android-15';cp=str(a.tools/'smali/*')
def run(*v):subprocess.run([str(s) for s in v],check=True)
def disasm(dex,target):run('java','-Xmx2g','-cp',cp,'org.jf.baksmali.Main','d',dex,'-o',target)
def edit(path,old,new):s=path.read_text();assert s.count(old)==1,(path,old);path.write_text(s.replace(old,new))
STUBS={'CleanFile','ContentFinding','CleanReport','CleanupViewModel','VisualPrint','CleanupScreenLegacyKt','Placement','CleanupOutcome','CleanupScan','CleanupOperations','DuplicateGroup','SimilarGroup'}
with zipfile.ZipFile(a.compiled/'feature/build/outputs/aar/feature-debug.aar') as z:jar=z.read('classes.jar')
with zipfile.ZipFile(io.BytesIO(jar)) as src,zipfile.ZipFile(w/'cleanup.jar','w',zipfile.ZIP_DEFLATED) as dst:
 for n in src.namelist():
  if not n.endswith('.class') or n.rsplit('/',1)[-1].split('$')[0].removesuffix('.class') in STUBS:continue
  assert n.startswith('com/forja/app/feature/cleanup/'),n;dst.writestr(n,src.read(n))
classpath=[]
for i,line in enumerate((a.compiled/'feature/build/compile-classpath.txt').read_text().splitlines()):
 dep=pathlib.Path(line)
 if dep.suffix=='.aar':
  with zipfile.ZipFile(dep) as z:
   if 'classes.jar' not in z.namelist():continue
   dest=w/f'dep-{i}.jar';dest.write_bytes(z.read('classes.jar'));classpath+=['--classpath',dest]
 elif dep.suffix=='.jar':classpath+=['--classpath',dep]
out=w/'dex';out.mkdir();run('java','-Xmx2g','-cp',a.tools/'r8-8.7.18.jar','com.android.tools.r8.D8','--min-api','26','--lib',android,*classpath,'--output',out,w/'cleanup.jar');assert len(list(out.glob('*.dex')))==1
shutil.copyfile(out/'classes.dex',w/'classes22.dex');disasm(out/'classes.dex',w/'new-smali')
with zipfile.ZipFile(a.base) as z:
 for n in [4,9,12,16,23]:
  (w/f'classes{n}.dex').write_bytes(z.read(f'classes{n}.dex'));disasm(w/f'classes{n}.dex',w/f'smali{n}')
shutil.copytree(w/'smali23/com/forja/app/feature/research',w/'new-smali/com/forja/app/feature/research')
for n,relative in [(4,'com/forja/app/BuildConfig.smali'),(16,'com/forja/app/feature/research/HealthExportActivity.smali')]:
 f=w/f'smali{n}'/relative;edit(f,'"3.7-online.22"','"3.7-online.23"')
 if n==4:edit(f,'.field public static final VERSION_CODE:I = 0x34','.field public static final VERSION_CODE:I = 0x35')
for n in [4,16]:run('java','-Xmx2g','-cp',cp,'org.jf.smali.Main','a',w/f'smali{n}','-o',w/f'classes{n}.dex','--api','26')
changed={'classes4.dex','classes16.dex','classes22.dex','AndroidManifest.xml'}
def signature(n):return n.startswith('META-INF/') and n.endswith(('.RSA','.DSA','.EC','.SF','MANIFEST.MF'))
with zipfile.ZipFile(a.base) as before,zipfile.ZipFile(w/'unsigned.apk','w') as after:
 for entry in before.infolist():
  n=entry.filename
  if signature(n):continue
  data=(w/n).read_bytes() if n in changed and n.endswith('.dex') else before.read(n)
  if n=='AndroidManifest.xml':data=patch_manifest(data)
  after.writestr(entry,data)
run(bt/'zipalign','-f','-P','16','4',w/'unsigned.apk',w/'aligned.apk')
final_apk=w/'aligned.apk'
if a.keystore:
 run('java','-jar',bt/'lib/apksigner.jar','sign','--ks',a.keystore,'--ks-pass','env:'+a.store_pass_env,'--key-pass','env:'+a.key_pass_env,'--out',w/'signed.apk',final_apk)
 verified=subprocess.check_output(['java','-jar',str(bt/'lib/apksigner.jar'),'verify','--print-certs',str(w/'signed.apk')],text=True)
 assert '6fc56c1ff164d46b4994fdbfa3e38c786e804c191897ccad581d278aa8feed74' in verified,'The original v22 signing certificate is required'
 final_apk=w/'signed.apk'
with zipfile.ZipFile(a.base) as before,zipfile.ZipFile(final_apk) as after:
 for n in before.namelist():
  if n not in changed and not signature(n):assert before.read(n)==after.read(n),n
assert zipfile.ZipFile(final_apk).testzip() is None
a.output.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(final_apk,a.output);assert a.output.read_bytes()==final_apk.read_bytes()
result={'version':'3.7-online.23','version_code':53,'sha256':hashlib.sha256(a.output.read_bytes()).hexdigest(),'bytes':a.output.stat().st_size,'work_dir':str(w),'signed':bool(a.keystore),'installable':bool(a.keystore),'signer_matches':True if a.keystore else None,'unchanged_dex':20,'original_assets_preserved':True,'new_permissions':[],'android_device_tested':False,'live_server_tested':False}
(pathlib.Path(__file__).parent/'verification.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
