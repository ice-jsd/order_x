import os
from pathlib import Path


def _unquote_env_value(value: str) -> str:
    text = str(value or "").strip()
    if len(text) >= 2 and text[0] == text[-1] and text[0] in {"'", '"'}:
        return text[1:-1]
    return text


def _load_env_file(path: Path, override: bool = False) -> bool:
    if not path.exists() or not path.is_file():
        return False
    loaded = False
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key.startswith("export "):
            key = key[7:].strip()
        if not key:
            continue
        if override or key not in os.environ:
            os.environ[key] = _unquote_env_value(value)
        loaded = True
    return loaded


def _bootstrap_env() -> None:
    base_dir = Path(__file__).resolve().parent
    override = str(os.environ.get("AUTO_PY_ENV_OVERRIDE") or "").strip().lower() in {"1", "true", "yes", "on"}
    configured = str(os.environ.get("AUTO_PY_ENV_FILE") or "").strip()
    candidates = []
    if configured:
        candidates.append(Path(configured))
    candidates.extend(
        [
            base_dir / ".env.local",
            base_dir / ".env",
        ]
    )
    loaded_files = []
    seen = set()
    for candidate in candidates:
        normalized = str(candidate.resolve()) if candidate.exists() else str(candidate)
        if normalized in seen:
            continue
        seen.add(normalized)
        if _load_env_file(candidate, override=override):
            loaded_files.append(str(candidate))
    if loaded_files:
        print(f"[bootstrap] loaded env files: {', '.join(loaded_files)}")


_bootstrap_env()

from ticket_python_server import main


if __name__ == "__main__":
    main()
