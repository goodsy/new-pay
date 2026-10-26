"""cve-scan.py가 '스캔 안 함'·'조회 실패'·'판정 불일치'를 취약점 0건으로 통과시키지 않는지 검사 (TECH-001).

실제 osv-scanner 대신 stub 실행 파일을 쓴다. stub은 지정된 exit 코드와 결과 JSON을 낸다.
"""
import hashlib
import importlib.util
import json
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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

    def run_generate_sboms(self, create_sboms: bool) -> list[list[str]]:
        """generate_sboms를 임시 ROOT에서 실행하고 gradle argv를 기록한다(실제 gradle·저장소 build/는 건드리지 않는다)."""
        calls: list[list[str]] = []

        def fake_run(argv, **kwargs):
            calls.append(list(argv))
            self.assertEqual(kwargs.get("cwd"), self.dir)
            if create_sboms:
                for module in cve_scan.MODULES:
                    bom = self.dir / f"apps/{module}/build/reports/cyclonedx-direct/bom.json"
                    bom.parent.mkdir(parents=True, exist_ok=True)
                    bom.write_text("{}", encoding="utf-8")
            return subprocess.CompletedProcess(argv, 0)

        with mock.patch.object(cve_scan, "ROOT", self.dir), mock.patch.object(cve_scan.subprocess, "run", fake_run):
            if create_sboms:
                cve_scan.generate_sboms()
            else:
                with self.assertRaises(cve_scan.ScanError):
                    cve_scan.generate_sboms()
        return calls

    def test_sbom_generation_bypasses_build_cache_and_reruns_tasks(self):
        # O-1: SBOM task 결과를 build cache/up-to-date에서 복원하면 현재 의존성으로 재생성됐다는 증거가 없다.
        calls = self.run_generate_sboms(create_sboms=True)
        self.assertEqual(len(calls), 1)
        argv = calls[0]
        self.assertIn("--no-build-cache", argv)
        self.assertIn("--rerun-tasks", argv)
        for module in cve_scan.MODULES:
            self.assertIn(f":apps:{module}:cyclonedxDirectBom", argv)

    def test_sbom_not_regenerated_is_error(self):
        # gradle이 exit 0이어도 이전 SBOM을 지운 뒤 새 파일이 없으면 실패한다.
        stale = self.dir / "apps/payment-api/build/reports/cyclonedx-direct/bom.json"
        stale.parent.mkdir(parents=True)
        stale.write_text("{}", encoding="utf-8")
        self.run_generate_sboms(create_sboms=False)
        self.assertFalse(stale.exists())

    def test_control_fixture_contains_known_vulnerable_component(self):
        control = json.loads(cve_scan.CONTROL_SBOM.read_text(encoding="utf-8"))
        purls = [c["purl"] for c in control["components"]]
        self.assertIn("pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1?type=jar", purls)


NPM_CONTROLS = [("lodash", "4.17.20", False), ("minimist", "1.2.5", True)]


class CveScanNpm(unittest.TestCase):
    """프론트 npm lockfile 검사(ADMIN-BOOT-001 S1, D-ADMIN1-007: dependencies·devDependencies 취약점 모두 FAIL).

    osv는 -L로 받은 lockfile마다 결과(source.path)를 나눈다(S1 실측). canary lockfile에는 dependencies 취약 패키지 1개와
    devDependencies 취약 패키지 1개가 있고 둘 다 검출되어야 그 호출이 dev 의존까지 실제로 조회했다는 증거가 된다.
    """

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        self.config = self.dir / "osv.toml"
        self.config.write_text("", encoding="utf-8")
        self.control = self.lockfile("control", NPM_CONTROLS)

    def lockfile(self, name: str, entries: list) -> Path:
        path = self.dir / name / "package-lock.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        packages = {"": {"name": name}}
        for pkg, version, dev in entries:
            packages[f"node_modules/{pkg}"] = dict({"version": version}, **({"dev": True} if dev else {}))
        path.write_text(json.dumps({"lockfileVersion": 3, "packages": packages}), encoding="utf-8")
        return path

    def frontend(self, count: int = 3) -> Path:
        return self.lockfile("frontend", [(f"p{i}", "1.0.0", i % 2 == 0) for i in range(count)])

    def stub(self, rc: int, frontend_packages: list, vulnerable: tuple = (), control_vulnerable=("lodash", "minimist"),
             include_control: bool = True, frontend_path: str = "") -> Path:
        """-L 순서대로 결과를 낸다. 실행 argv는 args.json에 기록한다."""
        frontend_result = [{"package": {"name": n, "version": v, "ecosystem": "npm"},
                            "vulnerabilities": [{"id": "GHSA-f"}] if n in vulnerable else []}
                           for n, v in frontend_packages]
        control_result = [{"package": {"name": n, "version": v, "ecosystem": "npm"},
                           "vulnerabilities": [{"id": "GHSA-c"}] if n in control_vulnerable else []}
                          for n, v, _ in NPM_CONTROLS]
        spec_file = self.dir / "stub-spec.json"
        spec_file.write_text(json.dumps({"control": str(self.control), "frontend": frontend_result,
                                         "controlResult": control_result, "includeControl": include_control,
                                         "frontendPath": frontend_path}),
                             encoding="utf-8")
        stub = self.dir / "osv-npm-stub"
        stub.write_text(
            f"#!{sys.executable}\n"
            "import json, sys\n"
            "args = sys.argv[1:]\n"
            f"spec = json.load(open({str(spec_file)!r}))\n"
            f"json.dump(args, open({str(self.dir / 'args.json')!r}, 'w'))\n"
            "results = []\n"
            "for i, a in enumerate(args):\n"
            "    if a == '-L':\n"
            "        path = args[i + 1]\n"
            "        if path == spec['control']:\n"
            "            if spec['includeControl']:\n"
            "                results.append({'source': {'path': path, 'type': 'lockfile'}, 'packages': spec['controlResult']})\n"
            "        else:\n"
            "            results.append({'source': {'path': spec['frontendPath'] or path, 'type': 'lockfile'}, 'packages': spec['frontend']})\n"
            "open(args[args.index('--output-file') + 1], 'w').write(json.dumps({'results': results}))\n"
            f"sys.exit({rc})\n", encoding="utf-8")
        stub.chmod(stub.stat().st_mode | stat.S_IEXEC)
        return stub

    def scan(self, stub: Path, lock: Path) -> dict:
        return cve_scan.scan_frontend_with_canary(stub, lock, self.control, self.config, self.dir, "fe")

    def packages(self, count: int = 3) -> list:
        return [(f"p{i}", "1.0.0") for i in range(count)]

    def test_clean_frontend_with_both_canaries_detected_passes(self):
        r = self.scan(self.stub(rc=1, frontend_packages=self.packages()), self.frontend())
        self.assertEqual((r["packages"], r["vulnerabilities"], r["control_vulnerabilities"]), (3, 0, 2))

    def test_frontend_vulnerabilities_exclude_canary(self):
        r = self.scan(self.stub(rc=1, frontend_packages=self.packages(), vulnerable=("p0", "p1")), self.frontend())
        self.assertEqual(r["vulnerabilities"], 2)

    def test_dependency_canary_not_detected_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, frontend_packages=self.packages(), control_vulnerable=("minimist",)), self.frontend())

    def test_dev_dependency_canary_not_detected_is_error(self):
        # devDependencies를 조회하지 않는 호출(예: dev 제외 옵션)은 이것으로 드러난다(D-ADMIN1-007).
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, frontend_packages=self.packages(), control_vulnerable=("lodash",)), self.frontend())

    def test_exit_zero_while_canary_vulnerable_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=0, frontend_packages=self.packages()), self.frontend())

    def test_tool_failure_exit_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=128, frontend_packages=self.packages()), self.frontend())

    def test_missing_control_result_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, frontend_packages=self.packages(), include_control=False), self.frontend())

    def test_result_for_another_source_path_is_not_taken_as_frontend(self):
        # 같은 패키지 목록이라도 다른 lockfile 경로의 결과는 이 프론트를 스캔했다는 증거가 아니다.
        stub = self.stub(rc=1, frontend_packages=self.packages(), frontend_path=str(self.dir / "elsewhere/package-lock.json"))
        with self.assertRaises(cve_scan.ScanError):
            self.scan(stub, self.frontend())

    def test_scanned_packages_differ_from_lockfile_is_error(self):
        # 하나 덜 스캔(중복 병합·파싱 누락)하거나 다른 패키지를 스캔하면 통과가 아니다.
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, frontend_packages=self.packages(2)), self.frontend(3))
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, frontend_packages=[("p0", "1.0.0"), ("p1", "1.0.0"), ("other", "9.9.9")]),
                      self.frontend(3))

    def test_frontend_containing_canary_package_fails_before_scan(self):
        lock = self.lockfile("frontend", [("p0", "1.0.0", False), ("minimist", "1.2.5", True)])
        with self.assertRaises(cve_scan.ControlOverlap):
            self.scan(self.stub(rc=1, frontend_packages=[("p0", "1.0.0"), ("minimist", "1.2.5")]), lock)

    def test_empty_frontend_lockfile_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, frontend_packages=[]), self.frontend(0))

    def test_missing_frontend_lockfile_is_error(self):
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, frontend_packages=self.packages()), self.dir / "absent/package-lock.json")

    def test_osv_argv_scans_both_lockfiles_without_dev_exclusion(self):
        lock = self.frontend()
        self.scan(self.stub(rc=1, frontend_packages=self.packages()), lock)
        argv = json.loads((self.dir / "args.json").read_text(encoding="utf-8"))
        self.assertEqual([argv[i + 1] for i, a in enumerate(argv) if a == "-L"], [str(lock), str(self.control)])
        self.assertIn("--all-packages", argv)
        forbidden = [a for a in argv if any(word in a.lower() for word in ("dev", "prod", "omit", "exclude", "ignore"))]
        self.assertEqual(forbidden, [])

    def test_control_lock_without_dev_dependency_is_rejected(self):
        # fixture가 dev canary를 잃으면 dev 조회 증거가 사라진다 — 스캔 전에 거부한다.
        self.control = self.lockfile("control", [("lodash", "4.17.20", False)])
        with self.assertRaises(cve_scan.ScanError):
            self.scan(self.stub(rc=1, frontend_packages=self.packages()), self.frontend())

    def test_registered_npm_control_fixture_has_dependency_and_dev_dependency(self):
        lock = json.loads(cve_scan.CONTROL_NPM_LOCK.read_text(encoding="utf-8"))
        entries = {k.split("node_modules/")[-1]: (v["version"], v.get("dev", False))
                   for k, v in lock["packages"].items() if k}
        self.assertEqual(entries, {"lodash": ("4.17.20", False), "minimist": ("1.2.5", True)})


if __name__ == "__main__":
    unittest.main()
