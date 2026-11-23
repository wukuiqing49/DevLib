"""验证离线定位的证据等级和不匹配拒绝；不连接设备、不下载符号。"""
import argparse
import contextlib
import io
import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
import native_symbols as symbols


class SymbolEvidenceTest(unittest.TestCase):
    """源码、函数和不可解析必须准确区分，损坏归档不能参与定位。"""

    def run_report(self, addr_output=None, tamper=False, outside=False):
        """用独立临时目录构造用户报告及符号归档，返回实际文本结果。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "libtest.so"
            binary.write_bytes(b"ELF fixture")
            row = {"status": "archived", "abi": "arm64-v8a", "module": "libtest.so", "buildId": "aabbccdd", "debugLines": False,
                   "file": "../outside.so" if outside else binary.name, "sha256": "wrong" if tamper else symbols.digest(binary)}
            manifest = root / "symbols.json"
            manifest.write_text(json.dumps({"buildId": "fixture", "modules": [row] if addr_output is not None else []}))
            report = root / "report.zip"
            with zipfile.ZipFile(report, "w") as archive:
                archive.writestr("native-trace.json", json.dumps({"architecture": 1, "frames": [{"module": "libtest.so", "buildId": "aabbccdd", "relativePc": "1234", "function": "reported_function", "functionOffset": "10"}]}))
            output = root / "result.txt"
            args = argparse.Namespace(ndk=root, report=report, symbols=manifest, system_symbols=None, output=output)
            with contextlib.redirect_stdout(io.StringIO()), patch.object(symbols, "tool", return_value=Path("llvm")), patch.object(symbols, "inspect_elf", return_value=("aabbccdd", False)), patch.object(symbols.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, addr_output or "", "")) as process:
                symbols.symbolize(args)
                if tamper or outside or addr_output is None:
                    process.assert_not_called()
            return output.read_text(encoding="utf-8")

    def test_missing_symbols_preserve_reported_function_and_offset(self):
        """没有符号时保留已有函数和偏移，不虚构来源行。"""
        text = self.run_report()
        self.assertIn("reported_function+0x10", text)
        self.assertIn("UNRESOLVED", text)
        self.assertNotIn("SOURCE_LINE /", text)

    def test_function_without_lines_is_not_reported_as_source(self):
        """精确二进制仅有函数时，不能把函数命中计作源码行命中。"""
        text = self.run_report("actual_function\n??:0\n")
        self.assertIn("FUNCTION_ONLY", text)
        self.assertIn("Source lines = 0; verified functions = 1", text)

    def test_matched_source_line_is_reported(self):
        """有效调试行号被单独计数，保留函数位置。"""
        text = self.run_report("actual_function\nsource.cpp:42\n")
        self.assertIn("SOURCE_LINE", text)
        self.assertIn("Source lines = 1", text)

    def test_tampered_or_external_file_is_never_used(self):
        """摘要错误和归档外路径都不执行解析工具。"""
        self.assertIn("UNRESOLVED", self.run_report("fake\nsource.cpp:42\n", tamper=True))
        self.assertIn("UNRESOLVED", self.run_report("fake\nsource.cpp:42\n", outside=True))


if __name__ == "__main__":
    unittest.main()
