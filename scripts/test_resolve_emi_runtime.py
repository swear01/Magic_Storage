from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/resolve_emi_runtime.py"


def load_resolver(test_case: unittest.TestCase):
    test_case.assertTrue(SCRIPT.exists(), "missing scripts/resolve_emi_runtime.py")
    spec = spec_from_file_location("resolve_emi_runtime", SCRIPT)
    test_case.assertIsNotNone(spec)
    test_case.assertIsNotNone(spec.loader)
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ResolveEmiRuntimeTests(unittest.TestCase):
    def test_selects_exact_neoforge_runtime_version_id(self):
        resolver = load_resolver(self)
        versions = [
            {
                "id": "wrong-loader",
                "version_number": "1.1.24+1.21.1+fabric",
                "loaders": ["fabric"],
                "game_versions": ["1.21.1"],
                "files": [{"filename": "emi-fabric.jar", "primary": True, "size": 10}],
            },
            {
                "id": "5sIPA1To",
                "version_number": "1.1.24+1.21.1+neoforge",
                "loaders": ["neoforge"],
                "game_versions": ["1.21.1"],
                "files": [
                    {
                        "filename": "emi-1.1.24+1.21.1+neoforge.jar",
                        "primary": True,
                        "size": 1_111_538,
                    }
                ],
            },
        ]

        self.assertEqual(
            "5sIPA1To",
            resolver.resolve_runtime_version_id(versions, "1.1.24+1.21.1", "1.21.1"),
        )

    def test_rejects_missing_or_ambiguous_exact_runtime(self):
        resolver = load_resolver(self)
        exact = {
            "id": "first",
            "version_number": "1.1.24+1.21.1+neoforge",
            "loaders": ["neoforge"],
            "game_versions": ["1.21.1"],
            "files": [{"filename": "emi.jar", "primary": True, "size": 10}],
        }

        with self.assertRaisesRegex(ValueError, "no exact Modrinth EMI runtime"):
            resolver.resolve_runtime_version_id([], "1.1.24+1.21.1", "1.21.1")
        with self.assertRaisesRegex(ValueError, "multiple exact Modrinth EMI runtimes"):
            resolver.resolve_runtime_version_id(
                [exact, exact | {"id": "second"}],
                "1.1.24+1.21.1",
                "1.21.1",
            )

    def test_rejects_runtime_without_one_valid_primary_jar(self):
        resolver = load_resolver(self)
        base = {
            "id": "bad-file",
            "version_number": "1.1.24+1.21.1+neoforge",
            "loaders": ["neoforge"],
            "game_versions": ["1.21.1"],
        }
        invalid_files = [
            [],
            [{"filename": "emi.jar", "primary": False, "size": 10}],
            [{"filename": "emi-api.jar", "primary": True, "size": 10}],
            [{"filename": "emi.jar", "primary": True, "size": 0}],
        ]

        for files in invalid_files:
            with self.subTest(files=files):
                with self.assertRaisesRegex(ValueError, "one valid primary runtime jar"):
                    resolver.resolve_runtime_version_id(
                        [base | {"files": files}],
                        "1.1.24+1.21.1",
                        "1.21.1",
                    )


if __name__ == "__main__":
    unittest.main()
