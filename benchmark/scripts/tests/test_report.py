import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("report", Path(__file__).parents[1] / "report.py")
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)

class ReportValidityTest(unittest.TestCase):
    def test_missing_measurements_cannot_become_a_valid_baseline(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "instrumentation.txt").write_text("OK (7 tests)")
            with self.assertRaisesRegex(ValueError, "Missing benchmark"):
                report.read_session(root)

    def test_percentiles_reject_empty_and_invalid_samples(self):
        for samples in [[], [float("nan")], [float("inf")]]:
            with self.assertRaises(ValueError):
                report.percentile(samples, .95)
        self.assertAlmostEqual(38.5, report.percentile([10, 20, 30, 40], .95))

    def test_meminfo_uses_pss_not_rss_and_does_not_invent_missing_fields(self):
        parsed = report.meminfo(" Java Heap: 1024 8000\n Native Heap: 2048 9000\n TOTAL PSS: 4096 TOTAL RSS: 30000")
        self.assertEqual(4, parsed["totalPssMB"])
        self.assertEqual(1, parsed["javaHeapMB"])
        self.assertNotIn("graphicsMB", parsed)
        with self.assertRaises(ValueError):
            report.meminfo("TOTAL RSS: 99999")
