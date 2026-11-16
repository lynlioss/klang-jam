import json,os,zipfile
from pathlib import Path
manifest=json.loads(Path('plugin.json').read_text())
assert manifest==json.loads(Path('plugin/src/main/res/raw/plugin.json').read_text())
assert manifest['apiVersion']==1 and not manifest['defaultEnabled']
tag=os.environ.get('GITHUB_REF','')
if tag.startswith('refs/tags/'):
    assert tag=='refs/tags/v'+manifest['version'], 'Tag must match plugin version'
with zipfile.ZipFile('plugin.apk') as apk:
    files=apk.namelist()
    assert any(p.startswith('lib/arm64-v8a/') for p in files), 'WebRTC arm64 native library missing'
print('Release manifest, version and native packaging verified')
