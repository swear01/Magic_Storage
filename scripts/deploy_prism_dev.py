#!/usr/bin/env python3
import os
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from bump_patch_version import bump_patch_version

DEFAULT_JAVA_HOME = Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")
DEFAULT_PRISM_MINECRAFT_DIR = Path.home() / "Library/Application Support/PrismLauncher/instances/dev/minecraft"


@dataclass(frozen=True)
class DeployResult:
    version: str
    jar: Path
    destination: Path
    backups: list[Path]


def run_gradle_build(project_dir: Path, version: str) -> None:
    if not DEFAULT_JAVA_HOME.exists():
        raise RuntimeError(f"JDK 21 not found at {DEFAULT_JAVA_HOME}")
    env = os.environ.copy()
    env["JAVA_HOME"] = str(DEFAULT_JAVA_HOME)
    subprocess.run(["./gradlew", "build", "--console=plain"], cwd=project_dir, env=env, check=True)


def magic_storage_jars(mods_dir: Path) -> list[Path]:
    return sorted(mods_dir.glob("magic_storage-*.jar"))


def backup_existing_magic_jars(mods_dir: Path, prism_minecraft_dir: Path) -> list[Path]:
    jars = magic_storage_jars(mods_dir)
    if not jars:
        return []
    backup_dir = prism_minecraft_dir / "magic_storage_backups" / datetime.now().strftime("%Y%m%d-%H%M%S")
    backup_dir.mkdir(parents=True, exist_ok=False)
    backups = [backup_dir / jar.name for jar in jars]
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


def deploy(project_dir: Path, prism_minecraft_dir: Path = DEFAULT_PRISM_MINECRAFT_DIR, build_runner=run_gradle_build) -> DeployResult:
    project_dir = project_dir.resolve()
    prism_minecraft_dir = prism_minecraft_dir.expanduser().resolve()
    mods_dir = prism_minecraft_dir / "mods"
    if not mods_dir.is_dir():
        raise RuntimeError(f"Prism dev mods directory not found: {mods_dir}")
    original_jar_paths = set(magic_storage_jars(mods_dir))

    properties_path = project_dir / "gradle.properties"
    original_properties = properties_path.read_text()

    backups = []
    destination = None
    staging = None
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
        backups = backup_existing_magic_jars(mods_dir, prism_minecraft_dir)
        staging.replace(destination)
        staging = None

        jars = magic_storage_jars(mods_dir)
        if jars != [destination]:
            names = ", ".join(str(p) for p in jars)
            raise RuntimeError(f"Expected exactly one Magic Storage jar in {mods_dir}, found: {names}")

        return DeployResult(version=version, jar=source_jar, destination=destination, backups=backups)
    except BaseException as deploy_error:
        rollback_errors = []
        if staging is not None and staging.exists():
            try:
                staging.unlink()
            except BaseException as rollback_error:
                rollback_errors.append(str(rollback_error))
        if destination is not None and destination.exists() and (destination not in original_jar_paths or backups):
            try:
                destination.unlink()
            except BaseException as rollback_error:
                rollback_errors.append(str(rollback_error))
        for backup in backups:
            try:
                shutil.move(str(backup), str(mods_dir / backup.name))
            except BaseException as rollback_error:
                rollback_errors.append(str(rollback_error))
        for backup_dir in {backup.parent for backup in backups}:
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
    if result.backups:
        print("Backed up old jar(s):")
        for path in result.backups:
            print(f"- {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
