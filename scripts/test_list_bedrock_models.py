import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("bedrock_catalog", Path(__file__).with_name("list-bedrock-models.py"))
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


class BedrockCatalogTest(unittest.TestCase):
    def test_model_specific_prefixes_use_the_same_mantle_catalog(self):
        for path in ("/v1", "/openai/v1", "/openai/v1/"):
            self.assertEqual(catalog.catalog_url("https://bedrock-mantle.us-east-1.api.aws" + path),
                             "https://bedrock-mantle.us-east-1.api.aws/v1/models")

    def test_rejects_quotes_non_aws_urls_http_and_signed_parameters(self):
        for url in ("https://bedrock-mantle.us-east-1.api.aws/openai/v1%22",
                    "https://bedrock-mantle.us-east-1.api.aws/v1?token=private",
                    "http://bedrock-mantle.us-east-1.api.aws/v1",
                    "https://unexpected.example/v1",
                    "https://user:private@bedrock-mantle.us-east-1.api.aws/v1"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                catalog.catalog_url(url)

    def test_environment_file_does_not_interpolate_or_strip_key_characters(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "private.env"
            path.write_text("# comment\nAPP_AI_BEDROCK_API_KEY=literal$VALUE=tail\n", encoding="utf-8")
            self.assertEqual(catalog.read_environment(path)["APP_AI_BEDROCK_API_KEY"], "literal$VALUE=tail")

    def test_authorization_is_sent_only_to_catalog_without_following_redirects(self):
        values = {"APP_AI_BEDROCK_URL": "https://bedrock-mantle.us-east-1.api.aws/openai/v1",
                  "APP_AI_BEDROCK_API_KEY": "synthetic-fixture-key"}
        with patch.object(catalog.urllib.request, "build_opener") as build:
            build.return_value.open.return_value.__enter__.return_value.read.return_value = (
                b'{"data":[{"id":"model-b"},{"id":"model-a"},{"id":"model-a"}]}')
            self.assertEqual(catalog.list_models(values), ["model-a", "model-b"])
            self.assertIsInstance(build.call_args.args[0], catalog.NoRedirect)
            request = build.return_value.open.call_args.args[0]
            self.assertEqual(request.full_url, "https://bedrock-mantle.us-east-1.api.aws/v1/models")
            self.assertEqual(request.get_header("Authorization"), "Bearer synthetic-fixture-key")
            self.assertIsNone(request.data)

    def test_missing_credential_is_rejected_before_network(self):
        with patch.object(catalog.urllib.request, "build_opener") as build:
            with self.assertRaisesRegex(ValueError, "APP_AI_BEDROCK_API_KEY"):
                catalog.list_models({"APP_AI_BEDROCK_URL": "https://bedrock-mantle.us-east-1.api.aws/v1"})
            build.assert_not_called()

    def test_invalid_catalog_is_explicit(self):
        values = {"APP_AI_BEDROCK_URL": "https://bedrock-mantle.us-east-1.api.aws/v1",
                  "APP_AI_BEDROCK_API_KEY": "synthetic-fixture-key"}
        for body in (b'{}', b'{"data":[]}', b'{"data":[{"id":12}]}'):
            with self.subTest(body=body), patch.object(catalog.urllib.request, "build_opener") as build:
                build.return_value.open.return_value.__enter__.return_value.read.return_value = body
                with self.assertRaises(ValueError):
                    catalog.list_models(values)


if __name__ == "__main__":
    unittest.main()
