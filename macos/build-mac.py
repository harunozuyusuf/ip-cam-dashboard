"""Güncel kaynakları iki mimariye ait doğrulanmış JRE ile macOS ZIP'ine paketler."""
import hashlib, io, json, plistlib, shutil, stat, struct, subprocess, tarfile, urllib.request, zipfile
from pathlib import Path

root=Path(__file__).resolve().parent.parent
folder=root/'macos'; build=root/'mac-build'; cache=root/'mac-downloads'
build.mkdir(exist_ok=True);cache.mkdir(exist_ok=True)
classes=build/'classes';classes.mkdir(exist_ok=True)
subprocess.run(['javac','-encoding','UTF-8','-d',str(classes),str(root/'src/Main.java')],check=True)
jar=build/'IPCameraMonitor-J.3.3.jar'
subprocess.run(['jar','--create','--file',str(jar),'--main-class','Main','-C',str(classes),'.'],check=True)
manifest=json.loads((folder/'runtime-manifest.json').read_text(encoding='utf-8-sig'))
for runtime in manifest:
    dest=cache/runtime['name']
    if not dest.exists():urllib.request.urlretrieve(runtime['link'],dest)
    assert hashlib.sha256(dest.read_bytes()).hexdigest()==runtime['checksum'],dest

output=root/'outputs';output.mkdir(exist_ok=True)
dest=output/'IPCameraMonitor-J.3.3-macOS-Universal-DAYRESET-EVENTLOG.zip'
prefix='IPCameraMonitor-J.3.3-macOS-Universal/'
def add(z,name,data,mode=0o644,kind=stat.S_IFREG):
    info=zipfile.ZipInfo(prefix+name);info.create_system=3
    info.external_attr=(kind|mode)<<16;info.compress_type=zipfile.ZIP_DEFLATED
    z.writestr(info,data)

with zipfile.ZipFile(dest,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for app,executable,script,identifier in [
        ('IP Kamera Monitor.app','IPKameraMonitor','launch.sh','local.ipkameramonitor.eventlog'),
        ('IP Kamera Monitor Durdur.app','IPKameraMonitorDurdur','stop.sh','local.ipkameramonitor.eventlog.stop')]:
        contents=app+'/Contents/'
        info={'CFBundleName':app[:-4],'CFBundleDisplayName':app[:-4],
              'CFBundleIdentifier':identifier,'CFBundleVersion':'3.3.0',
              'CFBundleShortVersionString':'3.3.0','CFBundlePackageType':'APPL',
              'CFBundleExecutable':executable,'CFBundleIconFile':'rj45.icns',
              'LSMinimumSystemVersion':'11.0','LSUIElement':True,
              'NSHighResolutionCapable':True}
        add(z,contents+'Info.plist',plistlib.dumps(info))
        add(z,contents+'MacOS/'+executable,(folder/script).read_bytes(),0o755)
        add(z,contents+'Resources/rj45.icns',(folder/'rj45.icns').read_bytes())
    res='IP Kamera Monitor.app/Contents/Resources/'
    add(z,res+jar.name,jar.read_bytes())
    add(z,res+'ui.html',(root/'src/ui.html').read_bytes())
    for runtime in manifest:
        arch='arm64' if runtime['arch']=='aarch64' else 'x64'
        with tarfile.open(cache/runtime['name'],'r:gz') as archive:
            for item in archive:
                # Üst JRE klasörünü kaldırır; macOS .jre Contents dizilimi korunur.
                path=item.name.split('/',1)
                if len(path)!=2 or not path[1] or item.isdir():continue
                name=res+'runtime-'+arch+'/'+path[1]
                assert '..' not in Path(path[1]).parts
                if item.issym():add(z,name,item.linkname.encode(),item.mode,stat.S_IFLNK)
                elif item.isfile():add(z,name,archive.extractfile(item).read(),item.mode)
                else:raise RuntimeError('Desteklenmeyen arşiv girdisi: '+item.name)
    add(z,'README_MAC.txt',(folder/'README_MAC.txt').read_bytes())
    add(z,'runtime-manifest.json',(folder/'runtime-manifest.json').read_bytes())

with zipfile.ZipFile(dest) as z:
    assert z.testzip() is None
    for arch,cpu in [('arm64',0x0100000c),('x64',0x01000007)]:
        executable=prefix+res+'runtime-'+arch+'/Contents/Home/bin/java'
        meta=z.getinfo(executable);binary=z.read(executable)
        assert (meta.external_attr>>16)&0o111,executable
        assert binary[:4]==b'\xcf\xfa\xed\xfe',executable
        assert struct.unpack('<I',binary[4:8])[0]==cpu,executable
    for name in z.namelist():
        if name.endswith('Info.plist'):
            meta=plistlib.loads(z.read(name))
            if '/runtime-' not in name:
                executable=name[:-len('Info.plist')]+'MacOS/'+meta['CFBundleExecutable']
                assert (z.getinfo(executable).external_attr>>16)&0o111
                assert b'\r' not in z.read(executable)
    assert not any(name.endswith('.exe') for name in z.namelist())
    assert z.read(prefix+res+'ui.html')==(root/'src/ui.html').read_bytes()
digest=hashlib.sha256(dest.read_bytes()).hexdigest()
dest.with_suffix('.zip.sha256').write_text(digest+'  '+dest.name+'\n',encoding='ascii')
print(json.dumps({'file':str(dest),'bytes':dest.stat().st_size,'sha256':digest,'checks':'ZIP CRC, arm64/x64 Mach-O, executable permissions, plists, UI identity'},ensure_ascii=False))
