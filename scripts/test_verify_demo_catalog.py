import unittest

from verify_demo_catalog import artifact_type, expected_demo_modules, catalog_entries, isolated_view_model


class DemoCoveragePolicyTest(unittest.TestCase):
    def test_formatted_catalog_retains_all_entries(self):
        self.assertEqual([("core_pdf", "/document/core_pdf", "document")], catalog_entries('DemoEntry(\n "core_pdf",\n "/document/core_pdf",\n "document", title)'))

    def test_saved_state_constructor_preserves_isolated_model(self):
        self.assertTrue(isolated_view_model("class OfficeViewModel(private val saved: SavedStateHandle) : DemoViewModel()", "OfficeViewModel"))
        self.assertFalse(isolated_view_model("class OfficeViewModel : OtherViewModel()", "OfficeViewModel"))

    def test_operations_android_plugin_must_not_be_classified_as_jvm(self):
        self.assertEqual("aar", artifact_type("plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }"))

    def test_xpopup_literal_plugin_is_still_an_aar(self):
        self.assertEqual("aar", artifact_type("plugins { id 'com.android.library'; id 'org.jetbrains.kotlin.android' }"))

    def test_kotlin_and_gradle_plugin_modules_are_jvm_artifacts(self):
        self.assertEqual("jvm", artifact_type("plugins { alias(libs.plugins.kotlin.jvm) }"))
        self.assertEqual("jvm", artifact_type("plugins { id 'java-gradle-plugin' }"))

    def test_unrecognized_plugin_cannot_silently_become_a_jvm_library(self):
        with self.assertRaises(AssertionError):
            artifact_type("plugins { id 'custom.plugin' }")

    def test_internal_contract_is_covered_through_real_consumers(self):
        modules = [{"module": "image"}, {"module": "operations", "role": "support", "demoCoveredBy": ["image"]}]
        self.assertEqual({"image"}, expected_demo_modules(modules))

    def test_missing_consumer_cannot_hide_an_uncovered_module(self):
        with self.assertRaises(AssertionError):
            expected_demo_modules([{"module": "operations", "role": "support", "demoCoveredBy": ["removed-image"]}])

    def test_support_modules_cannot_cover_each_other(self):
        with self.assertRaises(AssertionError):
            expected_demo_modules([
                {"module": "a", "role": "support", "demoCoveredBy": ["b"]},
                {"module": "b", "role": "support", "demoCoveredBy": ["a"]},
            ])

    def test_duplicate_classification_is_rejected(self):
        with self.assertRaises(AssertionError):
            expected_demo_modules([{"module": "image"}, {"module": "image"}])


if __name__ == "__main__":
    unittest.main()
