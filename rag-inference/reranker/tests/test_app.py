"""Exercise the API contract without loading a model or calling an AI API."""

import asyncio
import unittest
from threading import Event

from fastapi import HTTPException

import app
from inference_pool import InferencePool


class Scores:
    def tolist(self):
        return [0.2, 0.9, 0.9]


class Model:
    def __init__(self):
        self.started = Event()
        self.finish = Event()
        self.call = None

    def predict(self, pairs, **kwargs):
        self.call = (pairs, kwargs)
        self.started.set()
        if not self.finish.wait(5):
            raise TimeoutError("Test did not release model")
        return Scores()


class RerankerApiTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        app.app.state.pool = InferencePool(1)
        app.app.state.model = Model()
        self.request = app.RerankRequest(
            query="Redis timeout", documents=["first", "second", "third"], top_n=2
        )

    async def asyncTearDown(self):
        app.app.state.model.finish.set()
        app.app.state.pool.shutdown()

    async def test_busy_health_and_503_then_stable_scores(self):
        first = asyncio.create_task(app.rerank(self.request))
        await asyncio.sleep(0)
        self.assertTrue(app.app.state.model.started.wait(2))
        health = await app.health()
        self.assertEqual(health["status"], "UP")
        self.assertTrue(health["model_loaded"])
        self.assertTrue(health["busy"])
        with self.assertRaises(HTTPException) as raised:
            await app.rerank(self.request)
        self.assertEqual(raised.exception.status_code, 503)
        self.assertEqual(raised.exception.headers, {"Retry-After": "1"})
        app.app.state.model.finish.set()
        result = await first
        self.assertEqual(result["results"], [
            {"index": 1, "score": 0.9}, {"index": 2, "score": 0.9}
        ])
        self.assertEqual(app.app.state.model.call, (
            [("Redis timeout", "first"), ("Redis timeout", "second"),
             ("Redis timeout", "third")],
            {"batch_size": app.batch_size, "show_progress_bar": False},
        ))
        self.assertFalse((await app.health())["busy"])


if __name__ == "__main__":
    unittest.main()
