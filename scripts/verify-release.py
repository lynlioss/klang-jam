import json,os,struct,sys,zipfile
from pathlib import Path
manifest=json.loads(Path('plugin.json').read_text())
assert manifest==json.loads(Path('plugin/src/main/res/raw/plugin.json').read_text())
assert manifest['apiVersion']==1 and not manifest['defaultEnabled']
tag=os.environ.get('GITHUB_REF','')
if tag.startswith('refs/tags/'):
    assert tag=='refs/tags/v'+manifest['version'], 'Tag must match plugin version'
def dex_classes(data):
    def uint(offset):
        return struct.unpack_from('<I', data, offset)[0]
    def string(index):
        offset = uint(uint(60) + 4 * index)
        while data[offset] & 128:
            offset += 1
        offset += 1
        return data[offset:data.index(0, offset)].decode('utf-8')
    types = uint(68)
    classes = uint(100)
    return {string(uint(types + 4 * uint(classes + 32 * index))) for index in range(uint(96))}

with zipfile.ZipFile(sys.argv[1] if len(sys.argv) > 1 else 'plugin.apk') as apk:
    files=apk.namelist()
    assert any(p.startswith('lib/arm64-v8a/') for p in files), 'WebRTC arm64 native library missing'
    classes = set().union(*(dex_classes(apk.read(p)) for p in files if p.startswith('classes') and p.endswith('.dex')))
    for name in ('org/jni_zero/JniInit', 'org/jni_zero/JniUtil', 'org/webrtc/PeerConnectionFactory', 'org/webrtc/DataChannel'):
        assert 'L' + name + ';' in classes, f'Native JNI entry point removed from APK: {name}'
print('Release manifest, version and native packaging verified')
