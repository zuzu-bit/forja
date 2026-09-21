"""Sign the verified v23 build using the original v22 keystore. Never creates a new key."""
import argparse,getpass,hashlib,os,pathlib,shutil,subprocess,tempfile
p=argparse.ArgumentParser();p.add_argument('keystore',type=pathlib.Path);p.add_argument('--alias');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parent;apk=root/'FORJA_v23_de_semnat.apk';signer=root/'apksigner.jar'
assert a.keystore.is_file(),'Select the original .keystore/.jks file'
java=shutil.which('java');assert java,'Java 17 or newer is required'
assert hashlib.sha256(apk.read_bytes()).hexdigest()=='da09ba5a542b45b55dcf047f57d67ccb1d4e9b2fa1239844ebe26542f2fd8bdd','APK checksum differs from the verified build'
password=getpass.getpass('Parola keystore (nu se afiseaza): ');keypass=getpass.getpass('Parola cheii, Enter daca este aceeasi: ')or password
env={**os.environ,'FORJA_SIGN_STORE_PASSWORD':password,'FORJA_SIGN_KEY_PASSWORD':keypass}
with tempfile.TemporaryDirectory(prefix='forja-sign-')as tmp:
 signed=pathlib.Path(tmp)/'signed.apk'
 cmd=[java,'-jar',str(signer),'sign','--ks',str(a.keystore),'--ks-pass','env:FORJA_SIGN_STORE_PASSWORD','--key-pass','env:FORJA_SIGN_KEY_PASSWORD','--out',str(signed)]
 if a.alias:cmd+=['--ks-key-alias',a.alias]
 subprocess.run([*cmd,str(apk)],check=True,env=env)
 verified=subprocess.check_output([java,'-jar',str(signer),'verify','--print-certs',str(signed)],text=True)
 if '6fc56c1ff164d46b4994fdbfa3e38c786e804c191897ccad581d278aa8feed74' not in verified:raise SystemExit('Cheia nu corespunde versiunii v22. Nu a fost produs un APK de actualizare.')
 output=root/'FORJA_v23_Telefon_Pierdut.apk';shutil.copyfile(signed,output)
 print('APK semnat cu certificatul original:',output.name)
