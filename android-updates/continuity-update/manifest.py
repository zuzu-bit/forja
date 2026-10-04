"""Minimal binary XML edit: v26 version plus one non-exported location service."""
import struct
SERVICE='com.forja.app.feature.cleanup.JourneyLocationService'
def chunks(data):
 at=8
 while at<len(data):
  kind,header,size=struct.unpack_from('<HHI',data,at);yield at,kind,header,data[at:at+size];at+=size

def strings(block):
 count,styles,flags,start,_=struct.unpack_from('<IIIII',block,8);assert styles==0 and flags&0x100==0
 values=[]
 for i in range(count):
  at=start+struct.unpack_from('<I',block,28+4*i)[0];n=struct.unpack_from('<H',block,at)[0];assert n<32768
  values.append(block[at+2:at+2+2*n].decode('utf-16le'))
 return values

def patch(data):
 blocks=list(chunks(data));pool=next(b for _,k,_,b in blocks if k==1);names=strings(pool);assert SERVICE not in names
 version=names.index('3.7-online.25');pool=bytearray(pool);start=struct.unpack_from('<I',pool,20)[0];at=start+struct.unpack_from('<I',pool,28+4*version)[0]+2
 pool[at:at+len('3.7-online.25')*2]='3.7-online.26'.encode('utf-16le')
 count=len(names);old_start=struct.unpack_from('<I',pool,20)[0];old_data=pool[old_start:];extra=struct.pack('<H',len(SERVICE))+SERVICE.encode('utf-16le')+b'\0\0'
 offsets=pool[28:old_start]+struct.pack('<I',len(old_data));newpool=pool[:28]+offsets+old_data+extra
 newpool+=b'\0'*((-len(newpool))%4);struct.pack_into('<I',newpool,4,len(newpool));struct.pack_into('<I',newpool,8,count+1);struct.pack_into('<I',newpool,20,old_start+4)
 names.append(SERVICE);out=[];clone=None;endservice=None;inserted=False;versioncode=False
 for _,kind,_,raw in blocks:
  if kind==1:out.append(bytes(newpool));continue
  b=bytearray(raw)
  if kind==0x102:
   _,tag,offset,stride,n=struct.unpack_from('<IIHHH',b,16);attrs={}
   for i in range(n):
    pos=16+offset+i*stride;_,key,_=struct.unpack_from('<III',b,pos);value=struct.unpack_from('<I',b,pos+16)[0];attrs[names[key]]=(pos,names[value] if b[pos+15]==3 else value)
   if names[tag]=='manifest':
    pos,value=attrs['versionCode'];assert value==55;struct.pack_into('<I',b,pos+16,56);versioncode=True
   if names[tag]=='service' and attrs.get('name',(0,''))[1]=='com.forja.app.feature.cleanup.SocialLocationService':
    assert attrs['exported'][1]==0 and attrs['foregroundServiceType'][1]==8
    clone=bytearray(b);pos=attrs['name'][0];struct.pack_into('<I',clone,pos+8,count);struct.pack_into('<I',clone,pos+16,count)
  if kind==0x103:
   tag=struct.unpack_from('<I',b,20)[0]
   if names[tag]=='service':endservice=bytes(b)
   if names[tag]=='application':
    assert clone is not None and endservice is not None and not inserted;out.extend([bytes(clone),endservice]);inserted=True
  out.append(bytes(b))
 assert inserted and versioncode
 result=bytearray(data[:8]+b''.join(out));struct.pack_into('<I',result,4,len(result));return bytes(result)
