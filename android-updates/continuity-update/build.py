"""Build v26 strictly on the signed delivered v25, preserving every non-allowlisted entry."""
import argparse,hashlib,io,json,pathlib,re,shutil,subprocess,tempfile,zipfile,os
from manifest import patch as patch_manifest
ROOT=pathlib.Path(__file__).resolve().parent
BASE='b569e767f88de7f3a7825580313efe4f0f9594a9340f994524e146bf566a0ecd'
SIGNER='6fc56c1ff164d46b4994fdbfa3e38c786e804c191897ccad581d278aa8feed74'
STUBS={'CleanFile','ContentFinding','CleanReport','CleanupViewModel','VisualPrint','CleanupScreenLegacyKt','Placement','CleanupOutcome','CleanupScan','CleanupOperations','DuplicateGroup','SimilarGroup','SleepScreenKt'}
def run(*args):
 executable=pathlib.Path(args[0])
 if executable.is_file():executable.chmod(executable.stat().st_mode|0o100)
 subprocess.run([str(x)for x in args],check=True)
def signature(n):return n.startswith('META-INF/') and n.endswith(('.RSA','.DSA','.EC','.SF','MANIFEST.MF'))
def main():
 p=argparse.ArgumentParser();p.add_argument('--base',type=pathlib.Path,required=True);p.add_argument('--tools',type=pathlib.Path,required=True);p.add_argument('--compiled',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--keystore',type=pathlib.Path,required=True);p.add_argument('--store-pass-env',default='FORJA_KEYSTORE_PASSWORD');a=p.parse_args()
 assert hashlib.sha256(a.base.read_bytes()).hexdigest()==BASE
 work=pathlib.Path(tempfile.mkdtemp(prefix='forja-v26-'));print('WORK_DIR='+str(work),flush=True)
 android=a.tools/'sdk/android-35/android.jar';bt=a.tools/'sdk/android-15';smali=str(a.tools/'smali/*')
 def disassemble(dex,dest):run('java','-Xmx2g','-cp',smali,'org.jf.baksmali.Main','d',dex,'-o',dest)
 with zipfile.ZipFile(a.compiled/'feature/build/outputs/aar/feature-debug.aar')as z:body=z.read('classes.jar')
 with zipfile.ZipFile(io.BytesIO(body))as src,zipfile.ZipFile(work/'feature.jar','w',zipfile.ZIP_DEFLATED)as dst:
  for n in src.namelist():
   if not n.endswith('.class')or n.rsplit('/',1)[-1].split('$')[0].removesuffix('.class')in STUBS:continue
   assert n.startswith('com/forja/app/feature/cleanup/'),n;dst.writestr(n,src.read(n))
 cp=[]
 for i,line in enumerate((a.compiled/'feature/build/compile-classpath.txt').read_text().splitlines()):
  dep=pathlib.Path(line)
  if dep.suffix=='.aar':
   with zipfile.ZipFile(dep)as z:
    if 'classes.jar'not in z.namelist():continue
    dep=work/f'dependency-{i}.jar';dep.write_bytes(z.read('classes.jar'))
  if dep.suffix=='.jar':cp+=['--classpath',dep]
 dest=work/'cleanup-dex';dest.mkdir();run('java','-Xmx2g','-cp',a.tools/'r8-8.7.18.jar','com.android.tools.r8.D8','--min-api','26','--lib',android,*cp,'--output',dest,work/'feature.jar')
 assert len(list(dest.glob('*.dex')))==1;shutil.copyfile(dest/'classes.dex',work/'classes22.dex');disassemble(work/'classes22.dex',work/'new-smali')
 with zipfile.ZipFile(a.base)as base:
  for n in [4,6,9,11,12,16,23]:
   f=work/f'classes{n}.dex';f.write_bytes(base.read(f.name));disassemble(f,work/f'smali{n}')
 research=pathlib.Path('com/forja/app/feature/research');shutil.copytree(work/'smali23'/research,work/'new-smali'/research)
 for n,path in [(4,'com/forja/app/BuildConfig.smali'),(16,'com/forja/app/feature/research/HealthExportActivity.smali')]:
  f=work/f'smali{n}'/path;s=f.read_text();assert s.count('"3.7-online.25"')==1;s=s.replace('"3.7-online.25"','"3.7-online.26"')
  if n==4:
   old='.field public static final VERSION_CODE:I = 0x37';assert s.count(old)==1;s=s.replace(old,'.field public static final VERSION_CODE:I = 0x38')
  f.write_text(s);run('java','-Xmx2g','-cp',smali,'org.jf.smali.Main','a',work/f'smali{n}','-o',work/f'classes{n}.dex','--api','26')
 changed={'classes4.dex','classes16.dex','classes22.dex','AndroidManifest.xml'}
 with zipfile.ZipFile(a.base)as before,zipfile.ZipFile(work/'unsigned.apk','w')as after:
  for entry in before.infolist():
   if signature(entry.filename):continue
   raw=before.read(entry.filename)
   if entry.filename=='AndroidManifest.xml':raw=patch_manifest(raw)
   elif entry.filename in changed:raw=(work/entry.filename).read_bytes()
   after.writestr(entry,raw)
 run(bt/'zipalign','-f','-P','16','4',work/'unsigned.apk',work/'aligned.apk')
 run('java','-jar',bt/'lib/apksigner.jar','sign','--ks',a.keystore,'--ks-pass','env:'+a.store_pass_env,'--key-pass','env:'+a.store_pass_env,'--out',work/'signed.apk',work/'aligned.apk')
 cert=subprocess.check_output(['java','-jar',str(bt/'lib/apksigner.jar'),'verify','--print-certs',str(work/'signed.apk')],text=True);assert SIGNER in cert
 with zipfile.ZipFile(a.base)as before,zipfile.ZipFile(work/'signed.apk')as after:
  assert after.testzip()is None;assert {n for n in before.namelist()if not signature(n)}=={n for n in after.namelist()if not signature(n)}
  for n in before.namelist():
   if n not in changed and not signature(n):assert before.read(n)==after.read(n),n
 run('python3',ROOT/'verify.py',work/'signed.apk','--base',a.base,'--work-dir',work,'--android-jar',android)
 a.output.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(work/'signed.apk',a.output)
 result={'version':'3.7-online.26','version_code':56,'sha256':hashlib.sha256(a.output.read_bytes()).hexdigest(),'base_sha256':BASE,'bytes':a.output.stat().st_size,'work_dir':str(work),'signed':True,'signer_matches':True,'unchanged_dex':20,'original_assets_preserved':True,'new_permissions':[],'new_components':['com.forja.app.feature.cleanup.JourneyLocationService'],'android_device_tested':False,'live_account_tested':False};a.output.with_suffix('.verification.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
if __name__=='__main__':main()
