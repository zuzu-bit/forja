"""Build with the environment's configured proxy; no credentials are printed."""
import os,subprocess,urllib.parse,pathlib,sys
root=pathlib.Path(__file__).resolve().parents[1]
p=urllib.parse.urlparse(os.environ.get('HTTPS_PROXY',''))
cmd=['/tmp/forja-gradle/gradle-8.10.2/bin/gradle','--no-daemon']
if p.hostname:cmd += [f'-D{scheme}.proxy{key}={value}' for scheme in ['https','http'] for key,value in [('Host',p.hostname),('Port',p.port or 80)]]
cmd+=['-Pandroid.builder.sdkDownload=false']+(sys.argv[1:] or [':feature:assembleDebug',':feature:exportCompileClasspath',':feature:testDebugUnitTest'])
with open(root/'cleanup-build-v20.log','w') as f:raise SystemExit(subprocess.call(cmd,cwd=os.environ.get('FORJA_CLEANUP_SOURCE',str(root/'audio-diagnosis/forja-v17/cleanup-update')),env={**os.environ,'ANDROID_HOME':'/tmp/forja-sdk','JAVA_HOME':'/tmp/forja-jdk'},stdout=f,stderr=subprocess.STDOUT))
