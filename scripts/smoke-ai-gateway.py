"""Test isole du vrai proxy LiteLLM avec une API vLLM ou Bedrock simulee, sans modele ni donnees existantes."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import uuid

from poc_client import Session


ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("smoke_multiuser", Path(__file__).with_name("smoke-multiuser.py"))
multiuser = importlib.util.module_from_spec(spec)
spec.loader.exec_module(multiuser)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-image", help="Image applicative locale ; jamais tiree implicitement")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--backend", choices=("vllm", "bedrock"), default="vllm")
    args = parser.parse_args()
    if args.skip_build and not args.app_image:
        parser.error("--skip-build exige --app-image")
    project = "rd-ai-smoke-" + uuid.uuid4().hex[:10]
    environment = dict(os.environ)
    environment.update({
        "AI_SMOKE_APP_IMAGE": args.app_image or project + "-app:local",
        "AI_SMOKE_DB_PASSWORD": secrets.token_urlsafe(32),
        "APP_BOOTSTRAP_PASSWORD": secrets.token_urlsafe(32),
        "APP_AI_GATEWAY_API_KEY": "sk-" + secrets.token_hex(32),
        "APP_AI_CACHE_PASSWORD": secrets.token_hex(32),
        "APP_AI_VLLM_API_KEY": secrets.token_hex(32),
        "APP_AI_VLLM_URL": "http://mock-vllm:8000/v1",
        "APP_AI_VLLM_MODEL": "mistral",
        "APP_AI_ENABLED": "true", "APP_AI_DEFAULT_MODEL": "mistral-local",
        "APP_AI_CACHE_ENABLED": "true", "APP_AI_CACHE_TTL_SECONDS": "60",
        "APP_AI_VLLM_MODEL_REVISION": "1", "APP_AI_OLLAMA_MODEL_REVISION": "1",
    })
    compose = ["docker", "compose", "--project-directory", str(ROOT), "--project-name", project,
               "-f", str(ROOT / "scripts" / "docker-compose.ai-smoke.yml"),
               "-f", str(ROOT / "docker-compose.ai.yml")]
    alias = "bedrock" if args.backend == "bedrock" else "mistral-local"
    if args.backend == "bedrock":
        compose.extend(["-f", str(ROOT / "scripts" / "docker-compose.bedrock-smoke.yml")])

    def run(*arguments):
        result = subprocess.run(compose + list(arguments), cwd=ROOT, env=environment,
                                capture_output=True, text=True, encoding="utf-8", timeout=1200)
        if result.returncode:
            raise RuntimeError(f"Docker Compose a echoue ({arguments[0]}):\n{result.stdout}\n{result.stderr}")
        return result.stdout.strip()

    def fixture(path, payload=None):
        body = None if payload is None else json.dumps(payload).encode()
        code = ("import json,urllib.request; "
                f"request=urllib.request.Request('http://127.0.0.1:8000{path}',data={body!r},"
                "headers={'Content-Type':'application/json'}); "
                "print(json.dumps(json.load(urllib.request.urlopen(request,timeout=10))))")
        return json.loads(run("exec", "-T", "mock-vllm", "python", "-c", code))

    def proxy(payload, authenticated=True):
        code = ("import json,os,urllib.request,urllib.error; "
                "headers={'Content-Type':'application/json'}; "
                f"headers.update({{'Authorization':'Bearer '+os.environ['LITELLM_MASTER_KEY']}} if {authenticated!r} else {{}}); "
                f"request=urllib.request.Request('http://127.0.0.1:4000/v1/chat/completions',data={json.dumps(payload).encode()!r},headers=headers)\n"
                "try:\n"
                " with urllib.request.urlopen(request,timeout=30) as response: print(json.dumps({'status':response.status,'body':json.load(response)}))\n"
                "except urllib.error.HTTPError as error: print(json.dumps({'status':error.code}))")
        return json.loads(run("exec", "-T", "litellm", "python", "-c", code))

    def proxy_cache_contract(payload):
        code = ("import json,os,time,urllib.request; "
                f"payload=json.loads({json.dumps(payload)!r}); "
                "headers={'Content-Type':'application/json','Authorization':'Bearer '+os.environ['LITELLM_MASTER_KEY']}\n"
                "def call():\n"
                " request=urllib.request.Request('http://127.0.0.1:4000/v1/chat/completions',data=json.dumps(payload).encode(),headers=headers)\n"
                " with urllib.request.urlopen(request,timeout=30) as response: return json.load(response)['id']\n"
                "first=call(); cached=call(); time.sleep(3); expired=call()\n"
                "payload['cache']={'no-cache':True,'no-store':True}\n"
                "print(json.dumps({'first':first,'cached':cached,'expired':expired,'no_store':[call(),call()]}))")
        return json.loads(run("exec", "-T", "litellm", "python", "-c", code))

    try:
        run("config", "--quiet")
        run("up", "--detach", "--wait", "--wait-timeout", "180", "--quiet-pull",
            "--no-build" if args.skip_build else "--build")
        endpoint = run("port", "app", "8080")
        require(endpoint.startswith("127.0.0.1:") and endpoint.rsplit(":", 1)[-1].isdigit(),
                "Le port applicatif de fixture n'est pas publie sur la boucle locale")
        url = "http://" + endpoint
        admin = Session(url, "admin", environment["APP_BOOTSTRAP_PASSWORD"], 60)
        bob_password, alice_password = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        bob_account = admin.json("/api/admin/users", {
            "username": "fixture-bob", "displayName": "Fixture Bob", "role": "MANAGER",
            "managerId": None, "enabled": True, "password": bob_password,
        })
        other_manager = admin.json("/api/admin/users", {
            "username": "fixture-carol", "displayName": "Fixture Carol", "role": "MANAGER",
            "managerId": None, "enabled": True, "password": secrets.token_urlsafe(32),
        })
        alice_account = admin.json("/api/admin/users", {
            "username": "fixture-alice", "displayName": "Fixture Alice", "role": "USER",
            "managerId": bob_account["id"], "enabled": True, "password": alice_password,
        })
        alice = Session(url, "fixture-alice", alice_password, 60)
        bob = Session(url, "fixture-bob", bob_password, 60)
        lines = ["Rapport synthetique de controle du circuit. Debut du document fictif."]
        lines += [f"Notice {index}: protocole fictif de maintenance et archivage sans aucune donnee personnelle."
                  for index in range(32)]
        lines += ["Controle du circuit : RESULTAT_FICTIF_NEGATIF.",
                  "INDICE_FICTIF_5_70 ; seuil < 8,00.",
                  "Conclusion : aucune fuite observee, sous RESERVE_FICTIVE des conditions du controle."]
        lines += ["Notes techniques sans conclusion supplementaire."] * (45 - len(lines))
        lines += ["Page 2 : PREVENTION_FICTIVE, conseils generaux sur les controles du circuit.",
                  "Fin du document fictif."]
        multiuser.upload(alice, "shared", uuid.uuid4().hex[:8], bob_account["id"], lines)
        models = alice.json("/api/ai/models")
        require(models[0]["id"] == alias and models[0]["provider"] == "LITELLM",
                "Le catalogue ne prefere pas le backend configure via LiteLLM")
        if args.backend == "bedrock":
            require(len(models) == 1, "Le profil Bedrock expose des modeles on-premise non configures")
            require(models[0]["hosting"] == "CLOUD" and models[0]["displayName"] == "openai.gpt-5.4",
                    "Le catalogue Bedrock masque la destination cloud ou le modele amont")
        else:
            require(models[0]["hosting"] == "LOCAL", "Le modele vLLM on-premise n'est pas marque local")
        query = {"query": "rapport", "summarize": True, "aiModel": alias}

        def search(actor, request=query):
            result = actor.json("/api/search/", request)
            require(result.get("summary") and not result.get("summaryError"), "Synthese applicative absente")
            require(result["summary"]["sources"], "Sources de synthese absentes")
            return result

        before = fixture("/fixture/state")["calls"]
        first = search(alice)
        require(all(not source["partial"] for source in first["summary"]["sources"]),
                "Le petit document fictif n'a pas ete transmis integralement")
        require(fixture("/fixture/state")["source_context_verified"],
                "Le modele n'a pas recu le resultat, l'indice, la reserve et la seconde page")
        require(all("RESULTAT_FICTIF_NEGATIF" not in fragment["fragment"] for fragment in first["fragments"]),
                "La fixture ne reproduit pas un resultat absent de l'apercu de recherche")
        second = search(alice)
        require(first["summary"] == second["summary"] and fixture("/fixture/state")["calls"] == before + 1,
                "Le cache exact ne reutilise pas la requete autorisee identique")
        manager = search(bob)
        require(first["fragments"] == manager["fragments"], "Les deux utilisateurs ne partagent pas le meme contexte de fixture")
        require(manager["summary"]["text"] != first["summary"]["text"]
                and fixture("/fixture/state")["calls"] == before + 2, "Cache partage entre utilisateurs")

        probe = {"model": alias, "messages": [{"role": "user", "content": "TTL fixture"}],
                 "stream": False, "temperature": 0, "max_tokens": 32,
                 "cache": {"use-cache": True, "ttl": 2, "namespace": "ttl-" + uuid.uuid4().hex}}
        require(proxy(probe, authenticated=False)["status"] == 401, "Le proxy accepte une requete sans cle")
        require(proxy({**probe, "model": "unknown-model"})["status"] >= 400, "Le proxy accepte un modele non configure")
        cache = proxy_cache_contract(probe)
        require(cache["cached"] == cache["first"], "Cache TTL non reutilise")
        require(cache["expired"] != cache["first"], "Le cache ne respecte pas le TTL")
        require(cache["no_store"][0] != cache["no_store"][1], "La desactivation explicite du cache est ignoree")

        fixture("/fixture/control", {"delay": 3})
        before = fixture("/fixture/state")["calls"]
        with ThreadPoolExecutor(max_workers=1) as executor:
            pending = executor.submit(bob.json, "/api/search/", {**query, "query": "archivage"})
            deadline = time.monotonic() + 10
            while fixture("/fixture/state")["calls"] == before and time.monotonic() < deadline:
                time.sleep(.1)
            if pending.done() and fixture("/fixture/state")["calls"] == before:
                early_result = pending.result()
                raise RuntimeError("Generation differee absente : "
                                   f"nbResults={early_result.get('nbResults')}, "
                                   f"summaryError={early_result.get('summaryError')}")
            require(fixture("/fixture/state")["calls"] > before, "La generation differee n'a pas commence")
            revoked = {key: alice_account[key] for key in ("username", "displayName", "role", "enabled")}
            admin.json(f"/api/admin/users/{alice_account['id']}",
                       {**revoked, "managerId": other_manager["id"]}, "PUT")
            result = pending.result(timeout=20)
        require(result["nbResults"] == 0 and not result.get("summary") and result.get("summaryError"),
                "La revocation pendant la generation laisse fuiter la synthese")
        before = fixture("/fixture/state")["calls"]
        result = bob.json("/api/search/", query)
        require(result["nbResults"] == 0 and not result["summary"]["sources"]
                and fixture("/fixture/state")["calls"] == before, "Ancien cache restitue apres revocation")

        fixture("/fixture/control", {"delay": 0, "status": 503})
        result = alice.json("/api/search/", {**query, "query": "maintenance"})
        require(result["nbResults"] > 0 and result.get("summaryError") and not result.get("summary"),
                "La panne vLLM supprime les resultats ou masque l'erreur IA")
        fixture("/fixture/control", {"status": 200})
        require(search(alice)["nbResults"] > 0, "La recherche ne recupere pas apres la panne")
        logs = run("logs", "--no-color", "litellm")
        require("Rapport synthetique" not in logs and "Fin du document" not in logs,
                "Le proxy journalise les extraits synthetiques")
        print(json.dumps({
            "gateway": "LiteLLM", "inference": f"simulated-{args.backend} (no model loaded)",
            "transport": "bedrock_mantle" if args.backend == "bedrock" else "hosted_vllm",
            "cache": "exact, user-isolated, opt-in, TTL and no-store verified",
            "authorization": "revocation during generation and before cache reuse verified",
            "failure": "search results preserved", "project": project,
            "rag_context": "two-page synthetic source, result beyond preview, qualifications and cloud labeling verified",
        }, indent=2))
    finally:
        run("down", "--volumes", "--remove-orphans", "--timeout", "15")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
