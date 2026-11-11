"""测试用网络护栏：阻止 socket 出站和 requests HTTP，不读取任何凭证。"""
from __future__ import annotations

import socket

_INSTALLED = False


class NetworkBlocked(RuntimeError):
    pass


def install() -> None:
    global _INSTALLED
    if _INSTALLED:
        return

    def _blocked(*a, **k):
        raise NetworkBlocked("xx9 tests: outbound network is blocked")

    _RealSocket = socket.socket

    class GuardedSocket(_RealSocket):
        def connect(self, addr):  # type: ignore[override]
            fam = self.family
            if fam in (socket.AF_INET, socket.AF_INET6):
                raise NetworkBlocked(f"socket.connect blocked: {addr!r}")
            return _RealSocket.connect(self, addr)

        def connect_ex(self, addr):  # type: ignore[override]
            fam = self.family
            if fam in (socket.AF_INET, socket.AF_INET6):
                raise NetworkBlocked(f"socket.connect_ex blocked: {addr!r}")
            return _RealSocket.connect_ex(self, addr)

    socket.socket = GuardedSocket  # type: ignore[misc]
    socket.create_connection = _blocked  # type: ignore[assignment]
    try:
        import requests
        requests.api.request = _blocked  # type: ignore[assignment]
        requests.request = _blocked  # type: ignore[assignment]
        requests.get = _blocked  # type: ignore[assignment]
        requests.post = _blocked  # type: ignore[assignment]
        requests.head = _blocked  # type: ignore[assignment]
        requests.put = _blocked  # type: ignore[assignment]
        requests.delete = _blocked  # type: ignore[assignment]
        requests.Session.request = _blocked  # type: ignore[assignment]
    except Exception:
        pass
    try:
        import urllib.request as u
        u.urlopen = _blocked  # type: ignore[assignment]
    except Exception:
        pass
    _INSTALLED = True
