"""Add a test-only rendering Activity to a separate, signed-out application ID.
No production auth class or entry point is modified. This APK must not be distributed.
"""
import argparse,hashlib,io,os,pathlib,subprocess,zipfile,struct,tempfile

def run(*args):
    p=pathlib.Path(args[0])
    if p.is_file():p.chmod(p.stat().st_mode|0o100)
    subprocess.run([str(x)for x in args],check=True)
def manifest(raw):
    data=bytearray(raw);at=8;changes=0
    while at<len(data):
        kind,header,size=struct.unpack_from('<HHI',data,at)
        if kind==1:
            count,_,flags,start,_=struct.unpack_from('<IIIII',data,at+8);assert not flags&0x100
            for i in range(count):
                pos=at+start+struct.unpack_from('<I',data,at+header+i*4)[0];n=struct.unpack_from('<H',data,pos)[0];assert n<32768
                old=data[pos+2:pos+2+n*2].decode('utf-16le')
                new=old.replace('com.forja.app.research','com.forja.app.renderqa').replace('com.forja.app.MainActivity','com.forja.app.TestActivity')
                if new!=old:
                    assert len(new)==len(old);data[pos+2:pos+2+n*2]=new.encode('utf-16le');changes+=1
        at+=size
    assert changes>=3
    return bytes(data)
def signature(n):return n.startswith('META-INF/')and n.endswith(('.RSA','.DSA','.EC','.SF','MANIFEST.MF'))
def main():
    p=argparse.ArgumentParser();p.add_argument('--base',type=pathlib.Path,required=True);p.add_argument('--tools',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--keystore',type=pathlib.Path,required=True);a=p.parse_args()
    here=pathlib.Path(__file__).resolve().parent;compiled=here.parents[1]/'cleanup-update';work=pathlib.Path(tempfile.mkdtemp(prefix='forja-ui-probe-'));bt=a.tools/'sdk/android-15'
    with zipfile.ZipFile(next((here/'build/outputs/aar').glob('*-debug.aar')))as z:classes=z.read('classes.jar')
    with zipfile.ZipFile(io.BytesIO(classes))as src,zipfile.ZipFile(work/'probe.jar','w')as dst:
        for name in src.namelist():
            if name.startswith('com/forja/app/TestActivity') and name.endswith('.class'):dst.writestr(name,src.read(name))
    cp=[]
    for i,line in enumerate((compiled/'feature/build/compile-classpath.txt').read_text().splitlines()):
        f=pathlib.Path(line)
        if f.suffix=='.aar':
            with zipfile.ZipFile(f)as z:
                if 'classes.jar'not in z.namelist():continue
                f=work/f'cp-{i}.jar';f.write_bytes(z.read('classes.jar'))
        cp+=['--classpath',f]
    with zipfile.ZipFile(compiled/'feature/build/outputs/aar/feature-debug.aar')as z:(work/'feature.jar').write_bytes(z.read('classes.jar'))
    dex=work/'dex';dex.mkdir();run('java','-cp',a.tools/'r8-8.7.18.jar','com.android.tools.r8.D8','--min-api','26','--lib',a.tools/'sdk/android-35/android.jar',*cp,'--classpath',work/'feature.jar','--output',dex,work/'probe.jar')
    with zipfile.ZipFile(a.base)as src,zipfile.ZipFile(work/'probe.apk','w')as dst:
        for info in src.infolist():
            if signature(info.filename):continue
            raw=src.read(info.filename)
            if info.filename=='AndroidManifest.xml':raw=manifest(raw)
            dst.writestr(info,raw)
        assert 'classes24.dex'not in src.namelist();dst.writestr('classes24.dex',(dex/'classes.dex').read_bytes())
    run(bt/'zipalign','-f','-P','16','4',work/'probe.apk',work/'aligned.apk');a.output.parent.mkdir(parents=True,exist_ok=True)
    run('java','-jar',bt/'lib/apksigner.jar','sign','--ks',a.keystore,'--ks-pass','env:FORJA_KEYSTORE_PASSWORD','--key-pass','env:FORJA_KEYSTORE_PASSWORD','--out',a.output,work/'aligned.apk')
    with zipfile.ZipFile(a.base)as before,zipfile.ZipFile(a.output)as after:
        for n in before.namelist():
            if n!='AndroidManifest.xml'and not signature(n):assert before.read(n)==after.read(n),n
    print('Separate QA application: com.forja.app.renderqa; preserved all production DEX/assets.')
if __name__=='__main__':main()
