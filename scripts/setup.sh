#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
from pathlib import Path
import secrets
p=Path('.env')
if p.exists():
    print('Existing .env retained.')
else:
    values={'TF_DATABASE_PASSWORD':secrets.token_hex(24),'TF_STORAGE_ACCESS_KEY':'taskforge-local',
            'TF_STORAGE_SECRET_KEY':secrets.token_hex(24),'TF_AUTH_TOKENS':secrets.token_hex(24)+'=alice,'+secrets.token_hex(24)+'=bob'}
    p.write_text(''.join(f'{k}={v}\n' for k,v in values.items()))
    p.chmod(0o600)
    print('Created private local .env. Bearer tokens are in TF_AUTH_TOKENS; do not commit them.')
PY
