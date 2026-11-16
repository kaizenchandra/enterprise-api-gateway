#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
python3 - <<'INNER'
from pathlib import Path
import secrets,subprocess,base64
p=Path('docker/.env');existing=p.read_text() if p.exists() else ''
names={line.split('=',1)[0] for line in existing.splitlines() if '=' in line}
values={}
for name in ['CONTROL_TOKEN','DATABASE_PASSWORD','GRAFANA_PASSWORD']:
 if name not in names:values[name]=secrets.token_urlsafe(32)
if not {'IDENTITY_PRIVATE_KEY','IDENTITY_PUBLIC_KEY'}.issubset(names):
 if 'IDENTITY_PRIVATE_KEY' in names or 'IDENTITY_PUBLIC_KEY' in names:raise SystemExit('Incomplete identity keypair; repair .env explicitly')
 pem=subprocess.check_output(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048'],stderr=subprocess.DEVNULL)
 values['IDENTITY_PRIVATE_KEY']=''.join(x for x in pem.decode().splitlines() if not x.startswith('---'))
 public=subprocess.check_output(['openssl','pkey','-pubout','-outform','DER'],input=pem,stderr=subprocess.DEVNULL)
 values['IDENTITY_PUBLIC_KEY']=base64.b64encode(public).decode()
with p.open('a') as f:
 if existing and not existing.endswith('\n'):f.write('\n')
 for name,value in values.items():f.write(name+'='+value+'\n')
p.chmod(0o600)
print('Local environment ready; existing credentials preserved; secrets not displayed')
INNER
