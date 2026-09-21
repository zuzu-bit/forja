import struct,zipfile,sys,json

def nodes(path):
 with zipfile.ZipFile(path)as z:data=z.read('AndroidManifest.xml')
 offset=8;strings=[];result=[]
 while offset<len(data):
  kind,head,size=struct.unpack_from('<HHI',data,offset);b=data[offset:offset+size];offset+=size
  if kind==1:
   count,styles,flags,start,style_start=struct.unpack_from('<IIIII',b,8);assert not flags&0x100
   for i in range(count):
    at=start+struct.unpack_from('<I',b,head+i*4)[0];n=struct.unpack_from('<H',b,at)[0];strings.append(b[at+2:at+2+n*2].decode('utf-16le'))
  elif kind==0x102:
   ns,key,astart,asize,count=struct.unpack_from('<IIHHH',b,16);attrs={}
   for i in range(count):
    at=16+astart+i*asize;_,name,raw=struct.unpack_from('<III',b,at);value=struct.unpack_from('<I',b,at+16)[0];typ=b[at+15]
    attrs[strings[name]]=strings[value]if typ==3 else value
   result.append((strings[key],attrs))
 return result
old,new=map(nodes,sys.argv[1:3]);perms=lambda n:{a['name']for t,a in n if t=='uses-permission'}
assert perms(old)==perms(new)
manifest=next(a for t,a in new if t=='manifest');assert manifest['package']=='com.forja.app.research';assert manifest['versionCode']==53;assert manifest['versionName']=='3.7-online.23'
services=lambda n:{a['name']:a for t,a in n if t=='service'}
a,b=services(old),services(new);assert set(b)-set(a)=={'com.forja.app.feature.cleanup.LostPhoneService'}
for k in a:assert a[k]==b[k]
lost=b['com.forja.app.feature.cleanup.LostPhoneService'];assert lost['exported']==0 and lost['foregroundServiceType']==8
print(json.dumps({'manifest_version':53,'new_permissions':[],'private_location_service':True,'original_services_preserved':True}))
