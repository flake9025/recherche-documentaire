"""API vLLM ou Bedrock simulee pour les tests de transport ; aucune inference reelle."""
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import threading
import time


STATE = {"calls": 0, "delay": 0, "status": 200, "source_context_verified": False}
LOCK = threading.Lock()
MODEL = os.environ.get("MOCK_INFERENCE_MODEL", "mistral")
SOURCE_MARKERS = ("RESULTAT_FICTIF_NEGATIF", "INDICE_FICTIF_5_70", "RESERVE_FICTIVE", "PREVENTION_FICTIVE")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body):
        encoded = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"status": "ok"})
        elif self.path == "/fixture/state":
            with LOCK:
                snapshot = dict(STATE)
            self.reply(200, snapshot)
        elif self.path in ("/v1/models", "/openai/v1/models"):
            self.reply(200, {"object": "list", "data": [{"id": MODEL, "object": "model"}]})
        else:
            self.reply(404, {"error": {"message": "Unknown fixture endpoint"}})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        if self.path == "/fixture/control":
            with LOCK:
                STATE.update({key: body[key] for key in ("delay", "status") if key in body})
            self.reply(200, {"status": "ok"})
            return
        responses_api = self.path == "/openai/v1/responses"
        if self.path not in ("/v1/chat/completions", "/openai/v1/chat/completions", "/openai/v1/responses"):
            self.reply(404, {"error": {"message": "Expected vLLM chat completions"}})
            return
        if not hmac.compare_digest(self.headers.get("Authorization", ""),
                                   "Bearer " + os.environ["APP_AI_VLLM_API_KEY"]):
            self.reply(401, {"error": {"message": "Invalid fixture inference credential"}})
            return
        context = body.get("input") if responses_api else body.get("messages")
        budget = body.get("max_output_tokens") if responses_api else body.get("max_completion_tokens", body.get("max_tokens"))
        if (body.get("model") != MODEL or body.get("stream", False) is not False
                or not context or not isinstance(budget, int) or not 1 <= budget <= 512
                or "cache" in body):
            self.reply(400, {"error": {"message": "Invalid vLLM request contract"}})
            return
        if MODEL != "mistral" and (body.get("store") is not False or "temperature" in body):
            self.reply(400, {"error": {"message": "Expected no storage and no forced temperature for Mantle"}})
            return
        serialized_context = json.dumps(context)
        if "Sources (objets JSON" in serialized_context:
            if not all(marker in serialized_context for marker in SOURCE_MARKERS):
                self.reply(400, {"error": {"message": "Missing complete synthetic source context beyond the preview"}})
                return
            with LOCK:
                STATE["source_context_verified"] = True
        with LOCK:
            STATE["calls"] += 1
            counter, delay, status = STATE["calls"], STATE["delay"], STATE["status"]
        time.sleep(delay)
        if status != 200:
            self.reply(status, {"error": {"message": "Synthetic inference failure", "type": "server_error"}})
            return
        if responses_api:
            self.reply(200, {
                "id": f"resp-fixture-{counter}", "object": "response", "created_at": int(time.time()),
                "status": "completed", "model": MODEL, "error": None, "incomplete_details": None,
                "output": [{"id": f"msg-fixture-{counter}", "type": "message", "role": "assistant",
                            "status": "completed", "content": [{"type": "output_text",
                            "text": f"Synthese synthetique [1]. Generation {counter}.", "annotations": []}]}],
                "usage": {"input_tokens": 30, "output_tokens": 12, "total_tokens": 42},
                "parallel_tool_calls": False, "tool_choice": "auto", "tools": [], "store": False,
            })
            return
        self.reply(200, {
            "id": f"fixture-{counter}", "object": "chat.completion", "created": int(time.time()),
            "model": MODEL,
            "choices": [{"index": 0, "message": {"role": "assistant",
                        "content": f"Synthese synthetique [1]. Generation {counter}."}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 30, "completion_tokens": 12, "total_tokens": 42},
        })


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
