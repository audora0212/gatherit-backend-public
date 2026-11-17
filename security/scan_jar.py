"""Match a bootJar to resolved Maven coordinates, then query the public OSV API.

No application classes, configuration, JAR bytes or credentials leave this process.
Only public Maven group:name/version pairs are sent. Unknown packaged libraries,
incomplete API responses and network failures fail closed (exit 2); known affected
versions fail the independent security job (exit 1). Exit 0 is not a safety proof.
"""

import argparse
import hashlib
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path


API = 'https://api.osv.dev/v1/querybatch'
MAX_RESPONSE_BYTES = 16 * 1024 * 1024


def packaged_components(jar_path, resolved):
    if not isinstance(resolved, dict) or not isinstance(resolved.get('artifacts'), list):
        raise ValueError('Invalid resolved runtime inventory')
    mapping = {}
    for item in resolved['artifacts']:
        if not isinstance(item, dict):
            raise ValueError('Invalid resolved Maven coordinate')
        for key in ('file', 'group', 'name', 'version'):
            if not isinstance(item.get(key), str) or not item[key]:
                raise ValueError('Invalid resolved Maven coordinate')
        identity = (item['group'], item['name'], item['version'])
        if item['file'] in mapping and mapping[item['file']] != identity:
            raise ValueError('Ambiguous resolved library filename')
        mapping[item['file']] = identity
    with zipfile.ZipFile(jar_path) as archive:
        libraries = [n for n in archive.namelist()
                     if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')]
    if not libraries or len(libraries) != len(set(libraries)):
        raise ValueError('Missing or duplicate BOOT-INF/lib entries')
    components = []
    for entry in sorted(libraries):
        filename = entry.removeprefix('BOOT-INF/lib/')
        if '/' in filename or filename not in mapping:
            raise ValueError('Packaged library missing from resolved inventory: ' + filename)
        group, name, version = mapping[filename]
        purl = 'pkg:maven/{}/{}@{}'.format(
            urllib.parse.quote(group, safe='.'), urllib.parse.quote(name, safe=''),
            urllib.parse.quote(version, safe=''))
        components.append({'type': 'library', 'group': group, 'name': name,
                           'version': version, 'purl': purl, 'bom-ref': purl})
    # A classifier can yield two files for one Maven package; query the version once.
    return sorted({c['purl']: c for c in components}.values(), key=lambda c: c['purl']), len(libraries)


def request_batch(queries):
    request = urllib.request.Request(
        API, data=json.dumps({'queries': queries}).encode(),
        headers={'Content-Type': 'application/json', 'User-Agent': 'GatherIt-dependency-check/1'},
        method='POST')
    with urllib.request.urlopen(request, timeout=45) as response:
        raw = response.read(MAX_RESPONSE_BYTES + 1)
    if len(raw) > MAX_RESPONSE_BYTES:
        raise ValueError('OSV response exceeded the size limit')
    return json.loads(raw)


def query_osv(components, transport=request_batch):
    findings = {}
    for start in range(0, len(components), 100):
        batch = components[start:start + 100]
        pending = [(c, {'package': {'ecosystem': 'Maven', 'name': c['group'] + ':' + c['name']},
                        'version': c['version']}) for c in batch]
        seen_tokens = set()
        while pending:
            data = transport([query for _, query in pending])
            if not isinstance(data, dict):
                raise ValueError('Invalid OSV batch response')
            results = data.get('results')
            if not isinstance(results, list) or len(results) != len(pending):
                raise ValueError('Incomplete OSV batch response')
            next_pending = []
            for (component, query), result in zip(pending, results):
                if not isinstance(result, dict) or 'error' in result:
                    raise ValueError('OSV package lookup failed')
                vulns = result.get('vulns', [])
                if not isinstance(vulns, list):
                    raise ValueError('Invalid OSV vulnerability list')
                for vuln in vulns:
                    if not isinstance(vuln, dict) or not isinstance(vuln.get('id'), str) or not vuln['id']:
                        raise ValueError('Invalid OSV vulnerability identifier')
                    findings.setdefault(component['purl'], set()).add(vuln['id'])
                token = result.get('next_page_token')
                if token:
                    if not isinstance(token, str) or (component['purl'], token) in seen_tokens:
                        raise ValueError('Invalid or repeated OSV page token')
                    seen_tokens.add((component['purl'], token))
                    next_pending.append((component, {**query, 'page_token': token}))
            pending = next_pending
    return [{'purl': purl, 'vulnerabilities': sorted(ids)} for purl, ids in sorted(findings.items())]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--resolved', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--inventory-only', action='store_true')
    args = parser.parse_args(argv)
    try:
        args.output_dir.mkdir(parents=True, exist_ok=True)
        report_path = args.output_dir / 'osv-findings.json'
        # Reusing an output directory must not expose an earlier clean result on failure.
        report_path.write_text(json.dumps({'status': 'incomplete', 'findings': None}) + '\n', encoding='utf-8')
        with args.jar.open('rb') as artifact:
            components, libraries = packaged_components(artifact, json.loads(args.resolved.read_text(encoding='utf-8')))
            artifact.seek(0)
            digest = hashlib.file_digest(artifact, 'sha256').hexdigest()
        bom = {'bomFormat': 'CycloneDX', 'specVersion': '1.5', 'version': 1,
               'metadata': {'timestamp': datetime.now(timezone.utc).isoformat(),
                            'component': {'type': 'application', 'name': args.jar.name,
                                          'hashes': [{'alg': 'SHA-256', 'content': digest}]}},
               'components': components}
        (args.output_dir / 'runtime.cdx.json').write_text(json.dumps(bom, indent=2) + '\n', encoding='utf-8')
        print(f'Packaged libraries: {libraries}; distinct Maven package versions: {len(components)}')
        if args.inventory_only:
            print('Inventory only; no vulnerability lookup was performed')
            return 0
        findings = query_osv(components)
        report = {'status': 'affected' if findings else 'no-known-vulnerabilities',
                  'artifact_sha256': digest, 'checked_at': datetime.now(timezone.utc).isoformat(),
                  'package_versions_checked': len(components), 'findings': findings}
        report_path.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print(f'Known affected package versions: {len(findings)}')
        for finding in findings:
            print(finding['purl'] + ': ' + ', '.join(finding['vulnerabilities']))
        return 1 if findings else 0
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, urllib.error.URLError) as exc:
        # Do not echo server responses, raw file contents or credential-like values.
        print('Dependency check could not complete: ' + type(exc).__name__, file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
