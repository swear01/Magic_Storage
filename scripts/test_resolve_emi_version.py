from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/resolve_emi_version.py"


def load_resolver(test_case: unittest.TestCase):
    test_case.assertTrue(SCRIPT.exists(), "missing scripts/resolve_emi_version.py")
    spec = spec_from_file_location("resolve_emi_version", SCRIPT)
    test_case.assertIsNotNone(spec)
    test_case.assertIsNotNone(spec.loader)
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ResolveEmiVersionTests(unittest.TestCase):
    def test_selects_numeric_latest_supported_minecraft_build(self):
        resolver = load_resolver(self)
        metadata = """
            <metadata><versioning><versions>
              <version>1.1.24+1.21.1</version>
              <version>1.1.9+1.21.1</version>
              <version>1.2.3+1.21.1</version>
              <version>1.9.0+1.20.6</version>
            </versions></versioning></metadata>
        """

        self.assertEqual(
            "1.2.3+1.21.1",
            resolver.latest_compatible_version(metadata, "1.21.1", (1, 1, 24), (2, 0, 0)),
        )

    def test_excludes_versions_below_minimum_or_at_upper_bound(self):
        resolver = load_resolver(self)
        metadata = """
            <metadata><versioning><versions>
              <version>1.1.23+1.21.1</version>
              <version>1.1.24+1.21.1</version>
              <version>2.0.0+1.21.1</version>
              <version>9.0.0+1.20.4</version>
            </versions></versioning></metadata>
        """

        self.assertEqual(
            "1.1.24+1.21.1",
            resolver.latest_compatible_version(metadata, "1.21.1", (1, 1, 24), (2, 0, 0)),
        )

    def test_rejects_metadata_without_a_compatible_release(self):
        resolver = load_resolver(self)
        metadata = """
            <metadata><versioning><versions>
              <version>1.1.23+1.21.1</version>
              <version>1.1.24+1.20.6</version>
            </versions></versioning></metadata>
        """

        with self.assertRaisesRegex(ValueError, "no compatible EMI release"):
            resolver.latest_compatible_version(metadata, "1.21.1", (1, 1, 24), (2, 0, 0))

    def test_reads_the_release_range_as_inclusive_minimum_and_exclusive_upper_bound(self):
        resolver = load_resolver(self)

        self.assertEqual(
            ((1, 1, 24), (2, 0, 0)),
            resolver.parse_supported_range("[1.1.24,2)"),
        )
        for invalid in ["[1.1.24]", "(1.1.24,2)", "[1.1.24,2]", "1.1.24"]:
            with self.subTest(invalid=invalid):
                with self.assertRaisesRegex(ValueError, "inclusive minimum and exclusive upper bound"):
                    resolver.parse_supported_range(invalid)

    def test_baseline_coordinate_must_match_range_and_minecraft_version(self):
        resolver = load_resolver(self)

        resolver.validate_baseline("1.1.24+1.21.1", "1.21.1", (1, 1, 24), (2, 0, 0))
        with self.assertRaisesRegex(ValueError, "minimum supported EMI version"):
            resolver.validate_baseline("1.1.25+1.21.1", "1.21.1", (1, 1, 24), (2, 0, 0))
        with self.assertRaisesRegex(ValueError, "Minecraft version"):
            resolver.validate_baseline("1.1.24+1.21", "1.21.1", (1, 1, 24), (2, 0, 0))


if __name__ == "__main__":
    unittest.main()
