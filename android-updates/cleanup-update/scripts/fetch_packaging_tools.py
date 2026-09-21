"""Download exact packaging tools from their official Maven repositories."""
import argparse,concurrent.futures,pathlib,urllib.request,zipfile

p=argparse.ArgumentParser();p.add_argument('--tools',type=pathlib.Path,required=True);a=p.parse_args()
r=a.tools/'smali';r.mkdir(parents=True,exist_ok=True)
coordinates=[('org/smali',n,'2.5.2') for n in ['baksmali','smali','dexlib2','util']]+[
    ('org/antlr','antlr','3.5.2'),('org/antlr','antlr-runtime','3.5.2'),
    ('com/beust','jcommander','1.64'),('com/google/guava','guava','27.1-android'),
    ('com/google/guava','failureaccess','1.0.1'),('com/google/code/findbugs','jsr305','3.0.2')]
items=[(r/f'{n}-{v}.jar',f'https://repo.maven.apache.org/maven2/{g}/{n}/{v}/{n}-{v}.jar') for g,n,v in coordinates]
items.append((a.tools/'r8-8.7.18.jar','https://storage.googleapis.com/r8-releases/raw/8.7.18/r8.jar'))
def fetch(item):
    path,url=item
    if path.exists() and zipfile.is_zipfile(path):return
    staging=path.with_suffix('.part')
    with urllib.request.urlopen(url,timeout=90) as response,staging.open('wb') as output:
        while chunk:=response.read(1024*1024):output.write(chunk)
    with zipfile.ZipFile(staging) as archive:assert archive.testzip() is None
    staging.replace(path)
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:list(executor.map(fetch,items))
