#!/usr/bin/env python3
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from bump_patch_version import bump_patch_version

DEFAULT_JAVA_HOME = Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")
DEFAULT_PRISM_MINECRAFT_DIR = Path.home() / "Library/Application Support/PrismLauncher/instances/dev/minecraft"
FUSION_VERSION = "1.2.12"
FUSION_FILENAME = "fusion-1.2.12-neoforge-mc1.21.1.jar"
FUSION_URL = "https://cdn.modrinth.com/data/p19vrgc2/versions/h2GrA0Ku/fusion-1.2.12-neoforge-mc1.21.1.jar"
FUSION_SHA512 = "50604fa4125e846b659479a8bb8bcef5db47460a8185902b8655d8b12c6cc67eb3cc4c08fee45e82a6b215976bea2a480e32ce420f062cea88abe17cb362365c"


@dataclass(frozen=True)
class DeployResult:
    version: str
    jar: Path
    destination: Path
    backups: list[Path]
    fusion_destination: Path
    fusion_backups: list[Path]


def run_gradle_build(project_dir: Path, version: str) -> None:
    if not DEFAULT_JAVA_HOME.exists():
        raise RuntimeError(f"JDK 21 not found at {DEFAULT_JAVA_HOME}")
    env = os.environ.copy()
    env["JAVA_HOME"] = str(DEFAULT_JAVA_HOME)
    subprocess.run(["./gradlew", "build", "--console=plain"], cwd=project_dir, env=env, check=True)


def magic_storage_jars(mods_dir: Path) -> list[Path]:
    return sorted(mods_dir.glob("magic_storage-*.jar"))


def fusion_jars(mods_dir: Path) -> list[Path]:
    return sorted(mods_dir.glob("fusion-*.jar"))


def sha512(path: Path) -> str:
    digest = hashlib.sha512()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_url(url: str, destination: Path) -> None:
    with urllib.request.urlopen(url, timeout=60) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)


def create_backup_plan(jars: list[Path], prism_minecraft_dir: Path) -> list[Path]:
    if not jars:
        return []
    backup_dir = prism_minecraft_dir / "magic_storage_backups" / datetime.now().strftime("%Y%m%d-%H%M%S")
    backup_dir.mkdir(parents=True, exist_ok=False)
    return [backup_dir / jar.name for jar in jars]


def backup_existing_jars(
    jars: list[Path],
    prism_minecraft_dir: Path,
    backups: list[Path] | None = None,
) -> list[Path]:
    if not jars:
        return []
    if backups is None:
        backups = create_backup_plan(jars, prism_minecraft_dir)
    if len(backups) != len(jars):
        raise RuntimeError("Backup journal does not match active jar inventory")
    backup_dir = backups[0].parent
    try:
        for jar, dest in zip(jars, backups):
            shutil.move(str(jar), str(dest))
    except BaseException as backup_error:
        rollback_errors = []
        for jar, backup in reversed(list(zip(jars, backups))):
            if not backup.exists():
                continue
            try:
                shutil.move(str(backup), str(jar))
            except BaseException as rollback_error:
                rollback_errors.append(str(rollback_error))
        if backup_dir.exists():
            try:
                backup_dir.rmdir()
            except BaseException as rollback_error:
                rollback_errors.append(str(rollback_error))
        if rollback_errors:
            raise RuntimeError(
                f"Backing up active jars failed ({backup_error}); rollback also failed: {'; '.join(rollback_errors)}"
            ) from backup_error
        raise
    return backups


def backup_existing_magic_jars(mods_dir: Path, prism_minecraft_dir: Path) -> list[Path]:
    return backup_existing_jars(magic_storage_jars(mods_dir), prism_minecraft_dir)


def deploy(
    project_dir: Path,
    prism_minecraft_dir: Path = DEFAULT_PRISM_MINECRAFT_DIR,
    build_runner=run_gradle_build,
    fusion_downloader=download_url,
) -> DeployResult:
    project_dir = project_dir.resolve()
    prism_minecraft_dir = prism_minecraft_dir.expanduser().resolve()
    mods_dir = prism_minecraft_dir / "mods"
    if not mods_dir.is_dir():
        raise RuntimeError(f"Prism dev mods directory not found: {mods_dir}")
    original_jar_paths = set(magic_storage_jars(mods_dir))
    original_fusion_paths = set(fusion_jars(mods_dir))

    properties_path = project_dir / "gradle.properties"
    original_properties = properties_path.read_text()

    backups = []
    fusion_backups = []
    destination = None
    fusion_destination = mods_dir / FUSION_FILENAME
    staging = None
    fusion_staging = None
    try:
        version = bump_patch_version(properties_path)
        destination = mods_dir / f"magic_storage-{version}.jar"
        build_runner(project_dir, version)

        source_jar = project_dir / "build" / "libs" / f"magic_storage-{version}.jar"
        if not source_jar.is_file():
            raise RuntimeError(f"Built jar not found: {source_jar}")

        file_descriptor, staging_name = tempfile.mkstemp(prefix=f".{source_jar.name}.", suffix=".staging", dir=mods_dir)
        os.close(file_descriptor)
        staging = Path(staging_name)
        shutil.copy2(source_jar, staging)

        installed_fusion = fusion_jars(mods_dir)
        fusion_is_exact = (
            installed_fusion == [fusion_destination]
            and sha512(fusion_destination) == FUSION_SHA512
        )
        if not fusion_is_exact:
            file_descriptor, fusion_staging_name = tempfile.mkstemp(
                prefix=f".{FUSION_FILENAME}.",
                suffix=".staging",
                dir=mods_dir,
            )
            os.close(file_descriptor)
            fusion_staging = Path(fusion_staging_name)
            fusion_downloader(FUSION_URL, fusion_staging)
            actual_fusion_sha512 = sha512(fusion_staging)
            if actual_fusion_sha512 != FUSION_SHA512:
                raise RuntimeError(
                    f"Fusion SHA-512 mismatch: expected {FUSION_SHA512}, got {actual_fusion_sha512}"
                )

        magic_to_backup = magic_storage_jars(mods_dir)
        fusion_to_backup = fusion_jars(mods_dir) if fusion_staging is not None else []
        jars_to_backup = [*magic_to_backup, *fusion_to_backup]
        all_backups = create_backup_plan(jars_to_backup, prism_minecraft_dir)
        backups = all_backups[:len(magic_to_backup)]
        fusion_backups = all_backups[len(magic_to_backup):]
        backup_existing_jars(jars_to_backup, prism_minecraft_dir, all_backups)
        staging.replace(destination)
        staging = None
        if fusion_staging is not None:
            fusion_staging.replace(fusion_destination)
            fusion_staging = None

        jars = magic_storage_jars(mods_dir)
        if jars != [destination]:
            names = ", ".join(str(p) for p in jars)
            raise RuntimeError(f"Expected exactly one Magic Storage jar in {mods_dir}, found: {names}")
        installed_fusion = fusion_jars(mods_dir)
        if installed_fusion != [fusion_destination]:
            names = ", ".join(str(path) for path in installed_fusion)
            raise RuntimeError(f"Expected exactly one Fusion jar in {mods_dir}, found: {names}")
        actual_fusion_sha512 = sha512(fusion_destination)
        if actual_fusion_sha512 != FUSION_SHA512:
            raise RuntimeError(
                f"Installed Fusion SHA-512 mismatch: expected {FUSION_SHA512}, got {actual_fusion_sha512}"
            )

        return DeployResult(
            version=version,
            jar=source_jar,
            destination=destination,
            backups=backups,
            fusion_destination=fusion_destination,
            fusion_backups=fusion_backups,
        )
    except BaseException as deploy_error:
        rollback_errors = []
        for staged in (staging, fusion_staging):
            if staged is not None and staged.exists():
                try:
                    staged.unlink()
                except BaseException as rollback_error:
                    rollback_errors.append(str(rollback_error))
        if destination is not None and destination.exists() and (destination not in original_jar_paths or backups):
            try:
                destination.unlink()
            except BaseException as rollback_error:
                rollback_errors.append(str(rollback_error))
        if (
            fusion_destination.exists()
            and (fusion_destination not in original_fusion_paths or fusion_backups)
        ):
            try:
                fusion_destination.unlink()
            except BaseException as rollback_error:
                rollback_errors.append(str(rollback_error))
        for backup in [*backups, *fusion_backups]:
            if not backup.exists():
                continue
            try:
                shutil.move(str(backup), str(mods_dir / backup.name))
            except BaseException as rollback_error:
                rollback_errors.append(str(rollback_error))
        for backup_dir in {backup.parent for backup in [*backups, *fusion_backups]}:
            if backup_dir.exists():
                try:
                    backup_dir.rmdir()
                except BaseException as rollback_error:
                    rollback_errors.append(str(rollback_error))
        try:
            properties_path.write_text(original_properties)
        except BaseException as rollback_error:
            rollback_errors.append(str(rollback_error))
        if rollback_errors:
            raise RuntimeError(
                f"Deployment failed ({deploy_error}); rollback also failed: {'; '.join(rollback_errors)}"
            ) from deploy_error
        raise


def main() -> int:
    try:
        result = deploy(Path.cwd())
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(f"Deployed Magic Storage {result.version}")
    print(f"Jar: {result.jar}")
    print(f"Destination: {result.destination}")
    print(f"Fusion: {result.fusion_destination}")
    if result.backups:
        print("Backed up old jar(s):")
        for path in result.backups:
            print(f"- {path}")
    if result.fusion_backups:
        print("Backed up old Fusion jar(s):")
        for path in result.fusion_backups:
            print(f"- {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
