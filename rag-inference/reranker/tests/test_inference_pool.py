"""Run with python -m unittest discover -s tests from the reranker directory."""

import asyncio
import unittest
from threading import Event

from inference_pool import InferenceBusyError, InferencePool


class InferencePoolTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.pool = InferencePool(1)
        self.started = Event()
        self.finish = Event()

    async def asyncTearDown(self):
        self.finish.set()
        self.pool.shutdown()

    def slow_inference(self):
        self.started.set()
        if not self.finish.wait(5):
            raise TimeoutError("Test did not release inference")
        return {"results": []}

    async def test_busy_request_is_rejected_without_queuing(self):
        first = self.pool.submit(self.slow_inference)
        self.assertTrue(self.started.wait(2))
        for _ in range(10):
            with self.assertRaises(InferenceBusyError):
                self.pool.submit(lambda: self.fail("Rejected work must not run"))
        self.assertEqual(self.pool.snapshot(), {
            "max_concurrency": 1,
            "active_requests": 1,
            "busy": True,
            "rejected_requests": 10,
        })
        self.finish.set()
        await asyncio.wrap_future(first)
        self.assertFalse(self.pool.snapshot()["busy"])

    async def test_cancelled_request_does_not_free_running_model_slot(self):
        worker = self.pool.submit(self.slow_inference)
        self.assertTrue(self.started.wait(2))
        http_waiter = asyncio.wrap_future(worker)
        http_waiter.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await http_waiter
        await asyncio.sleep(0)
        self.assertFalse(worker.cancelled())
        with self.assertRaises(InferenceBusyError):
            self.pool.submit(lambda: {})
        self.assertEqual(self.pool.snapshot()["active_requests"], 1)
        self.finish.set()
        await asyncio.wrap_future(worker)
        result = await asyncio.wrap_future(self.pool.submit(lambda: {"next": True}))
        self.assertEqual(result, {"next": True})

    async def test_failed_inference_releases_its_slot(self):
        def failing_inference():
            raise RuntimeError("model failed")

        with self.assertRaisesRegex(RuntimeError, "model failed"):
            await asyncio.wrap_future(self.pool.submit(failing_inference))
        result = await asyncio.wrap_future(self.pool.submit(lambda: {"next": True}))
        self.assertEqual(result, {"next": True})
        self.assertEqual(self.pool.snapshot()["active_requests"], 0)

    async def test_shutdown_rejects_new_work(self):
        self.pool.shutdown()
        with self.assertRaises(InferenceBusyError):
            self.pool.submit(lambda: {})
        self.assertTrue(self.pool.snapshot()["busy"])


if __name__ == "__main__":
    unittest.main()
