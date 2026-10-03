"""cve-scan.py가 '스캔 안 함'·'조회 실패'·'판정 불일치'를 취약점 0건으로 통과시키지 않는지 검사 (TECH-001).

실제 osv-scanner 대신 stub 실행 파일을 쓴다. stub은 지정된 exit 코드와 결과 JSON을 낸다.
"""
import hashlib
import importlib.util
import json
import shutil
import stat
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/cve-scan.py"
spec = importlib.util.spec_from_file_location("cve_scan", SCRIPT)
cve_scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cve_scan)

CONTROL = {"name": "log4j-core", "version": "2.14.1"}


class CveScan(unittest.TestCase):

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        self.config = self.dir / "osv.toml"
        self.config.write_text("", encoding="utf-8")
        self.control = self.dir / "control.cdx.json"
        self.control.write_text(json.dumps({"components": [dict(CONTROL)]}), encoding="utf-8")

    def sbom(self, components: int) -> Path:
        path = self.dir / "bom.json"
        comps = [{"name": f"c{i}", "version": "1.0"} for i in range(components)]
        path.write_text(json.dumps({"components": comps}), encoding="utf-8")
        return path

    def stub(self, rc: int, module_packages: int, module_vulnerable: int = 0,
             control_present: bool = True, control_vulnerable: bool = True, write_output: bool = True) -> Path:
        packages = [{"package": {"name": f"c{i}", "version": "1.0"},
                     "vulnerabilities": [{"id": "X"}] if i < module_vulnerable else []}
                    for i in range(module_packages)]
        if control_present:
            packages.append({"package": dict(CONTROL),
                             "vulnerabilities": [{"id": "GHSA-control"}] if control_vulnerable else []})
        result = {"results": [{"packages": packages}]}
        stub = self.dir / "osv-stub"
        stub.write_text(
            f"#!{sys.executable}\n"
            "import sys\n"
            "args = sys.argv[1:]\n"
            "if '--version' in args:\n"
            "    print('osv-scanner version: stub'); sys.exit(0)\n"
            f"if {write_output!r}:\n"
            f"    open(args[args.index('--output-file') + 1], 'w').write({json.dumps(json.dumps(result))})\n"
            f"sys.exit({rc})\n", encoding="utf-8")
        stub.chmod(stub.stat().st_mode | stat.S_IEXEC)
        return stub

    def scan(self, stub: Path, sbom: Path) -> dict:
        return cve_scan.scan_with_canary(stub, sbom, self.control, self.config, self.dir, "m")

    def test_clean_module_with_detected_canary_passes(self):
        r = self.scan(self.stub(rc=1, module_packages=3), self.sbom(3))
        self.assertEqual((r["packages"], r["vulnerabilities"], r["control_vulnerabilities"]), (4, 0, 1))

    def test_module_vulnerabilities_exclude_canary(self):
        r = self.scan(self.stub(rc=1, module_packages=3, module_vulnerable=2), self.sbom(3))
        self.assertEqual(r["vulnerabilities"], 2)

    def test_canary_not_detected_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=0, module_packages=3, control_vulnerable=False), self.sbom(3))

    def test_exit_zero_while_canary_vulnerable_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=0, module_packages=3), self.sbom(3))

    def test_missing_canary_package_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, module_packages=4, control_present=False), self.sbom(3))

    def test_module_containing_canary_artifact_fails_before_merge(self):
        # 예: override가 빠져 모듈이 알려진 취약 버전으로 돌아간 경우. canary로 흡수되면 안 된다.
        sbom = self.dir / "bom.json"
        sbom.write_text(json.dumps({"components": [{"name": "c0", "version": "1.0"}, dict(CONTROL)]}), encoding="utf-8")
        with self.assertRaises(cve_scan.ControlOverlap):
            self.scan(self.stub(rc=1, module_packages=1), sbom)

    def test_osv_deduplicating_packages_is_error(self):
        # osv가 패키지를 합쳐 하나 덜 보고하면 통과가 아니다.
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, module_packages=2), self.sbom(3))

    def test_fewer_scanned_packages_than_components_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, module_packages=1), self.sbom(3))

    def test_empty_sbom_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, module_packages=0), self.sbom(0))

    def test_tool_failure_exit_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=128, module_packages=3), self.sbom(3))

    def test_missing_result_file_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, module_packages=3, write_output=False), self.sbom(3))

    def test_stale_result_file_is_not_reused(self):
        stale = {"results": [{"packages": [{"package": {"name": f"c{i}", "version": "1.0"}} for i in range(3)]
                              + [{"package": dict(CONTROL), "vulnerabilities": [{"id": "X"}]}]}]}
        (self.dir / "m.json").write_text(json.dumps(stale), encoding="utf-8")
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, module_packages=3, write_output=False), self.sbom(3))

    def test_tool_checksum_mismatch_is_error(self):
        stub = self.stub(rc=1, module_packages=1)
        with self.assertRaises(cve_scan.ScanError):
            cve_scan.check_tool(stub, "0" * 64)

    def test_tool_checksum_match_returns_version(self):
        stub = self.stub(rc=1, module_packages=1)
        digest = hashlib.sha256(stub.read_bytes()).hexdigest()
        self.assertIn("stub", cve_scan.check_tool(stub, digest))

    def test_tool_with_empty_version_output_is_error(self):
        silent = self.dir / "silent"
        silent.write_text(f"#!{sys.executable}\n", encoding="utf-8")
        silent.chmod(silent.stat().st_mode | stat.S_IEXEC)
        with self.assertRaises(cve_scan.ScanError):
            cve_scan.check_tool(silent, hashlib.sha256(silent.read_bytes()).hexdigest())

    def test_registered_checksum_is_read_from_commands(self):
        self.assertRegex(cve_scan.registered_osv_sha256(cve_scan.COMMANDS), r"^[0-9a-f]{64}$")

    def test_control_fixture_contains_known_vulnerable_component(self):
        control = json.loads(cve_scan.CONTROL_SBOM.read_text(encoding="utf-8"))
        purls = [c["purl"] for c in control["components"]]
        self.assertIn("pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1?type=jar", purls)


if __name__ == "__main__":
    unittest.main()
