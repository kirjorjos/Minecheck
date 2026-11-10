#!/usr/bin/env python3
"""Boots each packaged loader jar in a production instance before a release is published.

`checkProductionJars` only proves a jar carries production member names, and the lifecycle suites
boot the *development* classpath. Neither proves that the jar actually attached to a release loads
in an installed instance, which is the one thing a user does with it. This does: for every loader
the line ships, it installs a real dedicated server with the same loader version the branch builds
against, puts the packaged jar (never its `-deobf` twin, which is development names by definition)
in `mods/`, boots it, waits for the server to finish starting, and requires Minecheck to report
itself active. Anything else fails the release.

Every version this needs - the loader, the Fabric API and QFAPI the jar's metadata depends on - is
read from the branch's own build files, so one script serves every Minecraft line.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

FABRIC_INSTALLER = "https://meta.fabricmc.net/v2/versions/installer"
FABRIC_LAUNCHER = (
    "https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/{installer}/server/jar"
)
QUILT_INSTALLER = "https://quiltmc.org/api/v1/download-latest-installer/java-universal"
FORGE_INSTALLER = (
    "https://maven.minecraftforge.net/net/minecraftforge/forge/{mc}-{version}/"
    "forge-{mc}-{version}-installer.jar"
)
# The Maven artifacts of both APIs are metadata stubs: the jar that actually loads is the bundled
# distribution, and Modrinth is where their authors publish it. The pinned version still comes from
# the branch's build file, so this checks the API version the jar was compiled against.
MODRINTH_VERSIONS = (
    "https://api.modrinth.com/v2/project/{slug}/version?game_versions=%5B%22{mc}%22%5D"
)
FABRIC_API_PROJECT = "fabric-api"
QFAPI_PROJECT = "qsl"
USER_AGENT = "Minecheck-release-check (+https://github.com/kirjorjos/Minecheck)"

# The mod stages no world of its own and this run has no players: a small view distance and no
# command blocks keep the boot to reading the jar rather than generating terrain.
SERVER_PROPERTIES = {
    "online-mode": "false",
    "enable-command-block": "false",
    "spawn-protection": "0",
    "view-distance": "4",
    "simulation-distance": "4",
    "level-seed": "0",
    "sync-chunk-writes": "false",
}

STARTED = "Done ("
ACTIVE = "Minecheck server active"
CRASHED = ("Failed to initialize Minecheck", "Minecraft has crashed", "Mod resolution failed")


def fail(message: str) -> "NoReturn":
    print(f"::error::{message}", flush=True)
    sys.exit(1)


def info(message: str) -> None:
    print(message, flush=True)


def read_build_file(loader: str) -> str:
    path = ROOT / "src" / loader / "mod" / "build.gradle.kts"
    if not path.is_file():
        fail(f"{path} is missing; cannot read the {loader} versions this jar was built against")
    return path.read_text(encoding="utf-8")


def match(pattern: str, text: str, what: str, source: str) -> str:
    found = re.search(pattern, text)
    if not found:
        fail(f"{source} names no {what} (pattern {pattern!r})")
    return found.group(1)


def fetch_json(url: str) -> object:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.loads(response.read().decode("utf-8"))


def download_modrinth(slug: str, minecraft: str, version: str, dest: Path) -> None:
    """Fetches the distribution jar of [version] from Modrinth, failing when it is not there."""
    url = MODRINTH_VERSIONS.format(slug=slug, mc=minecraft)
    info(f"  looking up {slug} {version} for {minecraft}: {url}")
    for entry in fetch_json(url):
        if entry.get("version_number") != version:
            continue
        files = [f for f in entry.get("files", []) if f.get("primary")] or entry.get("files", [])
        if not files:
            break
        download(files[0]["url"], dest)
        return
    fail(
        f"Modrinth has no {slug} {version} for {minecraft}; the jar is built against an API a user "
        "cannot install, so the boot could not be set up"
    )


def download(url: str, dest: Path, attempts: int = 3) -> None:
    """Fetches [url] to [dest], retrying what a runner's network fails at: a dropped connection."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.is_file() and dest.stat().st_size > 0:
        return
    last = ""
    for attempt in range(1, attempts + 1):
        try:
            info(f"  fetching {url}")
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=180) as response:
                with dest.open("wb") as handle:
                    shutil.copyfileobj(response, handle)
            return
        except (urllib.error.URLError, OSError, TimeoutError) as error:
            last = str(error)
            info(f"  attempt {attempt}/{attempts} failed: {error}")
            time.sleep(5 * attempt)
    fail(f"could not download {url}: {last}")


def run(command: list[str], cwd: Path, timeout: int, env: dict[str, str] | None = None) -> None:
    """Runs an installer, keeping its output for the failure message."""
    info(f"  $ {' '.join(command)}")
    try:
        finished = subprocess.run(
            command,
            cwd=cwd,
            capture_output=True,
            text=True,
            timeout=timeout,
            env=env,
            stdin=subprocess.DEVNULL,
        )
    except subprocess.TimeoutExpired:
        fail(f"{command[0]} timed out after {timeout}s in {cwd}")
    if finished.returncode != 0:
        tail = "\n".join((finished.stdout + finished.stderr).splitlines()[-30:])
        fail(f"{' '.join(command)} exited {finished.returncode} in {cwd}:\n{tail}")


def write_server_files(workdir: Path, minecraft: str) -> None:
    workdir.mkdir(parents=True, exist_ok=True)
    (workdir / "eula.txt").write_text(
        f"# Accepted by tests/boot-production-jars.py for the {minecraft} release check.\n"
        "eula=true\n",
        encoding="utf-8",
    )
    properties = dict(SERVER_PROPERTIES)
    if minecraft >= "1.19":
        # 1.19 refuses to start a server without a profile key policy decision when this is left at
        # its default; the check signs nobody in, so the policy is irrelevant here.
        properties["enforce-secure-profile"] = "false"
    body = "\n".join(f"{key}={value}" for key, value in properties.items())
    (workdir / "server.properties").write_text(body + "\n", encoding="utf-8")


def fabric_installer_version() -> str:
    """The stable installer the meta API offers; a server launcher is published per installer."""
    with urllib.request.urlopen(FABRIC_INSTALLER, timeout=60) as response:
        versions = json.loads(response.read().decode("utf-8"))
    for entry in versions:
        if entry.get("stable"):
            return entry["version"]
    fail("the Fabric meta API lists no stable installer version")


def install_fabric(workdir: Path, minecraft: str, loader: str, java: str) -> list[str]:
    installer = fabric_installer_version()
    info(f"  installer {installer}")
    download(
        FABRIC_LAUNCHER.format(mc=minecraft, loader=loader, installer=installer),
        workdir / "fabric-server.jar",
    )
    return [java, "-Xmx1536M", "-jar", "fabric-server.jar", "nogui"]


def install_quilt(
    workdir: Path, minecraft: str, loader: str, java: str, qfapi: str
) -> list[str]:
    installer = workdir / "quilt-installer.jar"
    download(QUILT_INSTALLER, installer)
    # The loader version is positional (`install server <mc> [<loader>]`), and options must be
    # `--flag=value`: the installer treats anything else as a usage error and exits 0 having
    # installed nothing, which is the trap this check fell into before.
    run(
        [
            java,
            "-jar",
            installer.name,
            "install",
            "server",
            minecraft,
            loader,
            f"--install-dir={workdir}",
            "--download-server",
            "--create-scripts",
        ],
        cwd=workdir,
        timeout=900,
    )

    launcher = workdir / "quilt-server-launch.jar"
    if not launcher.is_file():
        fail(f"the Quilt installer produced no {launcher.name} in {workdir}")
    # The same workaround the Quilt build applies to its dev runs: QFAPI's rendering-fluids module
    # carries a `quilt_loader.recommends` key that this loader rejects as invalid metadata.
    return [
        java,
        "-Xmx1536M",
        "-Dloader.workaround.disable_strict_parsing=true",
        "-jar",
        launcher.name,
        "nogui",
    ]


def install_forge(workdir: Path, minecraft: str, version: str, java: str) -> list[str]:
    installer = workdir / f"forge-{minecraft}-{version}-installer.jar"
    download(FORGE_INSTALLER.format(mc=minecraft, version=version), installer)
    run([java, "-jar", installer.name, "--installServer"], cwd=workdir, timeout=1200)
    libraries = workdir / "libraries" / "net" / "minecraftforge" / "forge" / f"{minecraft}-{version}"
    if not libraries.is_dir():
        fail(f"the Forge installer produced no {libraries} in {workdir}")
    (workdir / "user_jvm_args.txt").write_text("-Xmx1536M\n", encoding="utf-8")
    return ["bash", "run.sh", "nogui"]


def boot(workdir: Path, command: list[str], log_path: Path, timeout: int) -> str:
    """Starts the server, waits for it to finish loading, then stops it. Returns the log."""
    info(f"  $ {' '.join(command)}")
    with log_path.open("w", encoding="utf-8", errors="replace") as log:
        process = subprocess.Popen(
            command,
            cwd=workdir,
            stdin=subprocess.PIPE,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
        )
        deadline = time.time() + timeout
        started = False
        while time.time() < deadline:
            if process.poll() is not None:
                break
            if STARTED in log_path.read_text(encoding="utf-8", errors="replace"):
                started = True
                break
            time.sleep(3)
        if started:
            try:
                process.communicate("stop\n", timeout=180)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=60)
        else:
            process.kill()
            process.wait(timeout=60)
    text = log_path.read_text(encoding="utf-8", errors="replace")
    if not started:
        tail = "\n".join(text.splitlines()[-40:])
        fail(f"the server in {workdir} never finished starting ({timeout}s):\n{tail}")
    return text


def check_log(text: str, loader: str, jar: Path, log_path: Path) -> None:
    for marker in CRASHED:
        if marker in text:
            tail = "\n".join(text.splitlines()[-40:])
            fail(f"the {loader} server reported '{marker}' with {jar.name}:\n{tail}")
    if ACTIVE not in text:
        tail = "\n".join(text.splitlines()[-40:])
        fail(
            f"the {loader} server started but Minecheck never reported itself active with "
            f"{jar.name}; it was not loaded. Log: {log_path}\n{tail}"
        )


def gradle_property(name: str) -> str:
    """Reads `name=value` from the root gradle.properties (the line's Minecraft version)."""
    path = ROOT / "gradle.properties"
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith(f"{name}="):
            return stripped.split("=", 1)[1].strip()
    fail(f"{path} names no {name}")


def declared_version() -> str:
    """The version the root build file declares, which every subproject copies."""
    path = ROOT / "build.gradle.kts"
    found = re.search(r'^version = "([^"]+)"', path.read_text(encoding="utf-8"), re.M)
    if not found:
        fail(f"{path} declares no version")
    return found.group(1)


def jar_for(artifacts: Path, prefix: str, loader: str, minecraft: str, version: str) -> Path:
    jar = artifacts / f"{prefix}{loader}-{minecraft}-{version}.jar"
    if not jar.is_file():
        fail(f"{jar} is missing; the {loader} release asset was never staged")
    return jar


def boot_loader(
    loader: str,
    minecraft: str,
    version: str,
    jar: Path,
    workdir: Path,
    java: str,
    timeout: int,
) -> None:
    info(f"== {loader} {minecraft}: {jar.name}")
    workdir.mkdir(parents=True, exist_ok=True)
    mods = workdir / "mods"
    mods.mkdir(exist_ok=True)
    for stale in mods.glob("*.jar"):
        stale.unlink()
    shutil.copy2(jar, mods / jar.name)
    write_server_files(workdir, minecraft)

    build_file = read_build_file(loader)
    if loader == "fabric":
        loader_version = match(
            r'fabric\s*\{\s*loader\("([^"]+)"\)', build_file, "Fabric loader version", "the Fabric build"
        )
        # The jar's metadata makes Fabric API mandatory, and a production instance has to be given
        # it: the development run gets it from the module classpath instead.
        api = match(
            r"fabric-api:fabric-api:([^\"\s]+)",
            build_file,
            "Fabric API version",
            "the Fabric build",
        )
        download_modrinth(FABRIC_API_PROJECT, minecraft, api, mods / f"fabric-api-{api}.jar")
        info(f"  loader {loader_version}, fabric-api {api}")
        command = install_fabric(workdir, minecraft, loader_version, java)
    elif loader == "quilt":
        loader_version = match(
            r'quilt\s*\{\s*loader\("([^"]+)"\)', build_file, "Quilt loader version", "the Quilt build"
        )
        api = match(
            r"quilted-fabric-api:quilted-fabric-api:([^\"\s]+)",
            build_file,
            "QFAPI version",
            "the Quilt build",
        )
        download_modrinth(QFAPI_PROJECT, minecraft, api, mods / f"quilted-fabric-api-{api}.jar")
        info(f"  loader {loader_version}, quilted-fabric-api {api}")
        command = install_quilt(workdir, minecraft, loader_version, java, api)
    elif loader == "forge":
        loader_version = match(
            r'minecraftForge\s*\{\s*loader\("([^"]+)"\)',
            build_file,
            "Forge loader version",
            "the Forge build",
        )
        info(f"  loader {loader_version}")
        command = install_forge(workdir, minecraft, loader_version, java)
    else:
        fail(f"no production boot is defined for loader '{loader}'")

    log_path = workdir / "boot.log"
    text = boot(workdir, command, log_path, timeout)
    check_log(text, loader, jar, log_path)
    info(f"  {loader} loaded {jar.name} and Minecheck is active ({log_path})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--mc", help="the Minecraft version (default: minecraft_version from gradle.properties)"
    )
    parser.add_argument(
        "--version", help="the mod version (default: the root build file's version)"
    )
    parser.add_argument(
        "--artifacts",
        type=Path,
        help="staged jars (default: release.artifactsDir from .github/metadata.json)",
    )
    parser.add_argument(
        "--workdir",
        type=Path,
        help="where the servers are installed (default: build/production-boot/<mc>-<version>)",
    )
    parser.add_argument("--only", action="append", help="boot one loader; repeatable")
    parser.add_argument("--java", default="java", help="the java executable to boot with")
    parser.add_argument("--timeout", type=int, default=600, help="seconds a server may take to start")
    args = parser.parse_args()

    metadata_path = ROOT / ".github" / "metadata.json"
    if not metadata_path.is_file():
        fail(f"{metadata_path} is missing; it names the loaders and assets a release ships")
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    release = metadata.get("release") or {}
    prefix = release.get("prefix")
    if not prefix:
        fail(f"{metadata_path} names no release.prefix")
    loaders = metadata.get("loaders") or []
    if not loaders:
        fail(f"{metadata_path} names no loaders")
    if args.only:
        unknown = sorted(set(args.only) - set(loaders))
        if unknown:
            fail(f"{', '.join(unknown)} is not a loader this line ships ({', '.join(loaders)})")
        loaders = [loader for loader in loaders if loader in args.only]

    minecraft = args.mc or gradle_property("minecraft_version")
    version = args.version or declared_version()
    artifacts = args.artifacts or (ROOT / release.get("artifactsDir", "build/artifacts"))
    workdir = args.workdir or (ROOT / "build" / "production-boot" / f"{minecraft}-{version}")

    info(f"booting {minecraft} v{version} from {artifacts}")
    for loader in loaders:
        boot_loader(
            loader,
            minecraft,
            version,
            jar_for(artifacts, prefix, loader, minecraft, version),
            workdir / loader,
            args.java,
            args.timeout,
        )
    info(f"every packaged jar for {minecraft} v{version} boots: {', '.join(loaders)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
