import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from contextlib import redirect_stderr
from unittest.mock import patch


spec = importlib.util.spec_from_file_location('scan_jar', Path(__file__).with_name('scan_jar.py'))
scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scan)


class JarScanTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.jar = self.root / 'app.jar'
        with zipfile.ZipFile(self.jar, 'w') as jar:
            jar.writestr('BOOT-INF/classes/application.yml', 'not read or uploaded')
            jar.writestr('BOOT-INF/lib/example-1.0.jar', b'library bytes are not read')
        self.inventory = {'artifacts': [{'file': 'example-1.0.jar', 'group': 'org.example',
                                         'name': 'example', 'version': '1.0'}]}

    def components(self):
        return scan.packaged_components(self.jar, self.inventory)[0]

    def test_only_packaged_runtime_components_are_included(self):
        self.inventory['artifacts'].append({'file': 'test-only.jar', 'group': 'org.test',
                                            'name': 'test-only', 'version': '2'})
        components, count = scan.packaged_components(self.jar, self.inventory)
        self.assertEqual(count, 1)
        self.assertEqual([c['purl'] for c in components], ['pkg:maven/org.example/example@1.0'])

    def test_unknown_library_fails_instead_of_silently_skipping(self):
        with self.assertRaises(ValueError):
            scan.packaged_components(self.jar, {'artifacts': []})

    def test_conflicting_coordinates_fail(self):
        self.inventory['artifacts'].append({**self.inventory['artifacts'][0], 'group': 'other'})
        with self.assertRaises(ValueError):
            self.components()

    def test_duplicate_jar_entries_fail(self):
        with zipfile.ZipFile(self.jar, 'a') as jar:
            with self.assertWarns(UserWarning):
                jar.writestr('BOOT-INF/lib/example-1.0.jar', b'duplicate')
        with self.assertRaises(ValueError):
            self.components()

    def test_only_public_package_coordinates_leave_the_process(self):
        sent = []
        def transport(queries):
            sent.extend(queries)
            return {'results': [{}]}
        self.assertEqual(scan.query_osv(self.components(), transport), [])
        self.assertEqual(sent, [{'package': {'ecosystem': 'Maven', 'name': 'org.example:example'},
                                 'version': '1.0'}])

    def test_known_findings_and_pagination_are_preserved(self):
        responses = iter([{'results': [{'vulns': [{'id': 'GHSA-first'}], 'next_page_token': 'next'}]},
                          {'results': [{'vulns': [{'id': 'GHSA-second'}, {'id': 'GHSA-first'}]}]}])
        sent = []
        def transport(queries):
            sent.append(queries)
            return next(responses)
        result = scan.query_osv(self.components(), transport)
        self.assertEqual(result[0]['vulnerabilities'], ['GHSA-first', 'GHSA-second'])
        self.assertEqual(sent[1][0]['page_token'], 'next')

    def test_empty_wrong_count_or_invalid_response_fails(self):
        for response in (None, {}, {'results': []}, {'results': [None]}, {'results': [{'error': 'failed'}]},
                         {'results': [{'vulns': [{}]}]}):
            with self.subTest(response=response), self.assertRaises(ValueError):
                scan.query_osv(self.components(), lambda _: response)

    def test_repeated_page_token_fails(self):
        with self.assertRaises(ValueError):
            scan.query_osv(self.components(), lambda _: {'results': [{'next_page_token': 'repeat'}]})

    def test_network_failure_is_not_a_clean_scan(self):
        resolved = self.root / 'resolved.json'
        resolved.write_text(json.dumps(self.inventory), encoding='utf-8')
        error = io.StringIO()
        with patch.object(scan, 'query_osv', side_effect=OSError('do not print secret-shaped message')), redirect_stderr(error):
            self.assertEqual(scan.main(['--jar', str(self.jar), '--resolved', str(resolved),
                                        '--output-dir', str(self.root / 'reports')]), 2)
        self.assertIn('OSError', error.getvalue())
        self.assertNotIn('secret-shaped', error.getvalue())
        self.assertTrue((self.root / 'reports/runtime.cdx.json').is_file())
        self.assertEqual(json.loads((self.root / 'reports/osv-findings.json').read_text())['status'], 'incomplete')

    def test_inventory_only_is_explicit_and_does_not_query(self):
        resolved = self.root / 'resolved.json'
        resolved.write_text(json.dumps(self.inventory), encoding='utf-8')
        with patch.object(scan, 'query_osv') as query:
            self.assertEqual(scan.main(['--jar', str(self.jar), '--resolved', str(resolved),
                                        '--output-dir', str(self.root / 'reports'), '--inventory-only']), 0)
            query.assert_not_called()

    def test_findings_fail_and_clean_lookup_is_explicit(self):
        resolved = self.root / 'resolved.json'
        resolved.write_text(json.dumps(self.inventory), encoding='utf-8')
        args = ['--jar', str(self.jar), '--resolved', str(resolved), '--output-dir', str(self.root / 'reports')]
        for findings, code, status in (([{'purl': 'pkg:maven/org.example/example@1.0',
                                        'vulnerabilities': ['GHSA-test']}], 1, 'affected'),
                                       ([], 0, 'no-known-vulnerabilities')):
            with patch.object(scan, 'query_osv', return_value=findings):
                self.assertEqual(scan.main(args), code)
            report = json.loads((self.root / 'reports/osv-findings.json').read_text())
            self.assertEqual(report['status'], status)
            self.assertEqual(report['package_versions_checked'], 1)

    def test_invalid_inventory_shapes_fail(self):
        for inventory in (None, {}, {'artifacts': None}, {'artifacts': [None]}):
            with self.subTest(inventory=inventory), self.assertRaises(ValueError):
                scan.packaged_components(self.jar, inventory)

    def test_requests_are_bounded_batches_and_keep_every_package(self):
        components = [{'group': 'org.example', 'name': f'package-{i}', 'version': '1.0',
                       'purl': f'pkg:maven/org.example/package-{i}@1.0'} for i in range(201)]
        requests = []
        def transport(queries):
            requests.append(queries)
            return {'results': [{} for _ in queries]}
        self.assertEqual(scan.query_osv(components, transport), [])
        self.assertEqual([len(q) for q in requests], [100, 100, 1])
        self.assertEqual(sum(len(q) for q in requests), 201)


if __name__ == '__main__':
    unittest.main()
