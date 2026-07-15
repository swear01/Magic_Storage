from pathlib import Path
import re
from urllib.request import Request, urlopen
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
METADATA_URL = "https://maven.terraformersmc.com/releases/dev/emi/emi-neoforge/maven-metadata.xml"
ARTIFACT_VERSION = re.compile(r"^(\d+)\.(\d+)\.(\d+)\+(.+)$")


def numeric_version(value: str) -> tuple[int, int, int]:
    parts = value.split(".")
    if not 1 <= len(parts) <= 3 or any(not part.isdigit() for part in parts):
        raise ValueError(f"unsupported numeric version: {value}")
    return tuple(int(part) for part in parts) + (0,) * (3 - len(parts))


def parse_supported_range(value: str) -> tuple[tuple[int, int, int], tuple[int, int, int]]:
    match = re.fullmatch(r"\[([^,]+),([^)]+)\)", value)
    if match is None:
        raise ValueError("EMI range must use an inclusive minimum and exclusive upper bound")
    minimum = numeric_version(match.group(1))
    upper_exclusive = numeric_version(match.group(2))
    if minimum >= upper_exclusive:
        raise ValueError("EMI range minimum must be below its upper bound")
    return minimum, upper_exclusive


def parse_artifact_version(value: str) -> tuple[tuple[int, int, int], str] | None:
    match = ARTIFACT_VERSION.fullmatch(value)
    if match is None:
        return None
    return tuple(int(match.group(index)) for index in range(1, 4)), match.group(4)


def validate_baseline(
    baseline: str,
    minecraft_version: str,
    minimum: tuple[int, int, int],
    upper_exclusive: tuple[int, int, int],
) -> None:
    parsed = parse_artifact_version(baseline)
    if parsed is None:
        raise ValueError(f"unsupported EMI baseline coordinate: {baseline}")
    version, artifact_minecraft_version = parsed
    if artifact_minecraft_version != minecraft_version:
        raise ValueError("EMI baseline Minecraft version does not match the project")
    if version != minimum:
        raise ValueError("EMI baseline must equal the minimum supported EMI version")
    if version >= upper_exclusive:
        raise ValueError("EMI baseline is outside the supported range")


def latest_compatible_version(
    metadata: str,
    minecraft_version: str,
    minimum: tuple[int, int, int],
    upper_exclusive: tuple[int, int, int],
) -> str:
    root = ElementTree.fromstring(metadata)
    candidates = []
    for element in root.findall("./versioning/versions/version"):
        artifact = (element.text or "").strip()
        parsed = parse_artifact_version(artifact)
        if parsed is None:
            continue
        version, artifact_minecraft_version = parsed
        if artifact_minecraft_version == minecraft_version and minimum <= version < upper_exclusive:
            candidates.append((version, artifact))
    if not candidates:
        raise ValueError(
            f"no compatible EMI release for Minecraft {minecraft_version} in the configured range"
        )
    return max(candidates)[1]


def read_properties(path: Path) -> dict[str, str]:
    properties = {}
    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        properties[key.strip()] = value.strip()
    return properties


def fetch_metadata(url: str = METADATA_URL) -> str:
    request = Request(url, headers={"User-Agent": "Magic-Storage-EMI-compatibility-check"})
    with urlopen(request, timeout=30) as response:
        return response.read().decode("utf-8")


def main() -> None:
    properties = read_properties(ROOT / "gradle.properties")
    minecraft_version = properties["minecraft_version"]
    baseline = properties["emi_version"]
    minimum, upper_exclusive = parse_supported_range(properties["emi_version_range"])
    validate_baseline(baseline, minecraft_version, minimum, upper_exclusive)
    print(
        latest_compatible_version(
            fetch_metadata(),
            minecraft_version,
            minimum,
            upper_exclusive,
        )
    )


if __name__ == "__main__":
    main()
