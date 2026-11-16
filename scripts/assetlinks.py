#!/usr/bin/env python3
"""Generate verified Android App Links metadata from the real Klang signing cert."""
import argparse,json,re
from pathlib import Path
parser=argparse.ArgumentParser()
parser.add_argument('fingerprint',help='SHA-256 fingerprint of the Klang APK signing certificate')
args=parser.parse_args()
if not re.fullmatch(r'(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}',args.fingerprint):
    parser.error('Use the colon-separated SHA-256 certificate fingerprint')
path=Path(__file__).parent.parent/'web/.well-known/assetlinks.json'
path.parent.mkdir(parents=True,exist_ok=True)
path.write_text(json.dumps([{'relation':['delegate_permission/common.handle_all_urls'],
 'target':{'namespace':'android_app','package_name':'io.github.lynlioss.klang',
 'sha256_cert_fingerprints':[args.fingerprint.upper()]}}],indent=2)+'\n')
print(path)
