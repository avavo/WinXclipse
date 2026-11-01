"""Check the actual GLES-to-Vulkan exporter and committed shader interfaces."""
import json
import unittest
import export_vulkan_shaders as exporter


class ShaderExportTest(unittest.TestCase):
    def test_generated_algorithms_match_the_current_effect_sources(self):
        manifest = json.loads((exporter.OUT / "manifest.json").read_text())
        for name, (source, marker) in exporter.SOURCES.items():
            with self.subTest(shader=name):
                shader = exporter.extract_java_shader((exporter.JAVA / source).read_text(), marker)
                result, metadata = exporter.convert(shader, name.endswith(".comp"))
                self.assertEqual(result, (exporter.OUT / name).read_text())
                metadata["source"] = source
                self.assertEqual(metadata, manifest[name])

    def test_unrecognized_java_interpolation_is_rejected(self):
        with self.assertRaises(ValueError):
            exporter.extract_java_shader('String source = "x" + runtimeExpression + "y";', "String source =")

    def test_rcas_sharpness_is_preserved_as_a_push_constant(self):
        source = exporter.extract_java_shader((exporter.JAVA / "effects/FSREffect.java").read_text(), "getFragmentShader()")
        result, metadata = exporter.convert(source, False)
        self.assertEqual({"name": "uSharpnessStops", "type": "float", "offset": 0, "size": 4}, metadata["uniforms"][0])
        self.assertIn("exp2(-uSharpnessStops)", result)

    def test_top_left_vulkan_storage_preserves_bottom_left_motion_coordinates(self):
        source = (exporter.OUT / "motion.comp").read_text()
        self.assertIn("imageSize(motionVectorOutput).y - 1 - p.y", source)
        self.assertIn("vec2(uv.x,1.0-uv.y)", source)
        self.assertIn("local_size_x=16, local_size_y=8", source)

    def test_shader_resources_fit_the_guaranteed_vulkan_limits(self):
        manifest = json.loads((exporter.OUT / "manifest.json").read_text())
        for shader, metadata in manifest.items():
            with self.subTest(shader=shader):
                self.assertLessEqual(metadata["push_bytes"], 128)
                bindings = metadata["bindings"]
                self.assertEqual(list(range(len(bindings))), [b["binding"] for b in bindings])
                for uniform in metadata["uniforms"]:
                    self.assertLessEqual(uniform["offset"] + uniform["size"], metadata["push_bytes"])


if __name__ == "__main__":
    unittest.main()
