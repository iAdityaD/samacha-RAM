"""Bounded, DNS-pinned outbound HTTP; no redirects or ambient proxy credentials."""
import http.client
import ipaddress
import json
import socket
import ssl
from urllib.parse import urlsplit


class NetworkError(ValueError):
    pass


def resolve(url, local=False):
    p = urlsplit(url)
    if p.username or p.password or p.fragment or not p.hostname:
        raise NetworkError('Invalid endpoint. Credentials belong in the API key field.')
    if p.scheme != 'https' and not (local and p.scheme == 'http'):
        raise NetworkError('Public endpoints must use HTTPS.')
    try:
        addresses = socket.getaddrinfo(p.hostname, p.port or (443 if p.scheme == 'https' else 80), type=socket.SOCK_STREAM)
    except (OSError, ValueError) as e:
        raise NetworkError('Endpoint could not be resolved.') from e
    ips = list(dict.fromkeys(x[4][0] for x in addresses))
    if not ips:
        raise NetworkError('Endpoint has no usable address.')
    for raw in ips:
        ip = ipaddress.ip_address(raw)
        if local:
            if not ip.is_loopback:
                raise NetworkError('Local providers must resolve exclusively to loopback on the app host.')
        elif not ip.is_global:
            raise NetworkError('Private, link-local and reserved destinations are blocked.')
    return p, ips


def request(url, method='GET', payload=None, key='', local=False, limit=3_000_000, timeout=18):
    p, ips = resolve(url, local)
    body = json.dumps(payload).encode() if payload is not None else None
    headers = {'User-Agent': 'samacha-RAM/0.1 (personal research reader)', 'Accept': 'application/json, application/atom+xml, application/rss+xml, text/xml', 'Accept-Encoding': 'identity'}
    if body is not None:
        headers['Content-Type'] = 'application/json'
    if key:
        headers['Authorization'] = 'Bearer ' + key
    connection = http.client.HTTPConnection(p.hostname, p.port or (443 if p.scheme == 'https' else 80), timeout=timeout)
    try:
        # Pin the validated IP; TLS still validates the original host name.
        sock = socket.create_connection((ips[0], connection.port), timeout=timeout)
        if p.scheme == 'https':
            sock = ssl.create_default_context().wrap_socket(sock, server_hostname=p.hostname)
        connection.sock = sock
        target=(p.path or '/') + ('?' + p.query if p.query else '')
        connection.request(method, target, body=body, headers=headers)
        response = connection.getresponse()
        if not 200 <= response.status < 300:
            raise NetworkError(f'Upstream returned HTTP {response.status}; redirects are not followed.')
        if response.getheader('Content-Length') and int(response.getheader('Content-Length')) > limit:
            raise NetworkError('Upstream response exceeds the size limit.')
        data = response.read(limit + 1)
        if len(data) > limit:
            raise NetworkError('Upstream response exceeds the size limit.')
        return data
    except (OSError, http.client.HTTPException, UnicodeError) as e:
        raise NetworkError('Network request failed. Check connectivity and endpoint settings.') from e
    finally:
        connection.close()


def request_json(*args, **kwargs):
    try:
        return json.loads(request(*args, **kwargs))
    except json.JSONDecodeError as e:
        raise NetworkError('Provider returned invalid JSON.') from e
