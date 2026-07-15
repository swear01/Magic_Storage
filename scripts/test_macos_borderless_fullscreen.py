import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class MacOsBorderlessFullscreenTests(unittest.TestCase):
    def test_client_mixin_replaces_monitor_backed_f11_without_native_fullscreen(self):
        metadata = (ROOT / "src/main/templates/META-INF/neoforge.mods.toml").read_text()
        self.assertIn('config="magic_storage.mixins.json"', metadata)

        config = json.loads((ROOT / "src/main/resources/magic_storage.mixins.json").read_text())
        self.assertTrue(config["required"])
        self.assertEqual(["MacOsWindowMixin"], config["client"])
        self.assertEqual(1, config["injectors"]["defaultRequire"])

        source = (
            ROOT
            / "src/main/java/com/swearprom/magicstorage/magic_storage/mixin/MacOsWindowMixin.java"
        ).read_text()
        self.assertIn("glfwSetWindowMonitor", source)
        self.assertIn("glfwGetWindowMonitor", source)
        self.assertNotIn("glfwCreateWindow", source)
        self.assertIn('method = "setMode"', source)
        self.assertIn("Minecraft.ON_OSX", source)
        self.assertIn("GLFW_DECORATED", source)
        self.assertIn("GLFW_DONT_CARE", source)
        self.assertIn("glfwGetCocoaWindow", source)
        self.assertIn("if (!CocoaWindow.isBorderlessFullscreen(window))", source)
        self.assertIn("return CocoaWindow.isBorderlessFullscreen(window) ? 1L", source)
        self.assertIn("setPresentationOptions:", source)
        self.assertIn("setLevel:", source)
        self.assertNotIn("toggleFullScreen", source)
        self.assertNotIn("MacosUtil", source)


if __name__ == "__main__":
    unittest.main()
