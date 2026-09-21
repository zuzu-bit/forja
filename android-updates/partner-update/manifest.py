"""Add read-only contacts permission and a private recovery receiver to v21.
Preserve all original binary XML string indices, resource IDs and components.
"""
import struct

def patch_manifest(data):
 chunks=[];offset=8;strings=[]
 while offset<len(data):
  typ,head,size=struct.unpack_from('<HHI',data,offset);chunks.append([typ,bytearray(data[offset:offset+size])]);offset+=size
 pool=next(b for t,b in chunks if t==1);head=struct.unpack_from('<H',pool,2)[0];count,styles,flags,start,style_start=struct.unpack_from('<IIIII',pool,8)
 assert not styles and not style_start and not flags&0x100
 for i in range(count):
  at=start+struct.unpack_from('<I',pool,head+4*i)[0];n=struct.unpack_from('<H',pool,at)[0];at+=2;assert n<32768
  strings.append(bytes(pool[at:at+2*n]).decode('utf-16le'))
 assert strings.count('3.7-online.21')==1;strings[strings.index('3.7-online.21')]='3.7-online.22'
 receiver='com.forja.app.feature.cleanup.SocialBootReceiver';permission='android.permission.READ_CONTACTS'
 assert receiver not in strings and permission not in strings
 def idx(value):
  if value not in strings:strings.append(value)
  return strings.index(value)
 nil=0xffffffff;android=idx('http://schemas.android.com/apk/res/android')
 def begin(tag,attrs=()):
  key=idx(tag);values=[]
  for name,kind,value in attrs:
   val=idx(value) if kind==3 else value
   values.append(struct.pack('<IIIHBBI',android,idx(name),val if kind==3 else nil,8,0,kind,val))
  return [0x102,struct.pack('<HHIII',0x102,16,36+20*len(attrs),0,nil)+struct.pack('<IIHHHHHH',nil,key,20,20,len(attrs),0,0,0)+b''.join(values)]
 def end(tag):return [0x103,struct.pack('<HHIIIII',0x103,16,24,0,nil,nil,idx(tag))]
 permission_chunks=[begin('uses-permission',[('name',3,permission)]),end('uses-permission')]
 receiver_chunks=[begin('receiver',[('name',3,receiver),('exported',0x12,0)]),begin('intent-filter')]
 for action in ['android.intent.action.BOOT_COMPLETED','android.intent.action.MY_PACKAGE_REPLACED']:receiver_chunks.extend([begin('action',[('name',3,action)]),end('action')])
 receiver_chunks.extend([end('intent-filter'),end('receiver')])
 application_start=None;application_end=None;version=False
 for i,(typ,b) in enumerate(chunks):
  if typ==0x102:
   ns,key,astart,asize,acount=struct.unpack_from('<IIHHH',b,16)
   if strings[key]=='application':application_start=i
   for j in range(acount):
    at=16+astart+j*asize;_,attr,raw=struct.unpack_from('<III',b,at)
    if strings[key]=='manifest' and strings[attr]=='versionCode':assert struct.unpack_from('<I',b,at+16)[0]==51;struct.pack_into('<I',b,at+16,52);version=True
  if typ==0x103 and strings[struct.unpack_from('<I',b,20)[0]]=='application':application_end=i
 assert application_start and application_end and version
 chunks[application_end:application_end]=receiver_chunks
 chunks[application_start:application_start]=permission_chunks
 values=bytearray();offsets=[]
 for s in strings:
  raw=s.encode('utf-16le');offsets.append(len(values));values+=struct.pack('<H',len(raw)//2)+raw+b'\0\0'
 values+=b'\0'*(-len(values)%4);newstart=head+4*len(strings)
 rebuilt=struct.pack('<HHI',1,head,newstart+len(values))+struct.pack('<IIIII',len(strings),0,flags&~1,newstart,0)+b''.join(struct.pack('<I',o) for o in offsets)+values
 for entry in chunks:
  if entry[0]==1:entry[1]=rebuilt
 result=b''.join(bytes(b) for _,b in chunks);return struct.pack('<HHI',3,8,8+len(result))+result
