"""归档与精确匹配Android原生符号；仅离线读写用户指定文件。"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path


def digest(path):
    """流式计算符号或APK摘要。"""
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def tool(ndk, name):
    """从指定NDK中定位LLVM工具，避免依赖环境中的不同版本。"""
    matches = list((ndk / "toolchains" / "llvm" / "prebuilt").glob("*/bin/" + name + ".exe"))
    matches += list((ndk / "toolchains" / "llvm" / "prebuilt").glob("*/bin/" + name))
    if not matches:
        raise ValueError("NDK tool unavailable: " + name)
    return matches[0]


def inspect_elf(readelf, path):
    """读取Build ID和调试段；不执行目标共享库。"""
    process = subprocess.run([str(readelf), "--notes", "--sections", str(path)], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
    if process.returncode:
        return "", False
    match = re.search(r"Build ID:\s*([0-9a-fA-F]+)", process.stdout)
    return (match.group(1).lower() if match else ""), ".debug_info" in process.stdout


def archive(args):
    """只归档与APK实际模块Build ID相符的符号，逐项记录缺失。"""
    readelf = tool(args.ndk, "llvm-readelf")
    args.output.mkdir(parents=True, exist_ok=True)
    candidates = {}
    for root in args.search_root:
        for path in root.rglob("*.so"):
            if "obj" in path.parts and ("cxx" in path.parts or ".cxx" in path.parts):
                candidates.setdefault(path.name, []).append(path)
    records = []
    with zipfile.ZipFile(args.apk) as apk, tempfile.TemporaryDirectory(prefix="native-symbols-") as temporary:
        for name in sorted(apk.namelist()):
            parts = name.split("/")
            if len(parts) != 3 or parts[0] != "lib" or not parts[2].endswith(".so"):
                continue
            abi, module = parts[1:]
            packed = Path(temporary) / (abi + "-" + module)
            packed.write_bytes(apk.read(name))
            build, _ = inspect_elf(readelf, packed)
            matches = []
            for candidate in candidates.get(module, []):
                if abi not in candidate.parts:
                    continue
                identity, debug = inspect_elf(readelf, candidate)
                if identity and identity == build and matches_abi(readelf, candidate, abi):
                    matches.append((debug, candidate))
            row = {"abi": abi, "module": module, "buildId": build, "status": "symbols_missing"}
            if matches:
                debug, source = max(matches, key=lambda pair: (pair[0], pair[1].stat().st_size))
                target = args.output / abi / (build + "-" + module)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
                row.update(status="archived", debugLines=debug, file=target.relative_to(args.output).as_posix(), sha256=digest(target))
            records.append(row)
    manifest = {"formatVersion": 1, "apkSha256": digest(args.apk), "buildId": args.build_id, "modules": records}
    (args.output / "symbols.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps({"archived": sum(row["status"] == "archived" for row in records), "missing": sum(row["status"] != "archived" for row in records)}))




def read_trace(path):
    """读取有界的用户附件，限制JSON及栈帧数量；不提取ZIP内文件。"""
    with zipfile.ZipFile(path) as report:
        entry = report.getinfo("native-trace.json")
        if entry.file_size > 2 * 1024 * 1024:
            raise ValueError("Native trace too large")
        trace = json.loads(report.read(entry))
    if len(trace["frames"]) > 128:
        raise ValueError("Too many native frames")
    return trace


def matches_abi(readelf, path, abi):
    """用ELF机器字段确认架构，不能仅信任目录或文件名。"""
    result = subprocess.run([str(readelf), "--file-header", str(path)], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
    machine = {"arm64-v8a": "AArch64", "armeabi-v7a": "ARM", "x86": "Intel 80386", "x86_64": "Advanced Micro Devices X86-64", "riscv64": "RISC-V"}.get(abi)
    return result.returncode == 0 and machine is not None and re.search(r"Machine:\s*" + re.escape(machine) + r"\s*$", result.stdout, re.M) is not None


def import_symbols(args):
    """导入开发者已有的OEM或AOSP符号，按用户报告的模块、Build ID和ELF架构匹配。"""
    trace = read_trace(args.report)
    abi = {0: "armeabi-v7a", 1: "arm64-v8a", 2: "x86", 3: "x86_64", 4: "riscv64"}.get(trace["architecture"], "unknown")
    wanted = {(f["module"], f["buildId"]) for f in trace["frames"] if re.fullmatch(r"[A-Za-z0-9_.+-]+\.so", f["module"]) and re.fullmatch(r"[a-fA-F0-9]{4,128}", f["buildId"])}
    readelf = tool(args.ndk, "llvm-readelf")
    candidates = {}
    for root in args.search_root:
        for candidate in root.rglob("*.so"):
            if candidate.is_file():
                candidates.setdefault(re.sub(r"^[a-fA-F0-9]{4,128}-(?=lib)", "", candidate.name), []).append(candidate)
    args.output.mkdir(parents=True, exist_ok=True)
    records = []
    for module, expected in sorted(wanted):
        matches = []
        for source in candidates.get(module, []):
            build, debug = inspect_elf(readelf, source)
            if build == expected.lower() and matches_abi(readelf, source, abi):
                matches.append((debug, source))
        row = {"abi": abi, "module": module, "buildId": expected, "status": "symbols_missing"}
        if matches:
            debug, source = max(matches, key=lambda pair: (pair[0], pair[1].stat().st_size))
            target = args.output / abi / (expected + "-" + module)
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.resolve() != target.resolve():
                shutil.copy2(source, target)
            row.update(status="archived", debugLines=debug, evidence="provided_symbols", file=target.relative_to(args.output).as_posix(), sha256=digest(target))
        records.append(row)
    (args.output / "symbols.json").write_text(json.dumps({"formatVersion": 1, "buildId": "provided-platform-symbols", "modules": records}, indent=2), encoding="utf-8")
    print(json.dumps({"matched": sum(r["status"] == "archived" for r in records), "missing": sum(r["status"] != "archived" for r in records)}))


def collect_system(args):
    """从明确指定的开发设备只读提取系统库，按报告Build ID核验后归档；不访问应用私有文件。"""
    trace = read_trace(args.report)
    abi = {0: "armeabi-v7a", 1: "arm64-v8a", 2: "x86", 3: "x86_64", 4: "riscv64"}.get(trace["architecture"], "unknown")
    readelf = tool(args.ndk, "llvm-readelf")
    adb = [args.adb, "-s", args.serial]
    args.output.mkdir(parents=True, exist_ok=True)
    wanted = {(f["module"], f["buildId"]) for f in trace["frames"]
              if re.fullmatch(r"[A-Za-z0-9_.+-]+\.so", f["module"]) and re.fullmatch(r"[a-fA-F0-9]{4,128}", f["buildId"])}
    records = []
    roots = ("/apex/", "/system/", "/system_ext/", "/vendor/", "/product/")
    with tempfile.TemporaryDirectory(prefix="system-symbols-") as temporary:
        for module, expected in sorted(wanted)[:32]:
            row = {"abi": abi, "module": module, "buildId": expected, "status": "symbols_missing"}
            result = subprocess.run(adb + ["shell", "find", *[r.rstrip("/") for r in roots], "-type", "f", "-name", module],
                                    capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
            lib_dir = "lib64" if abi in {"arm64-v8a", "x86_64", "riscv64"} else "lib"
            # 部分系统不允许遍历APEX根目录，但允许读取已知公开库路径。
            known = ["/system/" + lib_dir + "/" + module, "/system_ext/" + lib_dir + "/" + module,
                     "/vendor/" + lib_dir + "/" + module, "/product/" + lib_dir + "/" + module,
                     "/apex/com.android.runtime/" + lib_dir + "/bionic/" + module,
                     "/apex/com.android.art/" + lib_dir + "/" + module]
            paths = list(dict.fromkeys(known + sorted(set(p.strip() for p in result.stdout.splitlines()))))
            for remote in paths[:16]:
                if not remote.startswith(roots) or not re.fullmatch(r"/[A-Za-z0-9_./@+-]+", remote) or ".." in remote.split("/"):
                    continue
                size = subprocess.run(adb + ["shell", "stat", "-c", "%s", remote], capture_output=True, text=True, timeout=15)
                if not size.stdout.strip().isdigit() or not 0 < int(size.stdout.strip()) <= 64 * 1024 * 1024:
                    continue
                local = Path(temporary) / module
                pulled = subprocess.run(adb + ["pull", remote, str(local)], capture_output=True, timeout=60)
                if pulled.returncode:
                    continue
                build, debug = inspect_elf(readelf, local)
                if build != expected.lower():
                    continue
                # 同一Build ID仍核对ELF机器架构，防止跨ABI路径误用。
                if not matches_abi(readelf, local, abi):
                    continue
                target = args.output / abi / (build + "-" + module)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(local, target)
                row.update(status="archived", debugLines=debug, evidence="device_binary", file=target.relative_to(args.output).as_posix(), sha256=digest(target))
                break
            records.append(row)
    manifest = {"formatVersion": 1, "buildId": "exact-device-binaries", "modules": records}
    (args.output / "symbols.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps({"matchedBinaries": sum(r["status"] == "archived" for r in records), "missing": sum(r["status"] != "archived" for r in records), "sourceLinesGuaranteed": False}))


def symbolize(args):
    """严格匹配ABI、模块、ELF Build ID和文件摘要，错版符号保持未解析。"""
    addr2line = tool(args.ndk, "llvm-addr2line")
    manifests = []
    for path in [args.symbols] + (args.system_symbols or []):
        resolved_path = path.resolve()
        manifests.append((resolved_path, json.loads(resolved_path.read_text(encoding="utf-8"))))
    trace = read_trace(args.report)
    abi = {0: "armeabi-v7a", 1: "arm64-v8a", 2: "x86", 3: "x86_64", 4: "riscv64"}.get(trace["architecture"], "unknown")
    lines = ["Offline native symbols / 原生离线定位", "Symbol archives = " + ", ".join(m.get("buildId", "unknown") for _, m in manifests)]
    resolved = 0
    functions = 0
    readelf = tool(args.ndk, "llvm-readelf")
    for index, frame in enumerate(trace["frames"]):
        address = frame["relativePc"]
        if not re.fullmatch(r"[0-9a-fA-F]{1,16}", address):
            raise ValueError("Invalid relative PC")
        module = frame["module"]
        if not re.fullmatch(r"[A-Za-z0-9_.+-]{1,160}", module):
            raise ValueError("Invalid module")
        lines.append("#" + str(index) + " " + module + " pc=" + address + " BuildId=" + frame["buildId"])
        # tombstone函数名是系统回溯提供的证据，保留但不冒充源码符号化结果。
        reported = frame.get("function", "").replace("\r", " ").replace("\n", " ")[:2000]
        if reported:
            offset = frame.get("functionOffset", "")
            if offset and not re.fullmatch(r"[0-9a-fA-F]{1,16}", offset):
                raise ValueError("Invalid function offset")
            lines.append("REPORTED_FUNCTION / 系统已报告函数 = " + reported + ("+0x" + offset if offset else ""))
        matching = [(path, row) for path, manifest in manifests for row in manifest["modules"]
                    if row.get("status") == "archived" and row["abi"] == abi and row["module"] == module and row["buildId"] == frame["buildId"]]
        selected = None
        for manifest_path, row in sorted(matching, key=lambda pair: pair[1].get("debugLines", False), reverse=True):
            source = (manifest_path.parent / row["file"]).resolve()
            if (source.is_relative_to(manifest_path.parent) and source.is_file() and digest(source) == row["sha256"]
                    and inspect_elf(readelf, source)[0] == frame["buildId"]):
                selected = source
                break
        if selected is None:
            lines.append("UNRESOLVED / 缺少完整性和Build ID均匹配的符号或库文件；请提供匹配的OEM/AOSP符号或运行collect-system")
            continue
        result = subprocess.run([str(addr2line), "-C", "-f", "-i", "-e", str(selected), "0x" + address], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30, check=True)
        output = result.stdout.splitlines()
        has_source = any(re.search(r":([1-9][0-9]*)(?:\s|$)", line) for line in output[1::2])
        has_function = any(line.strip() and line.strip() != "??" for line in output[::2])
        if has_source:
            resolved += 1
            functions += 1
            lines.append("SOURCE_LINE / 已匹配源码行\n" + result.stdout.strip())
        elif has_function:
            functions += 1
            lines.append("FUNCTION_ONLY / 精确库版本函数，无源码行\n" + result.stdout.strip())
        else:
            lines.append("BINARY_MATCHED / 库版本已核对，地址暂无调试符号；保留模块PC和系统已报告函数")
    lines.append("Source lines = " + str(resolved) + "; verified functions = " + str(functions) + "; frames = " + str(len(trace["frames"])))
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"frames": len(trace["frames"]), "resolved": resolved, "verifiedFunctions": functions, "output": str(args.output)}))


def main():
    """命令行入口，归档和解析均无需网络或上传。"""
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    create = sub.add_parser("archive")
    create.add_argument("--apk", type=Path, required=True)
    create.add_argument("--ndk", type=Path, required=True)
    create.add_argument("--search-root", type=Path, action="append", required=True)
    create.add_argument("--build-id", required=True)
    create.add_argument("--output", type=Path, required=True)
    create.set_defaults(run=archive)
    resolve = sub.add_parser("symbolize")
    resolve.add_argument("--report", type=Path, required=True)
    resolve.add_argument("--symbols", type=Path, required=True)
    resolve.add_argument("--system-symbols", type=Path, action="append", help="额外OEM/AOSP符号或collect-system的symbols.json")
    resolve.add_argument("--ndk", type=Path, required=True)
    resolve.add_argument("--output", type=Path, required=True)
    resolve.set_defaults(run=symbolize)
    system = sub.add_parser("collect-system", help="只读获取指定开发设备的匹配系统库，不保证源码行号")
    system.add_argument("--report", type=Path, required=True)
    system.add_argument("--serial", required=True)
    system.add_argument("--adb", default="adb")
    system.add_argument("--ndk", type=Path, required=True)
    system.add_argument("--output", type=Path, required=True)
    system.set_defaults(run=collect_system)
    provided = sub.add_parser("import-symbols", help="导入已有OEM/AOSP符号，严格匹配模块、ELF Build ID及ABI")
    provided.add_argument("--report", type=Path, required=True)
    provided.add_argument("--ndk", type=Path, required=True)
    provided.add_argument("--search-root", type=Path, action="append", required=True)
    provided.add_argument("--output", type=Path, required=True)
    provided.set_defaults(run=import_symbols)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
