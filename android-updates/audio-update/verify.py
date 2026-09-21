"""Verify DEX identity, all new FORJA/Firebase references, and the retained recording gates."""
import argparse,collections,hashlib,json,pathlib,re,struct,zipfile,zlib
p=argparse.ArgumentParser();p.add_argument('apk',type=pathlib.Path);p.add_argument('--work-dir',type=pathlib.Path,required=True);p.add_argument('--android-jar',type=pathlib.Path,required=True);a=p.parse_args()
classes={};methods={};fields={};dex_count=0;recorder_references=set()
with zipfile.ZipFile(a.apk) as z:
 assert z.testzip() is None
 for filename in z.namelist():
  if not re.fullmatch(r'classes\d*\.dex',filename):continue
  dex_count+=1;b=z.read(filename)
  assert b[12:32]==hashlib.sha1(b[32:]).digest(),filename
  assert struct.unpack_from('<I',b,8)[0]==zlib.adler32(b[12:])&0xffffffff,filename
  sc,so=struct.unpack_from('<II',b,56);tc,to=struct.unpack_from('<II',b,64);pc,po=struct.unpack_from('<II',b,72);fc,fo=struct.unpack_from('<II',b,80);mc,mo=struct.unpack_from('<II',b,88);cc,co=struct.unpack_from('<II',b,96)
  def uleb(pos):
   value=0;shift=0
   while True:
    v=b[pos];pos+=1;value|=(v&127)<<shift
    if not v&128:return value,pos
    shift+=7
  def string(i):
   offset=struct.unpack_from('<I',b,so+4*i)[0];_,offset=uleb(offset)
   return b[offset:b.index(b'\0',offset)].decode('utf-8','replace')
  def typ(i):return string(struct.unpack_from('<I',b,to+4*i)[0])
  def method(i):
   cls,proto,name=struct.unpack_from('<HHI',b,mo+8*i);_,ret,params=struct.unpack_from('<III',b,po+12*proto);parts=[]
   if params:
    count=struct.unpack_from('<I',b,params)[0];parts=[typ(struct.unpack_from('<H',b,params+4+2*j)[0]) for j in range(count)]
   return string(name)+'('+''.join(parts)+')'+typ(ret)
  def field(i):
   cls,t,name=struct.unpack_from('<HHI',b,fo+8*i);return string(name)+':'+typ(t)
  for i in range(mc):
   cls,proto,name=struct.unpack_from('<HHI',b,mo+8*i);target=typ(cls)
   if any(target.startswith('Lcom/forja/app/feature/research/'+name) for name in ['TimedRecordingService','AudioDiagnostics','AudioReadyPolicy','AudioWebStatus']):
    recorder_references.add((target,method(i)))
  for i in range(cc):
   index,access,parent,interfaces,source,annotations,data,values=struct.unpack_from('<IIIIIIII',b,co+32*i);cls=typ(index)
   assert cls not in classes,cls
   classes[cls]=typ(parent) if parent!=0xffffffff else None;methods[cls]=set();fields[cls]=set()
   if not data:continue
   ns,data=uleb(data);ni,data=uleb(data);nd,data=uleb(data);nv,data=uleb(data)
   for count in [ns,ni]:
    index=0
    for j in range(count):diff,data=uleb(data);access,data=uleb(data);index+=diff;fields[cls].add(field(index))
   for count in [nd,nv]:
    index=0
    for j in range(count):diff,data=uleb(data);access,data=uleb(data);code,data=uleb(data);index+=diff;methods[cls].add(method(index))
apk_class_count=len(classes)
sdk=zipfile.ZipFile(a.android_jar)
def sdk_class(cls):
 name=cls[1:-1]+'.class'
 if name not in sdk.namelist():return False
 b=sdk.read(name);pos=10;pool=[None];count=struct.unpack_from('>H',b,8)[0]
 def u2(at):return struct.unpack_from('>H',b,at)[0]
 while len(pool)<count:
  tag=b[pos];pos+=1
  if tag==1:
   n=u2(pos);pos+=2;pool.append(b[pos:pos+n].decode('utf-8','replace'));pos+=n
  elif tag in [7,8,16,19,20]:pool.append(u2(pos));pos+=2
  elif tag in [3,4,9,10,11,12,17,18]:pool.append(None);pos+=4
  elif tag in [5,6]:pool.extend([None,None]);pos+=8
  elif tag==15:pool.append(None);pos+=3
  else:raise ValueError(tag)
 parent=u2(pos+4);classes[cls]='L'+pool[pool[parent]]+';' if parent else None;pos+=6
 count=u2(pos);pos+=2+2*count;methods[cls]=set();fields[cls]=set()
 for bucket,separator in [(fields,':'),(methods,'')]:
  count=u2(pos);pos+=2
  for i in range(count):
   flags,name,desc,n=struct.unpack_from('>HHHH',b,pos);pos+=8;bucket[cls].add(pool[name]+separator+pool[desc])
   for j in range(n):size=struct.unpack_from('>I',b,pos+2)[0];pos+=6+size
 return True
def exists(cls,sig):
 seen=set()
 while cls and cls not in seen:
  seen.add(cls)
  if cls not in classes:sdk_class(cls)
  if sig in methods.get(cls,set())|fields.get(cls,set()):return True
  cls=classes.get(cls)
 return False
checked=0
for f in (a.work_dir/'new-smali').rglob('*.smali'):
 for line in f.read_text().splitlines():
  if not line.strip().startswith(('invoke-','iget','iput','sget','sput')):continue
  m=re.search(r'(L(?:com/forja/|com/google/firebase/)[^;]+;)->([^\s,]+)',line)
  if not m:continue
  assert exists(*m.groups()),(str(f),m.groups());checked+=1
for reference in recorder_references:assert exists(*reference),reference
service=(a.work_dir/'new-smali/com/forja/app/feature/research/TimedRecordingService.smali').read_text()
for gate in ['CollectionSettings;->getVisible()Z','WebAccountControl;->enabled(Landroid/content/Context;)Z','android.permission.RECORD_AUDIO','TimedRecordingService;->permissions()Z','TimedRecordingService;->finish(Z)V','MediaRecorder;->setMaxDuration(I)V','MediaRecorder;->setMaxFileSize(J)V','RecordingStore;->enqueue(Landroid/content/Context;Lcom/forja/app/feature/research/TimedRecording;)V','explicit_web_audio_consent','TimedRecordingService;->isReady()Z','TimedRecordingService;->authorized()Z','AudioReadyPolicy;->durationAllowed(J)Z']:
 assert gate in service,gate
poll=(a.work_dir/'smali12/com/forja/app/core/data/WebAccountControl$poll$1.smali').read_text()
assert 'WebRecordingPolicy;->remaining(JJJJZZZ)Ljava/lang/Long;' in poll
assert 'TimedRecordingService;->dispatchWeb(Landroid/content/Context;Landroid/content/Intent;)V' in poll
assert 'AudioWebStatus;->decorate(Lorg/json/JSONObject;)Lorg/json/JSONObject;' in poll
assert poll.count('CollectionSettings;->getVisible()Z')==3 # loop, truthful report, unrelated collection
assert poll.count('TimedRecordingService;->isReady()Z')==2 # loop and web command eligibility
assert 'AudioWebStatus;->filterCommand(Lorg/json/JSONObject;)Lorg/json/JSONObject;' in poll
assert 'AudioReadyPolicy;->sameSession(Ljava/lang/String;Ljava/lang/String;)Z' in service
assert dex_count==23
assert 'Lcom/forja/app/feature/research/WebPairActivity;' in classes
print(json.dumps({'dex_files':dex_count,'unique_classes':apk_class_count,'duplicate_classes':0,'new_app_dependency_calls_verified':checked,'new_feature_abi_references_verified':len(recorder_references),'explicit_arming_permission_owner_and_upload_gates_present':True}))
