"""Liste les modeles Bedrock Mantle sans envoyer de prompt ni afficher de secret."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


def read_environment(path):
    values = dict(os.environ)
    if path:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            key, separator, value = line.partition("=")
            if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
                raise ValueError("Format env-file invalide : utiliser NOM=valeur, sans export ni guillemets")
            values[key] = value
    return values


def catalog_url(base_url):
    url = urllib.parse.urlsplit(base_url)
    if (url.scheme != "https" or not re.fullmatch(r"bedrock-mantle\.[a-z0-9-]+\.api\.aws", url.netloc)
            or url.query or url.fragment or url.path.rstrip("/") not in ("/v1", "/openai/v1")):
        raise ValueError("APP_AI_BEDROCK_URL doit etre un endpoint HTTPS Mantle avec /v1 ou /openai/v1, sans guillemet ni parametre")
    return urllib.parse.urlunsplit((url.scheme, url.netloc, "/v1/models", "", ""))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


def list_models(values):
    endpoint = catalog_url(values.get("APP_AI_BEDROCK_URL", ""))
    key = values.get("APP_AI_BEDROCK_API_KEY", "")
    if not key or key != key.strip() or "\r" in key or "\n" in key:
        raise ValueError("Renseigner APP_AI_BEDROCK_API_KEY dans le fichier prive, jamais dans la commande")
    request = urllib.request.Request(endpoint, headers={"Authorization": "Bearer " + key})
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=20) as response:
        content = response.read(2_000_001)
    if len(content) > 2_000_000:
        raise ValueError("Catalogue Bedrock trop volumineux")
    result = json.loads(content)
    if (not isinstance(result, dict) or not isinstance(result.get("data"), list)
            or any(not isinstance(model, dict) or not isinstance(model.get("id"), str)
                   or not model["id"] or any(ord(character) < 32 for character in model["id"])
                   for model in result["data"])):
        raise ValueError("Format du catalogue Bedrock invalide")
    models = sorted({model["id"] for model in result["data"]})
    if not models:
        raise ValueError("Le catalogue Bedrock ne contient aucun modele")
    return models


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", help="Fichier prive NOM=valeur, sans interpolation")
    args = parser.parse_args()
    try:
        for model in list_models(read_environment(args.env_file)):
            print(model)
    except urllib.error.HTTPError as error:
        print(f"Catalogue Bedrock refuse (HTTP {error.code}) ; verifier la cle, les droits IAM et la region", file=sys.stderr)
        return 1
    except (OSError, ValueError) as error:
        if isinstance(error, ValueError):
            print(str(error), file=sys.stderr)
        else:
            print(f"Catalogue Bedrock indisponible ({type(error).__name__})", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
