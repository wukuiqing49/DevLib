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


def artifact_type(build_text: str) -> str:
    # 本仓库同时存在插件 alias 和直接 id 写法，不从模块名或源码语言猜测产物。
    text = re.sub(r"//[^\n]*|/\*.*?\*/", "", build_text, flags=re.S)
    if re.search(r"alias\(libs\.plugins\.android\.library\)|id\s*\(?\s*['\"]com\.android\.library['\"]", text):
        return "aar"
    if re.search(r"alias\(libs\.plugins\.kotlin\.jvm\)|id\s*\(?\s*['\"](?:org\.jetbrains\.kotlin\.jvm|java-library|java-gradle-plugin)['\"]", text):
        return "jvm"
    raise AssertionError("Unknown library plugin; explicitly extend artifact detection")


def verify_artifacts(root: Path = ROOT) -> dict:
    modules = json.loads((root / "docs/library-catalog.json").read_text(encoding="utf-8"))["modules"]
    active = {p.name for p in (root / "core").iterdir() if (p / "build.gradle").is_file()}
    assert {m["module"] for m in modules} == active, "Library artifact catalog is stale"
    counts = {"aar": 0, "jvm": 0}
    for module in modules:
        actual = artifact_type((root / "core" / module["module"] / "build.gradle").read_text(encoding="utf-8"))
        assert module["artifactType"] == actual, f"Artifact mismatch: {module['module']} must be {actual}"
        counts[actual] += 1
    return counts


def expected_demo_modules(modules: list[dict]) -> set[str]:
    active = {m["module"] for m in modules}
    assert len(active) == len(modules), "Duplicate classification modules"
    public = {m["module"] for m in modules if m.get("role") != "support"}
    for module in modules:
        if module.get("role") == "support":
            consumers = set(module.get("demoCoveredBy", []))
            assert consumers and consumers <= public, "Support module must name retained demo consumers"
    return public


def catalog_entries(text: str) -> list[tuple[str, str, str]]:
    return re.findall(r'DemoEntry\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"', text)


def isolated_view_model(text: str, name: str) -> bool:
    return bool(re.search(r'class\s+' + re.escape(name) + r'\s*(?:\([^)]*\))?\s*:\s*DemoViewModel\(\)', text))


def verify(apk: Path | None) -> dict:
    verify_artifacts()
    catalog = ROOT / "feature/feature_app/src/main/java/com/wkq/dev/feature/app/catalog/DemoCatalog.kt"
    entries = catalog_entries(catalog.read_text(encoding="utf-8"))
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
        activity, binding, model = re.search(r'class\s+(\w+Activity)\s*:\s*DemoActivity\s*<\s*(\w+Binding)\s*,\s*(\w+ViewModel)\s*>', source).groups()
        assert isolated_view_model(source, model), f"Missing isolated ViewModel: {module}"
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
                                 b"Lcom/wkq/google/billing/GoogleBillingManager;",
                                 b"Lcom/wkq/google/rate/GoogleRateManager;",
                                 b"Lcom/wkq/google/rate/GoogleRatePolicy;",
                                 b"Lcom/google/android/play/core/review/ReviewManagerFactory;"):
                    assert required in dex, f"Unified Google SDK missing: {required!r}"
            if "core_nas" in active:
                assert b"Lcom/wkq/naslib/NasClient;" in dex, "NAS public package missing"
            if "core_usb" in active:
                assert b"Lcom/wkq/usb/UsbStorage;" in dex, "USB public package missing"
            for excluded_package in (
                b"Lcom/wkq/nas/transfer/",
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
    parser.add_argument("--catalog-only", action="store_true", help="Check artifact types before any build")
    args = parser.parse_args()
    print(json.dumps(verify_artifacts() if args.catalog_only else verify(args.apk), ensure_ascii=False, indent=2))
