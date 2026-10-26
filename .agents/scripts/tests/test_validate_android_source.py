"""源码规则与模板契约回归。"""
import importlib.util
from pathlib import Path
import unittest
SPEC = importlib.util.spec_from_file_location('source_validator', Path(__file__).resolve().parents[1] / 'validate_android_source.py')
v = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(v)

class SourceTest(unittest.TestCase):
    def test_docs_annotations_and_fake_declarations(self):
        source = '/** 页面说明 */\nclass Page {\n/** 初始化视图 */\n@Deprecated("class Fake")\noverride fun initView() { val s = "fun fake()" }\n}'
        self.assertEqual(v.scan('Page.kt', source), [])
    def test_private_override_and_english_doc(self):
        findings = v.scan('Page.kt', '/** English */\nclass Page { private fun load() {}\noverride fun initView() {} }')
        self.assertEqual([f['symbol'] for f in findings], ['Page','load','initView'])
    def test_base_and_visible_text(self):
        result = v.scan('Page.kt', '/** 页面 */\nclass Page : Fragment() { /** 加载 */\nfun load() { title.text = "Hello"; val key = "internal" } }')
        self.assertEqual({f['rule'] for f in result}, {'BASE001','I18N001'})
    def test_xml_resources_and_comments(self):
        self.assertEqual(len(v.scan('page.xml', '<!-- android:text="fake" --><TextView android:text="@string/title" android:hint="Name"/>')), 1)
    def test_kotlin_enum_name_and_documentation(self):
        self.assertEqual(v.scan('Kind.kt', '/** 错误分类 */\nenum class Kind { INPUT }'), [])
        result = v.scan('Kind.kt', 'enum class Kind { INPUT }')
        self.assertEqual([f['symbol'] for f in result], ['Kind'])

    def test_java(self):
        result = v.scan('Page.java', '/** 页面 */\npublic class Page {\n public void load() {}\n}')
        self.assertEqual([f['symbol'] for f in result], ['load'])
    def test_scaffold_contract(self):
        root = Path(__file__).resolve().parents[2] / 'skills/android-project-scaffold-workflow/assets/scaffold'
        for p in root.glob('*.kt.tmpl'):
            source = p.read_text(encoding='utf-8').replace('{{APPLICATION_CLASS}}','DemoApplication')
            self.assertEqual(v.scan(p.name.removesuffix('.tmpl'), source), [], p.name)
    def test_core_cannot_depend_on_host_resources(self):
        import tempfile
        script = Path(__file__).resolve().parents[2] / 'skills/android-project-architecture-workflow/scripts/validate_architecture.py'
        spec = importlib.util.spec_from_file_location('architecture_check', script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'build.gradle').write_text("dependencies { implementation project(':feature:feature_res') }", encoding='utf-8')
            errors = []
            module.validate_dependencies({':core:example': root}, errors, [])
            self.assertEqual(len(errors), 1)

    def test_exception_requires_auditable_scope(self):
        with self.assertRaises(ValueError):
            v.apply_exceptions([], [dict(path='*',rule='DOC001',line=1)])
        f = dict(path='Page.kt',rule='DOC001',line=1)
        result = v.apply_exceptions([f], [dict(f,reason='生成代码',alternativeValidation='生成器契约测试',reviewAfter='生成器变更')])
        self.assertTrue(result[0]['excepted'])

if __name__ == '__main__':
    unittest.main()
