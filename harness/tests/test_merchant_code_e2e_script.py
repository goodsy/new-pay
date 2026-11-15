"""MERCHANT-CODE-001 두 앱 Demo E2E 절차(harness/e2e/merchant_code_e2e.py)의 실행 없는 단위 시험.

Demo 스택·네트워크를 쓰지 않는다: Demo 전용 base URL(운영·원격 endpoint 거부), 인자 검증, 단계 목록 고정, 저장소 밖 모듈·셸·동적 import 금지,
TOTP(RFC 6238 벡터), 비밀 비노출(계정 repr·로그인 단계 기록), 실제 HTTP 상태 기록, 단계 판정(OPERATOR 역할·코드 검색 1건·matchedRetiredCode)만 본다.
실제 E2E 실행은 Coordinator가 Demo 스택에서 한다.
"""
import ast
import importlib.util
import io
import os
import tempfile
import unittest
import urllib.request
from email.message import Message

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PATH = os.path.join(ROOT, "harness", "e2e", "merchant_code_e2e.py")
SPEC = importlib.util.spec_from_file_location("merchant_code_e2e", PATH)
e2e = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(e2e)



def source_text():
    with open(PATH, encoding="utf-8") as f:
        return f.read()


RFC_SECRET_B32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"  # ASCII "12345678901234567890"(RFC 6238 Appendix B SHA-1 키)


class MerchantCodeE2eScriptTest(unittest.TestCase):
    def test_base_url_accepts_only_loopback_demo_endpoints(self):
        for ok in ("http://127.0.0.1:8090", "http://localhost:8090", "http://[::1]:8090", "https://localhost", "http://127.0.0.1:8090/"):
            self.assertTrue(e2e.loopback_base_url(ok).startswith(("http://", "https://")), ok)
        for bad in (
            "https://admin.paycore.example", "http://10.0.0.5:8090", "http://192.168.0.10:8090", "http://127.0.0.2:8090", "http://0.0.0.0:8090",
            "http://127.0.0.1.evil.test:8090", "http://localhost.paycore.example", "http://user@127.0.0.1:8090", "http://127.0.0.1:8090/api",
            "http://127.0.0.1:8090?x=1", "ftp://127.0.0.1", "127.0.0.1:8090", "http://[::2]:8090", "http://127.0.0.1:99999",
        ):
            with self.assertRaises(ValueError, msg=bad):
                e2e.loopback_base_url(bad)
        with self.assertRaises(SystemExit):
            e2e.parse_args(["--out", "r.json", "--base-url", "https://admin.paycore.example"])

    def test_invalid_arguments_are_rejected_before_anything_runs(self):
        good = e2e.parse_args(["--out", "r.json"])
        self.assertEqual(good.base_url, "http://127.0.0.1:8090")
        self.assertEqual(good.login, "demo-operator")
        for bad in (["--login", "demo operator"], ["--login", "Demo;rm"], ["--timeout-seconds", "0"], ["--timeout-seconds", "121"], []):
            with self.assertRaises(SystemExit, msg=bad):
                e2e.parse_args((["--out", "r.json"] if bad else []) + bad)

    def test_step_list_is_fixed_and_includes_the_mc2_code_search(self):
        self.assertEqual(e2e.STEPS, (
            "login", "register business", "register merchant with code", "resend same submissionId", "merchant list (businessId)", "merchant detail",
            "code search (merchantCode)", "code search with another criterion", "duplicate code new submissionId", "reserved word", "bad format uppercase",
            "seed merchant list",
            # 목록 확장(MERCHANT-CODE-001 MC-1b — §7 Demo 계정 둘): 승인자 로그인·요청·승인·변경 뒤 상세·은퇴 코드 검색·재사용 거절·두 번째 변경·계약 승인 뒤 가맹점.
            "approver login", "code change request", "code change approve", "detail after code change", "code search retired code", "retired code registration",
            "second code change request", "seed merchant detail", "seed merchant code change request",
        ))
        source = source_text()
        for name in [n for n in e2e.STEPS if n not in ("login", "approver login")]:
            self.assertIn('run.step("%s"' % name, source, name)
        # 재리뷰 NIT: HTTP E2E는 400만 확인한다 — "MASTER 미도달"을 이 절차의 증거로 주장하지 않는다.
        self.assertNotIn("MASTER 미도달", source)

    def test_no_out_of_repo_modules_no_shell_no_dynamic_import(self):
        tree = ast.parse(source_text())
        imported = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.update(a.name.split(".")[0] for a in node.names)
            elif isinstance(node, ast.ImportFrom):
                imported.add((node.module or "").split(".")[0])
        allowed = {"argparse", "base64", "datetime", "hashlib", "hmac", "ipaddress", "json", "os", "re", "secrets", "struct", "sys", "time", "urllib", "uuid"}
        self.assertLessEqual(imported, allowed)
        calls = {ast.unparse(n.func) for n in ast.walk(tree) if isinstance(n, ast.Call)}
        for forbidden in ("eval", "exec", "__import__", "os.system", "os.popen", "subprocess.run", "importlib.import_module", "util.spec_from_file_location"):
            self.assertNotIn(forbidden, calls)
        source = source_text()
        self.assertNotIn("admin-enroll", source)
        self.assertNotIn("spec_from_file_location", source)

    def test_totp_matches_rfc6238_sha1_vectors(self):
        # RFC 6238 Appendix B SHA-1 8자리 값의 뒤 6자리(ADMIN은 6자리 — golden test_totp_rfc6238_golden.py와 같은 키).
        for at, code in ((59, "287082"), (1111111109, "081804"), (1111111111, "050471"), (1234567890, "005924"), (2000000000, "279037")):
            self.assertEqual(e2e.totp(RFC_SECRET_B32, at), code, at)

    def test_secrets_never_appear_in_the_account_repr_or_the_login_step(self):
        with tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False, encoding="utf-8") as f:
            f.write("# demo\nother\tVIEWER\tpw-other\tAAAA\ndemo-operator\tOPERATOR\tS3cret-Pass\t%s\n" % RFC_SECRET_B32)
        try:
            account = e2e.load_account(f.name, "demo-operator")
            self.assertNotIn("S3cret-Pass", repr(account))
            self.assertNotIn(RFC_SECRET_B32, repr(account))
            with self.assertRaises(KeyError) as missing:
                e2e.load_account(f.name, "nobody")
            self.assertNotIn("S3cret-Pass", str(missing.exception))
        finally:
            os.unlink(f.name)
        def replies(totp_status):
            return [(204, b"", ["__Host-PCADMIN-XSRF=tok1; Path=/"]), (200, b'{"status":"TOTP_REQUIRED"}', ["PCADMIN=sess; Path=/"]),
                    (totp_status, b'{"roles":["OPERATOR"],"loginName":"demo-operator"}', []), (204, b"", [])]
        run = e2e.Run(e2e.Session("http://127.0.0.1:8090", 5, opener=FakeOpener(replies(200))))
        me = e2e.login(run, account)
        self.assertEqual(me["roles"], ["OPERATOR"])
        step = run.steps[-1]
        # 재리뷰 NIT: 기록하는 상태는 실제 값(TOTP 단계가 201이면 201) — 하드코딩 200이 아니다.
        self.assertEqual((step["step"], step["status"], step["statuses"], step["body"]), ("login", 200, [204, 200, 200, 204], {"roles": ["OPERATOR"]}))
        recorded = repr(run.steps)
        for secret in ("S3cret-Pass", RFC_SECRET_B32, "tok1", "sess", e2e.totp(RFC_SECRET_B32)):
            self.assertNotIn(secret, recorded)
        self.assertEqual(run.failures, [])
        # 대조: TOTP 단계가 201이면 기록도 201이고 판정이 어긋남으로 남긴다(200으로 덮어쓰지 않는다).
        other = e2e.Run(e2e.Session("http://127.0.0.1:8090", 5, opener=FakeOpener(replies(201))))
        e2e.login(other, account)
        self.assertEqual((other.steps[-1]["status"], other.failures), (201, ["login status 201"]))

    def test_login_requires_the_operator_role(self):
        self.assertEqual(e2e.check_login(200, {"roles": ["OPERATOR", "VIEWER"]}), [])
        self.assertEqual(e2e.check_login(200, {"roles": ["VIEWER"]}), ["login roles lack OPERATOR"])
        self.assertEqual(e2e.check_login(200, {}), ["login roles lack OPERATOR"])
        self.assertEqual(e2e.check_login(401, None), ["login status 401", "login roles lack OPERATOR"])

    def test_code_search_judgement(self):
        mer = "mer_01M7BZW09CN0EA1V9VYQD4DD5W"
        item = {"merchantId": mer, "merchantCode": "mce2eabc123", "matchedRetiredCode": False}
        self.assertEqual(e2e.check_code_search(200, {"items": [item], "nextCursor": None}, "mce2eabc123", mer), [])
        self.assertTrue(e2e.check_code_search(200, {"items": [], "nextCursor": None}, "mce2eabc123", mer))
        self.assertTrue(e2e.check_code_search(200, {"items": [item, item]}, "mce2eabc123", mer))
        self.assertTrue(e2e.check_code_search(200, {"items": [{**item, "matchedRetiredCode": True}]}, "mce2eabc123", mer))
        self.assertTrue(e2e.check_code_search(200, {"items": [{k: v for k, v in item.items() if k != "matchedRetiredCode"}]}, "mce2eabc123", mer))
        self.assertTrue(e2e.check_code_search(200, {"items": [{**item, "merchantId": "mer_01M7BZW09CN0EA1V9VYQD4DD5X"}]}, "mce2eabc123", mer))
        self.assertTrue(e2e.check_code_search(503, None, "mce2eabc123", mer))
        self.assertEqual(e2e.check_no_failure_signals("x", 503, None), ["x: 503"])
        self.assertEqual(e2e.check_no_failure_signals("x", 200, {"outcome": "UNKNOWN"}), ["x: Unknown"])
        # 재리뷰 NIT-3: 코드 검색은 0~1건 — nextCursor는 null이어야 한다(값이 있거나 키가 없으면 어긋남).
        self.assertEqual(e2e.check_code_search(200, {"items": [item], "nextCursor": mer}, "mce2eabc123", mer), ["code search nextCursor is not null"])
        self.assertEqual(e2e.check_code_search(200, {"items": [item]}, "mce2eabc123", mer), ["code search nextCursor is not null"])

    def test_base_url_is_normalised_and_tricky_forms_are_refused(self):
        # 재리뷰 NIT-2: 반환값은 urlunsplit으로 다시 만든 정규 값(소문자 host·끝 '/' 없음).
        self.assertEqual(e2e.loopback_base_url("http://LOCALHOST:8090/"), "http://localhost:8090")
        self.assertEqual(e2e.loopback_base_url("HTTP://127.0.0.1:8090"), "http://127.0.0.1:8090")
        self.assertEqual(e2e.loopback_base_url("http://[::1]"), "http://[::1]")
        for bad in (
            "http://[::1%25lo]:8090", "http://[::ffff:127.0.0.1]:8090", "http://localhost.:8090", "http://127.0.0.1.evil:8090", "http://127.1:8090",
            "http://127.0.0.1\t:8090", "http://127.0.0.1:8090\n", "http://local\nhost:8090", " http://127.0.0.1:8090", "http://127.0.0.1:8090#x",
        ):
            with self.assertRaises(ValueError, msg=repr(bad)):
                e2e.loopback_base_url(bad)

    def test_demo_opener_has_no_proxy_and_never_follows_redirects(self):
        # urllib은 빈 proxies의 ProxyHandler({})를 등록 목록에 넣지 않는다(열 프로토콜이 없음) — 대신 그것을 넘겼기 때문에 build_opener가 환경 변수 프록시를 읽는
        # 기본 ProxyHandler를 만들지 않는다. 그래서 관찰 가능한 계약은 "환경에 프록시가 있어도 ProxyHandler 0개"다(소스에 ProxyHandler({}) 고정도 확인).
        saved = {k: os.environ.get(k) for k in ("http_proxy", "HTTP_PROXY", "https_proxy")}
        os.environ.update({"http_proxy": "http://proxy.invalid:3128", "HTTP_PROXY": "http://proxy.invalid:3128", "https_proxy": "http://proxy.invalid:3128"})
        try:
            handlers = e2e.demo_opener().handlers
            default_handlers = urllib.request.build_opener().handlers
        finally:
            for k, v in saved.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v
        self.assertTrue([h for h in default_handlers if isinstance(h, urllib.request.ProxyHandler)])  # 대조: 기본 opener는 환경 프록시를 쓴다
        self.assertEqual([h for h in handlers if isinstance(h, urllib.request.ProxyHandler)], [])
        self.assertIn("urllib.request.ProxyHandler({})", source_text())
        self.assertTrue(any(isinstance(h, e2e._NoRedirect) for h in handlers))
        self.assertFalse([h for h in handlers if type(h) is urllib.request.HTTPRedirectHandler])

    def test_session_default_opener_is_demo_opener(self):
        sentinel = object()
        original = e2e.demo_opener
        e2e.demo_opener = lambda: sentinel
        try:
            self.assertIs(e2e.Session("http://127.0.0.1:8090", 5).opener, sentinel)
        finally:
            e2e.demo_opener = original
        injected = FakeOpener([])
        self.assertIs(e2e.Session("http://127.0.0.1:8090", 5, opener=injected).opener, injected)

    def test_a_3xx_is_recorded_as_its_status_and_not_followed(self):
        # 실제 loopback HTTP 대역(외부 접속 없음): 302 → 다른 호스트. 따라가면 URLError(존재하지 않는 .invalid)이고, 따라가지 않으면 상태 302가 기록된다.
        import http.server
        import threading

        class Redirect(http.server.BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                self.send_response(302)
                self.send_header("Location", "http://redirect-target.invalid/steal")
                self.send_header("Content-Length", "0")
                self.end_headers()

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Redirect)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            session = e2e.Session("http://127.0.0.1:%d" % server.server_address[1], 5)
            self.assertEqual(session.call("/api/auth/csrf"), (302, None))
            self.assertEqual(session.last_status, 302)
        finally:
            server.shutdown()
            server.server_close()

    def test_judgements_treat_non_object_items_as_mismatches(self):
        # 재리뷰 MINOR-3: 객체가 아닌 응답·항목은 예외가 아니라 어긋남.
        self.assertEqual(e2e.check_code_search(200, {"items": ["x"], "nextCursor": None}, "mce2eabc123", "mer_x"), ["code search: non-object item"])
        self.assertEqual(e2e.check_code_search(200, ["x"], "mce2eabc123", "mer_x"), ["code search: items is not a list"])
        self.assertEqual(e2e.check_business_list({"items": [None]}, "mce2eabc123"), ["merchant list: non-object item"])
        self.assertEqual(e2e.check_business_list({"items": [{"merchantCode": "mce2eabc123"}]}, "mce2eabc123"), [])
        self.assertIn("seed list: non-object item", e2e.check_seed_list({"items": [{"merchantCode": "demoshop01"}, 7]}))
        self.assertEqual(e2e.check_seed_list({"items": [{"merchantCode": "demoshop01"}]}), [])
        self.assertEqual(e2e.check_seed_list("oops"), ["seed list: items is not a list", "some list item lacks a §3.1 merchantCode"])

    def test_an_exception_after_login_is_incomplete_and_the_steps_so_far_are_written(self):
        account = e2e.Account("demo-operator", "OPERATOR", "pw", RFC_SECRET_B32)
        login_replies = [(204, b"", []), (200, b"{}", []), (200, b'{"roles":["OPERATOR"]}', []), (204, b"", [])]
        business = (200, b'{"outcome":"SUCCEEDED","reference":"biz_01M7BZW05BT001BT7PYAGRXNEA","replayed":false}', [])

        class Breaking(FakeOpener):
            def open(self, request, data=None, timeout=None):
                if not self.replies:
                    raise ConnectionResetError("peer reset")
                return super().open(request, data, timeout)

        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "result.json")
            run = e2e.Run(e2e.Session("http://127.0.0.1:8090", 5, opener=Breaking(login_replies + [business])))
            code = e2e.execute(run, account, out, "http://127.0.0.1:8090", "2026-11-14T00:00:00.000+00:00")
            with open(out, encoding="utf-8") as f:
                import json
                result = json.load(f)
        self.assertEqual(code, 1)
        self.assertEqual(result["verdict"], "INCOMPLETE")
        self.assertEqual([s["step"] for s in result["steps"]], ["login", "register business"])
        self.assertEqual(result["businessId"], "biz_01M7BZW05BT001BT7PYAGRXNEA")
        self.assertEqual(result["failures"][-1], "interrupted after 2 steps: ConnectionResetError")
        self.assertNotIn("pw", [s.get("body") for s in result["steps"]])


class MerchantCodeChangeE2eTest(unittest.TestCase):
    """MERCHANT-CODE-001 MC-1b 단계(네트워크 없음): 인자·판정 함수·TOTP 새 칸 대기·요청자/승인자 세션 분리·전체 흐름(가짜 opener 두 개)."""

    MER = "mer_01M7BZW09CN0EA1V9VYQD4DD5W"
    SEED = "mer_01M7BZW09CN0EA1V9VYQD4DD5X"

    def test_approver_login_is_required_valid_and_different_from_the_requester(self):
        a = e2e.parse_args(["--out", "x.json"])
        self.assertEqual((a.login, a.approver_login), ("demo-operator", "demo-approver"))
        for bad in (["--approver-login", "demo-operator"], ["--approver-login", "Bad Login"]):
            with self.assertRaises(SystemExit) as raised, open(os.devnull, "w") as sink:
                import contextlib
                with contextlib.redirect_stderr(sink):
                    e2e.parse_args(["--out", "x.json"] + bad)
            self.assertEqual(raised.exception.code, 2)

    def test_detail_after_change_judgement(self):
        history = [
            {"code": "mce2eabc123", "seq": 1, "source": "REGISTRATION", "replacesCode": None},
            {"code": "mcnewabc123", "seq": 2, "source": "CHANGE", "replacesCode": "mce2eabc123"},
        ]
        ok = {"merchant": {"merchantCode": "mcnewabc123"}, "merchantCodeHistory": history, "merchantCodeChangeable": False}
        self.assertEqual(e2e.check_detail_after_change(ok, "mce2eabc123", "mcnewabc123"), [])
        self.assertTrue(e2e.check_detail_after_change({**ok, "merchantCodeChangeable": True}, "mce2eabc123", "mcnewabc123"))
        self.assertTrue(e2e.check_detail_after_change({**ok, "merchantCodeHistory": history[:1]}, "mce2eabc123", "mcnewabc123"))
        self.assertTrue(e2e.check_detail_after_change({**ok, "merchant": {"merchantCode": "mce2eabc123"}}, "mce2eabc123", "mcnewabc123"))
        swapped = [history[0], {**history[1], "replacesCode": "other00000"}]
        self.assertTrue(e2e.check_detail_after_change({**ok, "merchantCodeHistory": swapped}, "mce2eabc123", "mcnewabc123"))
        leaking = [history[0], {**history[1], "assignedBy": self.MER}]
        self.assertIn("code history exposes mer_", e2e.check_detail_after_change({**ok, "merchantCodeHistory": leaking}, "mce2eabc123", "mcnewabc123"))
        self.assertEqual(e2e.check_detail_after_change(None, "a", "b"), ["detail after change is not an object"])

    def test_retired_code_search_and_refusal_judgements(self):
        item = {"merchantId": self.MER, "merchantCode": "mcnewabc123", "matchedRetiredCode": True}
        self.assertEqual(e2e.check_retired_search(200, {"items": [item], "nextCursor": None}, self.MER, "mcnewabc123"), [])
        self.assertTrue(e2e.check_retired_search(200, {"items": [{**item, "matchedRetiredCode": False}]}, self.MER, "mcnewabc123"))
        self.assertTrue(e2e.check_retired_search(200, {"items": []}, self.MER, "mcnewabc123"))
        self.assertTrue(e2e.check_retired_search(200, {"items": [{**item, "merchantCode": "mce2eabc123"}]}, self.MER, "mcnewabc123"))
        self.assertTrue(e2e.check_retired_search(503, None, self.MER, "mcnewabc123"))
        self.assertEqual(e2e.check_refused("x", 409, {"error": "TARGET_NOT_ELIGIBLE_ADVISORY"}, "TARGET_NOT_ELIGIBLE_ADVISORY"), [])
        self.assertEqual(len(e2e.check_refused("x", 201, {"approvalId": "apr_x"}, "TARGET_NOT_ELIGIBLE_ADVISORY")), 2)
        items = [{"merchantId": self.MER, "merchantCode": "mce2eabc123"}, {"merchantId": self.SEED, "merchantCode": "demoshop01"}]
        self.assertEqual(e2e.pick_seed(items, {"mce2eabc123"})["merchantId"], self.SEED)
        self.assertEqual(e2e.pick_seed(items[:1] + [{"merchantId": self.SEED, "merchantCode": "m0123456789abc"}], {"mce2eabc123"})["merchantId"], self.SEED)
        self.assertIsNone(e2e.pick_seed(items[:1], {"mce2eabc123"}))

    def test_the_approval_totp_waits_for_a_new_30_second_window(self):
        t = [60.0 * 30 + 29.0]
        slept = []

        def sleep(seconds):
            slept.append(seconds)
            t[0] += seconds
        self.assertTrue(e2e.wait_for_next_totp_window(60, clock=lambda: t[0], sleep=sleep))
        self.assertEqual(int(t[0]) // 30, 61)
        self.assertTrue(slept)
        frozen = [60.0 * 30]
        self.assertFalse(e2e.wait_for_next_totp_window(60, clock=lambda: frozen[0], sleep=lambda s: None, limit_seconds=2))

    def test_code_change_flow_uses_two_sessions_and_judges_every_step(self):
        operator = e2e.Account("demo-operator", "OPERATOR", "pw-op", RFC_SECRET_B32)
        approver = e2e.Account("demo-approver", "APPROVER", "pw-ap", RFC_SECRET_B32)
        history = ('[{"code":"mce2eabc123","seq":1,"source":"REGISTRATION","replacesCode":null},'
                   '{"code":"mcnewabc123","seq":2,"source":"CHANGE","replacesCode":"mce2eabc123"}]')
        op_replies = [
            (201, b'{"approvalId":"apr_01M7BZW09CN0EA1V9VYQD4DD5A"}', []),
            (200, ('{"merchant":{"merchantCode":"mcnewabc123"},"merchantCodeHistory":%s,"merchantCodeChangeable":false}' % history).encode(), []),
            (200, ('{"items":[{"merchantId":"%s","merchantCode":"mcnewabc123","matchedRetiredCode":true}],"nextCursor":null}' % self.MER).encode(), []),
            (200, b'{"outcome":"REJECTED","rejection":"MERCHANT_CODE_TAKEN","replayed":false}', []),
            (409, b'{"error":"TARGET_NOT_ELIGIBLE_ADVISORY"}', []),
            (200, b'{"merchant":{"merchantCode":"demoshop01"},"contracts":[{"revisions":[{"approvedAt":"2026-11-01T00:00:00Z"}]}],"merchantCodeChangeable":false}', []),
            (409, b'{"error":"TARGET_NOT_ELIGIBLE_ADVISORY"}', []),
        ]
        ap_opener = FakeOpener([(204, b"", []), (200, b"{}", []), (200, b'{"roles":["APPROVER"]}', []), (204, b"", []),
                                (200, b'{"approvalId":"apr_01M7BZW09CN0EA1V9VYQD4DD5A","status":"EXECUTED"}', [])])
        op_opener = FakeOpener(op_replies)
        run = e2e.Run(e2e.Session("http://127.0.0.1:8090", 5, opener=op_opener))
        facts = {"merchantId": self.MER, "code": "mce2eabc123", "businessId": "biz_01M7BZW05BT001BT7PYAGRXNEA",
                 "approver": (e2e.Session("http://127.0.0.1:8090", 5, opener=ap_opener), approver)}
        original = e2e.wait_for_next_totp_window
        e2e.wait_for_next_totp_window = lambda counter: True
        try:
            e2e.code_change(run, facts, [{"merchantId": self.MER, "merchantCode": "mcnewabc123"}, {"merchantId": self.SEED, "merchantCode": "demoshop01"}],
                            lambda: "s" * 32, "abc123")
        finally:
            e2e.wait_for_next_totp_window = original
        self.assertEqual(run.failures, [])
        self.assertEqual([st["step"] for st in run.steps], list(e2e.STEPS[e2e.STEPS.index("approver login"):]))
        # 요청자 ≠ 승인자: 승인 POST는 승인자 세션(opener)으로만, 요청·조회는 운영자 세션으로만 간다.
        self.assertEqual([r.full_url.rsplit("/", 1)[-1] for r in ap_opener.requests][-1], "approve")
        self.assertTrue(all("/approve" not in r.full_url for r in op_opener.requests))
        self.assertEqual(facts["newCode"], "mcnewabc123")
        self.assertNotIn("pw-ap", repr(run.steps))
        self.assertNotIn("approver", facts)
        # 같은 흐름에서 MASTER 쪽 거절이 오면 판정이 잡는다(두 번째 변경이 201이면 실패).
        op_replies2 = list(op_replies)
        op_replies2[4] = (201, b'{"approvalId":"apr_01M7BZW09CN0EA1V9VYQD4DD5B"}', [])
        ap2 = FakeOpener([(204, b"", []), (200, b"{}", []), (200, b'{"roles":["APPROVER"]}', []), (204, b"", []),
                          (200, b'{"approvalId":"apr_01M7BZW09CN0EA1V9VYQD4DD5A","status":"EXECUTED"}', [])])
        run2 = e2e.Run(e2e.Session("http://127.0.0.1:8090", 5, opener=FakeOpener(op_replies2)))
        facts2 = {**facts, "approver": (e2e.Session("http://127.0.0.1:8090", 5, opener=ap2), approver)}
        e2e.wait_for_next_totp_window = lambda counter: True
        try:
            e2e.code_change(run2, facts2, [{"merchantId": self.SEED, "merchantCode": "demoshop01"}], lambda: "s" * 32, "abc123")
        finally:
            e2e.wait_for_next_totp_window = original
        self.assertTrue(any(f.startswith("second code change request") for f in run2.failures))


class FakeResponse:
    def __init__(self, status, body, cookies):
        self.status = status
        self._body = io.BytesIO(body)
        self.headers = Message()
        for c in cookies:
            self.headers["Set-Cookie"] = c

    def read(self):
        return self._body.read()


class FakeOpener(urllib.request.OpenerDirector):
    """네트워크 없는 opener — 요청 순서대로 고정 응답(상태·본문·Set-Cookie)."""

    def __init__(self, replies):
        super().__init__()
        self.replies = list(replies)
        self.requests = []

    def open(self, request, data=None, timeout=None):
        self.requests.append(request)
        return FakeResponse(*self.replies.pop(0))


if __name__ == "__main__":
    unittest.main()
