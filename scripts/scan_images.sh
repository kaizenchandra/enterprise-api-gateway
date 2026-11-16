#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p results/trivy-cache
for service in gateway control orders; do
  image_id="$(docker inspect --format '{{.Image}}' "enterprise-gateway-${service}-1")"
  docker run --rm --cpus=1 --memory=1g \
    -v /var/run/docker.sock:/var/run/docker.sock:ro \
    -v "$PWD/results/trivy-cache:/root/.cache/trivy" \
    -v "$PWD/results:/output" aquasec/trivy:0.76.2 image \
    --image-src docker --scanners vuln --pkg-types os --format json \
    --output "/output/trivy-${service}.json" "$image_id"
done
python3 - <<'PY'
from pathlib import Path
import json,datetime,subprocess
reports=[]
for name in ['gateway','control','orders']:
 d=json.loads(Path(f'results/trivy-{name}.json').read_text());v=[v for r in d.get('Results',[])for v in r.get('Vulnerabilities',[])]
 reports.append({'service':name,'image':d.get('ArtifactName'),'os':d.get('Metadata',{}).get('OS'),'counts':{s:sum(x['Severity']==s for x in v)for s in ['CRITICAL','HIGH','MEDIUM','LOW','UNKNOWN']},'fix_available':sum(bool(x.get('FixedVersion'))for x in v)})
result={'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scanner':'aquasec/trivy:0.76.2','scanner_image':subprocess.check_output(['docker','image','inspect','--format','{{.Id}}','aquasec/trivy:0.76.2'],text=True).strip(),'boundary':'Local OS-package image scans. Maven components are separately scanned using OSV. No images or source uploaded. Three distinct runtime applications cover both gateway replicas and all three example service containers.','reports':reports}
Path('results/image-scan-summary.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
if any(r['counts']['CRITICAL'] or r['counts']['HIGH'] or r['fix_available'] for r in reports): raise SystemExit(1)
PY
