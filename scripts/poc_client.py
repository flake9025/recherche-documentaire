"""Client du POC avec session HTTP et renouvellement CSRF apres connexion."""
import http.cookiejar
import json
import urllib.parse
import urllib.request


class Session:
    def __init__(self, base_url: str, username: str, password: str, timeout: int):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        )
        self.csrf = self.json("/api/auth/csrf")
        with self.request(
            "/api/auth/login",
            urllib.parse.urlencode({"username": username, "password": password}).encode(),
            "application/x-www-form-urlencoded",
        ):
            pass
        self.csrf = self.json("/api/auth/csrf")
        self.user = self.json("/api/auth/me")

    def request(self, path: str, data: bytes | None = None, content_type: str = "application/json",
                method: str | None = None):
        headers = {"Content-Type": content_type}
        if data is not None or method in ("POST", "PUT", "DELETE", "PATCH"):
            headers[self.csrf["headerName"]] = self.csrf["token"]
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        return self.opener.open(request, timeout=self.timeout)

    def json(self, path: str, payload: dict | None = None, method: str | None = None):
        data = None if payload is None else json.dumps(payload).encode()
        with self.request(path, data, method=method) as response:
            return json.load(response)
