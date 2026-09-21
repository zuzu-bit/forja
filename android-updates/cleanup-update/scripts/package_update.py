"""Build a constrained feature update on the verified v13 APK, preserving unrelated code/resources.
No decompiled Java is rebuilt. Changed Kotlin sources are compiled normally; compile-only ABI
stubs are excluded. One feature DEX and two version metadata DEX are updated;
one new DEX is added. Other code and resources are preserved.
"""
import argparse,hashlib,io,json,pathlib,re,shutil,struct,subprocess,tempfile,zipfile
P=argparse.ArgumentParser();P.add_argument('--base',type=pathlib.Path,required=True);P.add_argument('--tools',type=pathlib.Path,required=True);P.add_argument('--output',type=pathlib.Path,required=True);P.add_argument('--work-dir',type=pathlib.Path,help='New local directory for packaging intermediates; must not exist');a=P.parse_args()
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE_SHA='8edc341e8699b32945c63bbee65c1b3c379f11fe4b936c2575c8c1d8916ee550'
assert hashlib.sha256(a.base.read_bytes()).hexdigest()==BASE_SHA,'Wrong base APK; preserve the delivered version'
# Keep generated assembly in a fresh local directory: synchronizing a reused
# build tree can restore stale files while version metadata is being patched.
if a.work_dir:
 WORK=a.work_dir.resolve();WORK.mkdir(parents=True,exist_ok=False)
else:WORK=pathlib.Path(tempfile.mkdtemp(prefix='forja-package-'))
print(json.dumps({'work_dir':str(WORK)}),flush=True)
SDK=a.tools/'android-sdk';SMALI_CP=str(a.tools/'smali/*');D8=a.tools/'r8-8.7.18.jar'
def run(*args):subprocess.run([str(x) for x in args],check=True)
def disassemble(dex,out):run('java','-Xmx2g','-cp',SMALI_CP,'org.jf.baksmali.Main','d',dex,'-o',out)
def smali_files(tree):return sorted(p for p in tree.rglob('*.smali') if not any(part.startswith('.') for part in p.relative_to(tree).parts))
def assemble(tree,output):
 # Pass only finalized source files, not temporary directory entries created
 # while the workspace synchronizes generated build output.
 run('java','-Xmx3g','-cp',SMALI_CP,'org.jf.smali.Main','a',*smali_files(tree),'-o',output,'--api','26')
def descriptor(path):return re.search(r'^\.class .* (L[^;]+;)$',path.read_text(),re.M).group(1)
def signatures(path):
 s=path.read_text();return set(re.findall(r'^\.method .*?([^\s]+\([^\n]+)$',s,re.M)),set(re.findall(r'^\.field .*?([^\s]+:[^\s=]+)',s,re.M))
def keydesc(ref):return ref.split('->',1)
# Filter the AAR. Stubs only tell the compiler the exact existing method signatures.
STUBS={'CleanFile','ContentFinding','CleanReport','CleanupViewModel','VisualPrint','CleanupScreenLegacyKt','Placement','CleanupOutcome','CleanupScan','CleanupOperations','DuplicateGroup','SimilarGroup'}
aar=ROOT/'feature/build/outputs/aar/feature-debug.aar'
with zipfile.ZipFile(aar) as z:jar=z.read('classes.jar')
with zipfile.ZipFile(io.BytesIO(jar)) as src,zipfile.ZipFile(WORK/'feature.jar','w',zipfile.ZIP_DEFLATED) as dst:
 for n in src.namelist():
  if not n.endswith('.class'):continue
  short=n.rsplit('/',1)[-1].split('$')[0].removesuffix('.class')
  if short in STUBS:continue
  assert n.startswith('com/forja/app/feature/cleanup/'),n
  dst.writestr(n,src.read(n))
(WORK/'dex').mkdir(exist_ok=True)
classpath=[]
for i,line in enumerate((ROOT/'feature/build/compile-classpath.txt').read_text().splitlines()):
 dep=pathlib.Path(line)
 if dep.suffix=='.aar':
  with zipfile.ZipFile(dep) as z:
   if 'classes.jar' not in z.namelist():continue
   dest=WORK/f'dep-{i}.jar';dest.write_bytes(z.read('classes.jar'));classpath += ['--classpath',dest]
 elif dep.suffix=='.jar':classpath += ['--classpath',dep]
run('java','-Xmx2g','-cp',D8,'com.android.tools.r8.D8','--min-api','26','--lib',SDK/'platforms/android-35/android.jar',*classpath,'--output',WORK/'dex',WORK/'feature.jar')
new=WORK/'new-smali';shutil.rmtree(new,ignore_errors=True);disassemble(WORK/'dex/classes.dex',new)
with zipfile.ZipFile(a.base) as z:
 (WORK/'base.dex').write_bytes(z.read('classes13.dex'))
base=WORK/'base-smali';shutil.rmtree(base,ignore_errors=True);disassemble(WORK/'base.dex',base)
# Validate every compile-only ABI method and field referenced by the new module.
base_paths={descriptor(p):p for p in smali_files(base)}
new_paths={descriptor(p):p for p in smali_files(new)}
api_prefix='Lcom/forja/app/feature/cleanup/'
for p in new_paths.values():
 for line in p.read_text().splitlines():
  if not line.strip().startswith(('invoke-','iget','iput','sget','sput')):continue
  match=re.search(r'(Lcom/forja/app/feature/cleanup/[^;]+;)->([^\s,]+)',line)
  if not match:continue
  cls,sig=match.groups();simple=cls[len(api_prefix):-1]
  if simple not in STUBS or simple=='CleanupScreenLegacyKt':continue
  assert cls in base_paths,(cls,'missing ABI class')
  methods,fields=signatures(base_paths[cls]);assert sig in methods|fields,(cls,sig,'ABI mismatch')
# Only these original implementations are replaced; all other app functionality stays intact.
REPLACE={'CleanupCatalog','CleanupContent','LocalSemanticModel','WordPiece'}
for cls,p in list(base_paths.items()):
 short=cls.rsplit('/',1)[-1][:-1]
 if short.split('$')[0] in REPLACE:p.unlink();continue
 if 'CleanupScreenKt' in short:
  value=p.read_text().replace('CleanupScreenKt','CleanupScreenLegacyKt')
  target=p.with_name(p.name.replace('CleanupScreenKt','CleanupScreenLegacyKt'));p.unlink();target.write_text(value)
cache=base/'com/forja/app/feature/cleanup/CleanupCache.smali';s=cache.read_text();assert 'content-2-topics-0859a11b' in s;cache.write_text(s.replace('content-2-topics-0859a11b','content-3-topics-0859a11b'))
retained={descriptor(p):p for p in smali_files(base)}
assert not retained.keys() & new_paths.keys(),'Duplicate feature classes'
assert 'ABI stub' not in '\n'.join(p.read_text() for p in new_paths.values())
entry='CleanupScreen(Lkotlin/jvm/functions/Function0;Landroidx/compose/runtime/Composer;I)V'
assert entry in signatures(new_paths[api_prefix+'CleanupScreenKt;'])[0]
assert entry in signatures(retained[api_prefix+'CleanupScreenLegacyKt;'])[0]
# Validate original callers against every replacement's compiled ABI, too.
for p in retained.values():
 for line in p.read_text().splitlines():
  if not line.strip().startswith(('invoke-','iget','iput','sget','sput')):continue
  match=re.search(r'(Lcom/forja/app/feature/cleanup/[^;]+;)->([^\s,]+)',line)
  if not match:continue
  cls,sig=match.groups()
  if cls not in new_paths:continue
  methods,fields=signatures(new_paths[cls]);assert sig in methods|fields,(cls,sig,'Original caller ABI mismatch')
assemble(base,WORK/'classes13.dex')
# Keep the displayed/exported version consistent. These edits only change exact version metadata.
for number,relative in [(4,'com/forja/app/BuildConfig.smali'),(16,'com/forja/app/feature/research/HealthExportActivity.smali')]:
 dexname=f'classes{number}.dex';tree=WORK/f'version-{number}';shutil.rmtree(tree,ignore_errors=True)
 with zipfile.ZipFile(a.base) as z:(WORK/dexname).write_bytes(z.read(dexname))
 disassemble(WORK/dexname,tree)
 path=tree/relative;old=path.read_text();assert '"3.7-online.13"' in old
 value=old.replace('"3.7-online.13"','"3.7-online.17"')
 if number==4:
  assert '.field public static final VERSION_CODE:I = 0x2b' in value
  value=value.replace('.field public static final VERSION_CODE:I = 0x2b','.field public static final VERSION_CODE:I = 0x2f')
 path.write_text(value)
 assemble(tree,WORK/dexname)
# Binary XML version bump: same-length string replacement plus the typed versionCode attribute.
def manifest_version(data):
 b=bytearray(data);offset=8;strings=[];code_changed=False;name_changed=False
 while offset<len(b):
  typ,head,size=struct.unpack_from('<HHI',b,offset)
  if typ==1:
   count,styles,flags,start,style_start=struct.unpack_from('<IIIII',b,offset+8)
   utf8=bool(flags&0x100)
   def length(pos):
    if utf8:
     n=b[pos];return ((n&127)<<8|b[pos+1],pos+2) if n&128 else (n,pos+1)
    n=struct.unpack_from('<H',b,pos)[0];return (((n&32767)<<16)|struct.unpack_from('<H',b,pos+2)[0],pos+4) if n&32768 else (n,pos+2)
   for i in range(count):
    rel=struct.unpack_from('<I',b,offset+head+4*i)[0];pos=offset+start+rel
    n,pos=length(pos)
    if utf8:n,pos=length(pos)
    raw=bytes(b[pos:pos+n*(1 if utf8 else 2)]);text=raw.decode('utf-8' if utf8 else 'utf-16le');strings.append(text)
    if text=='3.7-online.13':
     replacement='3.7-online.17'.encode('utf-8' if utf8 else 'utf-16le');assert len(raw)==len(replacement);b[pos:pos+len(raw)]=replacement;name_changed=True
  elif typ==0x102:
   namespace,name,attr_start,attr_size,attr_count=struct.unpack_from('<IIHHH',b,offset+16)
   if strings[name]=='manifest':
    for i in range(attr_count):
     pos=offset+16+attr_start+i*attr_size;ns,key,raw=struct.unpack_from('<III',b,pos)
     if strings[key]=='versionCode':
      assert b[pos+15]==0x10 and struct.unpack_from('<I',b,pos+16)[0]==43
      struct.pack_into('<I',b,pos+16,47);code_changed=True
  offset+=size
 assert code_changed and name_changed,'Manifest version not safely identified'
 return bytes(b)
unsigned=WORK/'unsigned.apk';changed=['classes4.dex','classes13.dex','classes16.dex','AndroidManifest.xml']
with zipfile.ZipFile(a.base) as src,zipfile.ZipFile(unsigned,'w') as dst:
 for item in src.infolist():
  if item.filename.startswith('META-INF/') and (item.filename.endswith(('.RSA','.DSA','.EC','.SF')) or item.filename=='META-INF/MANIFEST.MF'):continue
  data=(WORK/item.filename).read_bytes() if item.filename in changed and item.filename.endswith('.dex') else src.read(item.filename)
  if item.filename=='AndroidManifest.xml':data=manifest_version(data)
  dst.writestr(item,data)
 dst.writestr('classes22.dex',(WORK/'dex/classes.dex').read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
aligned=WORK/'aligned.apk';run(SDK/'build-tools/35.0.0/zipalign','-f','-P','16','4',unsigned,aligned)
a.output.parent.mkdir(parents=True,exist_ok=True)
run(SDK/'build-tools/35.0.0/apksigner','sign','--ks',ROOT.parent/'app/debug.keystore','--ks-pass','pass:android','--key-pass','pass:android','--out',a.output,aligned)
run(SDK/'build-tools/35.0.0/apksigner','verify','--print-certs',a.output)
with zipfile.ZipFile(a.base) as before,zipfile.ZipFile(a.output) as after:
 for name in before.namelist():
  if name in changed or name.startswith('META-INF/') and name.endswith(('.SF','.RSA','.DSA','.EC','MANIFEST.MF')):continue
  assert before.read(name)==after.read(name),f'Unrelated entry changed: {name}'
print(json.dumps({'output':str(a.output),'sha256':hashlib.sha256(a.output.read_bytes()).hexdigest(),'bytes':a.output.stat().st_size,'preserved_other_dex':18,'metadata_only_dex':[4,16],'base_sha256':BASE_SHA}))
