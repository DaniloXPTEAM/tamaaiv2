"""The upstream offline-DNS test fixture must not catch unrelated domains."""

from __future__ import annotations

import socket

import pytest
from conftest import EXAMPLE_HOST_ADDRESS, _is_example_host


@pytest.mark.parametrize(
    "host",
    [
        "example",
        "shop.Example",
        "example.com",
        "Shop.EXAMPLE.COM",
        "example.org",
        "sub.example.org",
        "EXAMPLE.NET",
        "sub.example.net",
    ],
)
def test_reserved_examples_are_resolved_without_dns(host: str) -> None:
    assert _is_example_host(host)
    answer = socket.getaddrinfo(host, 443)
    assert answer[0][4] == (EXAMPLE_HOST_ADDRESS, 443)


@pytest.mark.parametrize(
    "host",
    [
        None,
        b"example.com",
        "notexample.com",
        "example.com.invalid",
        "example-company.org",
        "127.0.0.1",
    ],
)
def test_unrelated_hosts_are_not_intercepted(host: bytes | str | None) -> None:
    assert not _is_example_host(host)


def test_numeric_loopback_keeps_its_real_address() -> None:
    answer = socket.getaddrinfo("127.0.0.1", 80, socket.AF_INET, socket.SOCK_STREAM)
    assert answer[0][4] == ("127.0.0.1", 80)
