"""Append one private location-service declaration without recompiling resources.
Binary XML string indices and original attributes are preserved, except version.
"""
import struct

def patch_manifest(data):
 chunks=[];offset=8;strings=[]
 while offset<len(data):
  typ,head,size=struct.unpack_from('<HHI',data,offset);chunks.append([typ,bytearray(data[offset:offset+size])]);offset+=size
 pool=next(b for t,b in chunks if t==1);head=struct.unpack_from('<H',pool,2)[0];count,styles,flags,start,style_start=struct.unpack_from('<IIIII',pool,8)
 assert not styles and not style_start and flags&0x100==0, 'Expected style-free UTF16 manifest pool'
 for i in range(count):
  at=start+struct.unpack_from('<I',pool,head+4*i)[0];n=struct.unpack_from('<H',pool,at)[0];at+=2
  assert n<32768
  strings.append(bytes(pool[at:at+2*n]).decode('utf-16le'))
 assert strings.count('3.7-online.22')==1;strings[strings.index('3.7-online.22')]='3.7-online.23'
 service='com.forja.app.feature.cleanup.LostPhoneService';assert service not in strings;strings.append(service);index=len(strings)-1
 values=bytearray();offsets=[]
 for s in strings:
  raw=s.encode('utf-16le');offsets.append(len(values));values+=struct.pack('<H',len(raw)//2)+raw+b'\0\0'
 values+=b'\0'*(-len(values)%4);newstart=head+4*len(strings)
 rebuilt=bytearray(struct.pack('<HHI',1,head,newstart+len(values))+struct.pack('<IIIII',len(strings),0,flags&~1,newstart,0))+b''.join(struct.pack('<I',o) for o in offsets)+values
 clone=None;application_end=None;version=False
 for i,(typ,b) in enumerate(chunks):
  if typ==1:chunks[i][1]=rebuilt
  if typ==0x102:
   ns,key,astart,asize,acount=struct.unpack_from('<IIHHH',b,16)
   for j in range(acount):
    at=16+astart+j*asize;_,attr,raw=struct.unpack_from('<III',b,at)
    if strings[key]=='manifest' and strings[attr]=='versionCode':assert struct.unpack_from('<I',b,at+16)[0]==52;struct.pack_into('<I',b,at+16,53);version=True
    if strings[key]=='service' and strings[attr]=='name' and raw!=0xffffffff and strings[raw]=='com.forja.app.core.location.GoTrackService':
     assert chunks[i+1][0]==0x103
     clone=[bytearray(b),bytearray(chunks[i+1][1])];struct.pack_into('<I',clone[0],at+8,index);struct.pack_into('<I',clone[0],at+16,index)
  if typ==0x103 and strings[struct.unpack_from('<I',b,20)[0]]=='application':application_end=i
 assert clone and application_end and version
 chunks[application_end:application_end]=[[0x102,clone[0]],[0x103,clone[1]]]
 result=b''.join(bytes(b) for _,b in chunks);return struct.pack('<HHI',3,8,8+len(result))+result
