"""cve-scan.py가 '스캔 안 함'이나 '판정 불일치'를 취약점 0건으로 통과시키지 않는지 검사 (TECH-001).

실제 osv-scanner 대신 stub 실행 파일을 쓴다. stub은 지정된 exit 코드와 결과 JSON을 낸다.
"""
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


class CveScan(unittest.TestCase):

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        self.config = self.dir / "osv.toml"
        self.config.write_text("", encoding="utf-8")

    def sbom(self, components: int) -> Path:
        path = self.dir / "bom.json"
        path.write_text(json.dumps({"components": [{"name": f"c{i}"} for i in range(components)]}), encoding="utf-8")
        return path

    def stub(self, rc: int, packages: int, vulnerable: int = 0, write_output: bool = True) -> Path:
        result = {"results": [{"packages": [
            {"package": {"name": f"c{i}"}, "vulnerabilities": [{"id": "X"}] if i < vulnerable else []}
            for i in range(packages)]}]}
        stub = self.dir / "osv-stub"
        stub.write_text(
            f"#!{sys.executable}\n"
            "import sys\n"
            "args = sys.argv[1:]\n"
            f"if {write_output!r}:\n"
            f"    open(args[args.index('--output-file') + 1], 'w').write({json.dumps(json.dumps(result))})\n"
            f"sys.exit({rc})\n", encoding="utf-8")
        stub.chmod(stub.stat().st_mode | stat.S_IEXEC)
        return stub

    def scan(self, stub: Path, sbom: Path) -> dict:
        return cve_scan.scan(stub, sbom, self.config, self.dir, "m")

    def test_clean_scan_counts_all_packages(self):
        r = self.scan(self.stub(rc=0, packages=3), self.sbom(3))
        self.assertEqual((r["packages"], r["vulnerabilities"]), (3, 0))

    def test_vulnerabilities_are_counted(self):
        r = self.scan(self.stub(rc=1, packages=3, vulnerable=2), self.sbom(3))
        self.assertEqual(r["vulnerabilities"], 2)

    def test_exit_one_without_parsed_vulnerabilities_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, packages=3), self.sbom(3))

    def test_exit_zero_with_parsed_vulnerabilities_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=0, packages=3, vulnerable=1), self.sbom(3))

    def test_fewer_scanned_packages_than_components_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=0, packages=0), self.sbom(3))

    def test_empty_sbom_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=0, packages=0), self.sbom(0))

    def test_tool_failure_exit_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=128, packages=3), self.sbom(3))

    def test_missing_result_file_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=0, packages=3, write_output=False), self.sbom(3))

    def test_stale_result_file_is_not_reused(self):
        (self.dir / "m.json").write_text(json.dumps({"results": [{"packages": [{"package": {}}] * 3}]}), encoding="utf-8")
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=0, packages=3, write_output=False), self.sbom(3))

    def test_control_fixture_contains_known_vulnerable_component(self):
        control = json.loads(cve_scan.CONTROL_SBOM.read_text(encoding="utf-8"))
        purls = [c["purl"] for c in control["components"]]
        self.assertIn("pkg:maven/org.apache.tomcat.embed/tomcat-embed-core@11.0.24?type=jar", purls)


if __name__ == "__main__":
    unittest.main()
