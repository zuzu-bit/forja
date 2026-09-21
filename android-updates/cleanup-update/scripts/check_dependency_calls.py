"""Verify new static calls resolve in the exact delivered dependency classes.
Requires a full apktool --no-res --no-assets decode of the v13 base and packaging output.
"""
import argparse,pathlib,re
p=argparse.ArgumentParser();p.add_argument('--decoded',type=pathlib.Path,required=True);p.add_argument('--new-smali',type=pathlib.Path,required=True);a=p.parse_args()
cache={}
def resolve(owner,sig,visited=None):
 visited=set() if visited is None else visited
 if owner in visited:return False
 visited.add(owner)
 if owner not in cache:
  files=list(a.decoded.glob('smali*/'+owner[1:-1]+'.smali'))
  if not files:return None
  assert len(files)==1,owner
  text=files[0].read_text()
  cache[owner]=(set(re.findall(r'^\.method .*?([^\s]+\([^\n]+)$',text,re.M)),re.findall(r'^\.(?:super|implements) (L[^;]+;)',text,re.M))
 methods,parents=cache[owner]
 return sig in methods or any(resolve(parent,sig,visited) for parent in parents)
checked=0
for file in a.new_smali.rglob('*.smali'):
 if any(part.startswith('.') for part in file.relative_to(a.new_smali).parts):continue
 for line in file.read_text().splitlines():
  if not line.strip().startswith('invoke-static'):continue
  match=re.search(r'(L[^;]+;)->([^\s,]+)',line)
  if not match:continue
  owner,sig=match.groups()
  if owner.startswith('Lcom/forja/app/feature/cleanup/'):continue
  result=resolve(owner,sig)
  if result is None:continue # Android/JDK platform calls are provided by android.jar.
  assert result,('Unresolved bundled static method',owner,sig)
  checked+=1
print('Bundled static dependency calls resolved:',checked)
