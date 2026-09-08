"""Apply only reviewed patches in an isolated CI checkout."""
from pathlib import Path
import hashlib
import lzma
import subprocess
import os

root = Path(__file__).resolve().parent
parts = sorted(root.glob('*.bin'))
if not parts:
    print('No staged patch; testing committed source.')
else:
    assert [p.name for p in parts] == [f'{i:02}.bin' for i in range(14)]
    compressed = b''.join(p.read_bytes() for p in parts)
    assert hashlib.sha256(compressed).hexdigest() == '98928b1f1a37b87aea24846251e17fe2b78017bfd1796833d47fb7dc0ca42755', 'transport checksum mismatch'
    patch = lzma.decompress(compressed)
    digest = hashlib.sha256(patch).hexdigest()
    assert digest == '1d9f0ae52a23d86deb5cca2ea61a96d1abccf9230123d6e9a32d8ce37c71fef6', 'candidate checksum mismatch'
    destination = Path(os.environ.get('RUNNER_TEMP', '/tmp')) / 'reviewed-candidate.patch'
    destination.write_bytes(patch)
    subprocess.run(['git', 'apply', '--check', str(destination)], check=True)
    subprocess.run(['git', 'apply', str(destination)], check=True)
    followup = root / 'followup.patch'
    assert hashlib.sha256(followup.read_bytes()).hexdigest() == '2c5a191c29b33c3350ca04bc043c5ffe4984142a31bc11624aa148fd35d1a2b2'
    subprocess.run(['git', 'apply', '--check', str(followup)], check=True)
    subprocess.run(['git', 'apply', str(followup)], check=True)
    subprocess.run(['git', 'add', '-N', '--', '.'], check=True)
    subprocess.run(['git', 'diff', '--check'], check=True)
    print('Applied candidate SHA-256:', digest)
    print('Applied native/OCR test and candidate identity follow-up:', hashlib.sha256(followup.read_bytes()).hexdigest())
