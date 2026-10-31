#!/usr/bin/env python3
"""构建 / 校验 cardesk 的系统导航预留区覆盖包（RRO）。

第八十七轮（参考 uni-launcher `tools/build_nav_insets.py` + `tools/sign_car_apk.py`）：

    python3 tools/build_insets.py            # 重新构建并签名 → app/src/main/assets/cardesk-nav-insets.apk
    python3 tools/build_insets.py --check    # 只校验已有资产（CI / 提交前用）

为什么要写这个脚本（而不是让 gradle 编一个 module）：
  覆盖包是**给 framework-res（targetPackage="android"）用的 RRO**，只有资源、
  没有代码，也不应该被 gradle 当成普通依赖编进主 APK；它是在运行时被
  `PackageInstaller` 静默装进系统、再由 `IOverlayManager` 启用的独立 APK。
  所以用 aapt2 直接 compile/link 出裸 APK，再用平台密钥签名后作为 asset 入库。

为什么必须用平台签名：
  AOSP 的 `OverlayManagerService` 只信任「平台签名」的框架覆盖包（targetPackage=android），
  普通签名装进去也启用不了。密钥是 `cardesk/platform-key/platform.pk8` +
  `platform.x509.pem`（与主工程 `assembleSystem` 同一套 AOSP platform 测试密钥，
  **不入库**，见 `.gitignore`）。证书 SHA-256 在脚本里**固定校验**，
  防止有人误换密钥后打出「装不上/启用不了」的资产却毫无提示。

签名方案：v1 + v2 开、v3 + v4 关（与参考实现一致）。
  - v1（JAR 签名）保证系统 PackageManager 在 Android 9 上能校验；
  - v2（APK Signature Scheme v2）是 Android 7+ 的整包签名；
  - v3/v4 是 Android 9/11 的新方案，车机 ROM 对覆盖包的校验路径不需要，
    关掉可以让产物与系统内置 RRO 的形态一致、减少 ROM 差异带来的不确定性。

路径约定：
  工具链默认 `/Users/roy/Documents/desktopapp/toolchain`，可用 `CARDESK_TOOLCHAIN`
  环境变量或 `--toolchain` 覆盖（与 `tools/release.sh` 一致）。
  会从中找 jdk、`sdk/build-tools/<ver>/aapt2`、`sdk/build-tools/<ver>/lib/apksigner.jar`、
  `sdk/platforms/android-9|android-28|android-34/android.jar`。
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# ---------------------------------------------------------------- 路径约定

CARDESK = Path(__file__).resolve().parents[1]
INSETS = CARDESK / "insets"
DEFAULT_OUTPUT = CARDESK / "app/src/main/assets/cardesk-nav-insets.apk"
PLATFORM_KEY = CARDESK / "platform-key"
DEFAULT_TOOLCHAIN = Path("/Users/roy/Documents/desktopapp/toolchain")
TOOLCHAIN_ENV = "CARDESK_TOOLCHAIN"

#: 与 cardesk(`tools/release.sh`) 一致的 build-tools；aapt2 与 apksigner.jar 都从这里取。
BUILD_TOOLS_VERSION = "34.0.0"

#: android.jar 候选（API 28 = android-9 与覆盖包 min/targetSdk 28 对齐；没有就退到 34）。
PLATFORM_CANDIDATES = ("android-9", "android-28", "android-34")

#: AOSP platform 测试密钥（platform.x509.pem）的 SHA-256。
#: 固定校验的原因：这张证书决定覆盖包能否被 OverlayManagerService 启用；
#: 密钥换掉后产物照样能构建能安装，只会在**真车启用时**静默失败 ——
#: 那种失败没有日志、很难查，所以在构建时就拦下。
EXPECTED_CERT_SHA256 = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"


def die(message: str) -> "NoReturn":  # noqa: F821 - 仅用于类型提示可读性
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    """执行外部命令并回显（构建脚本要能一眼看出到底跑了什么）。"""
    print("+ " + " ".join(str(part) for part in cmd))
    return subprocess.run([str(part) for part in cmd], check=True, **kwargs)


# ---------------------------------------------------------------- 工具链解析


def resolve_toolchain(cli_value: str | None) -> Path:
    raw = cli_value or os.environ.get(TOOLCHAIN_ENV) or str(DEFAULT_TOOLCHAIN)
    toolchain = Path(raw).expanduser().resolve()
    if not toolchain.is_dir():
        die(
            "toolchain 目录不存在: "
            f"{toolchain}（用 --toolchain 或 {TOOLCHAIN_ENV} 指定）"
        )
    return toolchain


def resolve_java(toolchain: Path, cli_value: str | None) -> Path:
    """优先用同一套工具链里的 JDK（与 release.sh 一致），再退回 PATH。"""
    if cli_value:
        java = Path(cli_value).expanduser().resolve()
        return java if java.is_file() else die(f"指定的 java 不存在: {java}")
    candidates: list[Path] = []
    home = os.environ.get("JAVA_HOME")
    if home:
        # macOS 的 JDK 在 Contents/Home 下，Linux 直接在根
        candidates += [Path(home) / "bin/java", Path(home) / "Contents/Home/bin/java"]
    # toolchain/jdk-17.0.20.1+1 这种版本目录（macOS / Linux 两种布局都试）
    for jdk in sorted(toolchain.glob("jdk-*")):
        candidates += [jdk / "bin/java", jdk / "Contents/Home/bin/java"]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    which = shutil.which("java")
    if which:
        return Path(which)
    die(
        "找不到 java：既没有 toolchain/jdk-*/bin/java，也不在 PATH；"
        "用 --java 或 JAVA_HOME 指定（apksigner 需要 JDK 17）"
    )
    raise AssertionError("unreachable")


def resolve_sdk_artifacts(toolchain: Path) -> tuple[Path, Path, Path]:
    """定位 aapt2 / apksigner.jar / android.jar。"""
    sdk = toolchain / "sdk"
    build_tools = sdk / "build-tools" / BUILD_TOOLS_VERSION
    aapt2 = build_tools / "aapt2"
    apksigner = build_tools / "lib/apksigner.jar"
    if not aapt2.is_file():
        die(f"找不到 aapt2: {aapt2}（需要 SDK build-tools {BUILD_TOOLS_VERSION}）")
    if not apksigner.is_file():
        die(f"找不到 apksigner.jar: {apksigner}（需要 SDK build-tools {BUILD_TOOLS_VERSION}）")

    android_jar = None
    for name in PLATFORM_CANDIDATES:
        candidate = sdk / "platforms" / name / "android.jar"
        if candidate.is_file():
            android_jar = candidate
            break
    if android_jar is None:
        die(
            "找不到 android.jar：试过 "
            + "、".join(str(sdk / "platforms" / name / "android.jar") for name in PLATFORM_CANDIDATES)
        )
    return aapt2, apksigner, android_jar


def signing_inputs() -> tuple[Path, Path]:
    key = PLATFORM_KEY / "platform.pk8"
    cert = PLATFORM_KEY / "platform.x509.pem"
    for path in (key, cert):
        if not path.is_file():
            die(f"缺少平台签名文件: {path}（密钥不入库，只在本机保存）")
    return key, cert


# ---------------------------------------------------------------- aapt2


def compile_resources(aapt2: Path, work: Path) -> Path:
    """`aapt2 compile --dir insets/res -o resources.zip`（编译所有 values/*.xml）。"""
    compiled = work / "resources.zip"
    run(
        [
            aapt2,
            "compile",
            "--dir",
            INSETS / "res",
            "-o",
            compiled,
        ]
    )
    if not compiled.is_file():
        die(f"aapt2 compile 没有产出资源包: {compiled}")
    return compiled


def link_apk(aapt2: Path, android_jar: Path, compiled: Path, work: Path) -> Path:
    """`aapt2 link` 出未签名 APK：manifest + 编译好的资源。

    **`--no-resource-deduping` 不能省**：这是实测踩出来的（与 UNI 参考实现一致）。
    不加它时，四套 qualifier 的 dimensions（default / sw600dp / sw720dp / sw900dp）
    会被 aapt2 去重成**只有 default**：`aapt2 dump resources` 里只剩 `()` 一条，
    framework-res 的 sw900dp 56dp 照旧胜出，覆盖包等于没生效，而且构建/签名
    全部成功、毫无报错。加上之后 arsc 里四套 config 都在，覆盖才真正生效。
    """
    unsigned = work / "cardesk-nav-insets-unsigned.apk"
    run(
        [
            aapt2,
            "link",
            "-I",
            android_jar,
            "--manifest",
            INSETS / "AndroidManifest.xml",
            "--auto-add-overlay",
            "--no-resource-deduping",
            "-o",
            unsigned,
            "-R",
            compiled,
        ]
    )
    if not unsigned.is_file():
        die(f"aapt2 link 没有产出 APK: {unsigned}")
    return unsigned


# ---------------------------------------------------------------- 签名 / 校验


def sign_apk(java: Path, apksigner_jar: Path, key: Path, cert: Path, unsigned: Path, out: Path) -> None:
    run(
        [
            java,
            "-cp",
            apksigner_jar,
            "com.android.apksigner.ApkSignerTool",
            "sign",
            "--key",
            key,
            "--cert",
            cert,
            # 与参考实现一致：v1/v2 开、v3/v4 关。
            "--v1-signing-enabled",
            "true",
            "--v2-signing-enabled",
            "true",
            "--v3-signing-enabled",
            "false",
            "--v4-signing-enabled",
            "false",
            "--out",
            out,
            unsigned,
        ]
    )
    if not out.is_file():
        die(f"apksigner 没有产出签名 APK: {out}")


def apksigner_verify(java: Path, apksigner_jar: Path, apk: Path, min_sdk: int | None = None) -> str:
    """`apksigner verify -v --print-certs` 的输出（含证书摘要与签名方案状态）。

    必须加 `-v`：这个版本的 apksigner 只有 verbose 模式才打印
    `Verified using vN scheme ...` 那几行，否则只输出证书摘要。

    `min_sdk` 用于覆盖校验范围。为什么不直接用 manifest 里的 minSdkVersion=28：
    apksigner 只校验「目标 API 范围真正会用到的方案」，API 24+ 有 v2 就够，
    于是 v1 永远显示 false —— 那样就验不出 v1 到底签没签对。
    传 21 时 API 21~23 只有 v1 可用，v1 必须为 true，才能证明它真的有效。
    """
    cmd = [
        str(java),
        "-cp",
        str(apksigner_jar),
        "com.android.apksigner.ApkSignerTool",
        "verify",
        "-v",
        "--print-certs",
    ]
    if min_sdk is not None:
        cmd += ["--min-sdk-version", str(min_sdk)]
    cmd.append(str(apk))
    completed = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if completed.returncode != 0:
        detail = (completed.stdout + completed.stderr).strip()
        die(f"apksigner verify 失败: {apk}\n{detail}")
    return completed.stdout


def parse_cert_sha256(verify_output: str) -> str:
    # 形如 "Signer #1 certificate SHA-256 digest: c8a2…"
    matches = re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F]+)", verify_output)
    if not matches:
        die("apksigner verify 输出里找不到证书 SHA-256 摘要")
    return matches[0].lower()


def parse_scheme_enabled(verify_output: str, scheme: str) -> bool | None:
    pattern = rf"Verified using v{scheme} scheme[^:]*:\s*(true|false)"
    match = re.search(pattern, verify_output)
    return None if match is None else match.group(1) == "true"


EXPECTED_DIMENS = (
    "navigation_bar_height",
    "navigation_bar_height_landscape",
    "navigation_bar_height_car_mode",
    "navigation_bar_height_landscape_car_mode",
)
#: framework-res 里这四个 dimens 会按 sw600/720/900dp 命中不同值（56dp / 96dp 等），
#: RRO 必须在**同样的四套 qualifier** 下都写 112dp 才盖得住，见 link_apk 注释。
EXPECTED_QUALIFIERS = ("", "sw600dp", "sw720dp", "sw900dp")


def verify_resources(aapt2: Path, apk: Path) -> tuple[list[str], str]:
    """用 `aapt2 dump resources` 断言四套 qualifier 都在、值都是 112dp。"""
    completed = subprocess.run(
        [str(aapt2), "dump", "resources", str(apk)],
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        return [f"aapt2 dump resources 失败: {(completed.stdout + completed.stderr).strip()}"], ""

    found: dict[str, dict[str, str]] = {}
    current: "str | None" = None
    for raw in completed.stdout.splitlines():
        line = raw.strip()
        match = re.match(r"resource 0x[0-9a-f]+ dimen/(\w+)$", line)
        if match:
            current = match.group(1)
            found.setdefault(current, {})
            continue
        if current is None:
            continue
        entry = re.match(r"\(([^)]*)\)\s*(.+)$", line)
        if entry:
            found[current][entry.group(1)] = entry.group(2).strip()

    problems: list[str] = []
    for name in EXPECTED_DIMENS:
        configs = found.get(name)
        if not configs:
            problems.append(f"资源里缺少 dimen/{name}")
            continue
        for qualifier in EXPECTED_QUALIFIERS:
            value = configs.get(qualifier)
            label = qualifier or "default"
            if value is None:
                problems.append(
                    f"dimen/{name} 缺少 {label} qualifier（aapt2 去重或资源没编译进去）"
                )
            elif not re.fullmatch(r"112(\.0+)?dp", value):
                problems.append(f"dimen/{name} 在 {label} 下是 {value}，应为 112dp")
    detail = "、".join(f"{name}×{len(found.get(name, {}))}" for name in EXPECTED_DIMENS)
    return problems, detail


def verify_apk(java: Path, apksigner_jar: Path, aapt2: Path, apk: Path) -> dict:
    """校验一项资产：签名方案、证书、zip 结构、资源 qualifier；成功时打印摘要。"""
    if not apk.is_file():
        die(f"覆盖包资产不存在: {apk}（先不带 --check 跑一次构建）")
    if apk.stat().st_size <= 0:
        die(f"覆盖包资产是空文件: {apk}")

    # 默认范围（API 28+）：证书 + v2/v3/v4 状态。
    verify_output = apksigner_verify(java, apksigner_jar, apk)
    cert_sha = parse_cert_sha256(verify_output)
    schemes = {scheme: parse_scheme_enabled(verify_output, scheme) for scheme in ("1", "2", "3", "4")}
    # 额外按 API 21 校验一次：此时只有 v1 可用，v1 必须真的验证通过（见函数注释）。
    v1_verify_output = apksigner_verify(java, apksigner_jar, apk, min_sdk=21)
    v1_schemes = {scheme: parse_scheme_enabled(v1_verify_output, scheme) for scheme in ("1", "2")}

    problems: list[str] = []
    if cert_sha != EXPECTED_CERT_SHA256:
        problems.append(
            f"证书 SHA-256 不是平台证书: 实际 {cert_sha}，期望 {EXPECTED_CERT_SHA256}"
        )
    if v1_schemes["1"] is not True:
        problems.append("v1（JAR）签名未开启或无效（按 API 21 校验应为 true）")
    if schemes["2"] is not True:
        problems.append("v2 签名未开启")
    if schemes["3"] is not False:
        problems.append("v3 签名应为关闭")
    if schemes["4"] is not False:
        problems.append("v4 签名应为关闭")

    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        # RRO 必需：清单 + 资源表；有代码才是错的。
        if "AndroidManifest.xml" not in names:
            problems.append("APK 内缺少 AndroidManifest.xml")
        if "resources.arsc" not in names:
            problems.append("APK 内缺少 resources.arsc")
        if any(name == "classes.dex" for name in names):
            problems.append("APK 内出现了 classes.dex（覆盖包应 hasCode=false）")
        # v1 签名块：META-INF/CERT.SF + META-INF/CERT.RSA（apksigner --key/--cert 的别名是 CERT）
        signature_blocks = [
            name
            for name in names
            if re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC)", name)
        ]
        if not signature_blocks:
            problems.append("APK 内缺少 META-INF 下的 v1 签名块（.RSA/.DSA/.EC）")
        if "META-INF/MANIFEST.MF" not in names:
            problems.append("APK 内缺少 META-INF/MANIFEST.MF")

    resource_problems, resource_detail = verify_resources(aapt2, apk)
    problems.extend(resource_problems)

    if problems:
        die("覆盖包校验失败:\n  - " + "\n  - ".join(problems))

    apk_sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    print(f"覆盖包: {apk}")
    print(f"  大小: {apk.stat().st_size} bytes")
    print(f"  APK SHA-256: {apk_sha}")
    print(f"  证书 SHA-256: {cert_sha}（平台证书，已校验）")
    print(
        "  签名方案: v1={v1}（按 API 21 校验） v2={v2} v3={v3} v4={v4}".format(
            v1=v1_schemes["1"], v2=schemes["2"], v3=schemes["3"], v4=schemes["4"]
        )
    )
    print(f"  v1 签名块: {', '.join(signature_blocks)}")
    print(f"  资源 qualifier: {resource_detail}（default/sw600dp/sw720dp/sw900dp 均 112dp）")
    return {"sha256": apk_sha, "certSha256": cert_sha, "schemes": schemes}


# ---------------------------------------------------------------- 入口


def build(out: Path, toolchain: Path, java_cli: str | None) -> None:
    aapt2, apksigner_jar, android_jar = resolve_sdk_artifacts(toolchain)
    java = resolve_java(toolchain, java_cli)
    key, cert = signing_inputs()
    print(f"aapt2: {aapt2}")
    print(f"android.jar: {android_jar}")
    print(f"apksigner: {apksigner_jar}")
    print(f"java: {java}")

    with tempfile.TemporaryDirectory(prefix="cardesk-insets-") as temp:
        work = Path(temp)
        compiled = compile_resources(aapt2, work)
        unsigned = link_apk(aapt2, android_jar, compiled, work)
        out.parent.mkdir(parents=True, exist_ok=True)
        # 先写到临时文件再原子替换，避免构建中断留下半截资产被提交。
        staged = work / out.name
        sign_apk(java, apksigner_jar, key, cert, unsigned, staged)
        verify_apk(java, apksigner_jar, aapt2, staged)
        shutil.copyfile(staged, out)

    # 产物落在 assets 后，用同一套逻辑再校验一次（防止拷贝出问题），并打印最终路径。
    print(f"已写入: {out}")
    verify_apk(java, apksigner_jar, aapt2, out)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="构建 / 校验 cardesk 系统导航预留区覆盖包（RRO）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例:\n"
            "  python3 tools/build_insets.py\n"
            "  python3 tools/build_insets.py --check\n"
            "  CARDESK_TOOLCHAIN=/path/to/toolchain python3 tools/build_insets.py\n"
        ),
    )
    parser.add_argument("--out", type=Path, default=DEFAULT_OUTPUT, help="输出 APK 路径")
    parser.add_argument(
        "--toolchain",
        default=None,
        help=f"Android 工具链根目录（默认 {DEFAULT_TOOLCHAIN}，可用 {TOOLCHAIN_ENV} 覆盖）",
    )
    parser.add_argument("--java", default=None, help="运行 apksigner 的 java（默认取工具链里的 JDK）")
    parser.add_argument(
        "--check",
        action="store_true",
        help="只校验已有资产（不重新构建）；文件缺失 / 签名不符时退出码非 0",
    )
    args = parser.parse_args(argv)

    out = args.out.expanduser().resolve()
    toolchain = resolve_toolchain(args.toolchain)
    if args.check:
        aapt2, apksigner_jar, _ = resolve_sdk_artifacts(toolchain)
        java = resolve_java(toolchain, args.java)
        verify_apk(java, apksigner_jar, aapt2, out)
        print("校验通过。")
        return 0

    build(out, toolchain, args.java)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
