import unittest

from verify_demo_catalog import expected_demo_modules


class DemoCoveragePolicyTest(unittest.TestCase):
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
