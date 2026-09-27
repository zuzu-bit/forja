"""Version-only edit of the checksum-pinned v26 binary Android manifest."""
import struct

def patch(data):
    result=bytearray(data);at=8;names=[];version=False;code=False
    while at<len(result):
        kind,header,size=struct.unpack_from('<HHI',result,at)
        if kind==1:
            count,styles,flags,start,_=struct.unpack_from('<IIIII',result,at+8)
            assert not flags&0x100
            for i in range(count):
                p=at+start+struct.unpack_from('<I',result,at+header+4*i)[0]
                n=struct.unpack_from('<H',result,p)[0];assert n<32768
                name=result[p+2:p+2+2*n].decode('utf-16le');names.append(name)
                if name=='3.7-online.26':
                    assert not version;result[p+2:p+2+2*n]='3.7-online.27'.encode('utf-16le');version=True
        elif kind==0x102:
            _,tag,offset,stride,count=struct.unpack_from('<IIHHH',result,at+16)
            if names[tag]=='manifest':
                for i in range(count):
                    p=at+16+offset+i*stride;key=struct.unpack_from('<I',result,p+4)[0]
                    if names[key]=='versionCode':
                        assert struct.unpack_from('<I',result,p+16)[0]==56
                        struct.pack_into('<I',result,p+16,57);code=True
        at+=size
    assert version and code
    return bytes(result)
