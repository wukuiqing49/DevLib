"""验证物理目录分组与稳定 Gradle 逻辑路径的映射，避免迁移后漏检模块。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("architecture_validator", ROOT / ".agents/skills/android-project-architecture-workflow/scripts/validate_architecture.py")
architecture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(architecture)


class CoreLayoutTest(unittest.TestCase):
    """覆盖真实目录闭环及临时工程中的映射、缺失模块和依赖边界。"""

    def test_catalog_matches_settings_and_physical_modules(self):
        """所有库必须在正确分组中且映射到既有逻辑模块。"""
        modules = json.loads((ROOT / "docs/library-catalog.json").read_text(encoding="utf-8"))["modules"]
        collected = architecture.collect_modules(ROOT)
        for module in modules:
            expected = ROOT / module["path"]
            self.assertEqual(expected, collected[":core:" + module["module"]])
            self.assertEqual(expected, architecture.module_dir_from_path(ROOT, ":core:" + module["module"]))
            self.assertEqual(module["directoryGroup"], expected.parent.name)
            self.assertEqual(module["category"], module["directoryGroup"])
        self.assertEqual(19, len([m for m in collected if m.startswith(":core:")]))

    def test_mapped_module_still_enforces_dependency_boundary(self):
        """目录映射不得让 core 对 feature 的违规依赖逃过检查。"""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module = root / "core/functional/example"
            module.mkdir(parents=True)
            (root / "settings.gradle").write_text("include ':core:example'\nproject(':core:example').projectDir = file('core/functional/example')", encoding="utf-8")
            (module / "build.gradle").write_text("implementation project(':feature:feature_app')", encoding="utf-8")
            modules = architecture.collect_modules(root)
            errors, warnings = [], []
            architecture.validate_settings_modules(root, modules, errors, warnings)
            self.assertEqual([], errors)
            self.assertEqual([], warnings)
            architecture.validate_dependencies(modules, errors, warnings)
            self.assertEqual(1, len(errors))
            (module / "build.gradle").unlink()
            errors = []
            architecture.validate_settings_modules(root, {}, errors, warnings)
            self.assertEqual(1, len(errors))

    def test_project_profile_uses_the_same_library_inventory(self):
        """项目档案不得遗漏模块或保留已经失效的分类路径。"""
        profile = yaml.safe_load((ROOT / ".agents/config/project-profile.yml").read_text(encoding="utf-8"))
        modules = json.loads((ROOT / "docs/library-catalog.json").read_text(encoding="utf-8"))["modules"]
        self.assertEqual({m["path"] for m in modules}, set(profile["modules"]["libraries"]))
        self.assertEqual({m["path"] for m in modules if m["category"] == "architecture"}, set(profile["modules"]["foundationLibraries"]))
        for module in modules:
            self.assertEqual(Path(module["path"]).parent.as_posix(), profile["libraryOrganization"][module["category"]])


if __name__ == "__main__":
    unittest.main()
