"""用隔离 Gradle 片段验证架构边界与静态图检查范围。"""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "skills/android-project-architecture-workflow/scripts/validate_architecture.py"
SPEC = importlib.util.spec_from_file_location("architecture_validator", SCRIPT)
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)


class ArchitectureGraphTest(unittest.TestCase):
    """覆盖模块发现、依赖边界和循环检测的可观察结果。"""

    def setUp(self):
        """为每项测试创建隔离目录。"""
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, relative, text):
        """保存测试构建文件并返回路径。"""
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def test_kotlin_multiline_includes(self):
        """同一 include 内的所有模块均应参与检查。"""
        self.write("settings.gradle.kts", 'include(\n ":app", ":feature:a",\n ":feature:b",\n)')
        self.assertEqual({":app", ":feature:a", ":feature:b"}, validator.collect_settings_modules(self.root))

    def test_groovy_multiple_includes_and_comments(self):
        """Groovy 多参数与注释内伪入口正确区分。"""
        self.write("settings.gradle", "include ':app', ':feature:a' // include ':bad'\n/* include ':bad2' */\ninclude ':feature:b'")
        self.assertEqual({":app", ":feature:a", ":feature:b"}, validator.collect_settings_modules(self.root))

    def test_strings_are_not_calls(self):
        """日志字符串里的依赖和 include 不进入依赖图。"""
        self.write("settings.gradle.kts", 'println("include(\':bad\')")\ninclude(":app")')
        self.write("app/build.gradle.kts", 'println("project(\':bad\')")\nimplementation(project(":core:base"))')
        self.assertEqual({":app"}, validator.collect_settings_modules(self.root))
        self.assertEqual({":core:base"}, validator.collect_project_dependencies(self.root / "app"))

    def test_named_and_positional_dependencies(self):
        """支持 Groovy/Kotlin path 以及普通位置参数。"""
        self.write("app/build.gradle.kts", 'implementation(project(path = ":feature:a"))\napi(project(":core:b"))')
        self.write("app/build.gradle", "implementation project(path: ':feature:c', configuration: 'default')\n// project(':bad')")
        self.assertEqual({":feature:a", ":core:b", ":feature:c"}, validator.collect_project_dependencies(self.root / "app"))

    def test_named_dependency_cannot_bypass_core_boundary(self):
        """命名参数的反向依赖必须报错。"""
        path = self.write("core/base/build.gradle.kts", 'implementation(project(path = ":feature:a"))').parent
        errors = []
        validator.validate_dependencies({":core:base": path}, errors, [])
        self.assertTrue(any("core module must not depend" in e for e in errors))

    def test_dependency_cycle(self):
        """循环依赖需报告包含完整闭环的模块路径。"""
        a = self.write("a/build.gradle", "implementation project(':feature:b')").parent
        b = self.write("b/build.gradle", "implementation project(':feature:a')").parent
        errors = []
        validator.validate_dependencies({":feature:a": a, ":feature:b": b}, errors, [])
        self.assertTrue(any(":feature:a -> :feature:b -> :feature:a" in e for e in errors))

    def test_self_cycle(self):
        """自依赖不能被访问标记忽略。"""
        a = self.write("a/build.gradle", "implementation project(':feature:a')").parent
        errors = []
        validator.validate_dependencies({":feature:a": a}, errors, [])
        self.assertTrue(any("cycle" in e for e in errors))

    def test_shared_dependency_is_not_cycle(self):
        """菱形无环依赖不应误报。"""
        modules = {}
        for name, dependencies in {"a": ["b", "c"], "b": ["d"], "c": ["d"], "d": []}.items():
            modules[f":feature:{name}"] = self.write(f"{name}/build.gradle", "\n".join(f"api project(':feature:{d}')" for d in dependencies)).parent
        errors = []
        validator.validate_dependencies(modules, errors, [])
        self.assertEqual([], errors)

    def test_dynamic_input_is_incomplete(self):
        """动态入口与依赖不能静默解释为空图。"""
        self.write("settings.gradle.kts", 'include(":app", featureName)')
        self.write("app/build.gradle.kts", 'implementation(project(targetModule))\nimplementation(projects.feature.editor)')
        warnings = []
        self.assertEqual({":app"}, validator.collect_settings_modules(self.root, warnings))
        self.assertEqual(set(), validator.collect_project_dependencies(self.root / "app", warnings))
        self.assertGreaterEqual(len(warnings), 3)
        self.assertTrue(all("incomplete" in w for w in warnings))

    def test_interpolated_module_is_not_literal(self):
        """字符串插值不会被误当已解析模块路径。"""
        self.write("settings.gradle.kts", 'include(":$name")')
        warnings = []
        self.assertEqual(set(), validator.collect_settings_modules(self.root, warnings))
        self.assertTrue(warnings)

    def test_directory_mapping_and_missing_second_module(self):
        """显式映射与同一 include 后续缺失模块均接受检查。"""
        self.write("settings.gradle", "include ':core:base', ':feature:missing'\nproject(':core:base').projectDir = file('libraries/base')")
        self.write("libraries/base/build.gradle", "")
        modules = validator.collect_modules(self.root)
        self.assertIn(":core:base", modules)
        errors = []
        validator.validate_settings_modules(self.root, modules, errors, [])
        self.assertEqual(1, len(errors))
        self.assertIn(":feature:missing", errors[0])

    def test_dynamic_directory_mapping_is_incomplete(self):
        """未支持的目录映射需明确报告范围不足。"""
        self.write("settings.gradle.kts", 'include(":app")\nproject(":app").projectDir = File(rootDir, appPath)')
        warnings = []
        validator.collect_settings_modules(self.root, warnings)
        self.assertTrue(any("projectDir" in w for w in warnings))


if __name__ == "__main__":
    unittest.main()
