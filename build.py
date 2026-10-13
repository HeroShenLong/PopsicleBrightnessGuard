"""Build both APK variants with official SDK tools; API jars are compile-only.
Usage: python build.py --sdk PATH --jdk PATH [--out PATH]
No Gradle, external cache writes, or network access during this build.
"""
import argparse
import hashlib
import json
import shutil
import struct
import re
from pathlib import Path
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--sdk', required=True, type=Path)
parser.add_argument('--jdk', required=True, type=Path)
parser.add_argument('--out', type=Path, default=ROOT / 'dist')
args = parser.parse_args()
tools = args.sdk / 'build-tools/36.1.0'
android = args.sdk / 'platforms/android-36/android.jar'
java = args.jdk / 'bin/java.exe'
javac = args.jdk / 'bin/javac.exe'
keytool = args.jdk / 'bin/keytool.exe'
args.out.mkdir(parents=True, exist_ok=True)
build = ROOT / 'build'
build.mkdir(exist_ok=True)

def run(command):
    command = [str(c) for c in command]
    print('RUN', Path(command[0]).name, ' '.join(command[1:3]), flush=True)
    subprocess.run(command, check=True)

check_results={}
def checked_java(classpath,main_class):
    result=subprocess.run([str(java),'-cp',str(classpath),main_class],check=True,capture_output=True,text=True)
    print(result.stdout,flush=True)
    found=re.search(r'PASS (\d+)',result.stdout)
    if not found: raise ValueError('Test did not report a check count')
    check_results[main_class]=int(found.group(1))

def clean_build_dir(path):
    # Only remove explicitly checked intermediate directories inside this project's build folder.
    resolved=path.resolve()
    if not resolved.is_relative_to(build.resolve()) or resolved==build.resolve():
        raise ValueError('Refusing to clean path outside intermediate build directory')
    if path.exists(): shutil.rmtree(path)
    path.mkdir(parents=True)

def dex_definitions(data):
    string_count,string_offset=struct.unpack_from('<II',data,56)
    type_count,type_offset=struct.unpack_from('<II',data,64)
    class_count,class_offset=struct.unpack_from('<II',data,96)
    strings=[]
    for i in range(string_count):
        p=struct.unpack_from('<I',data,string_offset+4*i)[0]
        while data[p]&128: p+=1
        p+=1
        strings.append(data[p:data.index(b'\x00',p)].decode('utf-8',errors='replace'))
    types=[strings[struct.unpack_from('<I',data,type_offset+4*i)[0]] for i in range(type_count)]
    return [types[struct.unpack_from('<I',data,class_offset+32*i)[0]] for i in range(class_count)]

test_classes = build / 'test-classes'
clean_build_dir(test_classes)
base = ROOT / 'src/io/github/popsicle/brightnessguard'
run([javac, '-encoding','UTF-8','-d',test_classes,base/'Policy.java',base/'Reflect.java',ROOT/'tests/PolicyTest.java'])
checked_java(test_classes,'PolicyTest')
fixture_classes=build/'fixture-classes'
clean_build_dir(fixture_classes)
fixture_sources=list((ROOT/'tests/fixtures').rglob('*.java'))
fixture_sources += [base/(name+'.java') for name in ['Policy','Reflect','Keys','HookBridge','GuardEngine','BrightnessAssessment','BrightnessMonitor','RootBacklightReader']]
fixture_sources += [ROOT/'tests/HookIntegrationTest.java',ROOT/'tests/BrightnessMonitorTest.java']
run([javac,'-encoding','UTF-8','-classpath',android,'-d',fixture_classes]+fixture_sources)
checked_java(str(fixture_classes)+';'+str(android),'HookIntegrationTest')
checked_java(str(fixture_classes)+';'+str(android),'BrightnessMonitorTest')

key = build / 'experimental-signing.p12'
if not key.exists():
    run([keytool,'-genkeypair','-keystore',key,'-storetype','PKCS12','-alias','brightness-guard',
         '-storepass','local-experimental','-keypass','local-experimental','-keyalg','RSA','-keysize','3072',
         '-validity','3650','-dname','CN=Popsicle Brightness Guard Experimental','-noprompt'])

resources = build / 'resources.zip'
run([tools/'aapt2.exe','compile','--dir',ROOT/'res','-o',resources])
results = []
for variant, api, entry in [('modern','modern-101','ModernEntry'),('legacy','legacy-82','LegacyEntry')]:
    work = build / variant
    work.mkdir(exist_ok=True)
    classes = work / 'classes'
    clean_build_dir(classes)
    generated = work / 'generated/io/github/popsicle/brightnessguard/VariantInfo.java'
    generated.parent.mkdir(parents=True,exist_ok=True)
    generated.write_text('package io.github.popsicle.brightnessguard; public final class VariantInfo { public static final String API = "'+api+'"; }\n',encoding='utf-8')
    api_jar = ROOT / ('libs/libxposed-api-101.0.1.jar' if variant=='modern' else 'libs/xposed-api-82.jar')
    api_classes = work/'api-classes'
    clean_build_dir(api_classes)
    with zipfile.ZipFile(api_jar) as z:
        for name in z.namelist():
            if name.endswith('.class'):
                dest=api_classes/name
                dest.parent.mkdir(parents=True,exist_ok=True)
                dest.write_bytes(z.read(name))
    sources = list((ROOT/'src').rglob('*.java')) + list((ROOT/variant).rglob('*.java')) + [generated]
    argfile = work/'javac.args'
    compiler_args = ['-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-classpath',str(android)+';'+str(api_classes),'-d',str(classes)]+[str(p) for p in sources]
    argfile.write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in compiler_args),encoding='utf-8')
    run([javac,'@'+str(argfile)])
    classjar = work/'classes.jar'
    with zipfile.ZipFile(classjar,'w',zipfile.ZIP_DEFLATED) as z:
        for p in classes.rglob('*.class'): z.write(p,p.relative_to(classes).as_posix())
    dex = work/'dex'
    clean_build_dir(dex)
    run([java,'-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','31','--lib',android,'--classpath',api_jar,'--output',dex,classjar])
    manifest = work/'AndroidManifest.xml'
    tree = ET.parse(ROOT/'AndroidManifest.xml')
    ET.register_namespace('android','http://schemas.android.com/apk/res/android')
    if variant=='legacy':
        app=tree.getroot().find('application')
        for name,value in [('xposedmodule','true'),('xposedminversion','82'),('xposeddescription','针对主屏显示热亮度上限的检测与拦截实验模块')]:
            ET.SubElement(app,'meta-data',{'{http://schemas.android.com/apk/res/android}name':name,'{http://schemas.android.com/apk/res/android}value':value})
        ET.SubElement(app,'meta-data',{'{http://schemas.android.com/apk/res/android}name':'xposedscope','{http://schemas.android.com/apk/res/android}resource':'@array/xposed_scope'})
    tree.write(manifest,encoding='utf-8',xml_declaration=True)
    unsigned=work/'unsigned.apk'
    run([tools/'aapt2.exe','link','-o',unsigned,'-I',android,'--manifest',manifest,
         '--min-sdk-version','31','--target-sdk-version','36','--version-code','2',
         '--version-name','0.2.0-experimental',resources])
    with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:
        for p in dex.glob('*.dex'): z.write(p,p.name)
        if variant=='modern':
            z.writestr('META-INF/xposed/java_init.list','io.github.popsicle.brightnessguard.ModernEntry\n')
            z.writestr('META-INF/xposed/module.prop','minApiVersion=101\ntargetApiVersion=101\nstaticScope=true\nexceptionMode=protective\n')
            z.writestr('META-INF/xposed/scope.list','system\n')
        else: z.writestr('assets/xposed_init','io.github.popsicle.brightnessguard.LegacyEntry\n')
    aligned=work/'aligned.apk'
    run([tools/'zipalign.exe','-f','-p','4',unsigned,aligned])
    final=args.out/('PopsicleBrightnessGuard-0.2.0-'+variant+'.apk')
    run([java,'-jar',tools/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias','brightness-guard',
         '--ks-pass','pass:local-experimental','--key-pass','pass:local-experimental','--out',final,aligned])
    run([java,'-jar',tools/'lib/apksigner.jar','verify','--verbose','--print-certs',final])
    with zipfile.ZipFile(final) as z:
        names=set(z.namelist())
        assert 'classes.dex' in names and 'AndroidManifest.xml' in names
        assert ('assets/xposed_init' in names)==(variant=='legacy')
        assert ('META-INF/xposed/java_init.list' in names)==(variant=='modern')
        definitions=[]
        for name in names:
            if name.endswith('.dex'): definitions+=dex_definitions(z.read(name))
        assert definitions and all(d.startswith('Lio/github/popsicle/brightnessguard/') for d in definitions), 'API or test classes embedded in APK'
    results.append({'file':final.name,'bytes':final.stat().st_size,'sha256':hashlib.sha256(final.read_bytes()).hexdigest(),'api':api,'module_class_definitions':len(definitions),'api_and_test_classes_embedded':False})

(args.out/'build-verification-0.2.0.json').write_text(json.dumps({'host_checks':check_results,'total_host_checks':sum(check_results.values()),'test_note':'isolated JVM checks, not ART/device tests','artifacts':results,'device_test':'not performed; user report confirms previous 0.1.0 module loads'},indent=2),encoding='utf-8')
print(json.dumps(results,indent=2),flush=True)
