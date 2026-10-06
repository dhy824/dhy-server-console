"""Read-only version inventory, streamed over SSH; no package-index refresh."""
import json
import re
import subprocess


def run(args):
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=8)
        return (result.stdout + result.stderr)[:4096] if result.returncode == 0 else ''
    except (OSError, subprocess.TimeoutExpired):
        return ''


def match(pattern, text):
    value = re.search(pattern, text)
    return value.group(1) if value else ''


inventory = {
    'mihomo': match(r'Mihomo\s+Meta\s+v?([0-9]+\.[0-9]+\.[0-9]+[^\s]*)', run(['mihomo', '-v'])),
    'nginx': match(r'nginx/([0-9.]+)', run(['nginx', '-v'])),
    'nginx_package': run(['dpkg-query', '-W', '-f=${Version}', 'nginx']).strip(),
    'nginx_candidate': match(r'Candidate:\s*(\S+)', run(['env', 'LC_ALL=C', 'apt-cache', 'policy', 'nginx'])),
    'astrbot': run(['docker', 'exec', 'astrbot', 'python', '-c',
                    "import tomllib,pathlib;print(tomllib.loads(pathlib.Path('/AstrBot/pyproject.toml').read_text())['project']['version'])"]).strip(),
    'cliproxyapi': match(r'CLIProxyAPI Version:\s*v?([0-9]+\.[0-9]+\.[0-9]+)', run(['/opt/cliproxyapi/cli-proxy-api', '--help'])),
}
# Only emit version-shaped fields, never diagnostics, paths, configuration or tokens.
print(json.dumps({k: v if re.fullmatch(r'[A-Za-z0-9.+:~_-]{1,100}', v) else ''
                  for k, v in inventory.items()}))
