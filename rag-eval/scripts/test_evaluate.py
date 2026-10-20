"""Answer-client contract tests; synthetic responses only, no live corpus or provider calls."""

import io
import unittest
import urllib.error
from unittest.mock import MagicMock, patch

import evaluate


ROUTE = "01234567-89ab-4cde-8fab-0123456789ab"
BASE = "http://127.0.0.1:18080"
KEY = "synthetic-evaluation-key"


def pending(status="RUNNING", **changes):
    data = {
        "owner": "HARNESS", "routeId": ROUTE, "status": status,
        "statusUrl": f"/api/rag/harness/answers/{ROUTE}",
    }
    data.update(changes)
    return {"code": 0, "data": data}


ANSWER = {"code": 0, "data": {"answer": "Synthetic answer", "references": []}}


class AnswerClientTest(unittest.TestCase):
    def call(self, **kwargs):
        return evaluate.ask_answer(BASE, {"question": "Synthetic"}, "synthetic-token", KEY, **kwargs)

    @patch.object(evaluate, "request_json", return_value=ANSWER)
    def test_legacy_answer_remains_compatible(self, request):
        self.assertEqual("Synthetic answer", self.call()["answer"])
        self.assertEqual(1, request.call_count)
        self.assertEqual(KEY, request.call_args.kwargs["idempotency_key"])
        self.assertEqual("POST", request.call_args.args[1])

    @patch.object(evaluate.time, "sleep")
    @patch.object(evaluate, "request_json", side_effect=[pending("QUEUED"), pending(), ANSWER])
    def test_async_result_uses_only_bound_read_route(self, request, sleep):
        self.assertEqual("Synthetic answer", self.call()["answer"])
        self.assertEqual(3, request.call_count)
        for call in request.call_args_list[1:]:
            self.assertEqual((f"{BASE}/api/rag/harness/answers/{ROUTE}",), call.args)
            self.assertNotIn("idempotency_key", call.kwargs)

    @patch.object(evaluate, "request_json")
    def test_uncertain_or_terminal_status_is_not_scored_or_replayed(self, request):
        for status in ("UNKNOWN", "NEEDS_ATTENTION", "FAILED", "CANCELLED", "EXPIRED",
                       "REJECTED", "BUDGET_EXCEEDED", "PAUSED", "WAITING_APPROVAL",
                       "WAITING_INPUT", "COMPLETED", None):
            request.reset_mock()
            request.return_value = pending(status)
            with self.subTest(status=status), self.assertRaisesRegex(RuntimeError, "requires attention"):
                self.call()
            self.assertEqual(1, request.call_count)

    @patch.object(evaluate, "request_json")
    def test_remote_or_malformed_polling_route_is_rejected_before_get(self, request):
        for change in (
            {"statusUrl": "https://outside.invalid/steal"},
            {"statusUrl": "//outside.invalid/steal"},
            {"routeId": "../escape"},
            {"owner": "OTHER"},
        ):
            request.reset_mock()
            request.return_value = pending(**change)
            with self.subTest(change=change), self.assertRaisesRegex(RuntimeError, "invalid or changed"):
                self.call()
            self.assertEqual(1, request.call_count)

    @patch.object(evaluate.time, "sleep")
    @patch.object(evaluate, "request_json")
    def test_route_and_owner_cannot_change_during_polling(self, request, sleep):
        other = "11111111-1111-4111-8111-111111111111"
        for change in (
            {"owner": "LEGACY"},
            {"routeId": other, "statusUrl": f"/api/rag/harness/answers/{other}"},
        ):
            request.reset_mock()
            request.side_effect = [pending(), pending(**change)]
            with self.subTest(change=change), self.assertRaisesRegex(RuntimeError, "invalid or changed"):
                self.call()
            self.assertEqual(2, request.call_count)

    @patch.object(evaluate, "request_json", return_value={"code": 40900, "data": None})
    def test_business_error_never_becomes_an_empty_answer(self, request):
        with self.assertRaisesRegex(RuntimeError, "40900"):
            self.call()
        self.assertEqual(1, request.call_count)

    @patch.object(evaluate, "request_json", side_effect=TimeoutError("private response details"))
    def test_lost_post_response_has_no_retry_and_keeps_recovery_key(self, request):
        with self.assertRaisesRegex(RuntimeError, KEY) as caught:
            self.call()
        self.assertNotIn("private response details", str(caught.exception))
        self.assertEqual(1, request.call_count)

    @patch.object(evaluate.time, "sleep")
    @patch.object(evaluate, "request_json", side_effect=[pending(), TimeoutError()])
    def test_lost_get_response_keeps_original_route_without_post_retry(self, request, sleep):
        with self.assertRaisesRegex(RuntimeError, ROUTE):
            self.call()
        self.assertEqual(2, request.call_count)

    @patch.object(evaluate.time, "monotonic", side_effect=[0, 0, 3])
    @patch.object(evaluate.time, "sleep")
    @patch.object(evaluate, "request_json", return_value=pending())
    def test_deadline_stops_read_polling_without_creating_another_run(self, request, sleep, clock):
        with self.assertRaisesRegex(RuntimeError, "timed out"):
            self.call(timeout=2)
        self.assertEqual(1, request.call_count)

    def test_keys_resume_same_case_and_distinguish_explicit_new_run(self):
        run = "22222222-2222-4222-8222-222222222222"
        key = evaluate.answer_key(run, "case-1")
        self.assertEqual(key, evaluate.answer_key(run, "case-1"))
        self.assertNotEqual(key, evaluate.answer_key(run, "case-2"))
        self.assertNotEqual(key, evaluate.answer_key(ROUTE, "case-1"))

    @patch.object(evaluate.urllib.request, "build_opener")
    def test_wire_request_sends_key_and_uses_redirect_rejection(self, build):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b'{"code":0,"data":{}}'
        build.return_value.open.return_value = response
        evaluate.request_json(BASE, "POST", {}, "synthetic-token", idempotency_key=KEY)
        request = build.return_value.open.call_args.args[0]
        self.assertEqual(KEY, request.get_header("Idempotency-key"))
        self.assertIsInstance(build.call_args.args[0], evaluate.NoApiRedirect)

    def test_redirect_handler_rejects_external_and_same_origin_redirect(self):
        request = evaluate.urllib.request.Request(BASE)
        for target in ("https://outside.invalid", f"{BASE}/elsewhere"):
            with self.subTest(target=target), self.assertRaises(urllib.error.HTTPError):
                evaluate.NoApiRedirect().redirect_request(
                    request, io.BytesIO(), 302, "Found", {}, target)


if __name__ == "__main__":
    unittest.main()
