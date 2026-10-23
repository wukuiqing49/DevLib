"""Check active-core demo coverage, manifest wiring, generated routes and APK SPI.

This is a build integration check, not a UI or device functional test.
Run after assembleDebug: python scripts/verify_demo_catalog.py --apk <apk>
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def expected_demo_modules(modules: list[dict]) -> set[str]:
    active = {m["module"] for m in modules}
    assert len(active) == len(modules), "Duplicate classification modules"
    public = {m["module"] for m in modules if m.get("role") != "support"}
    for module in modules:
        if module.get("role") == "support":
            consumers = set(module.get("demoCoveredBy", []))
            assert consumers and consumers <= public, "Support module must name retained demo consumers"
    return public


def verify(apk: Path | None) -> dict:
    catalog = ROOT / "feature/feature_app/src/main/java/com/wkq/dev/feature/app/catalog/DemoCatalog.kt"
    entries = re.findall(r'DemoEntry\("([^"]+)","([^"]+)","([^"]+)"', catalog.read_text(encoding="utf-8"))
    classification = json.loads((ROOT / "docs/library-catalog.json").read_text(encoding="utf-8"))
    assert len([m for m in classification["modules"] if m["category"] == "foundation"]) == 8
    active = {p.name for p in (ROOT / "core").iterdir() if p.is_dir() and (p / "build.gradle").is_file()}
    assert {m["module"] for m in classification["modules"]} == active, "Classification manifest is stale"
    assert not any(m["sourceRepository"] in classification["excludedSources"] for m in classification["modules"])
    expected = expected_demo_modules(classification["modules"])
    assert {e[0] for e in entries} == expected, "Demo catalog does not match retained public modules"
    assert len(entries) == len(expected), "Duplicate library entries"
    assert len({e[1] for e in entries}) == len(entries), "Duplicate route paths"
    sources = list((ROOT / "feature").glob("feature_demo_*/src/main/java/**/*.kt"))
    source_texts = {p: p.read_text(encoding="utf-8") for p in sources}
    groups = set()
    for module, route, domain in entries:
        groups.add(domain)
        assert route.startswith(f"/{domain}/"), "Route groups must belong to their business domain"
        matches = [(p, text) for p, text in source_texts.items() if f'@Route(path = "{route}")' in text]
        assert len(matches) == 1, f"Missing or duplicate page: {module}"
        path, source = matches[0]
        activity, binding, model = re.search(r'class (\w+Activity) : DemoActivity<(\w+Binding), (\w+ViewModel)>', source).groups()
        assert f"class {model} : DemoViewModel()" in source, f"Missing isolated ViewModel: {module}"
        main = ROOT / f"feature/feature_demo_{domain}/src/main"
        names = {item.get(ANDROID + "name") for item in ET.parse(main / "AndroidManifest.xml").iter("activity")}
        assert f"com.wkq.dev.demo.{domain}.{activity}" in names, f"Activity not registered: {module}"
        layout = re.sub(r"(?<!^)(?=[A-Z])", "_", binding.removesuffix("Binding")).lower()
        assert (main / f"res/layout/{layout}.xml").is_file(), f"Missing independent XML: {module}"
        generated = ROOT / f"feature/feature_demo_{domain}/build/generated/ksp/debug"
        assert any(route in p.read_text(encoding="utf-8") for p in generated.rglob("*.kt")), f"Route not generated: {module}"
    initializers = []
    if apk:
        with ZipFile(apk) as package:
            initializers = package.read("META-INF/services/com.wkq.router.api.IRouteInit").decode().splitlines()
            expected = {"com.wkq.router.generated.RouteInit_DevDemo" + group.title() for group in groups}
            assert set(initializers) == expected, "APK lost a generated route initializer"
            dex = b"".join(package.read(n) for n in package.namelist() if re.fullmatch(r"classes\d*\.dex", n))
            if "core_google" in active:
                for required in (b"Lcom/wkq/google/auth/GoogleSignInCoordinator;",
                                 b"Lcom/wkq/google/auth/DropGoogleAccount;",
                                 b"Lcom/wkq/google/billing/GoogleBillingManager;"):
                    assert required in dex, f"Unified Google SDK missing: {required!r}"
            for excluded_package in (
                b"Lcom/starxen/", b"Lcom/beautysdk/", b"Lio/agora/",
                b"Lcom/phototoolkit/engine/", b"Lcom/phototoolkit/raw/",
                b"Lcom/wkq/iptc/camera/camera/", b"Lcom/wkq/nativepdf/",
                b"Lcom/wkq/droplan/html/", b"Lcom/wkq/dev/entitlement/",
                b"Lcom/wkq/photo/qrtransfer/", b"Lcom/wkq/photo/mediastore/",
                b"Lcom/wkq/photo/idlibrary/", b"Lcom/wkq/crash/",
                b"Lcom/wkq/google/ads/", b"Lcom/wkq/google/analytics/",
                b"Lcom/google/firebase/analytics/",
            ):
                assert excluded_package not in dex, f"Excluded source remains in APK: {excluded_package!r}"
            assert not any(name.endswith((".tflite", ".onnx", ".task")) for name in package.namelist()), "Retired model assets remain in APK"
            for _, route, _ in entries:
                assert route.encode() in dex, f"Route missing in APK DEX: {route}"
    return {"activeModules": len(active), "independentPages": len(entries), "supportModules": sorted(active - expected_demo_modules(classification["modules"])), "routeGroups": sorted(groups), "apkInitializers": initializers, "deviceTesting": "not run"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.apk), ensure_ascii=False, indent=2))
