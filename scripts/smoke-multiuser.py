"""Test fonctionnel multi-role sur un deploiement de demonstration, avec documents PDF synthetiques."""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import sys
import threading
import urllib.error
import urllib.request
import uuid

from poc_client import Session


def pdf_document(lines: list[str]) -> bytes:
    pages = [lines[index:index + 45] for index in range(0, len(lines), 45)]
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        f"<< /Type /Pages /Count {len(pages)} /Kids [{' '.join(f'{4 + 2 * index} 0 R' for index in range(len(pages)))}] >>".encode(),
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    for index, page in enumerate(pages):
        content = b"BT /F1 10 Tf 50 790 Td 14 TL\n"
        for line in page:
            escaped = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            content += f"({escaped}) Tj T*\n".encode("ascii")
        content += b"ET\n"
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents {5 + 2 * index} 0 R >>".encode())
        objects.append(f"<< /Length {len(content)} >>\nstream\n".encode() + content + b"endstream")
    data = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for number, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data += f"{number} 0 obj\n".encode() + obj + b"\nendobj\n"
    start = len(data)
    data += f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode()
    data += b"".join(f"{offset:010d} 00000 n \n".encode() for offset in offsets[1:])
    data += f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n".encode()
    return bytes(data)


def upload(session: Session, name: str, tag: str, spoofed_owner: int, lines: list[str] | None = None):
    if lines is None:
        lines = [f"Rapport synthetique {name}. Debut du document {tag}."]
        if name == "alice":
            lines += [f"Section {index}: recherche documentaire, maintenance, archivage et suivi du projet."
                      for index in range(180)]
        else:
            lines += ["Recherche documentaire : plan de maintenance et archivage du projet."]
        lines += [f"Fin du document : FIN_SMOKE_{tag}_{name}."]
    document = pdf_document(lines)
    boundary = "smoke-" + uuid.uuid4().hex
    fields = {
        "titre": f"Rapport synthetique {tag} {name}", "auteur": f"qa-{tag}-{name}", "categorie": "rapport",
        "ocrType": "pdfbox", "ownerId": str(spoofed_owner),
    }
    data = bytearray()
    for field, value in fields.items():
        data += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"\r\n\r\n{value}\r\n".encode()
    data += f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{tag}-{name}.pdf\"\r\nContent-Type: application/pdf\r\n\r\n".encode()
    data += document + f"\r\n--{boundary}--\r\n".encode()
    with session.request("/api/index/addFromOCR", bytes(data), "multipart/form-data; boundary=" + boundary):
        pass
    matches = [doc for doc in session.json("/api/documents") if doc["titre"] == fields["titre"]]
    if len(matches) != 1 or matches[0]["ownerId"] != session.user["id"]:
        raise RuntimeError(f"Proprietaire non impose cote serveur pour {name}")
    with session.request(f"/api/documents/{matches[0]['id']}/file") as response:
        if response.read() != document:
            raise RuntimeError(f"Fichier restitue different de l'original pour {name}")
    return matches[0]


def expect_error(operation, status: int):
    try:
        result = operation()
        if hasattr(result, "close"):
            result.close()
    except urllib.error.HTTPError as error:
        if error.code == status:
            error.close()
            return
        raise
    raise RuntimeError(f"Operation acceptee alors que HTTP {status} etait attendu")


def user_input(user: dict, **changes):
    data = {key: user[key] for key in ("username", "displayName", "role", "managerId", "enabled")}
    data.update(changes)
    return data


def check_scopes(sessions: dict[str, Session]):
    admin = sessions["admin"]
    documents = admin.json("/api/documents")
    accounts = admin.json("/api/admin/users")
    denied = 0
    for name, session in sessions.items():
        user = session.user
        owners = {user["id"]}
        if user["role"] == "MANAGER":
            owners.update(account["id"] for account in accounts
                          if account["role"] == "USER" and account["managerId"] == user["id"])
        expected = {doc["id"] for doc in documents if user["role"] == "ADMIN" or doc.get("ownerId") in owners}
        actual = {doc["id"] for doc in session.json("/api/documents")}
        if expected != actual:
            raise RuntimeError(f"Perimetre de metadonnees incorrect pour {name}")
        result = session.json("/api/search/", {"query": "rapport", "allowedDocumentIds": [doc["id"] for doc in documents]})
        hits = {int(fragment["id"]) for fragment in result["fragments"]}
        if not hits <= expected or (expected and not hits):
            raise RuntimeError(f"Recherche hors perimetre ou sans resultats pour {name}")
        suggestions = session.json("/api/autocomplete/authors?query=qa-&limit=100")
        visible_authors = {doc["auteur"] for doc in documents if doc["id"] in expected}
        if not {item["author"] for item in suggestions} <= visible_authors:
            raise RuntimeError(f"Fuite dans l'autocompletion pour {name}")
        forbidden = next((doc for doc in documents if doc["id"] not in expected), None)
        if forbidden:
            expect_error(lambda: session.request(f"/api/documents/{forbidden['id']}/file"), 404)
            denied += 1
    return denied


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--write-synthetic-documents", action="store_true",
                        help="Autorise imports et modifications temporaires des comptes de demo ; jamais en production")
    parser.add_argument("--ai-model")
    parser.add_argument("--timeout", type=int, default=300, help="Le premier embedding peut telecharger DJL et le modele")
    args = parser.parse_args()
    password = os.environ.get("LOAD_PASSWORD", "")
    if not password or not args.write_synthetic_documents or args.timeout < 1:
        parser.error("LOAD_PASSWORD et --write-synthetic-documents obligatoires ; utiliser une instance de demo isolee")
    names = ("admin", "carol", "david", "alice", "bob", "eve")
    sessions = {name: Session(args.url, name, password, args.timeout) for name in names}
    admin = sessions["admin"]
    expect_error(lambda: urllib.request.urlopen(args.url.rstrip("/") + "/api/documents", timeout=10), 401)
    expect_error(lambda: sessions["alice"].json("/api/admin/users"), 403)
    missing_csrf = urllib.request.Request(args.url.rstrip("/") + "/api/search/", data=b'{"query":"rapport"}',
                                         headers={"Content-Type": "application/json"})
    expect_error(lambda: sessions["alice"].opener.open(missing_csrf, timeout=10), 403)

    tag = uuid.uuid4().hex[:8]
    uploaded = {"admin": upload(admin, "admin", tag, sessions["bob"].user["id"])}
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
        futures = {name: executor.submit(upload, sessions[name], name, tag, sessions["bob"].user["id"])
                   for name in names if name != "admin"}
        uploaded.update({name: future.result() for name, future in futures.items()})
    denied = check_scopes(sessions)
    accounts = admin.json("/api/admin/users")
    bob = next(user for user in accounts if user["username"] == "bob")
    admin_account = next(user for user in accounts if user["id"] == admin.user["id"])
    try:
        admin.json(f"/api/admin/users/{bob['id']}", user_input(bob, managerId=sessions["david"].user["id"]), "PUT")
        check_scopes(sessions)
    finally:
        admin.json(f"/api/admin/users/{bob['id']}", user_input(bob), "PUT")
    expect_error(lambda: admin.request(f"/api/admin/users/{sessions['carol'].user['id']}", method="DELETE"), 409)
    expect_error(lambda: admin.json(f"/api/admin/users/{admin.user['id']}",
                                   user_input(admin_account, role="MANAGER"), "PUT"), 409)

    temporary = admin.json("/api/admin/users", {
        "username": "qa-" + tag, "displayName": "Compte synthetique", "password": password,
        "role": "MANAGER", "managerId": None, "enabled": True,
    })
    path = f"/api/admin/users/{temporary['id']}"
    try:
        active = Session(args.url, temporary["username"], password, args.timeout)
        expect_error(lambda: admin.json(path, user_input(temporary, role="USER", managerId=temporary["id"]), "PUT"), 400)
        temporary = admin.json(path, user_input(temporary, displayName="Compte synthetique modifie",
                                               role="USER", managerId=sessions["carol"].user["id"]), "PUT")
        if active.json("/api/auth/me")["displayName"] != temporary["displayName"]:
            raise RuntimeError("Modification utilisateur non visible dans la session")
        if active.json("/api/auth/me")["role"] != "USER":
            raise RuntimeError("Changement de role non applique a la session")
        admin.json(path, user_input(temporary, enabled=False), "PUT")
        expect_error(lambda: active.json("/api/auth/me"), 401)
        admin.json(path, user_input(temporary, enabled=True), "PUT")
        active = Session(args.url, temporary["username"], password, args.timeout)
        new_password = uuid.uuid4().hex
        admin.json(path, user_input(temporary, password=new_password), "PUT")
        expect_error(lambda: active.json("/api/auth/me"), 401)
        Session(args.url, temporary["username"], new_password, args.timeout)
    finally:
        with admin.request(path, method="DELETE"):
            pass

    concurrent_sessions = [Session(args.url, "admin", password, args.timeout) for _ in range(2)]
    barrier = threading.Barrier(2)

    def concurrent_create(actor: Session):
        barrier.wait()
        try:
            return 201, actor.json("/api/admin/users", {
                "username": "qa-race-" + tag, "displayName": "Compte de concurrence", "password": password,
                "role": "USER", "managerId": sessions["carol"].user["id"], "enabled": True,
            })
        except urllib.error.HTTPError as error:
            if error.code != 409:
                raise
            error.close()
            return 409, None

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(concurrent_create, concurrent_sessions))
    created = [user for status, user in results if status == 201]
    try:
        if sorted(status for status, _ in results) != [201, 409]:
            raise RuntimeError("Creation concurrente non protegee contre les doublons")
    finally:
        for user in created:
            with admin.request(f"/api/admin/users/{user['id']}", method="DELETE"):
                pass

    summary_model = None
    if args.ai_model:
        result = sessions["alice"].json("/api/search/", {"query": "rapport", "summarize": True, "aiModel": args.ai_model})
        summary = result.get("summary")
        hits = {int(fragment["id"]) for fragment in result["fragments"]}
        if result.get("summaryError") or not summary or not summary.get("text", "").strip():
            raise RuntimeError(f"Synthese absente: {result.get('summaryError')}")
        if not summary.get("sources") or not {int(source["documentId"]) for source in summary["sources"]} <= hits:
            raise RuntimeError("Sources IA hors perimetre")
        summary_model = summary["model"]
    print(json.dumps({
        "scopeChecks": "passed", "accounts": list(names), "syntheticTag": tag,
        "documents": {name: document["id"] for name, document in uploaded.items()},
        "ownershipSpoofRejected": True, "forbiddenDownloadsChecked": denied,
        "csrfEnforced": True, "adminCrud": "passed", "managerReassignment": "passed", "concurrentUploads": 5,
        "sessionRevocation": "passed", "concurrentDuplicateCreation": "passed", "summaryModel": summary_model,
    }, indent=2))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, ValueError, urllib.error.URLError, TimeoutError) as error:
        print(f"Smoke test interrompu : {error}", file=sys.stderr)
        sys.exit(1)
