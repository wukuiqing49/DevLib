"""校验支付交互稿语言覆盖、占位符及资源编码；不代替母语审校。"""

import json
import re
from pathlib import Path


def unique_object(pairs):
    """拒绝重复语言或资源键，避免 JSON 解码静默覆盖翻译。"""
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"重复键：{key}")
        result[key] = value
    return result


def main():
    """核对所有语言与英文基线的键及命名参数，并输出摘要。"""
    root = Path(__file__).resolve().parent
    text = (root / "locales.js").read_text(encoding="utf-8")
    names_text, locales_text = text.split("window.BILLING_LOCALES = ", 1)
    names = json.loads(names_text.removeprefix("window.BILLING_LOCALE_NAMES = ").strip().removesuffix(";"), object_pairs_hook=unique_object)
    locales = json.loads(locales_text.strip().removesuffix(";"), object_pairs_hook=unique_object)
    assert set(names) == set(locales), "语言选项与语言资源不一致"
    baseline = locales["en"]
    for locale, strings in locales.items():
        assert strings.keys() == baseline.keys(), f"语言键不一致：{locale}"
        for key, value in strings.items():
            assert isinstance(value, str) and value.strip(), f"空翻译：{locale}/{key}"
            assert "\ufffd" not in value, f"编码损坏：{locale}/{key}"
            assert sorted(re.findall(r"\{\w+\}", value)) == sorted(re.findall(r"\{\w+\}", baseline[key])), f"占位符不一致：{locale}/{key}"
    source = (root / "billing.js").read_text(encoding="utf-8")
    for key in re.findall(r"\bt\('([A-Za-z]+)'", source):
        assert key in baseline, f"引用了不存在的键：{key}"
    print(f"通过：{len(locales)} 种语言，每种 {len(baseline)} 个键，共 {len(locales) * len(baseline)} 项翻译；占位符一致。")


if __name__ == "__main__":
    main()
