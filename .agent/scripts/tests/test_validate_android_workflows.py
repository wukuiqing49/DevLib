"""验证聚合校验能覆盖实际启用的业务模块。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "workflow_validator", Path(__file__).resolve().parents[1] / "validate_android_workflows.py")
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)


class HostModulesTest(unittest.TestCase):
    """在临时工程中验证模块收集与源码、资源入口。"""

    def setUp(self):
        """建立与业务工程隔离的测试目录。"""
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = dict(app="app", featureApp="feature/feature_app", featureRes="feature/feature_res")

    def module(self, name, kotlin=False):
        """创建具有源代码和资源入口的最小模块。"""
        path = self.root / name
        (path / "src/main/java").mkdir(parents=True)
        (path / "src/main/res/values").mkdir(parents=True)
        (path / "src/main/res/values/strings.xml").write_text("<resources/>", encoding="utf-8")
        (path / ("build.gradle.kts" if kotlin else "build.gradle")).write_text("", encoding="utf-8")

    def test_includes_active_features_and_excludes_inactive_and_core(self):
        """收集新增业务模块，排除未注册历史模块及独立基础库。"""
        for module in [*self.config.values(), "feature/feature_demo_files", "feature/inactive", "core/core_base"]:
            self.module(module)
        (self.root / "settings.gradle").write_text(
            "include ':app'\ninclude ':feature:feature_demo_files' // ':feature:inactive'\n"
            "// include ':feature:inactive'\ninclude ':core:core_base'\n", encoding="utf-8")
        result = validator.discover_host_modules(self.root, self.config)
        self.assertIn("feature/feature_demo_files", result)
        self.assertNotIn("feature/inactive", result)
        self.assertNotIn("feature/feature_app", result)
        self.assertNotIn("core/core_base", result)
        self.assertEqual(result.count("app"), 1)

    def test_kotlin_settings_multiline_and_resource_arguments(self):
        """支持 Kotlin settings 多行 include 并将业务资源传给校验器。"""
        self.module("feature/feature_demo_image", kotlin=True)
        (self.root / "settings.gradle.kts").write_text(
            'include(\n ":feature:feature_demo_image",\n ":feature:missing"\n)\n', encoding="utf-8")
        result = validator.discover_host_modules(self.root, self.config)
        self.assertEqual(result, ["feature/feature_demo_image"])
        args = validator.existing_args(self.root, "--res-dir", [f"{x}/src/main/res" for x in result])
        self.assertEqual(args, ["--res-dir", "feature/feature_demo_image/src/main/res"])

    def test_external_project_does_not_use_tool_config(self):
        tool = self.root / 'tool'
        (tool / 'config').mkdir(parents=True)
        (tool / 'config/project-scaffold.yml').write_text('modules:\n  app: wrong-app', encoding='utf-8')
        self.assertEqual(validator.load_scaffold_modules(self.root, tool, None)['app'], 'app')

    def test_required_locales_follow_target_profile(self):
        self.assertEqual(validator.required_host_locales(self.root), [])
        profile = self.root / '.agents/config/project-profile.yml'
        profile.parent.mkdir(parents=True)
        profile.write_text('android:\n  requiredHostLocales: [en, fr]\n', encoding='utf-8')
        self.assertEqual(validator.required_host_locales(self.root), ['en', 'fr'])

    def test_navigation_does_not_imply_pager(self):
        report = self.root / 'page.md'
        report.write_text('底部主导航', encoding='utf-8')
        self.assertFalse(validator.report_requires_pager(report))
        report.write_text('- 是否需要 ViewPager2 + Fragment：是', encoding='utf-8')
        self.assertTrue(validator.report_requires_pager(report))

    def test_custom_feature_directory(self):
        """使用目标配置的业务目录，避免绑定 DevLib 固定路径。"""
        self.config.update(featureApp="business/main", featureRes="business/resources")
        self.module("business/editor")
        (self.root / "settings.gradle").write_text("include ':business:editor'\n", encoding="utf-8")
        self.assertEqual(validator.discover_host_modules(self.root, self.config), ["business/editor"])


if __name__ == "__main__":
    unittest.main()
