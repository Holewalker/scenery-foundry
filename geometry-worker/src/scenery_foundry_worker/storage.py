"""No-replace artifact publish (ADR-0002): each attempt publishes to an immutable, exclusive path;
the winning attempt is whichever hardlinks first, and a losing attempt's temp file is always cleaned
up. Mirrors `backend/.../storage/StorageResolver.publish()`'s `createLink`+`EEXIST` semantics.
"""

from __future__ import annotations

import os
from pathlib import Path


def publish_no_replace(source: Path, destination: Path) -> None:
    source, destination = Path(source), Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.link(source, destination)
    except FileExistsError:
        pass  # another attempt already published this key first; that artifact wins, untouched
    finally:
        source.unlink(missing_ok=True)
